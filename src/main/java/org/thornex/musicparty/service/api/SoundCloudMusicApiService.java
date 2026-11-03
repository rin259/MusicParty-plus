package org.thornex.musicparty.service.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SoundCloudMusicApiService extends YtDlpMusicApiService {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudMusicApiService.class);
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final SoundCloudSearchClient searchClient;

    public SoundCloudMusicApiService(LocalCacheService localCacheService, YtDlpResolver resolver,
                                     AppProperties properties, SoundCloudSearchClient searchClient) {
        super("soundcloud", localCacheService, resolver, properties.getSoundcloud().getSearchLimit(),
                properties.getSoundcloud().getFetchLimit());
        this.searchClient = searchClient;
    }

    @Override
    protected Mono<List<YtDlpResolver.TrackMetadata>> searchPage(String keyword, int offset, int limit) {
        return searchClient.search(keyword, offset, limit)
                .onErrorResume(error -> {
                    log.warn("SoundCloud api-v2 search failed for '{}', falling back to yt-dlp: {}",
                            keyword, error.getMessage());
                    return super.searchPage(keyword, offset, limit);
                });
    }

    @Override protected Mono<List<YtDlpResolver.TrackMetadata>> search(String keyword, int limit) {
        return searchClient.search(keyword, 0, limit)
                .onErrorResume(error -> resolver.searchSoundCloud(keyword, limit));
    }
    @Override protected Mono<YtDlpResolver.TrackMetadata> resolveMetadata(String musicId) { return resolver.resolveSoundCloudMetadata(musicId); }

    @Override
    protected Mono<Void> validateBeforeDownload(YtDlpResolver.TrackMetadata metadata) {
        return resolver.validateSoundCloudAudio(metadata.sourceUrl())
                .onErrorMap(error -> {
                    String message = error.getMessage() == null ? "" : error.getMessage();
                    if (message.toLowerCase().contains("drm protected")) {
                        return new ApiRequestException("该 SoundCloud 曲目受 DRM 保护，无法添加");
                    }
                    return new ApiRequestException("SoundCloud 曲目当前无法解析或下载");
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        String handle = normalizeHandle(keyword);
        return resolver.resolveSoundCloudUser(handle)
                .map(user -> List.of(new UserSearchResult(user.handle(), user.name(), user.avatarUrl(), getPlatformName())));
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        String handle = normalizeHandle(userId);
        return resolver.getSoundCloudUserSets(handle)
                .map(playlists -> playlists.stream()
                        .map(playlist -> new Playlist(playlist.id(), playlist.name(), playlist.coverUrl(),
                                playlist.trackCount(), getPlatformName()))
                        .toList());
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        if (!playlistId.matches("\\d+")) {
            return Mono.error(new ApiRequestException("SoundCloud 歌单 ID 无效"));
        }
        return resolver.getSoundCloudPlaylistTracks(playlistId, offset, limit)
                .map(tracks -> tracks.stream()
                        .map(track -> new Music(track.id(), track.title(), List.of(track.artist()), track.durationMs(),
                                getPlatformName(), track.thumbnail()))
                        .toList());
    }

    private static final Set<String> RESERVED_PATHS = Set.of(
            "tracks", "sets", "albums", "playlists", "search", "charts", "discover",
            "stream", "you", "upload", "settings", "pages", "imprint", "jobs");

    private static final HttpClient SHORT_LINK_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 把用户粘贴的内容归一成 SoundCloud handle。
     *
     * 研判（2026-11）：绑定入口原本只接受「soundcloud.com/xxx」和 @handle，但真实分享里
     * 最常见的是 on.soundcloud.com 短链（移动端分享出来的就是它）、带 /sets/... 的歌单链接、
     * 以及没有 scheme 的裸域名。这些以前要么解析成错误的 handle，要么直接被拒。
     * 现在统一处理：短链先跟随重定向展开，再取第一段路径；站内页面路径明确报错而不是当成用户名。
     */
    String normalizeHandle(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiRequestException("请粘贴 SoundCloud 主页链接、短链，或 @handle");
        }

        String candidate = value.trim();
        if (candidate.startsWith("@")) candidate = candidate.substring(1).trim();
        if (!candidate.contains("/") && HANDLE_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }

        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        else if (!candidate.matches("(?i)^https?://.*")) candidate = "https://" + candidate;

        if (candidate.toLowerCase(Locale.ROOT).contains("on.soundcloud.com/")) {
            candidate = expandShortLink(candidate);
        }

        int domainIndex = candidate.toLowerCase(Locale.ROOT).indexOf("soundcloud.com/");
        if (domainIndex < 0) {
            throw new ApiRequestException("这不是 SoundCloud 链接：请粘贴主页 URL、on.soundcloud.com 短链，或 @handle");
        }
        candidate = candidate.substring(domainIndex + "soundcloud.com/".length());
        candidate = candidate.split("[/?#]", 2)[0];

        if (RESERVED_PATHS.contains(candidate.toLowerCase(Locale.ROOT))) {
            throw new ApiRequestException("这是 SoundCloud 的站内页面，不是用户主页；请打开该用户主页再复制链接");
        }
        if (!HANDLE_PATTERN.matcher(candidate).matches()) {
            throw new ApiRequestException("无法从链接中识别用户名，请粘贴形如 soundcloud.com/用户名 的主页地址");
        }
        return candidate;
    }

    /** on.soundcloud.com/xxxx 是分享短链，必须跟随重定向才能拿到真实主页。 */
    private String expandShortLink(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 (compatible; MusicParty/1.0)")
                    .build();
            HttpResponse<Void> response = SHORT_LINK_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.uri().toString();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiRequestException("展开 SoundCloud 短链被中断，请重试");
        } catch (Exception error) {
            throw new ApiRequestException("无法展开这个 SoundCloud 短链，请直接粘贴用户主页 URL");
        }
    }
}
