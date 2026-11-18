package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.LocalCacheService.DownloadPriority;
import org.thornex.musicparty.service.YtDlpResolver;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class BilibiliMusicApiService implements IMusicApiService {

    private final WebClient webClient;
    private final String baseUrl;
    private volatile String sessdata;
    private final LocalCacheService localCacheService;
    private static final String PLATFORM = "bilibili";
    private final BilibiliWbiService wbiService;
    private final YtDlpResolver ytDlpResolver;
    // 实测(2026-10-14)：仅发 SESSDATA 会被 B 站风控 412（playurl/网页尤甚），
    // 附上 buvid3 设备指纹后全通。每个 JVM 进程稳定一份。
    private final String buvid3 = UUID.randomUUID().toString().toUpperCase() + "infoc";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Cookie 头统一出口：SESSDATA(若有) + buvid3 设备指纹。 */
    private String cookieHeader() {
        String device = "buvid3=" + buvid3;
        return sessdata.isBlank() ? device : "SESSDATA=" + sessdata + "; " + device;
    }

    private static class WbiSignatureException extends RuntimeException {
        public WbiSignatureException(String message) { super(message); }
    }

    public BilibiliMusicApiService(WebClient webClient, AppProperties appProperties, LocalCacheService localCacheService, BilibiliWbiService wbiService, YtDlpResolver ytDlpResolver) {
        this.webClient = webClient;
        this.baseUrl = appProperties.getBilibili().getBaseUrl();
        this.sessdata = normalizeSessdata(appProperties.getBilibili().getSessdata());
        this.localCacheService = localCacheService;
        this.wbiService = wbiService;
        this.ytDlpResolver = ytDlpResolver;
    }

    private void ensureConfigured() {
        if (!org.springframework.util.StringUtils.hasText(sessdata)) {
            throw new ApiRequestException("尚未配置 Bilibili SESSDATA，请联系管理员设置");
        }
    }

    public void updateSessdata(String newSessdata) {
        this.sessdata = normalizeSessdata(newSessdata);
        this.wbiService.updateSessdata(this.sessdata);
        log.info("Bilibili API Service SESSDATA updated.");
    }

    /**
     * 管理端可能粘贴完整 cookie 对（"SESSDATA=xxx"），直接拼接会发出
     * "Cookie: SESSDATA=SESSDATA=xxx" 这种废头，反而触发 B 站 412 风控。
     * 此处统一剥掉前缀，保证各处拿到的是裸值。
     */
    private static String normalizeSessdata(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.regionMatches(true, 0, "SESSDATA=", 0, "SESSDATA=".length())) {
            value = value.substring("SESSDATA=".length()).trim();
        }
        return value;
    }

    public String getSessdata() {
        return sessdata;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }
    private WebClient.RequestHeadersSpec<?> buildBilibiliRequest(String uri) {
        return webClient.get()
                .uri(uri)
                .header("Cookie", cookieHeader())
                .header("Referer", "https://www.bilibili.com/");
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        return searchMusic(keyword, 0, 20);
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword, int offset, int limit) {
        ensureConfigured();
        int page = (offset / Math.max(limit, 1)) + 1;
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", String.valueOf(page));
        params.put("page_size", String.valueOf(Math.min(limit, 50)));

        // 2. 调用 WBI 签名服务
        Mono<List<Music>> requestMono = wbiService.signParams(params)
                .flatMap(signedParams -> {
                    // 3. 构建 QueryString，注意：WBI 签名要求参数顺序及 URL 编码
                    // 这里我们直接利用 UriComponentsBuilder 确保符合文档要求
                    UriComponentsBuilder builder = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                    signedParams.forEach(builder::queryParam);

                    return webClient.get()
                            .uri(builder.build().toUri()) // 使用编码后的 URI
                            .header("Cookie", cookieHeader())
                            .header("User-Agent", BROWSER_UA)
                            .header("Referer", "https://www.bilibili.com/") // 必须带 Referer
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .handle((json, sink) -> {
                                int code = json.path("code").asInt();
                                // 🟢 关键点 1: 检测 WBI 潜在的错误码
                                // -403: 访问权限不足 (可能是签名挂了)
                                // -400: 请求错误 (可能是参数/签名校验不过)
                                if (code == -403 || code == -400) {
                                    sink.error(new WbiSignatureException("WBI signature invalid, code: " + code));
                                    return;
                                }

                                // 其他常规错误，不重试，直接记录日志返回空列表
                                if (code != 0) {
                                    log.error("Bilibili search failed: {}", json.path("message").asText());
                                    sink.next(new ArrayList<>());
                                    return;
                                }


                                List<Music> musicList = new ArrayList<>();

                                JsonNode results = json.path("data").path("result");
                                if (results.isArray()) {
                                    results.forEach(video -> {
                                        // 清洗标题中的 <em class="keyword">xxx</em> 标签
                                        String rawTitle = video.path("title").asText();
                                        String cleanTitle = rawTitle.replaceAll("<[^>]*>", "");

                                        // 处理时长
                                        String durationStr = video.path("duration").asText();
                                        long durationMs = BilibiliApiUtils.durationToMillis(durationStr);

                                        // 获取图片，确保有 https
                                        String picUrl = video.path("pic").asText();
                                        if (!picUrl.startsWith("http")) {
                                            picUrl = "https:" + picUrl;
                                        }

                                        musicList.add(new Music(
                                                video.path("bvid").asText(),
                                                cleanTitle,
                                                List.of(video.path("author").asText()),
                                                durationMs,
                                                PLATFORM,
                                                picUrl));
                                    });
                                }
                                sink.next(musicList);
                            });
                });

        // 0 命中重试：B 站风控/边缘节点偶尔返回 200 + 空 result，冷一次就恢复。
        // 只重试一次（250ms 后），仍为空则如实返回空列表；代价是「真的没有结果」的查询会多打一次上游。
        Mono<List<Music>> withEmptyRetry = requestMono.flatMap(list -> {
            if (list != null && !list.isEmpty()) {
                return Mono.just(list);
            }
            log.info("Bilibili search returned 0 for '{}', retrying once", keyword);
            return Mono.delay(Duration.ofMillis(250)).then(requestMono);
        });

        // 添加重试机制
        return withEmptyRetry.retryWhen(Retry.max(1) // 最多重试 1 次
                        .filter(throwable -> throwable instanceof WbiSignatureException) // 只针对签名异常重试
                        .doBeforeRetry(retrySignal -> {
                            log.warn("Detected WBI signature error, refreshing key and retrying...");
                            wbiService.invalidateCache(); // 清除缓存
                        }))
                // 如果重试后还是失败，降级为空列表
                .onErrorResume(WbiSignatureException.class, e -> {
                    log.error("Bilibili search failed after retry: {}", e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

    @Override
    public void prefetchMusic(String bvid) {
        prefetchMusic(bvid, DownloadPriority.NORMAL);
    }

    @Override
    public void prefetchMusic(String bvid, DownloadPriority priority) {
        ensureConfigured();
        // 检查缓存状态，如果已经下载或正在下载，直接返回
        CacheStatus status = localCacheService.getStatus(bvid);
        if (status == CacheStatus.COMPLETED || status == CacheStatus.PENDING || status == CacheStatus.DOWNLOADING) {
            localCacheService.promoteDownload(bvid, priority);
            return;
        }

        log.info("Prefetching Bilibili music: {}", bvid);

        // 复用之前的解析逻辑，获取 DASH 音频流地址
        Mono<String> urlProvider = resolveDashAudioUrl(bvid)
                .onErrorResume(error -> {
                    log.warn("Bilibili API audio resolution failed for {} ({}), trying yt-dlp", bvid, error.getMessage());
                    return ytDlpResolver.resolveBilibiliAudioUrl(bvid, sessdata);
                });

        // 准备请求头 (防盗链)
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/video/" + bvid);
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        // 提交异步下载任务 (.m4a 是 B站 dash 音频的常用格式)
        localCacheService.submitDownload(bvid, urlProvider, headers, ".m4a", priority);
    }

    @Override
    public Mono<Music> getMusicMetadata(String bvid) {
        ensureConfigured();
        return BilibiliApiUtils.getVideoDetails(bvid, webClient, baseUrl, sessdata);
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String bvid) {
        ensureConfigured();
        // 1. 检查本地缓存
        String localUrl = localCacheService.getLocalUrl(bvid);

        if (localUrl != null) {
            // 2. 如果本地存在，直接返回静态资源路径
            // 此时 needsProxy = false，因为对于前端来说，这就是一个普通的 http 链接
            return BilibiliApiUtils.getVideoDetails(bvid, webClient, baseUrl, sessdata)
                    .map(music -> new PlayableMusic(
                            music.id(), music.name(), music.artists(), music.duration(),
                            PLATFORM, localUrl, music.coverUrl(), false // needsProxy = false
                    ));
        } else {
            // 3. 如果本地没有（可能是下载失败，或者还没下载完就被强制切歌）
            // 触发一次预加载（如果任务不存在的话）
            prefetchMusic(bvid, DownloadPriority.URGENT);

            // 即使在下载中，也返回元数据，但 URL 设为特殊值
            // 这样 MusicPlayerService.enqueue 就能拿到名字、封面等信息成功入队
            return BilibiliApiUtils.getVideoDetails(bvid, webClient, baseUrl, sessdata)
                    .map(music -> new PlayableMusic(
                            music.id(), music.name(), music.artists(), music.duration(),
                            PLATFORM, "PENDING_DOWNLOAD", music.coverUrl(), false
                    ));
        }
    }

    private Mono<String> resolveDashAudioUrl(String bvid) {
        return BilibiliApiUtils.getVideoCid(bvid, webClient, baseUrl, sessdata)
                .flatMap(cid -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("bvid", bvid);
                    params.put("cid", cid);
                    params.put("fnval", "16"); // DASH

                    return wbiService.signParams(params)
                            .flatMap(signedParams -> {
                                UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/x/player/wbi/playurl");
                                signedParams.forEach(builder::queryParam);

                                return webClient.get()
                                        .uri(builder.build().toUri())
                                        .header("Cookie", cookieHeader())
                                        .header("User-Agent", BROWSER_UA)
                                        .header("Referer", "https://www.bilibili.com/video/" + bvid)
                                        .retrieve()
                                        .bodyToMono(JsonNode.class)
                                        .flatMap(jsonNode -> {
                                            int code = jsonNode.path("code").asInt();
                                            if (code == -403 || code == -400) {
                                                return Mono.error(new WbiSignatureException("Invalid WBI signature, code: " + code));
                                            }
                                            if (code != 0) {
                                                return Mono.error(new ApiRequestException("Bilibili API Error, code: " + code));
                                            }
                                            JsonNode audioStreams = jsonNode.path("data").path("dash").path("audio");
                                            if (audioStreams.isMissingNode()) {
                                                return Mono.error(new ApiRequestException("No DASH audio found"));
                                            }

                                            String url = StreamSupport.stream(audioStreams.spliterator(), false)
                                                    .max(Comparator.comparingInt(a -> a.path("id").asInt()))
                                                    .map(a -> a.path("baseUrl").asText())
                                                    .orElseThrow(() -> new ApiRequestException("No audio url found in json"));
                                            return Mono.just(url);
                                        });
                            })
                            // 统一重试：WBI 签名失效(重签) + B 站风控 412/429(指数退避)。
                            // 2026-10-13 实测：风控窗口内直叩与 yt-dlp 会同时失败，
                            // 稍候重试即可自愈，避免直接把失败抛给用户。
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                                    .jitter(0.5)
                                    .filter(throwable -> {
                                        if (throwable instanceof WbiSignatureException) return true;
                                        if (throwable instanceof WebClientResponseException wcre) {
                                            int status = wcre.getStatusCode().value();
                                            return status == 412 || status == 429;
                                        }
                                        return false;
                                    })
                                    .doBeforeRetry(signal -> {
                                        if (signal.failure() instanceof WbiSignatureException) {
                                            log.warn("WBI signature error on getting play url. Invalidating cache and retrying...");
                                            wbiService.invalidateCache();
                                        } else {
                                            log.warn("Bilibili playurl risk-control (412/429), backing off and retrying: {}",
                                                    signal.failure().getMessage());
                                        }
                                    })
                            );
                });
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        ensureConfigured();
        // API: /x/v3/fav/folder/created/list-all
        // 参数: up_mid (目标用户ID)
        // 注意：移除了 type=2，以获取所有类型的收藏夹
        String favListApi = baseUrl + "/x/v3/fav/folder/created/list-all";

        // 构建 URI
        String uri = UriComponentsBuilder.fromHttpUrl(favListApi)
                .queryParam("up_mid", userId)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) {
                        // 如果用户隐私设置导致无法获取，或者用户不存在，返回空列表而非报错
                        log.warn("Failed to get Bilibili favorites for user {}: {}", userId, jsonNode.path("message").asText());
                        return new ArrayList<>();
                    }

                    List<Playlist> playlists = new ArrayList<>();
                    JsonNode list = jsonNode.path("data").path("list");

                    if (list.isArray()) {
                        list.forEach(fav -> {
                            // 过滤掉媒体数为0的空收藏夹
                            int count = fav.path("media_count").asInt();
                            if (count > 0) {
                                playlists.add(new Playlist(
                                        fav.path("id").asText(), // 这里是 media_id / fid
                                        fav.path("title").asText(),
                                        // B站收藏夹有时候没有封面，可以用默认图，或者取第一张
                                        // cover 字段通常存在
                                        fav.path("cover").asText(),
                                        count,
                                        PLATFORM
                                ));
                            }
                        });
                    }
                    return playlists;
                });
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        ensureConfigured();
        int safeLimit = Math.min(limit, 20);

        int pageNumber = (offset / safeLimit) + 1;

        // API: /x/v3/fav/resource/list
        // 关键参数: media_id (收藏夹ID), pn (页码), ps (页大小), platform=web
        String favDetailApi = baseUrl + "/x/v3/fav/resource/list";

        String uri = UriComponentsBuilder.fromHttpUrl(favDetailApi)
                .queryParam("media_id", playlistId)
                .queryParam("ps", safeLimit)
                .queryParam("pn", pageNumber)
                .build()
                .toUriString();

        return buildBilibiliRequest(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    List<Music> musicList = new ArrayList<>();

                    int code = jsonNode.path("code").asInt();
                    if (code != 0) {
                        // -404 通常表示空页或没有权限，视为正常结束
                        if (code == -404) return musicList;
                        log.error("Failed to get Bilibili favorite details: {}", jsonNode.path("message").asText());
                        return musicList;
                    }

                    JsonNode medias = jsonNode.path("data").path("medias");
                    // 注意：如果是空文件夹，medias可能是 null
                    if (medias.isArray()) {
                        medias.forEach(media -> {
                            String title = media.path("title").asText();
                            // 过滤失效视频
                            if ("已失效视频".equals(title)) {
                                musicList.add(new Music(
                                        "INVALID_SKIP", // 特殊 ID
                                        "已失效视频",
                                        List.of("Unknown"),
                                        0,
                                        PLATFORM,
                                        ""
                                ));
                                return; // 结束当前循环，继续下一个
                                }

                            // 构造 Music 对象
                            musicList.add(new Music(
                                    media.path("bvid").asText(),
                                    title,
                                    List.of(media.path("upper").path("name").asText()),
                                    media.path("duration").asLong() * 1000,
                                    PLATFORM,
                                    media.path("cover").asText()
                            ));
                        });
                    }
                    return musicList;
                });
    }

    /**
     * B 站接口按页码（pn）取数，所以「下一页偏移」必须对齐到页边界。
     * 默认实现用 offset + 本页条数，一旦某页条数少于 limit（失效视频被过滤等），
     * 偏移就不再是 limit 的整数倍，pn 会把同一页再取一次。
     */
    @Override
    public Mono<PagedPlaylist> getPlaylistMusicsPaged(String playlistId, int offset, int limit) {
        int safeLimit = Math.clamp(limit, 1, 20);
        int safeOffset = Math.max(0, offset);
        return getPlaylistMusics(playlistId, safeOffset, safeLimit)
                .map(items -> new PagedPlaylist(items, nextOffsetAlignedToPage(safeOffset, safeLimit),
                        items.size() >= safeLimit));
    }

    /** 下一页偏移必须落在页边界上（接口按 pn 取数，不是按 offset）。 */
    static int nextOffsetAlignedToPage(int offset, int limit) {
        int safeLimit = Math.clamp(limit, 1, 20);
        int safeOffset = Math.max(0, offset);
        return (safeOffset / safeLimit + 1) * safeLimit;
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        ensureConfigured();
        // 1. 准备 WBI 搜索参数
        Map<String, String> params = new HashMap<>();
        params.put("search_type", "bili_user"); // 搜索用户类型
        params.put("keyword", keyword);
        // params.put("page", "1"); // 默认第1页，可选

        // 2. 调用 WBI 签名服务
        return wbiService.signParams(params)
                .flatMap(signedParams -> {
                    // 3. 构建 URL: /x/web-interface/wbi/search/type
                    UriComponentsBuilder builder = UriComponentsBuilder
                            .fromHttpUrl(baseUrl + "/x/web-interface/wbi/search/type");

                    signedParams.forEach(builder::queryParam);

                    return webClient.get()
                            .uri(builder.build().toUri())
                            .header("Cookie", "SESSDATA=" + sessdata)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com/")
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .map(jsonNode -> {
                                List<UserSearchResult> users = new ArrayList<>();

                                if (jsonNode.path("code").asInt() != 0) {
                                    log.error("Bilibili user search failed: {}", jsonNode.path("message").asText());
                                    return users;
                                }

                                JsonNode results = jsonNode.path("data").path("result");
                                if (results.isArray()) {
                                    results.forEach(u -> {
                                        String pic = u.path("upic").asText();
                                        if (!pic.startsWith("http")) {
                                            pic = "https:" + pic;
                                        }
                                        users.add(new UserSearchResult(
                                                u.path("mid").asText(),
                                                u.path("uname").asText(),
                                                pic,
                                                PLATFORM
                                        ));
                                    });
                                }
                                return users;
                            });
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        return Mono.just(""); // B站暂时不支持歌词
    }
}
