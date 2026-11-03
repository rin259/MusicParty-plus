package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ObjectMapper objectMapper;
    private static final String BUVID3 = java.util.UUID.randomUUID().toString().toUpperCase() + "infoc";

    public YtDlpResolver(AppProperties appProperties, ObjectMapper objectMapper) {
        this.executable = appProperties.getYtDlp().getPath();
        this.enabled = appProperties.getYtDlp().isEnabled();
        this.timeoutSeconds = appProperties.getYtDlp().getTimeoutSeconds();
        this.downloadTimeoutSeconds = appProperties.getYtDlp().getDownloadTimeoutSeconds();
        this.objectMapper = objectMapper;
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

    public Mono<List<TrackMetadata>> getSoundCloudPlaylistTracks(String playlistId, int offset, int limit) {
        requireEnabled();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.clamp(limit, 1, 100);
        return Mono.fromCallable(() -> parseSearchResult(runJsonCommand(List.of(
                        "--dump-single-json", "--playlist-start", String.valueOf(safeOffset + 1),
                        "--playlist-end", String.valueOf(safeOffset + safeLimit),
                        "https://api.soundcloud.com/playlists/" + playlistId)), "soundcloud"))
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
        return Mono.fromCallable(() -> parseSearchResult(runJsonCommand(args), platform))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
    }

    private Mono<TrackMetadata> resolveMetadata(String sourceUrl, String platform) {
        requireEnabled();
        return Mono.fromCallable(() -> parseMetadata(runJsonCommand(List.of("--skip-download", "--dump-single-json", sourceUrl)), platform))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(timeoutSeconds + 5));
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
        List<String> command = List.of(
                executable, "--no-playlist", "--no-warnings", "--no-progress", "--no-simulate",
                "--format", "bestaudio", "--retries", "5", "--fragment-retries", "10",
                "--file-access-retries", "3", "--socket-timeout", "30", "--continue",
                "--output", outputTemplate,
                "--print", "after_move:__MUSIC_PARTY_FILE__:%(filepath)s",
                sourceUrl
        );

        String output = runCommand(command, "audio download", downloadTimeoutSeconds);
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
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        FutureTask<String> outputReader = new FutureTask<>(() ->
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        Thread.ofVirtual().name("yt-dlp-output-reader").start(outputReader);

        if (!process.waitFor(actionTimeoutSeconds, TimeUnit.SECONDS)) {
            stopProcess(process);
            throw new IllegalStateException("yt-dlp timed out during " + action);
        }
        String output = outputReader.get(5, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("yt-dlp failed during " + action + ": " + output);
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
