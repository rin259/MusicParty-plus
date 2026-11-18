package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Uses the pinned yt-dlp binary for server-side metadata and audio resolution. */
@Service
@Slf4j
public class YtDlpResolver {

    /**
     * @param sourceUrl 解析/校验用的地址（可能指向 API 端点）
     * @param pageUrl   用户可读的源站页面地址；拿不到时为 null（SoundCloud 的数字 id 拼不出页面）
     */
    public record TrackMetadata(String id, String title, String artist, long durationMs, String thumbnail,
                                String sourceUrl, String pageUrl) {
        public TrackMetadata(String id, String title, String artist, long durationMs, String thumbnail, String sourceUrl) {
            this(id, title, artist, durationMs, thumbnail, sourceUrl, null);
        }
    }
    public record DownloadedAudio(Path file, String extension) {}
    public record SoundCloudUser(String handle, String name, String avatarUrl) {}
    public record PlaylistMetadata(String id, String name, String coverUrl, int trackCount) {}

    private final String executable;
    private final boolean enabled;
    private final long timeoutSeconds;
    private final long downloadTimeoutSeconds;
    private final int downloadConcurrency;
    private final String cookiesFile;
    private final String jsRuntime;
    private final String youtubePlayerClient;
    private final String youtubeFallbackFormat;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.Semaphore metadataProcesses = new java.util.concurrent.Semaphore(2, true);
    /**
     * One YouTube session, one writer. Rotating __Secure-*PSIDTS tokens issued to
     * concurrent runs cannot all be current, and Google drops the session when it
     * sees a token chain that cannot be reconciled.
     */
    private final java.util.concurrent.Semaphore youtubeSession;
    private final java.util.concurrent.Semaphore downloadProcesses;
    private static final String BUVID3 = java.util.UUID.randomUUID().toString().toUpperCase() + "infoc";
    /** m4a first so the browser can play it everywhere; Opus/WebM as a fallback. */
    private static final String AUDIO_FORMAT = "bestaudio[ext=m4a]/bestaudio";

    public YtDlpResolver(AppProperties appProperties, ObjectMapper objectMapper) {
        this.executable = appProperties.getYtDlp().getPath();
        this.enabled = appProperties.getYtDlp().isEnabled();
        this.timeoutSeconds = appProperties.getYtDlp().getTimeoutSeconds();
        this.downloadTimeoutSeconds = appProperties.getYtDlp().getDownloadTimeoutSeconds();
        this.downloadConcurrency = Math.clamp(appProperties.getYtDlp().getDownloadConcurrency(), 1, 64);
        this.cookiesFile = appProperties.getYtDlp().getCookiesFile();
        this.jsRuntime = appProperties.getYtDlp().getJsRuntime();
        this.youtubePlayerClient = appProperties.getYtDlp().getYoutubePlayerClient();
        this.youtubeFallbackFormat = appProperties.getYtDlp().getYoutubeFallbackFormat();
        this.objectMapper = objectMapper;
        this.downloadProcesses = new java.util.concurrent.Semaphore(
                Math.max(1, appProperties.getPreparation().getYtDlpConcurrency()), true);
        this.youtubeSession = new java.util.concurrent.Semaphore(
                Math.clamp(appProperties.getYtDlp().getYoutubeConcurrency(), 1, 8), true);
    }

    @jakarta.annotation.PostConstruct
    void logConfiguration() {
        if (!enabled) return;
        Path cookies = youtubeCookiesFile();
        String executableVersion;
        try {
            executableVersion = runCommand(List.of(executable, "--version"), "version", 10);
        } catch (Exception error) {
            executableVersion = "unavailable: " + error.getMessage();
        }
        log.info("yt-dlp {} | youtube cookies: {}", executableVersion,
                cookies == null ? "none (YouTube may reject anonymous streaming)" : cookies);
    }

    public Mono<String> resolveBilibiliAudioUrl(String bvid, String sessdata) {
        requireEnabled();
        return Mono.fromCallable(() -> runBilibiliResolver(bvid, sessdata))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    public Mono<List<TrackMetadata>> searchSoundCloud(String keyword, int limit) {
        return search("scsearch" + limit + ":" + keyword, "soundcloud",
                "--extractor-args", "soundcloud:formats=skip");
    }

    public Mono<List<TrackMetadata>> searchYoutubeMusic(String keyword, int limit) {
        return search("ytsearch" + limit + ":" + keyword, "ytmusic");
    }

    public Mono<TrackMetadata> resolveSoundCloudMetadata(String trackId) {
        return resolveMetadata("https://api.soundcloud.com/tracks/" + trackId, "soundcloud");
    }

    /** 粘贴 SoundCloud 单曲页链接时，先解析出数字 ID，再走正常的点歌链路。 */
    public Mono<TrackMetadata> resolveSoundCloudUrl(String url) {
        return resolveMetadata(url, "soundcloud");
    }

    public Mono<Void> validateSoundCloudAudio(String sourceUrl) {
        requireEnabled();
        return Mono.fromRunnable(() -> {
                    try {
                        runCommand(List.of(executable, "--no-playlist", "--no-warnings", "--quiet",
                                "--simulate", "--format", "bestaudio", "--print", "%(id)s", sourceUrl),
                                "SoundCloud audio validation");
                    } catch (Exception error) {
                        throw new IllegalStateException(error.getMessage(), error);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5))
                .then();
    }

    public Mono<TrackMetadata> resolveYoutubeMusicMetadata(String videoId) {
        return resolveMetadata("https://music.youtube.com/watch?v=" + videoId, "ytmusic");
    }

    public Mono<SoundCloudUser> resolveSoundCloudUser(String handle) {
        requireEnabled();
        return Mono.fromCallable(() -> parseSoundCloudUser(
                        runJsonCommand(List.of("--flat-playlist", "--dump-single-json", soundCloudProfileUrl(handle))), handle))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    public Mono<List<PlaylistMetadata>> getSoundCloudUserSets(String handle) {
        requireEnabled();
        return Mono.fromCallable(() -> parseSoundCloudPlaylists(
                        runJsonCommand(List.of("--flat-playlist", "--dump-single-json", "--playlist-end", "100",
                                soundCloudProfileUrl(handle) + "/sets"))))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    /** playlistId → 曲目总数（5 分钟缓存；-1 表示未知） */
    private final java.util.Map<String, Integer> playlistCountCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> playlistCountAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 公开 Set 的曲目。
     *
     * <p>坑（2026-11 实测）：
     *  - 只把 offset/limit 传给 --playlist-start/--playlist-end 时，曲目数少于 offset 的 set
     *    仍然返回整份（offset=50 也拿回同样的 3 首）→ 前端重复、提前判定到结尾；
     *  - 一次取全量对 140 首的 set 会超时（yt-dlp 逐条抽取元数据）。
     * 所以：先用 --flat-playlist 拿「总数」（快，2s 级，缓存 5 分钟），再按范围抽取并夹住上界。
     */
    public Mono<List<TrackMetadata>> getSoundCloudPlaylistTracks(String playlistId, int offset, int limit) {
        requireEnabled();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.clamp(limit, 1, 100);
        return Mono.fromCallable(() -> {
                    int count = playlistCount(playlistId);
                    if (count >= 0 && safeOffset >= count) return List.<TrackMetadata>of();

                    int end = count >= 0 ? Math.min(count, safeOffset + safeLimit) : safeOffset + safeLimit;
                    List<TrackMetadata> items = parseSearchResult(runJsonCommand(List.of(
                            "--dump-single-json",
                            "--playlist-start", String.valueOf(safeOffset + 1),
                            "--playlist-end", String.valueOf(end),
                            "https://api.soundcloud.com/playlists/" + playlistId)), "soundcloud");

                    // 某些 yt-dlp 版本会忽略范围、直接返回整份：按请求窗口截断
                    int window = end - safeOffset;
                    if (items.size() > window) items = new java.util.ArrayList<>(items.subList(0, window));
                    return items;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    /** playlistId → 曲目 id 列表（flat 抽取，5 分钟缓存） */
    private final java.util.Map<String, List<String>> playlistIdsCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 只取歌单的曲目 id 列表（--flat-playlist，秒级）。元数据由 api-v2 的 /tracks?ids= 批量补全，
     * 因为逐条完整抽取在 140 首的歌单上会超时。
     */
    public Mono<List<String>> getSoundCloudPlaylistTrackIds(String playlistId) {
        requireEnabled();
        return Mono.fromCallable(() -> playlistIdsCache.computeIfAbsent(playlistId, key -> {
                    List<String> ids = new java.util.ArrayList<>();
                    try {
                        JsonNode node = objectMapper.readTree(runJsonCommand(List.of(
                                "--flat-playlist", "--dump-single-json",
                                "https://api.soundcloud.com/playlists/" + key)));
                        for (JsonNode entry : node.path("entries")) {
                            String id = entry.path("id").asText("");
                            if (!id.isBlank()) ids.add(id);
                        }
                    } catch (RuntimeException error) {
                        log.warn("Could not list SoundCloud playlist {}: {}", key, error.getMessage());
                    } catch (Exception error) {
                        log.warn("Could not parse SoundCloud playlist {}: {}", key, error.getMessage());
                    }
                    return ids;
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    /** 曲目总数：--flat-playlist 只取列表，不抽取每条元数据，快得多。 */
    private int playlistCount(String playlistId) {
        Long at = playlistCountAt.get(playlistId);
        if (at != null && System.currentTimeMillis() - at < 5 * 60 * 1000L) {
            Integer cached = playlistCountCache.get(playlistId);
            if (cached != null) return cached;
        }
        try {
            String json = runJsonCommand(List.of("--flat-playlist", "--dump-single-json",
                    "https://api.soundcloud.com/playlists/" + playlistId));
            JsonNode node = objectMapper.readTree(json);
            JsonNode entries = node.path("entries");
            int count = entries.isArray() ? entries.size() : -1;
            if (count >= 0) {
                playlistCountCache.put(playlistId, count);
                playlistCountAt.put(playlistId, System.currentTimeMillis());
            }
            return count;
        } catch (RuntimeException error) {
            return -1;
        } catch (Exception error) {
            return -1;
        }
    }

    /** 用户「喜欢的音乐」：api-v2 不可用时的最后兜底。 */
    public Mono<List<TrackMetadata>> getSoundCloudUserLikes(String handle, int offset, int limit) {
        requireEnabled();
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.clamp(limit, 1, 100);
        return Mono.fromCallable(() -> parseSearchResult(runJsonCommand(List.of(
                        "--dump-single-json", "--playlist-start", String.valueOf(safeOffset + 1),
                        "--playlist-end", String.valueOf(safeOffset + safeLimit),
                        soundCloudProfileUrl(handle) + "/likes")), "soundcloud"))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    /**
     * Downloads a single platform source through yt-dlp itself. This keeps extractor
     * headers, signed URLs and HLS fragment handling in the same client session.
     */
    public Mono<DownloadedAudio> downloadAudio(String sourceUrl, Path outputDirectory, String fileStem) {
        requireEnabled();
        return Mono.fromCallable(() -> runAudioDownload(sourceUrl, outputDirectory, fileStem))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(downloadTimeoutSeconds + 10));
    }

    private Mono<List<TrackMetadata>> search(String query, String platform) {
        return search(query, platform, new String[0]);
    }

    private Mono<List<TrackMetadata>> search(String query, String platform, String... extraArgs) {
        requireEnabled();
        List<String> args = new ArrayList<>(List.of("--flat-playlist", "--dump-single-json", query));
        args.addAll(Arrays.asList(extraArgs));
        return Mono.fromCallable(() -> parseSearchResult(
                        runWithSession(args, query, "metadata query", timeoutSeconds, this::runJsonCommand), platform))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    private Mono<TrackMetadata> resolveMetadata(String sourceUrl, String platform) {
        requireEnabled();
        return Mono.fromCallable(() -> parseMetadata(
                        runWithSession(List.of("--skip-download", "--dump-single-json", sourceUrl),
                                sourceUrl, "metadata query", timeoutSeconds, this::runJsonCommand), platform))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    /** Runs one yt-dlp command through the per-run cookie jar and the session gate. */
    private String runWithSession(List<String> args, String target, String action, long actionTimeoutSeconds,
                                  CommandRunner runner) throws Exception {
        requireUsableYoutubeCookies(target);
        YoutubeCookieJar.Run jar = openCookieJar(target);
        if (jar == null) return runner.run(withYoutubeCookies(args, target, null));
        boolean gated = false;
        try {
            youtubeSession.acquire();
            gated = true;
            return runner.run(withYoutubeCookies(args, target, jar));
        } finally {
            if (gated) youtubeSession.release();
            YoutubeCookieJar.release(jar);
        }
    }

    @FunctionalInterface
    private interface CommandRunner {
        String run(List<String> command) throws Exception;
    }

    /**
     * YouTube rejects anonymous streaming requests from many datacenter IPs
     * ("Sign in to confirm you're not a bot"). A Netscape cookie file exported
     * from a signed-in browser is the only reliable fix; it is optional so
     * deployments without YouTube never need it.
     */
    private List<String> withYoutubeCookies(List<String> command, String target, YoutubeCookieJar.Run jar) {
        if (!isYoutubeTarget(target)) return command;
        List<String> prepared = new ArrayList<>(command);
        Path cookies = jar != null ? jar.file() : youtubeCookiesFile();
        if (cookies != null) {
            prepared.add(1, cookies.toString());
            prepared.add(1, "--cookies");
        }
        if (jsRuntime != null && !jsRuntime.isBlank()) {
            prepared.add(1, jsRuntime);
            prepared.add(1, "--js-runtimes");
        }
        if (youtubePlayerClient != null && !youtubePlayerClient.isBlank()) {
            prepared.add(1, "youtube:player_client=" + youtubePlayerClient);
            prepared.add(1, "--extractor-args");
        }
        return prepared;
    }

    private static boolean isYoutubeTarget(String target) {
        return target != null && (target.startsWith("ytsearch") || target.contains("youtube.com") || target.contains("youtu.be"));
    }

    private Path youtubeCookiesFile() {
        Path file = YoutubeCookieFile.resolve(cookiesFile);
        return YoutubeCookieFile.usable(file) ? file : null;
    }

    /**
     * Per-run cookie jar so concurrent yt-dlp processes cannot clobber each
     * other's rotated session tokens. Null when the target is not YouTube.
     */
    /**
     * A jar that lost SID/SAPISID/LOGIN_INFO cannot authenticate, so every fetch
     * would spend its full timeout and rewrite the file again. Fail in a
     * millisecond with the reason the operator can act on.
     */
    private void requireUsableYoutubeCookies(String target) {
        if (!isYoutubeTarget(target)) return;
        Path file = YoutubeCookieFile.resolve(cookiesFile);
        if (!YoutubeCookieFile.usable(file)) return;
        try {
            String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> missing = YoutubeCookiesAdmin.missingAuthCookies(content);
            if (!missing.isEmpty()) {
                throw new org.thornex.musicparty.exception.ApiRequestException(
                        "YouTube 要求登录校验：服务端 cookies 缺少 "
                        + String.join("、", missing) + "，取流被跳过。请重新导出完整 cookies。");
            }
        } catch (java.io.IOException ignored) {
            // Unreadable file: let the run fail with its own message.
        }
    }

    private YoutubeCookieJar.Run openCookieJar(String target) {
        if (!isYoutubeTarget(target)) return null;
        return YoutubeCookieJar.open(YoutubeCookieFile.resolve(cookiesFile));
    }

    /** Audio format for YouTube: prefer a real audio stream, else the smallest muxed one. */
    private String youtubeAudioFormat() {
        String fallback = youtubeFallbackFormat == null || youtubeFallbackFormat.isBlank()
                ? "best[height<=360]" : youtubeFallbackFormat;
        return "bestaudio/" + fallback;
    }

    /**
     * Admin check: can this deployment stream YouTube audio right now?
     *
     * <p>It downloads a few seconds of the real media. A {@code --simulate} run only
     * resolves format metadata, so it happily reports success on an IP whose media
     * requests are answered with 403.</p>
     */
    public Mono<Void> verifyYoutubeStreaming() {
        requireEnabled();
        String target = "https://music.youtube.com/watch?v=dQw4w9WgXcQ";
        return Mono.fromCallable(() -> {
                    Path directory = Files.createTempDirectory("yt-verify");
                    try {
                        String output = directory.resolve("probe.%(ext)s").toAbsolutePath().toString();
                        // Mirror the production download (same format, same audio
                        // extraction) so a green check cannot hide a broken path.
                        return runWithSession(
                                List.of(executable, "--no-warnings", "--no-playlist", "--no-progress",
                                        "--format", youtubeAudioFormat(),
                                        "--download-sections", "*0-5",
                                        "--extract-audio", "--audio-format", "m4a", "--audio-quality", "0",
                                        "--output", output, target),
                                target, "YouTube verification", 60,
                                command -> runCommand(command, "YouTube verification", 60));
                    } finally {
                        deleteQuietly(directory);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(65))
                .then();
    }

    private static void deleteQuietly(Path directory) {
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A leftover probe file in /tmp is harmless.
                        }
                    });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("yt-dlp resolver is disabled");
    }

    private String runBilibiliResolver(String bvid, String sessdata) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                executable, "--no-playlist", "--no-warnings", "--quiet",
                "--format", "ba[ext=m4a]/ba/b", "--retries", "2", "--fragment-retries", "3",
                "--socket-timeout", "15", "--get-url", "--add-header", "Referer: https://www.bilibili.com/"
        ));
        String cookieValue = normalizeSessdata(sessdata);
        if (!cookieValue.isBlank()) {
            // 用 --cookies 文件替代 --add-header Cookie：后者已被 yt-dlp 弃用，
            // 且会与 yt-dlp 自身的 cookie 管理冲突，导致 B 站返回降级数据（No video formats found）。
            command.add("--cookies");
            command.add(writeBilibiliCookieFile(cookieValue).toString());
        }
        command.add("https://www.bilibili.com/video/" + bvid);
        String output = runCommand(command, "Bilibili audio URL");
        return output.lines().map(String::trim)
                .filter(line -> line.startsWith("http://") || line.startsWith("https://"))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new IllegalStateException("yt-dlp returned no Bilibili media URL"));
    }

    private static String normalizeSessdata(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.regionMatches(true, 0, "SESSDATA=", 0, "SESSDATA=".length())) {
            value = value.substring("SESSDATA=".length()).trim();
        }
        return value;
    }

    private Path writeBilibiliCookieFile(String sessdata) throws Exception {
        Path dataDir = Path.of("data");
        Files.createDirectories(dataDir);
        Path cookieFile = dataDir.resolve("bilibili-cookies.txt");
        // 实测(2026-10-14)：仅带 SESSDATA 反被 B 站 412（有登录态而无设备指纹最可疑），
        // 附上 buvid3 后网页与取流全通。buvid3 每个 JVM 进程稳定一份。
        String content = "# Netscape HTTP Cookie File\n"
                + ".bilibili.com\tTRUE\t/\tTRUE\t0\tSESSDATA\t" + sessdata + "\n"
                + ".bilibili.com\tTRUE\t/\tTRUE\t0\tbuvid3\t" + BUVID3 + "\n";
        if (!Files.exists(cookieFile) || !Files.readString(cookieFile).equals(content)) {
            Files.writeString(cookieFile, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(cookieFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统，忽略权限设置
            }
        }
        return cookieFile;
    }

    private String runJsonCommand(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable, "--no-warnings", "--quiet",
                "--socket-timeout", "10", "--retries", "1", "--ignore-errors",
                "--sleep-requests", "0", "--sleep-interval", "0", "--max-sleep-interval", "0"));
        command.addAll(arguments);
        return runCommand(command, "metadata query");
    }

    private DownloadedAudio runAudioDownload(String sourceUrl, Path outputDirectory, String fileStem) throws Exception {
        if (!sourceUrl.startsWith("https://") && !sourceUrl.startsWith("http://")) {
            throw new IllegalArgumentException("yt-dlp source URL must be HTTP(S)");
        }
        if (!fileStem.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("Invalid cache file identifier");
        }

        Files.createDirectories(outputDirectory);
        String outputTemplate = outputDirectory.resolve(fileStem + ".%(ext)s").toAbsolutePath().toString();
        boolean youtube = isYoutubeTarget(sourceUrl);
        List<String> command = new ArrayList<>(List.of(
                executable, "--no-playlist", "--no-warnings", "--no-progress", "--no-simulate",
                "--format", youtube ? youtubeAudioFormat() : AUDIO_FORMAT,
                "--retries", "5", "--fragment-retries", "10",
                "--file-access-retries", "3", "--socket-timeout", "30", "--continue",
                // SoundCloud 的 bestaudio 是 HLS（96k aac）：不并发分片时单曲要 ~20s，
                // 4 路并发实测 ~8s 且批量时不会触发 CDN 限流；非 HLS 源会忽略这个参数。
                "--concurrent-fragments", String.valueOf(downloadConcurrency),
                "--output", outputTemplate
        ));
        if (youtube) {
            // web_safari only exposes muxed HLS renditions, so pull the smallest one
            // and remux the audio out instead of caching tens of megabytes of video.
            command.addAll(List.of("--extract-audio", "--audio-format", "m4a", "--audio-quality", "0"));
        }
        command.addAll(List.of(
                "--print", "after_move:__MUSIC_PARTY_FILE__:%(filepath)s",
                sourceUrl
        ));

        String output = runWithSession(command, sourceUrl, "audio download", downloadTimeoutSeconds,
                prepared -> runCommand(prepared, "audio download", downloadTimeoutSeconds));
        String prefix = "__MUSIC_PARTY_FILE__:";
        String outputFile = output.lines()
                .map(String::trim)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new IllegalStateException("yt-dlp completed without producing an audio file"));

        Path file = Path.of(outputFile).toAbsolutePath().normalize();
        Path allowedDirectory = outputDirectory.toAbsolutePath().normalize();
        if (!file.startsWith(allowedDirectory) || !Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IllegalStateException("yt-dlp produced an invalid audio file");
        }

        String fileName = file.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String extension = extensionStart >= 0 ? fileName.substring(extensionStart + 1) : "m4a";
        if (!extension.matches("[A-Za-z0-9]{2,5}")) extension = "m4a";
        return new DownloadedAudio(file, extension);
    }

    private String runCommand(List<String> command, String action) throws Exception {
        return runCommand(command, action, timeoutSeconds);
    }

    private String runCommand(List<String> command, String action, long actionTimeoutSeconds) throws Exception {
        java.util.concurrent.Semaphore permits = "audio download".equals(action) ? downloadProcesses : metadataProcesses;
        if (!permits.tryAcquire(5, TimeUnit.SECONDS)) throw new IllegalStateException("yt-dlp busy during " + action);
        Process process = null;
        FutureTask<String> outputReader = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process child = process;
            outputReader = new FutureTask<>(() -> new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
            Thread.ofVirtual().name("yt-dlp-output-reader").start(outputReader);
            if (!process.waitFor(actionTimeoutSeconds, TimeUnit.SECONDS)) throw new IllegalStateException("yt-dlp timed out during " + action);
            String output = outputReader.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("yt-dlp failed during " + action + ": " + explain(output));
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                // Also on Reactor cancellation / interrupted waitFor, not only on its own timeout.
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
            if (outputReader != null) outputReader.cancel(true);
            permits.release();
        }
    }

    /** Turns the one yt-dlp error operators actually hit into an actionable Chinese message. */
    private static String explain(String output) {
        if (output == null) return "";
        String lowered = output.toLowerCase(java.util.Locale.ROOT);
        if (lowered.contains("sign in to confirm")) {
            return "YouTube 要求登录校验：服务端 cookies 没有登录态或已失效，"
                    + "需要包含 SID / SAPISID / LOGIN_INFO 的完整导出（原始输出：" + output + "）";
        }
        return output;
    }

    private void stopProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private List<TrackMetadata> parseSearchResult(String json, String platform) throws Exception {
        JsonNode entries = objectMapper.readTree(json).path("entries");
        List<TrackMetadata> tracks = new ArrayList<>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                TrackMetadata track = toMetadata(entry, platform);
                if (track != null) tracks.add(track);
            }
        }
        return tracks;
    }

    private TrackMetadata parseMetadata(String json, String platform) throws Exception {
        TrackMetadata track = toMetadata(objectMapper.readTree(json), platform);
        if (track == null) throw new IllegalStateException("yt-dlp returned incomplete metadata");
        return track;
    }

    private SoundCloudUser parseSoundCloudUser(String json, String handle) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        String name = node.path("title").asText(handle).replaceFirst("\\s*\\((All|Sets)\\)$", "").trim();
        String avatar = node.path("thumbnail").asText();
        return new SoundCloudUser(handle, name.isBlank() ? handle : name, avatar);
    }

    private List<PlaylistMetadata> parseSoundCloudPlaylists(String json) throws Exception {
        JsonNode entries = objectMapper.readTree(json).path("entries");
        List<PlaylistMetadata> playlists = new ArrayList<>();
        if (!entries.isArray()) return playlists;

        for (JsonNode entry : entries) {
            String id = entry.path("id").asText();
            String title = entry.path("title").asText();
            if (id.isBlank() || title.isBlank()) continue;
            playlists.add(new PlaylistMetadata(id, title, entry.path("thumbnail").asText(),
                    entry.path("playlist_count").asInt(entry.path("n_entries").asInt(0))));
        }
        return playlists;
    }

    private String soundCloudProfileUrl(String handle) {
        return "https://soundcloud.com/" + handle;
    }

    private TrackMetadata toMetadata(JsonNode node, String platform) {
        String id = node.path("id").asText();
        String title = node.path("title").asText();
        if (id.isBlank() || title.isBlank()) return null;
        String artist = node.path("uploader").asText(node.path("channel").asText("Unknown"));
        long durationMs = Math.round(node.path("duration").asDouble(0) * 1000);
        String thumbnail = node.path("thumbnail").asText();
        if (thumbnail.isBlank()) {
            JsonNode thumbnails = node.path("thumbnails");
            if (thumbnails.isArray() && !thumbnails.isEmpty()) thumbnail = thumbnails.get(thumbnails.size() - 1).path("url").asText();
        }
        if ("ytmusic".equals(platform) && thumbnail.isBlank()) thumbnail = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        String sourceUrl = node.path("webpage_url").asText();
        if (sourceUrl.isBlank()) sourceUrl = node.path("original_url").asText();
        if (sourceUrl.isBlank()) sourceUrl = node.path("url").asText();
        if ("ytmusic".equals(platform)) sourceUrl = "https://music.youtube.com/watch?v=" + id;
        if (sourceUrl.isBlank()) return null;
        // api.soundcloud.com 是接口地址，不是可读页面，不能当跳转链接
        String pageUrl = sourceUrl.contains("api.soundcloud.com") ? null : sourceUrl;
        return new TrackMetadata(id, title, artist.isBlank() ? "Unknown" : artist, durationMs, thumbnail, sourceUrl, pageUrl);
    }
}
