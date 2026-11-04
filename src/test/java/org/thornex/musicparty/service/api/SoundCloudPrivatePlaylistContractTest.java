package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.SoundCloudOAuthService;
import org.thornex.musicparty.service.YtDlpResolver;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端契约测试：起一个本地假 SoundCloud API，按官方 OpenAPI 的形状返回
 * （Playlist / Track 只有 urn，私密歌单 sharing=private），验证我们的代码能：
 *  - 把私密歌单列出来（名称带「 · 私密」）
 *  - 用 /playlists/{urn}/tracks 读到自己的歌单曲目
 *  - 读「喜欢的音乐」并解析 urn
 *  - 接受 Bearer / OAuth 两种鉴权头
 */
class SoundCloudPrivatePlaylistContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private SoundCloudMusicApiService service;

    @BeforeEach
    void startFakeApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/me", exchange -> {
            record(exchange);
            respond(exchange, """
                    {"urn":"soundcloud:users:1","username":"DJ Test",
                     "permalink_url":"https://soundcloud.com/djtest"}
                    """);
        });

        server.createContext("/me/playlists", exchange -> {
            record(exchange);
            respond(exchange, """
                    {"collection":[
                      {"urn":"soundcloud:playlists:111","title":"My Private Set","sharing":"private","track_count":2},
                      {"urn":"soundcloud:playlists:222","title":"My Public Set","sharing":"public","track_count":1}
                    ]}
                    """);
        });

        server.createContext("/playlists/soundcloud:playlists:111/tracks", exchange -> {
            record(exchange);
            respond(exchange, """
                    {"collection":[
                      {"urn":"soundcloud:tracks:9001","title":"Private A","duration":200000,
                       "permalink_url":"https://soundcloud.com/djtest/private-a","user":{"username":"DJ Test"}},
                      {"urn":"soundcloud:tracks:9002","title":"Private B","duration":180000,
                       "permalink_url":"https://soundcloud.com/djtest/private-b","user":{"username":"DJ Test"}}
                    ]}
                    """);
        });

        server.createContext("/me/likes/tracks", exchange -> {
            record(exchange);
            respond(exchange, """
                    {"collection":[
                      {"urn":"soundcloud:tracks:7001","title":"Liked A","duration":150000,
                       "permalink_url":"https://soundcloud.com/x/liked-a","user":{"username":"X"}}
                    ]}
                    """);
        });

        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AppProperties props = new AppProperties();
        props.getSoundcloudOAuth().setApiBase(base);
        props.getSoundcloud().setSearchLimit(30);
        props.getSoundcloud().setFetchLimit(100);

        SoundCloudOAuthService oauth = mock(SoundCloudOAuthService.class);
        when(oauth.validAccessToken("djtest")).thenReturn(Optional.of("test-token"));
        when(oauth.hasUserToken("djtest")).thenReturn(true);

        YtDlpResolver resolver = mock(YtDlpResolver.class);
        when(resolver.getSoundCloudUserSets("djtest")).thenReturn(Mono.just(List.of()));

        service = new SoundCloudMusicApiService(
                mock(LocalCacheService.class), resolver, props,
                mock(SoundCloudSearchClient.class), oauth, WebClient.builder().build());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void record(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null) authHeaders.add(header);
    }

    private static void respond(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void listsPrivatePlaylistsWithMarker() {
        List<Playlist> playlists = service.getUserPlaylists("djtest").block();

        assertThat(playlists).isNotNull();
        assertThat(playlists).extracting(Playlist::name)
                .contains("喜欢的音乐", "我上传的曲目", "My Private Set · 私密", "My Public Set");
        assertThat(playlists).extracting(Playlist::id)
                .contains("own:djtest:111", "own:djtest:222");
    }

    @Test
    void readsTracksOfOwnPlaylistByUrn() {
        List<Music> tracks = service.getPlaylistMusics("own:djtest:111", 0, 50).block();

        assertThat(tracks).isNotNull().hasSize(2);
        assertThat(tracks).extracting(Music::name).containsExactly("Private A", "Private B");
        assertThat(tracks.get(0).id()).isEqualTo("9001");
        assertThat(tracks.get(0).url()).isEqualTo("https://soundcloud.com/djtest/private-a");
    }

    @Test
    void readsLikedTracksWithUrnIds() {
        List<Music> tracks = service.getPlaylistMusics("likes:djtest", 0, 50).block();

        assertThat(tracks).isNotNull().hasSize(1);
        assertThat(tracks.get(0).id()).isEqualTo("7001");
        assertThat(tracks.get(0).name()).isEqualTo("Liked A");
    }

    @Test
    void sendsAValidAuthorizationHeader() {
        service.getUserPlaylists("djtest").block();
        assertThat(authHeaders).isNotEmpty();
        assertThat(authHeaders).anyMatch(header -> header.equals("Bearer test-token") || header.equals("OAuth test-token"));
    }
}
