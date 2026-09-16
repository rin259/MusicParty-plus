package org.thornex.musicparty.service.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.service.LocalCacheService;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeteaseMusicApiServiceTest {

    private HttpServer server;
    private AppProperties properties;
    private LocalCacheService cache;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/song/detail", this::respondWithSongDetails);
        server.createContext("/song/url/v1", this::respondWithSongUrl);
        server.start();

        properties = new AppProperties();
        AppProperties.NeteaseApiConfig netease = new AppProperties.NeteaseApiConfig();
        netease.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        netease.setCookie("test-cookie");
        properties.setNetease(netease);
        cache = mock(LocalCacheService.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsCachedMediaWithoutResolvingAnotherExternalPlaybackUrl() {
        when(cache.getLocalUrl("netease_42")).thenReturn("/media/netease_42.mp3");
        NeteaseMusicApiService service = new NeteaseMusicApiService(WebClient.builder().build(), properties, cache);

        PlayableMusic playable = service.getPlayableMusic("42").block();

        assertThat(playable.url()).isEqualTo("/media/netease_42.mp3");
        verify(cache).getLocalUrl("netease_42");
    }

    @Test
    void queuesAnUncachedTrackAndDoesNotExposeTheCdnUrlToTheBrowser() {
        NeteaseMusicApiService service = new NeteaseMusicApiService(WebClient.builder().build(), properties, cache);

        PlayableMusic playable = service.getPlayableMusic("42").block();

        assertThat(playable.url()).isEqualTo("PENDING_DOWNLOAD");
        verify(cache).submitDownload(eq("netease_42"), org.mockito.ArgumentMatchers.<Mono<LocalCacheService.DownloadSource>>any());
    }

    private void respondWithSongDetails(HttpExchange exchange) throws IOException {
        respond(exchange, "{\"songs\":[{\"id\":42,\"name\":\"Cached Song\",\"ar\":[{\"name\":\"Artist\"}],\"dt\":120000,\"al\":{\"picUrl\":\"https://example.test/cover.jpg\"}}]}");
    }

    private void respondWithSongUrl(HttpExchange exchange) throws IOException {
        respond(exchange, "{\"data\":[{\"url\":\"https://cdn.example.test/music/42.mp3\"}]}");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
