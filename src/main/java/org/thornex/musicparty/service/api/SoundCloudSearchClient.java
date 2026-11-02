package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast SoundCloud track search over the public {@code api-v2} endpoint that the
 * SoundCloud web player itself uses.
 *
 * <p>{@code /search/tracks} accepts {@code limit} (up to 200) and {@code offset}, so
 * paging is a plain query parameter instead of a yt-dlp process spawn. The endpoint
 * needs a {@code client_id}, which is hydrated into the SoundCloud home page and
 * rotates occasionally, so it is discovered lazily and re-discovered on 401/403.
 */
@Component
public class SoundCloudSearchClient {

    private static final String HOME_URL = "https://soundcloud.com/";
    private static final String TRACK_SEARCH_PATH = "/search/tracks";
    private static final String TRACK_URL = "https://api.soundcloud.com/tracks/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(45);
    private static final Pattern HYDRATED_CLIENT_ID = Pattern.compile(
            "\"hydratable\"\\s*:\\s*\"apiClient\"\\s*,\\s*\"data\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([0-9a-zA-Z]{32})\"");
    private static final Pattern CLIENT_ID_ASSIGNMENT =
            Pattern.compile("client_id[\"']?\\s*[:=]\\s*[\"']?([0-9a-zA-Z]{32})");
    private static final Pattern SCRIPT_ASSET =
            Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[A-Za-z0-9_.-]+\\.js");

    private static final Logger log = LoggerFactory.getLogger(SoundCloudSearchClient.class);

    private final WebClient webClient;
    private volatile String clientId;

    public SoundCloudSearchClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<List<YtDlpResolver.TrackMetadata>> search(String keyword, int offset, int limit) {
        return Mono.fromCallable(() -> searchBlocking(keyword, offset, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(OVERALL_TIMEOUT);
    }

    /** Resolves the rotating client_id and warms the TLS session before first use. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        search("music", 0, 1).subscribe(
                ignored -> log.info("SoundCloud search client warmed up"),
                error -> log.debug("SoundCloud warm-up skipped: {}", error.getMessage()));
    }

    private List<YtDlpResolver.TrackMetadata> searchBlocking(String keyword, int offset, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();

        int safeLimit = Math.clamp(limit, 1, 200);
        int safeOffset = Math.max(offset, 0);

        JsonNode response;
        try {
            response = fetch(keyword, safeOffset, safeLimit, resolveClientId(false));
        } catch (WebClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status != 401 && status != 403) throw error;
            response = fetch(keyword, safeOffset, safeLimit, resolveClientId(true));
        }
        return parseTracks(response);
    }

    private JsonNode fetch(String keyword, int offset, int limit, String clientId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api-v2.soundcloud.com")
                        .path(TRACK_SEARCH_PATH)
                        .queryParam("q", keyword)
                        .queryParam("client_id", clientId)
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .queryParam("linked_partitioning", "1")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
    }

    private List<YtDlpResolver.TrackMetadata> parseTracks(JsonNode response) {
        List<YtDlpResolver.TrackMetadata> tracks = new ArrayList<>();
        for (JsonNode track : response.path("collection")) {
            long id = track.path("id").asLong(0L);
            String title = track.path("title").asText("");
            if (id == 0L || title.isEmpty()) continue;

            String artwork = highResArtwork(track.path("artwork_url").asText(""));
            if (artwork.isEmpty()) {
                artwork = highResArtwork(track.path("user").path("avatar_url").asText(""));
            }

            tracks.add(new YtDlpResolver.TrackMetadata(String.valueOf(id), title,
                    track.path("user").path("username").asText("Unknown"),
                    track.path("duration").asLong(0L), artwork, TRACK_URL + id));
        }
        return tracks;
    }

    /**
     * The api-v2 search response hands out {@code -large.jpg} artworks (100×100),
     * which are visibly soft once scaled up to the center console. SoundCloud's CDN
     * exposes size variants as a path suffix, so request the 500×500 rendition.
     */
    static String highResArtwork(String url) {
        if (url == null || url.isBlank()) return "";
        return url.replaceFirst("-(?:(?:t\\d+x\\d+)|(?:badge)|(?:large)|(?:mini)|(?:tiny)|(?:small))\\.jpg", "-t500x500.jpg");
    }

    private String resolveClientId(boolean forceRefresh) {
        String cached = clientId;
        if (!forceRefresh && cached != null) return cached;
        synchronized (this) {
            if (!forceRefresh && clientId != null) return clientId;
            clientId = discoverClientId();
            return clientId;
        }
    }

    private String discoverClientId() {
        String html = getText(HOME_URL);

        String fromHome = firstMatch(HYDRATED_CLIENT_ID, html);
        if (fromHome == null) fromHome = firstMatch(CLIENT_ID_ASSIGNMENT, html);
        if (fromHome != null) return fromHome;

        Matcher assets = SCRIPT_ASSET.matcher(html);
        while (assets.find()) {
            String fromAsset = firstMatch(CLIENT_ID_ASSIGNMENT, getText(assets.group()));
            if (fromAsset != null) return fromAsset;
        }
        throw new IllegalStateException("SoundCloud client_id could not be discovered");
    }

    private String getText(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    private static String firstMatch(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
