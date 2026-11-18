package org.thornex.musicparty.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.music-api")
@Data
public class AppProperties {
    private NeteaseApiConfig  netease;
    private BilibiliApiConfig bilibili;
    private YtDlpSourceConfig soundcloud = new YtDlpSourceConfig();
    /** SoundCloud OAuth 2.1（注册应用的凭据；redirect_uri 必须与应用设置完全一致） */
    private SoundCloudOAuthConfig soundcloudOAuth = new SoundCloudOAuthConfig();
    private YtDlpSourceConfig ytmusic = new YtDlpSourceConfig();
    private String adminPassword;
    private String baseUrl;
    private String authorName = "ThorNex";
    private String backWords = "THORNEX";
    private String ffmpegPath = "ffmpeg"; // 默认使用环境变量中的 ffmpeg
    private YtDlpConfig ytDlp = new YtDlpConfig();

    // 新增配置项
    private QueueConfig queue = new QueueConfig();
    private PlayerConfig player = new PlayerConfig();
    private ChatConfig chat = new ChatConfig();
    private CacheConfig cache = new CacheConfig();
    private AuthConfig auth = new AuthConfig();
    private RoomsConfig rooms = new RoomsConfig();
    private PreparationConfig preparation = new PreparationConfig();

    @Data
    public static class PreparationConfig {
        private int concurrency = 4;
        private int perSourceConcurrency = 2;
        private int ytDlpConcurrency = 2;
        private int maxPending = 512;
        private int metadataConcurrency = 8;
        private int windowSize = 12;
        private int perUserWindow = 2;
        private long resolveTimeoutSeconds = 35;
        private long transferTimeoutSeconds = 45;
        private long taskTimeoutSeconds = 240;
        private long broadcastIntervalMs = 200;
        /**
         * 直链源（网易云 / B 站）每次释放下载槽后的同源间隔。
         * 旧实现串行且固定 3s，批量导入时同源后一首白白等 3s；现在由
         * per-source 并发闸门控速，只需一个很短的礼貌间隔，避免同源瞬间连发。
         */
        private long directSourceCooldownMs = 1200;
    }

    @Data
    public static class RoomsConfig {
        /** 同时存在的房间上限（含默认房） */
        private int maxRooms = 50;
        /** 单个设备最多创建多少个房间 */
        private int maxRoomsPerDevice = 5;
        /** 无人在线多久后卸载运行时（元数据与快照保留） */
        private int idleUnloadMinutes = 30;
        /** 房间码长度 */
        private int codeLength = 8;
        /** 同一房间两次邮件召唤之间的间隔；0 表示不限间隔。 */
        private int summonCooldownHours = 6;
    }

    @Data
    public static class QueueConfig {
        private int maxSize = 1000;
        private int historySize = 50;
        private int maxUserSongs = 100;
        private String persistenceFile = "data/queue-data.json";
        private long persistenceIntervalMs = 60000; // Default save every 1 minute
    }

    @Data
    public static class PlayerConfig {
        private int maxPlaylistImportSize = 100;
        private boolean voteSkipEnabled = false;
        private double voteSkipThreshold = 0.5;
        private int voteSkipWaitTime = 15;
    }

    @Data
    public static class ChatConfig {
        private int maxHistorySize = 1000;
        private long minIntervalMs = 1000;
        private int maxMessageLength = 200;
    }

    @Data
    public static class CacheConfig {
        private org.springframework.util.unit.DataSize maxSize = org.springframework.util.unit.DataSize.ofGigabytes(1);
    }

    @Data
    public static class AuthConfig {
        private boolean rateLimitEnabled = true;
        private int maxAttempts = 5;
        private int windowSeconds = 60;
        private int blockDurationSeconds = 300;
    }

    @Data
    public static class YtDlpConfig {
        private boolean enabled = true;
        private String path = "yt-dlp";
        private long timeoutSeconds = 45;
        private long downloadTimeoutSeconds = 180;
        /**
         * HLS 分片并发数。SoundCloud 的 bestaudio 是 m3u8（96k aac），默认单分片串行下载。
         * 实测：1 路 ~20s，4 路 ~8s，8 路 ~7s 但批量时约 1/5 的歌会被 CDN 限流卡 25~37s。
         * 批量导入要的是稳定吞吐，默认 4 路（20 首连续下载 0 次超 15s）。
         */
        private int downloadConcurrency = 4;
        /** 下载队列任务之间的冷却；过大会让批量导入整体变慢，过小可能触发上游限流。 */
        private long downloadCooldownMs = 1000;
        /**
         * YouTube 的 Netscape 格式 cookies 文件。YouTube 对机房 IP 常返回
         * "Sign in to confirm you're not a bot"，缺少 cookies 时取流必然失败。
         * 留空则读 data/yt-cookies.txt（容器内即 /app/data/yt-cookies.txt）。
         */
        private String cookiesFile = "";
        /**
         * YouTube 的签名/n-challenge 需要 JavaScript 运行时（yt-dlp EJS）。
         * 镜像内置 bun；显式传入，避免依赖 PATH 探测。
         */
        private String jsRuntime = "bun:/usr/local/bin/bun";
        /**
         * YouTube 会話的 cookie 每次應答都會輪換（__Secure-*PSIDTS）。同一會話被多個
         * yt-dlp 進程並發使用時，各路各自拿到不同的輪換值，Google 會把無法對齊的
         * token 鏈視為盜用並強制登出。故 YouTube 的 yt-dlp 調用默認串行化（1 路）。
         */
        private int youtubeConcurrency = 1;
    }

    @Data
    public static class SoundCloudOAuthConfig {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        /** API 基址；测试时指向本地假服务 */
        private String apiBase = "https://api.soundcloud.com";
    }

    @Data
    public static class YtDlpSourceConfig {
        private boolean enabled = true;
        private int searchLimit = 30;
        /** How many results the yt-dlp fallback pulls in one batch so paging can continue. */
        private int fetchLimit = 100;
    }

    @Data
    public static class ApiConfig {
        private String baseUrl;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class BilibiliApiConfig extends ApiConfig {
        private String sessdata;
        private boolean enabled = true;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class NeteaseApiConfig extends ApiConfig {
        private String cookie;
        private String quality = "exhigh"; // 默认音质：极高 (exhigh)
        private boolean enabled = true;
        /**
         * 取流地址解析的总预算（含重试与降档）。上游整段不回包时，
         * 解析不能占着串行下载队列超过这个时间。
         */
        private long resolveTimeoutSeconds = 30;
    }
}
