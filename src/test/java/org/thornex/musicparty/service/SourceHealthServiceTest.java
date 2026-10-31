package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.SourceHealth;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SourceHealthServiceTest {

    private static class FakeService implements IMusicApiService {
        private final String platform;
        private final Supplier<Mono<List<Music>>> search;
        final AtomicInteger calls = new AtomicInteger();

        FakeService(String platform, Supplier<Mono<List<Music>>> search) {
            this.platform = platform;
            this.search = search;
        }

        @Override public String getPlatformName() { return platform; }
        @Override public Mono<List<Music>> searchMusic(String keyword) { return searchMusic(keyword, 0, 1); }
        @Override public Mono<List<Music>> searchMusic(String keyword, int offset, int limit) {
            calls.incrementAndGet();
            return search.get();
        }
        @Override public Mono<PlayableMusic> getPlayableMusic(String musicId) { return Mono.empty(); }
        @Override public Mono<List<Playlist>> getUserPlaylists(String userId) { return Mono.just(List.of()); }
        @Override public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) { return Mono.just(List.of()); }
        @Override public Mono<List<UserSearchResult>> searchUsers(String keyword) { return Mono.just(List.of()); }
        @Override public Mono<String> getLyric(String musicId) { return Mono.just(""); }
    }

    private static AppProperties allEnabled() {
        AppProperties props = new AppProperties();
        props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig());
        props.setSoundcloud(new AppProperties.YtDlpSourceConfig());
        props.setYtmusic(new AppProperties.YtDlpSourceConfig());
        return props;
    }

    private static Music song(String id) {
        return new Music(id, "hello", List.of("artist"), 1000, "netease", "");
    }

    @Test
    void reportsReachableSourcesInStableOrderAndCachesFor60s() {
        FakeService netease = new FakeService("netease", () -> Mono.just(List.of(song("1"))));
        FakeService bilibili = new FakeService("bilibili", () -> Mono.just(List.of(song("2"), song("3"))));

        SourceHealthService service = new SourceHealthService(List.of(bilibili, netease), allEnabled());
        List<SourceHealth> first = service.health().block();

        assertThat(first).extracting(SourceHealth::platform).containsExactly("netease", "bilibili");
        assertThat(first.get(0).ok()).isTrue();
        assertThat(first.get(0).sampleCount()).isEqualTo(1);
        assertThat(first.get(1).sampleCount()).isEqualTo(2);
        assertThat(first).allSatisfy(h -> assertThat(h.latencyMs()).isGreaterThanOrEqualTo(0));

        List<SourceHealth> second = service.health().block();
        assertThat(second).hasSize(2);
        // 60s 内命中缓存，不重复打上游
        assertThat(netease.calls.get()).isEqualTo(1);
        assertThat(bilibili.calls.get()).isEqualTo(1);
    }

    @Test
    void reportsFailureWithoutThrowing() {
        FakeService broken = new FakeService("netease", () -> Mono.error(new IllegalStateException("boom")));
        SourceHealthService service = new SourceHealthService(List.of(broken), allEnabled());

        SourceHealth health = service.health().block().get(0);
        assertThat(health.ok()).isFalse();
        assertThat(health.enabled()).isTrue();
        assertThat(health.error()).contains("boom");
    }

    @Test
    void disabledSourceIsNotProbed() {
        AppProperties props = allEnabled();
        props.getNetease().setEnabled(false);

        FakeService netease = new FakeService("netease", () -> Mono.just(List.of(song("1"))));
        SourceHealthService service = new SourceHealthService(List.of(netease), props);

        SourceHealth health = service.health().block().get(0);
        assertThat(health.enabled()).isFalse();
        assertThat(health.ok()).isFalse();
        assertThat(health.error()).isEqualTo("disabled");
        assertThat(netease.calls.get()).isZero();
    }
}
