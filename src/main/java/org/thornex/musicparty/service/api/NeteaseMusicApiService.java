package org.thornex.musicparty.service.api;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AlbumSearchResult;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.LocalCacheService.DownloadPriority;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class NeteaseMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private final String initialCookieFromConfig;
    private final String quality;
    private final LocalCacheService localCacheService;
    private final Duration resolveTimeout;
    private volatile String currentCookie;
    private static final String PLATFORM = "netease";

    // 取流容错：上游 502 read ETIMEDOUT 属瞬时抖动，退避重试并逐级降音质
    private static final int URL_RESOLVE_ATTEMPTS = 4;
    private static final Duration URL_RESOLVE_BACKOFF = Duration.ofMillis(700);
    private static final List<String> QUALITY_LADDER = List.of("exhigh", "higher", "standard");
    private static final String NO_URL_MESSAGE = "网易云未返回可播放音频地址";

    public NeteaseMusicApiService(WebClient webClient, AppProperties appProperties, LocalCacheService localCacheService) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getNetease().getBaseUrl();
        this.initialCookieFromConfig = appProperties.getNetease().getCookie();
        this.quality = appProperties.getNetease().getQuality();
        this.localCacheService = localCacheService;
        this.resolveTimeout = Duration.ofSeconds(
                Math.max(1L, appProperties.getNetease().getResolveTimeoutSeconds()));
        // 初始化时先使用配置文件的内容
        this.currentCookie = initialCookieFromConfig;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing NeteaseCloudMusic API client with quality: {}...", quality);
        if (!StringUtils.hasText(currentCookie) || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(currentCookie)) {
            log.info("Netease Cookie is empty. Service running in passive mode (waiting for config).");
        } else {
            log.info("Netease Cookie configured.");
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(currentCookie) || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(currentCookie)) {
            throw new ApiRequestException("尚未配置网易云 Cookie，请联系管理员设置");
        }
    }

    public void updateCookie(String newCookie) {
        this.currentCookie = newCookie;
        checkCookie(newCookie).subscribe(isValid -> {
            if (isValid) {
                log.info("Netease cookie updated and verified successfully.");
            } else {
                log.warn("The newly updated Netease cookie appears to be invalid.");
            }
        });
    }

    public String getCurrentCookie() {
        return currentCookie;
    }

    private Mono<Boolean> checkCookie(String cookie) {
        return webClient.get()
                .uri(baseUrl + "/user/account?cookie={cookie}", cookie)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> jsonNode.has("profile") && !jsonNode.get("profile").isNull())
                .onErrorReturn(false);
    }

    // UPDATED: Renamed method and removed encoding.
    private String getCookie() {
        return currentCookie != null ? currentCookie : "";
    }
    
    // Construct Netease cover URL from album/song data
    private String albumCoverUrl(JsonNode album) {
        String picUrl = album.path("picUrl").asText();
        if (!StringUtils.hasText(picUrl)) {
            picUrl = album.path("coverImgUrl").asText();
        }
        if (StringUtils.hasText(picUrl)) {
            String httpsUrl = picUrl.startsWith("http://") ? picUrl.replace("http://", "https://") : picUrl;
            if (httpsUrl.contains("music.126.net")) {
                int paramIdx = httpsUrl.indexOf("?param=");
                if (paramIdx >= 0) {
                    httpsUrl = httpsUrl.substring(0, paramIdx);
                }
                return httpsUrl + "?param=1024y1024";
            }
            return httpsUrl;
        }
        return "";
    }

    private String songCoverUrl(JsonNode song) {
        JsonNode album = song.has("al") ? song.path("al") : song.path("album");
        String cover = albumCoverUrl(album);
        if (StringUtils.hasText(cover)) {
            return cover;
        }
        // Fallback: use artist img1v1Url
        JsonNode artists = song.has("artists") ? song.path("artists") : song.path("ar");
        if (artists.isArray() && artists.size() > 0) {
            String imgUrl = artists.get(0).path("img1v1Url").asText();
            if (StringUtils.hasText(imgUrl)) {
                String httpsUrl = imgUrl.startsWith("http://") ? imgUrl.replace("http://", "https://") : imgUrl;
                int paramIdx = httpsUrl.indexOf("?param=");
                if (paramIdx >= 0) {
                    httpsUrl = httpsUrl.substring(0, paramIdx);
                }
                return httpsUrl + "?param=1024y1024";
            }
        }
        return "";
    }

    // Helper to force HTTPS
    private String upgradeToHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return url.replace("http://", "https://");
        }
        return url;
    }


    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    private Mono<ApiRequestException> handleApiError(String apiName, org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new ApiRequestException(
                        String.format("Netease API '%s' failed with status %d: %s", apiName, response.statusCode().value(), errorBody)
                )));
    }

    // UPDATED: All API calls now use the raw cookie from getCookie()
    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        return searchMusic(keyword, 0, 30);
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword, int offset, int limit) {
        ensureConfigured();
        // /cloudsearch 一次返回 dt（时长）、al.picUrl（封面）与 ar[]（艺人），
        // 故无需再发 /song/detail；旧实现漏读字段，搜索结果时长恒为 0。
        return cloudSearch(keyword, offset, limit)
                .onErrorResume(error -> {
                    log.warn("cloudsearch failed ({}), falling back to /search + /song/detail", error.getMessage());
                    return legacySearch(keyword, offset, limit);
                });
    }

    private Mono<List<Music>> cloudSearch(String keyword, int offset, int limit) {
        return webClient.get()
                .uri(baseUrl + "/cloudsearch?keywords={keyword}&type=1&offset={offset}&limit={limit}&cookie={cookie}",
                        keyword, offset, limit, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("cloudsearch", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    JsonNode songs = jsonNode.path("result").path("songs");
                    if (!songs.isArray()) return musicList;
                    for (JsonNode song : songs) {
                        List<String> artists = new ArrayList<>();
                        JsonNode artistNode = song.has("ar") ? song.path("ar") : song.path("artists");
                        artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                        String cover = albumCoverUrl(song.path("al"));
                        musicList.add(new Music(
                                song.path("id").asText(),
                                song.path("name").asText(),
                                artists,
                                song.path("dt").asLong(),
                                PLATFORM,
                                cover
                        ));
                    }
                    return musicList;
                });
    }

    /** 旧版搜索：/search 不返回时长与封面，需再查 /song/detail；仅作 cloudsearch 失败时的兜底。 */
    private Mono<List<Music>> legacySearch(String keyword, int offset, int limit) {
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&offset={offset}&limit={limit}&cookie={cookie}", keyword, offset, limit, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("search", response))
                .bodyToMono(JsonNode.class)
                .flatMap(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    JsonNode songs = jsonNode.path("result").path("songs");
                    if (!songs.isArray() || songs.size() == 0) {
                        return Mono.just(musicList);
                    }
                    List<String> ids = new ArrayList<>();
                    for (JsonNode song : songs) {
                        List<String> artists = new ArrayList<>();
                        JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                        artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                        musicList.add(new Music(
                                song.path("id").asText(),
                                song.path("name").asText(),
                                artists,
                                song.path("duration").asLong(),
                                PLATFORM,
                                ""
                        ));
                        ids.add(song.path("id").asText());
                    }
                    if (ids.isEmpty()) return Mono.just(musicList);
                    return webClient.get()
                            .uri(baseUrl + "/song/detail?ids={ids}&cookie={cookie}", String.join(",", ids), getCookie())
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .map(detailJson -> {
                                JsonNode detailSongs = detailJson.path("songs");
                                if (detailSongs.isArray()) {
                                    for (int i = 0; i < detailSongs.size() && i < musicList.size(); i++) {
                                        JsonNode detailSong = detailSongs.get(i);
                                        JsonNode al = detailSong.path("al");
                                        String cover = albumCoverUrl(al);
                                        if (StringUtils.hasText(cover)) {
                                            Music old = musicList.get(i);
                                            musicList.set(i, new Music(old.id(), old.name(), old.artists(), old.duration(), old.platform(), cover));
                                        }
                                    }
                                }
                                return musicList;
                            })
                            .onErrorReturn(musicList);
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String musicId) {
        ensureConfigured();
        return getMusicDetails(musicId)
                .map(music -> {
                    String localUrl = localCacheService.getLocalUrl(cacheId(musicId));
                    if (localUrl == null) {
                        prefetchMusic(musicId, DownloadPriority.URGENT);
                    }
                    return new PlayableMusic(
                            music.id(), music.name(), music.artists(), music.duration(), music.platform(),
                            localUrl == null ? "PENDING_DOWNLOAD" : localUrl, music.coverUrl(), false
                    );
                });
    }

    @Override
    public void prefetchMusic(String musicId) {
        prefetchMusic(musicId, DownloadPriority.NORMAL);
    }

    @Override
    public void prefetchMusic(String musicId, DownloadPriority priority) {
        ensureConfigured();
        String cacheId = cacheId(musicId);
        CacheStatus status = localCacheService.getStatus(cacheId);
        if (status == CacheStatus.COMPLETED || status == CacheStatus.PENDING || status == CacheStatus.DOWNLOADING) {
            return;
        }

        localCacheService.submitDownload(cacheId, resolveMusicUrl(musicId)
                .map(url -> new LocalCacheService.DownloadSource(
                        upgradeToHttps(url),
                        Map.of(
                                "Referer", "https://music.163.com/",
                                "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
                        ),
                        extensionFor(url)
                )), priority);
    }

    /**
     * 取流地址解析：网易云网关对个别曲目会瞬时返回 502 read ETIMEDOUT
     * （实测：同一请求数秒后即 200，账号与 cookie 均正常，属上游抖动），
     * 故此处随身退避重试，并逐级降音质（exhigh → higher → standard）：
     * 降一档仍可播，也远胜整首失败。
     */
    // package-private：供 NeteaseMusicApiServiceTest 直接验证重试/降档行为
    Mono<String> resolveMusicUrl(String musicId) {
        List<String> levels = new ArrayList<>();
        if (StringUtils.hasText(quality)) {
            levels.add(quality);
        }
        for (String level : QUALITY_LADDER) {
            if (!levels.contains(level)) {
                levels.add(level);
            }
        }
        // 实测 2026-11-10：网易云网关偶发「连接建立成功但整段不回包」，
        // 没有总预算时单个任务会把串行下载队列堵满 4 分钟（外层 TASK_TIMEOUT），
        // 期间其他用户点的歌全部排在后面。重试 + 降档整体不超过 resolveTimeout。
        return resolveMusicUrl(musicId, levels, 0)
                .timeout(resolveTimeout)
                .onErrorMap(TimeoutException.class, error -> new ApiRequestException(
                        "网易云取流地址解析超时（" + resolveTimeout.toSeconds() + "s），已跳过本曲"));
    }

    private Mono<String> resolveMusicUrl(String musicId, List<String> levels, int index) {
        if (index >= levels.size()) {
            return Mono.error(new ApiRequestException(
                    "网易云未返回可播放音频地址（已试音质：" + String.join("/", levels) + "）"));
        }
        String level = levels.get(index);
        return webClient.get()
                .uri(baseUrl + "/song/url/v1?id={musicId}&level={level}&cookie={cookie}", musicId, level, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song URL", response))
                .bodyToMono(JsonNode.class)
                // 注意：Jackson 的 asText() 对 null 节点会返回字符串 "null"，
                // 会被 hasText() 误判为有效地址（不可播曲目因此去下载 “null”）。
                .map(jsonNode -> {
                    JsonNode urlNode = jsonNode.path("data").path(0).path("url");
                    return urlNode.isTextual() ? urlNode.asText() : "";
                })
                .filter(StringUtils::hasText)
                .switchIfEmpty(Mono.error(new ApiRequestException(NO_URL_MESSAGE)))
                .retryWhen(Retry.backoff(URL_RESOLVE_ATTEMPTS, URL_RESOLVE_BACKOFF)
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(NeteaseMusicApiService::isTransientFailure)
                        .doBeforeRetry(signal -> log.warn(
                                "Netease URL resolve retry {}/{} (id={}, level={}): {}",
                                signal.totalRetries() + 1, URL_RESOLVE_ATTEMPTS, musicId, level,
                                signal.failure().getMessage())))
                // 本档位失败（含「未返回地址」）：退到下一档音质再试
                .onErrorResume(error -> resolveMusicUrl(musicId, levels, index + 1));
    }

    /** 网关瞬时故障（5xx / 网关超时 / 连接中断）值得重试；鉴权或参数错误则不必。 */
    private static boolean isTransientFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            final String message = cause.getMessage();
            // 連不上（Connection refused / ECONNREFUSED）＝ 服務本身不可用：立即失敗，重試無意義
            if (message != null && (message.contains("Connection refused") || message.contains("ECONNREFUSED"))) {
                return false;
            }
            if (cause instanceof TimeoutException || cause instanceof IOException
                    || cause instanceof WebClientRequestException) {
                return true;
            }
            if (cause instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 408 || status == 429 || status >= 500;
            }
            if (message != null && (message.contains("ETIMEDOUT")
                    || message.contains("Connection reset")
                    || message.contains("timed out")
                    || message.matches(".*status 5\\d\\d.*"))) {
                return true;
            }
        }
        return false;
    }

    private String cacheId(String musicId) {
        return PLATFORM + "_" + musicId;
    }

    private String extensionFor(String url) {
        try {
            String path = URI.create(url).getPath();
            int lastDot = path.lastIndexOf('.');
            if (lastDot >= 0) {
                String extension = path.substring(lastDot);
                if (extension.matches("\\.[A-Za-z0-9]{2,5}")) {
                    return extension;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // The cache downloader will report malformed source URLs through its normal retry path.
        }
        return ".mp3";
    }

    private Mono<Music> getMusicDetails(String musicId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/song/detail?ids={musicId}&cookie={cookie}", musicId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get song detail", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    JsonNode song = jsonNode.path("songs").get(0);
                    List<String> artists = new ArrayList<>();
                    JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                    artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                    return new Music(
                            song.path("id").asText(),
                            song.path("name").asText(),
                            artists,
                            song.path("dt").asLong(),
                            PLATFORM,
                            songCoverUrl(song)
                    );
                });
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/user/playlist?uid={userId}&cookie={cookie}", userId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get user playlists", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Playlist> playlists = new ArrayList<>();
                    jsonNode.path("playlist").forEach(pl -> playlists.add(new Playlist(
                            pl.path("id").asText(),
                            pl.path("name").asText(),
                            albumCoverUrl(pl),
                            pl.path("trackCount").asInt(),
                            PLATFORM
                    )));
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/playlist/track/all?id={playlistId}&limit={limit}&offset={offset}&cookie={cookie}", playlistId, limit, offset, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get playlist tracks", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    jsonNode.path("songs").forEach(song -> {
                        JsonNode artistNode = song.has("artists") ? song.path("artists") : song.path("ar");
                        List<String> artists = StreamSupport.stream(artistNode.spliterator(), false)
                                .map(artist -> artist.path("name").asText())
                                .toList();
                        musicList.add(new Music(
                                song.path("id").asText(),
                                song.path("name").asText(),
                                artists,
                                song.path("dt").asLong(),
                                PLATFORM,
                                songCoverUrl(song)
                        ));
                    });
                    return musicList;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        ensureConfigured();
        // type=1002 表示搜索用户
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&type=1002&cookie={cookie}", keyword, getCookie())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<UserSearchResult> users = new ArrayList<>();
                    // 网易云返回结构: result.userprofiles
                    JsonNode profiles = jsonNode.path("result").path("userprofiles");
                    if (profiles.isArray()) {
                        profiles.forEach(u -> users.add(new UserSearchResult(
                                u.path("userId").asText(),
                                u.path("nickname").asText(),
                                upgradeToHttps(u.path("avatarUrl").asText()),
                                PLATFORM
                        )));
                    }
                    return users;
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/lyric?id={id}", musicId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    if (json.has("lrc") && json.get("lrc").has("lyric")) {
                        return json.get("lrc").get("lyric").asText();
                    }
                    return "";
                })
                .onErrorReturn("");
    }

    @Override
    public Mono<List<AlbumSearchResult>> searchAlbums(String keyword) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/search?keywords={keyword}&type=10&cookie={cookie}", keyword, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("search albums", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<AlbumSearchResult> albums = new ArrayList<>();
                    JsonNode albumNodes = jsonNode.path("result").path("albums");
                    if (albumNodes.isArray()) {
                        for (JsonNode album : albumNodes) {
                            albums.add(new AlbumSearchResult(
                                    album.path("id").asText(),
                                    album.path("name").asText(),
                                    album.path("artist").path("name").asText(),
                                    albumCoverUrl(album),
                                    album.path("size").asInt(),
                                    PLATFORM
                            ));
                        }
                    }
                    return albums;
                });
    }

    @Override
    public Mono<List<Music>> getAlbumMusics(String albumId) {
        ensureConfigured();
        return webClient.get()
                .uri(baseUrl + "/album?id={albumId}&cookie={cookie}", albumId, getCookie())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> handleApiError("get album tracks", response))
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();
                    JsonNode songs = jsonNode.path("songs");
                    if (songs.isArray()) {
                        for (JsonNode song : songs) {
                            List<String> artists = new ArrayList<>();
                            JsonNode artistNode = song.has("ar") ? song.path("ar") : song.path("artists");
                            if (artistNode.isArray()) {
                                artistNode.forEach(artist -> artists.add(artist.path("name").asText()));
                            }
                            musicList.add(new Music(
                                    song.path("id").asText(),
                                    song.path("name").asText(),
                                    artists,
                                    song.path("dt").asLong(),
                                    PLATFORM,
                                    songCoverUrl(song)
                            ));
                        }
                    }
                    return musicList;
                });
    }
}
