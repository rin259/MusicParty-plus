package org.thornex.musicparty.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliApiUtilsTest {
    private HttpServer server;
    private String baseUrl;
    private volatile String body;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private BilibiliApiUtils.BilibiliVideoInfo fetch() {
        return BilibiliApiUtils.getVideoInfo("BVtest", WebClient.builder().build(), baseUrl, "")
                .block(java.time.Duration.ofSeconds(5));
    }

    @Test
    void multiPartVideoReportsTheFirstPartOnlyBecauseOnlyThatAudioIsCached() {
        // data.duration is the sum of every part (422s); page 1 alone is 211s and
        // that is the audio we actually download. Reporting 422 doubled the bar and
        // made the player seek past the end of a 211s file, so it restarted at 0.
        body = """
                {"code":0,"data":{
                  "cid":111,"duration":422,"title":"two parts",
                  "owner":{"name":"uploader"},"pic":"https://i0.hdslb.com/a.jpg",
                  "pages":[{"cid":222,"page":1,"duration":211},{"cid":333,"page":2,"duration":211}]
                }}
                """;

        var info = fetch();

        assertThat(info.music().duration()).isEqualTo(211_000L);
        assertThat(info.cid()).isEqualTo("222");
    }

    @Test
    void singlePartVideoKeepsItsDuration() {
        body = """
                {"code":0,"data":{
                  "cid":111,"duration":99,"title":"single",
                  "owner":{"name":"uploader"},"pic":"https://i0.hdslb.com/a.jpg",
                  "pages":[{"cid":111,"page":1,"duration":99}]
                }}
                """;

        var info = fetch();

        assertThat(info.music().duration()).isEqualTo(99_000L);
        assertThat(info.cid()).isEqualTo("111");
    }

    @Test
    void fallsBackToTheTopLevelDurationWhenPagesAreAbsent() {
        body = """
                {"code":0,"data":{
                  "cid":111,"duration":77,"title":"no pages",
                  "owner":{"name":"uploader"},"pic":"https://i0.hdslb.com/a.jpg"
                }}
                """;

        assertThat(fetch().music().duration()).isEqualTo(77_000L);
    }

    @Test
    void parsesMinuteSecondTimestampsFromSearchResults() {
        assertThat(BilibiliApiUtils.durationToMillis("03:05")).isEqualTo(185_000L);
        assertThat(BilibiliApiUtils.durationToMillis("1:02:03")).isEqualTo(3_723_000L);
        assertThat(BilibiliApiUtils.durationToMillis("")).isZero();
    }
}
