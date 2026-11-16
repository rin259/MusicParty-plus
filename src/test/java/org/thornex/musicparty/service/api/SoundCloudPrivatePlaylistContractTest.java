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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final AtomicInteger mePlaylistsCalls = new AtomicInteger();
    private final AtomicInteger ownTracksCalls = new AtomicInteger();
    private YtDlpResolver resolver;
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
            mePlaylistsCalls.incrementAndGet();
            respond(exchange, """
                    {"collection":[
                      {"urn":"soundcloud:playlists:111","title":"My Private Set","sharing":"private","track_count":2},
                      {"urn":"soundcloud:playlists:222","title":"My Public Set","sharing":"public","track_count":1}
                    ]}
                    """);
        });

        server.createContext("/playlists/soundcloud:playlists:111/tracks", exchange -> {
            record(exchange);
            ownTracksCalls.incrementAndGet();
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

        resolver = mock(YtDlpResolver.class);
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
    void nonNumericPlaylistIdStillReportsMoreWhenPageIsFull() {
        // own: 走的是默认分页映射；2 首 < limit 50 -> 到底
        var page = service.getPlaylistMusicsPaged("own:djtest:111", 0, 50).block();
        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();

        // limit 1 -> 满页，必须告诉前端「还有」
        var small = service.getPlaylistMusicsPaged("own:djtest:111", 0, 1).block();
        assertThat(small).isNotNull();
        assertThat(small.items()).hasSize(1);
        assertThat(small.hasMore()).isTrue();
    }

    @Test
    void doesNotRunYtDlpWhenAnOAuthTokenExists() {
        // /me/playlists 已经是「公开 + 私密」的全集；再跑 yt-dlp 要 2.5 秒，纯浪费
        service.getUserPlaylists("djtest").block();

        verify(resolver, never()).getSoundCloudUserSets(anyString());
    }

    @Test
    void cachesTheListSoSwitchingBackDoesNotRequery() {
        service.getUserPlaylists("djtest").block();
        int afterFirst = mePlaylistsCalls.get();
        assertThat(afterFirst).isGreaterThan(0);

        service.getUserPlaylists("djtest").block();
        assertThat(mePlaylistsCalls.get()).as("第二次必须命中缓存").isEqualTo(afterFirst);
    }

    @Test
    void fallsBackToPublicSetsWhenThereIsNoToken() {
        SoundCloudOAuthService noToken = mock(SoundCloudOAuthService.class);
        when(noToken.validAccessToken("djtest")).thenReturn(Optional.empty());
        YtDlpResolver publicOnly = mock(YtDlpResolver.class);
        when(publicOnly.getSoundCloudUserSets("djtest")).thenReturn(Mono.just(List.of(
                new YtDlpResolver.PlaylistMetadata("333", "Public From YtDlp", "", 7))));

        AppProperties props = new AppProperties();
        props.getSoundcloudOAuth().setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        SoundCloudMusicApiService noTokenService = new SoundCloudMusicApiService(
                mock(LocalCacheService.class), publicOnly, props,
                mock(SoundCloudSearchClient.class), noToken, WebClient.builder().build());

        List<Playlist> list = noTokenService.getUserPlaylists("djtest").block();

        assertThat(list).isNotNull();
        assertThat(list).extracting(Playlist::name)
                .contains("喜欢的音乐", "我上传的曲目", "Public From YtDlp");
    }

    @Test
    void sendsAValidAuthorizationHeader() {
        service.getUserPlaylists("djtest").block();
        assertThat(authHeaders).isNotEmpty();
        assertThat(authHeaders).anyMatch(header -> header.equals("Bearer test-token") || header.equals("OAuth test-token"));
    }

    @Test
    void cachesPlaylistTracksAndRefreshesOnDemand() {
        // 第一次获取：调用后端接口
        var first = service.getPlaylistMusicsPaged("own:djtest:111", 0, 50, false).block();
        assertThat(first.items()).hasSize(2);
        assertThat(ownTracksCalls.get()).isEqualTo(1);

        // 第二次获取（普通模式）：命中缓存，调用次数不变
        var cached = service.getPlaylistMusicsPaged("own:djtest:111", 0, 50, false).block();
        assertThat(cached.items()).hasSize(2);
        assertThat(ownTracksCalls.get()).as("未超时命中缓存").isEqualTo(1);

        // 第三次获取（点击 REFRESH LIST）：refresh=true 穿透缓存，重新向源站拉取
        var refreshed = service.getPlaylistMusicsPaged("own:djtest:111", 0, 50, true).block();
        assertThat(refreshed.items()).hasSize(2);
        assertThat(ownTracksCalls.get()).as("refresh=true 清除缓存重新拉取").isEqualTo(2);
    }
}
