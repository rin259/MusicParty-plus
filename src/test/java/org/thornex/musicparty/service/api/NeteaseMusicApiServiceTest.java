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

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/song/url/v1", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int attempt = requests.incrementAndGet();
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
        AppProperties properties = new AppProperties();
        AppProperties.NeteaseApiConfig netease = new AppProperties.NeteaseApiConfig();
        netease.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        netease.setQuality("exhigh");
        netease.setCookie("MUSIC_U=test-cookie");
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

    @Test
    void reportsFailureAfterAllLevelsExhausted() {
        onlyLevelWithUrl = "lossless-never-offered";

        assertThatThrownBy(() -> service().resolveMusicUrl("1").block())
                .isInstanceOf(ApiRequestException.class);
        assertThat(requests.get()).as("每个音质档各试一次").isEqualTo(3);
    }
}
