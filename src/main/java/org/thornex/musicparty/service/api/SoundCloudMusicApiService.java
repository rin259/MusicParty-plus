package org.thornex.musicparty.service.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.SoundCloudOAuthService;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SoundCloudMusicApiService extends YtDlpMusicApiService {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudMusicApiService.class);
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String LIKES_PREFIX = "likes:";
    private static final String MY_TRACKS_PREFIX = "mytracks:";
    /** own:<handle>:<playlistId> —— 带 token 取的歌单（可能包含私密） */
    private static final String OWN_PLAYLIST_PREFIX = "own:";

    private final SoundCloudSearchClient searchClient;
    private final SoundCloudOAuthService oauthService;
    private final WebClient webClient;
    private final AppProperties appProperties;

    public SoundCloudMusicApiService(LocalCacheService localCacheService, YtDlpResolver resolver,
                                     AppProperties properties, SoundCloudSearchClient searchClient,
                                     SoundCloudOAuthService oauthService, WebClient webClient) {
        super("soundcloud", localCacheService, resolver, properties.getSoundcloud().getSearchLimit(),
                properties.getSoundcloud().getFetchLimit());
        this.searchClient = searchClient;
        this.oauthService = oauthService;
        this.webClient = webClient;
        this.appProperties = properties;
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
        // OAuth 绑定后最先出现的三个入口，是公开接口根本拿不到的：
        // 喜欢的音乐、我自己的歌单（含私密）、我上传的曲目。
        Playlist likes = new Playlist(LIKES_PREFIX + handle, "喜欢的音乐", "", 0, getPlatformName());
        Playlist myTracks = new Playlist(MY_TRACKS_PREFIX + handle, "我上传的曲目", "", 0, getPlatformName());

        Mono<List<Playlist>> publicSets = resolver.getSoundCloudUserSets(handle)
                .map(sets -> sets.stream()
                        .map(playlist -> new Playlist(playlist.id(), playlist.name(), playlist.coverUrl(),
                                playlist.trackCount(), getPlatformName()))
                        .toList())
                .onErrorResume(error -> Mono.just(List.of()));

        Mono<List<Playlist>> ownPlaylists = ownPlaylistsWithToken(handle)
                .onErrorResume(error -> Mono.empty())
                .defaultIfEmpty(List.of());

        return ownPlaylists.zipWith(publicSets, (own, sets) -> {
            List<Playlist> merged = new java.util.ArrayList<>();
            merged.add(likes);
            merged.add(myTracks);
            merged.addAll(own);
            // 公开 Sets 与自己的歌单按底层 id 去重（自己那份更完整，含私密）
            java.util.Set<String> seen = own.stream().map(SoundCloudMusicApiService::underlyingPlaylistId)
                    .collect(java.util.stream.Collectors.toSet());
            sets.stream()
                    .filter(playlist -> !seen.contains(playlist.id()))
                    .forEach(merged::add);
            return merged;
        });
    }

    private static String underlyingPlaylistId(Playlist playlist) {
        String id = playlist.id();
        if (id.startsWith(OWN_PLAYLIST_PREFIX)) {
            int second = id.indexOf(':', OWN_PLAYLIST_PREFIX.length());
            return second > 0 ? id.substring(second + 1) : id;
        }
        return id;
    }

    /** GET /me/playlists —— 含私密歌单（仅 token 可读） */
    private Mono<List<Playlist>> ownPlaylistsWithToken(String handle) {
        return Mono.defer(() -> {
            Optional<String> token = oauthService.validAccessToken(handle);
            if (token.isEmpty()) return Mono.empty();
            JsonNode response = authorizedGet(apiBase() + "/me/playlists?limit=100&linked_partitioning=1", token.get());
            return Mono.just(response)
                    .map(node -> {
                        List<Playlist> out = parseOwnPlaylists(handle, node, getPlatformName());
                        long privateCount = out.stream().filter(p -> p.name().endsWith(" · 私密")).count();
                        log.info("SoundCloud /me/playlists for {}: {} playlists ({} private)", handle, out.size(), privateCount);
                        return out;
                    });
        });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        if (playlistId.startsWith(LIKES_PREFIX)) {
            String handle = playlistId.substring(LIKES_PREFIX.length());
            return likedTracks(handle, offset, limit).map(this::toMusicList);
        }
        if (playlistId.startsWith(MY_TRACKS_PREFIX)) {
            String handle = playlistId.substring(MY_TRACKS_PREFIX.length());
            return userScopedTracks(handle, "https://api.soundcloud.com/me/tracks?limit="
                            + Math.clamp(limit, 1, 200) + "&offset=" + Math.max(offset, 0))
                    .map(this::toMusicList)
                    .onErrorResume(error -> Mono.error(new ApiRequestException("读取「我上传的曲目」失败，请重新绑定授权")));
        }
        if (playlistId.startsWith(OWN_PLAYLIST_PREFIX)) {
            // own:<handle>:<id>
            String rest = playlistId.substring(OWN_PLAYLIST_PREFIX.length());
            int split = rest.indexOf(':');
            if (split <= 0) return Mono.error(new ApiRequestException("歌单标识无效"));
            String handle = rest.substring(0, split);
            String realId = rest.substring(split + 1);
            return ownPlaylistTracks(handle, realId, offset, limit)
                    .map(this::toMusicList)
                    .onErrorResume(error -> resolver.getSoundCloudPlaylistTracks(realId, offset, limit)
                            .map(this::toMusicList));
        }
        if (!playlistId.matches("\\d+")) {
            return Mono.error(new ApiRequestException("SoundCloud 歌单 ID 无效"));
        }
        return resolver.getSoundCloudPlaylistTracks(playlistId, offset, limit)
                .map(tracks -> tracks.stream()
                        .map(track -> new Music(track.id(), track.title(), List.of(track.artist()), track.durationMs(),
                                getPlatformName(), track.thumbnail()))
                        .toList());
    }


    /**
     * 带用户 token 的 GET。SoundCloud 的鉴权头写法在不同文档/版本里出现过
     * `Bearer <token>` 与 `OAuth <token>` 两种，这里都试一遍并记录哪个可用：
     * 与其猜，不如让服务器日志告诉我们事实。
     */
    private JsonNode authorizedGet(String url, String token) {
        RuntimeException last = null;
        for (String scheme : new String[]{"Bearer ", "OAuth "}) {
            try {
                JsonNode node = webClient.get()
                        .uri(URI.create(url))
                        .header("Authorization", scheme + token)
                        .retrieve()
                        .onStatus(status -> status.isError(),
                                response -> response.bodyToMono(String.class).map(body ->
                                        new IllegalStateException("HTTP " + response.statusCode().value()
                                                + " " + trim(body))))
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(12))
                        .block();
                log.info("SoundCloud auth ok with '{}' for {}", scheme.trim(), pathOf(url));
                return node;
            } catch (RuntimeException error) {
                log.warn("SoundCloud auth with '{}' failed for {}: {}", scheme.trim(), pathOf(url), error.getMessage());
                last = error;
            }
        }
        throw last == null ? new IllegalStateException("SoundCloud 请求失败") : last;
    }

    private String apiBase() {
        String base = appProperties.getSoundcloudOAuth().getApiBase();
        if (base == null || base.isBlank()) return "https://api.soundcloud.com";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String pathOf(String url) {
        int idx = url.indexOf("api.soundcloud.com");
        return idx >= 0 ? url.substring(idx + "api.soundcloud.com".length()) : url;
    }

    private static String trim(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private List<Music> toMusicList(List<YtDlpResolver.TrackMetadata> tracks) {
        return tracks.stream()
                .map(track -> new Music(track.id(), track.title(), List.of(track.artist()),
                        track.durationMs(), getPlatformName(), track.thumbnail(), track.pageUrl()))
                .toList();
    }

    /** 任何以 OAuth token 授权的用户级集合（/me/tracks 等），返回 collection 里的 track 列表 */
    private Mono<List<YtDlpResolver.TrackMetadata>> userScopedTracks(String handle, String url) {
        return Mono.defer(() -> {
            Optional<String> token = oauthService.validAccessToken(handle);
            if (token.isEmpty()) return Mono.error(new ApiRequestException("需要重新绑定 SoundCloud 以读取该列表"));
            JsonNode response = authorizedGet(url, token.get());
            return Mono.just(response).map(SoundCloudSearchClient::parseLikes);
        });
    }

    /**
     * 自己的歌单曲目（含私密）。按规范走 GET /playlists/{playlist_urn}/tracks，
     * urn 形如 soundcloud:playlists:<id>。该端点用 linked_partitioning 游标分页，
     * 我们的 UI 用 offset 分页，因此这里按 offset+limit 取一批再切片（上限 1000，
     * 超出部分走翻页的收益不值得把逻辑复杂化）。
     */
    private Mono<List<YtDlpResolver.TrackMetadata>> ownPlaylistTracks(String handle, String playlistId, int offset, int limit) {
        return Mono.defer(() -> {
            Optional<String> token = oauthService.validAccessToken(handle);
            if (token.isEmpty()) return Mono.empty();
            int safeLimit = Math.clamp(limit, 1, 200);
            int safeOffset = Math.max(offset, 0);
            int want = Math.min(1000, safeOffset + safeLimit);
            String urn = "soundcloud:playlists:" + playlistId;
            JsonNode response = authorizedGet(apiBase() + "/playlists/"
                    + java.net.URLEncoder.encode(urn, java.nio.charset.StandardCharsets.UTF_8)
                    + "/tracks?limit=" + want + "&linked_partitioning=1", token.get());
            return Mono.just(response)
                    .map(node -> {
                        List<YtDlpResolver.TrackMetadata> all = SoundCloudSearchClient.parseLikes(node);
                        if (safeOffset >= all.size()) return List.<YtDlpResolver.TrackMetadata>of();
                        return all.subList(safeOffset, Math.min(all.size(), safeOffset + safeLimit));
                    });
        });
    }

    /**
     * 「喜欢的音乐」取数顺序：
     * 1) OAuth token 调 /me/likes/tracks（能拿到用户自己的完整点赞，含非公开项）
     * 2) 退回 api-v2 /users/{id}/likes（公开点赞，无需授权）
     * 3) 再退回 yt-dlp 的 likes 页面
     */
    private Mono<List<YtDlpResolver.TrackMetadata>> likedTracks(String handle, int offset, int limit) {
        // 注意：switchIfEmpty 的参数会被立即求值——必须包 Mono.defer，
        // 否则 token 成功时也会额外打一次公开接口（白费配额，还加速限流）
        return userLikesWithToken(handle, offset, limit)
                .filter(tracks -> !tracks.isEmpty())
                .switchIfEmpty(Mono.defer(() -> searchClient.likedTracks(handle, offset, limit)))
                .onErrorResume(error -> searchClient.likedTracks(handle, offset, limit))
                .flatMap(tracks -> tracks.isEmpty()
                        ? resolver.getSoundCloudUserLikes(handle, offset, limit)
                        : Mono.just(tracks));
    }

    private Mono<List<YtDlpResolver.TrackMetadata>> userLikesWithToken(String handle, int offset, int limit) {
        return Mono.defer(() -> {
            Optional<String> token = oauthService.validAccessToken(handle);
            if (token.isEmpty()) return Mono.empty();
            int safeLimit = Math.clamp(limit, 1, 200);
            int safeOffset = Math.max(offset, 0);
            JsonNode response = authorizedGet(apiBase() + "/me/likes/tracks"
                    + "?limit=" + safeLimit + "&offset=" + safeOffset + "&linked_partitioning=1", token.get());
            List<YtDlpResolver.TrackMetadata> tracks = SoundCloudSearchClient.parseLikes(response);
            log.info("SoundCloud /me/likes/tracks for {}: {} items (offset {})", handle, tracks.size(), safeOffset);
            return Mono.just(tracks);
        });
    }

    /**
     * 解析 /me/playlists（规范里只有 urn，没有 id）：抽成静态方法便于单测。
     * 私密歌单在名称后加「 · 私密」，前端据此区分（Playlist DTO 没有隐私字段）。
     */
    static List<Playlist> parseOwnPlaylists(String handle, JsonNode response, String platform) {
        List<Playlist> out = new java.util.ArrayList<>();
        for (JsonNode playlist : response.path("collection")) {
            long id = playlist.path("id").asLong(0L);
            if (id == 0L) id = SoundCloudSearchClient.numericIdFromUrn(playlist.path("urn").asText(""));
            String title = playlist.path("title").asText("");
            if (id == 0L || title.isEmpty()) continue;
            boolean isPrivate = !"public".equalsIgnoreCase(playlist.path("sharing").asText("public"));
            String cover = SoundCloudSearchClient.highResArtwork(playlist.path("artwork_url").asText(""));
            out.add(new Playlist(OWN_PLAYLIST_PREFIX + handle + ":" + id,
                    isPrivate ? title + " · 私密" : title,
                    cover, playlist.path("track_count").asInt(0), platform));
        }
        return out;
    }

    /**
     * 绑定诊断（管理员调用）：有 token 时逐个打真实接口并回报状态/计数，
     * 上线后一次性确认「哪个鉴权头可用、返回多少歌单、其中多少私密」。
     */
    public Mono<Map<String, Object>> diagnose(String handle) {
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            String normalized = normalizeHandle(handle);
            Optional<String> token = oauthService.validAccessToken(normalized);
            out.put("handle", normalized);
            out.put("tokenStored", token.isPresent());
            if (token.isEmpty()) return out;

            out.put("me", probe(apiBase() + "/me", token.get()));
            out.put("playlists", probePlaylists(normalized, token.get()));
            out.put("likes", probeCount(apiBase() + "/me/likes/tracks?limit=50", token.get()));
            out.put("myTracks", probeCount(apiBase() + "/me/tracks?limit=50", token.get()));
            return out;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Map<String, Object> probe(String url, String token) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            JsonNode node = authorizedGet(url, token);
            result.put("status", "ok");
            result.put("name", node.path("username").asText(""));
            result.put("urn", node.path("urn").asText(""));
            result.put("permalink", node.path("permalink_url").asText(""));
        } catch (RuntimeException error) {
            result.put("status", "error");
            result.put("message", error.getMessage());
        }
        return result;
    }

    private Map<String, Object> probeCount(String url, String token) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            JsonNode node = authorizedGet(url, token);
            result.put("status", "ok");
            result.put("count", SoundCloudSearchClient.parseLikes(node).size());
            JsonNode collection = node.path("collection");
            if (collection.isArray() && collection.size() > 0) {
                JsonNode item = collection.get(0);
                JsonNode track = item.has("track") ? item.path("track") : item;
                result.put("firstUrn", track.path("urn").asText(""));
                result.put("firstTitle", track.path("title").asText(""));
            }
        } catch (RuntimeException error) {
            result.put("status", "error");
            result.put("message", error.getMessage());
        }
        return result;
    }

    private Map<String, Object> probePlaylists(String handle, String token) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            JsonNode node = authorizedGet(
                    apiBase() + "/me/playlists?limit=100&linked_partitioning=1", token);
            List<Playlist> playlists = parseOwnPlaylists(handle, node, getPlatformName());
            result.put("status", "ok");
            result.put("count", playlists.size());
            result.put("privateCount", playlists.stream().filter(p -> p.name().endsWith(" · 私密")).count());
            result.put("names", playlists.stream().limit(8).map(Playlist::name).toList());
        } catch (RuntimeException error) {
            result.put("status", "error");
            result.put("message", error.getMessage());
        }
        return result;
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
    @Override
    public Mono<String> resolvePageUrl(String musicId) {
        return searchClient.resolvePermalink(musicId)
                .onErrorResume(error -> resolver.resolveSoundCloudMetadata(musicId)
                        .map(YtDlpResolver.TrackMetadata::pageUrl))
                .onErrorResume(error -> Mono.empty());
    }

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
