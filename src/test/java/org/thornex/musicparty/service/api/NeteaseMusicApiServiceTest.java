package org.thornex.musicparty.service.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 网易云取流容错：上游 502 read ETIMEDOUT 属瞬时抖动（实测同一请求稍后即 200）。
 * 这些测试用本地 stub 复现该行为，锁定「退避重试 + 逐级降音质」两条承诺。
 */
class NeteaseMusicApiServiceTest {

    private static final String URL = "http://cdn.example/x.mp3";
    private static final Pattern LEVEL = Pattern.compile("level=([^&]+)");

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int failFirstRequests = 0;
    private volatile String onlyLevelWithUrl = null;
    /** 模拟「连接建立成功但一直不回包」的上游。 */
    private volatile long hangMillis = 0;

    private final AtomicInteger playlistRequests = new AtomicInteger();
    private final AtomicInteger trackRequests = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/song/url/v1", this::handle);
        server.createContext("/user/playlist", exchange -> {
            playlistRequests.incrementAndGet();
            respond(exchange, 200, "{\"code\":200,\"playlist\":[{\"id\":\"pl1\",\"name\":\"我的歌单\",\"trackCount\":10,\"coverImgUrl\":\"\"}]}");
        });
        server.createContext("/playlist/track/all", exchange -> {
            trackRequests.incrementAndGet();
            respond(exchange, 200, "{\"code\":200,\"songs\":[{\"id\":\"101\",\"name\":\"Song 101\",\"ar\":[{\"name\":\"Singer\"}],\"dt\":200000,\"al\":{\"picUrl\":\"\"}}]}");
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int attempt = requests.incrementAndGet();
        if (hangMillis > 0) {
            try {
                Thread.sleep(hangMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        Matcher matcher = LEVEL.matcher(query == null ? "" : query);
        String level = matcher.find() ? matcher.group(1) : "";

        if (attempt <= failFirstRequests) {
            respond(exchange, 502, "{\"code\":502,\"msg\":\"read ETIMEDOUT\"}");
            return;
        }
        boolean hasUrl = onlyLevelWithUrl == null || onlyLevelWithUrl.equals(level);
        respond(exchange, 200, hasUrl
                ? "{\"data\":[{\"id\":\"1\",\"url\":\"" + URL + "\"}]}"
                : "{\"data\":[{\"id\":\"1\",\"url\":null}]}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private NeteaseMusicApiService service() {
        return service(30);
    }

    private NeteaseMusicApiService service(long resolveTimeoutSeconds) {
        AppProperties properties = new AppProperties();
        AppProperties.NeteaseApiConfig netease = new AppProperties.NeteaseApiConfig();
        netease.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        netease.setQuality("exhigh");
        netease.setCookie("MUSIC_U=test-cookie");
        netease.setResolveTimeoutSeconds(resolveTimeoutSeconds);
        properties.setNetease(netease);
        return new NeteaseMusicApiService(WebClient.builder().build(), properties, mock(LocalCacheService.class));
    }

    @Test
    void retriesTransientGatewayFailureThenSucceeds() {
        failFirstRequests = 2;

        String url = service().resolveMusicUrl("1").block();

        assertThat(url).isEqualTo(URL);
        assertThat(requests.get()).as("1 次 + 2 次重试").isEqualTo(3);
    }

    @Test
    void fallsBackToLowerQualityWhenTopQualityReturnsNoUrl() {
        onlyLevelWithUrl = "higher";

        String url = service().resolveMusicUrl("1").block();

        assertThat(url).isEqualTo(URL);
        assertThat(requests.get()).as("exhigh 无地址 → 退到 higher").isGreaterThanOrEqualTo(2);
    }

    /**
     * 2026-11-10 线上事故：网易云网关接了连接却整段不回包，Java 侧没有读超时，
     * 单个任务把串行下载队列堵满 4 分钟（外层 TASK_TIMEOUT）。解析必须有总预算。
     */
    @Test
    void givesUpQuicklyWhenGatewayNeverResponds() {
        hangMillis = 2_500;
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> service(1).resolveMusicUrl("1").block())
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("超时");

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(elapsedMs).as("1s 预算内失败，而不是等到 30s/4min").isLessThan(3_000L);
    }

    @Test
    void reportsFailureAfterAllLevelsExhausted() {
        onlyLevelWithUrl = "lossless-never-offered";

        assertThatThrownBy(() -> service().resolveMusicUrl("1").block())
                .isInstanceOf(ApiRequestException.class);
        assertThat(requests.get()).as("每个音质档各试一次").isEqualTo(3);
    }

    @Test
    void cachesUserPlaylistsToPreventRepeatedHits() {
        NeteaseMusicApiService api = service();
        var first = api.getUserPlaylists("user_1").block();
        var second = api.getUserPlaylists("user_1").block();

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(playlistRequests.get()).as("60s 内第二次调用命中缓存").isEqualTo(1);
    }

    @Test
    void cachesPlaylistTracksAndRefreshesWhenRequested() {
        NeteaseMusicApiService api = service();

        // 第一次调用：网络请求
        var first = api.getPlaylistMusicsPaged("pl1", 0, 50, false).block();
        assertThat(first.items()).hasSize(1);
        assertThat(trackRequests.get()).isEqualTo(1);

        // 第二次调用（普通模式）：命中 5 分钟缓存，不产生新请求
        var cached = api.getPlaylistMusicsPaged("pl1", 0, 50, false).block();
        assertThat(cached.items()).hasSize(1);
        assertThat(trackRequests.get()).as("5分钟内命中缓存不打网络").isEqualTo(1);

        // 第三次调用（用户点击 REFRESH LIST）：refresh=true 穿透缓存，向上游刷新
        var refreshed = api.getPlaylistMusicsPaged("pl1", 0, 50, true).block();
        assertThat(refreshed.items()).hasSize(1);
        assertThat(trackRequests.get()).as("refresh=true 穿透缓存重新获取").isEqualTo(2);
    }
}
