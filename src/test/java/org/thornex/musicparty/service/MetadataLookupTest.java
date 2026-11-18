package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.dto.Music;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataLookupTest {
    @Test void failedLookupsAreNotCachedAndCanRetryImmediately() {
        var api = mock(IMusicApiService.class);
        when(api.getPlatformName()).thenReturn("netease");
        var calls = new AtomicInteger();
        var song = new Music("1", "Song", List.of("Artist"), 1000, "netease", "");
        when(api.getMusicMetadata("1")).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? Mono.error(new IllegalStateException("temporary")) : Mono.just(song));
        try (var lookup = new MetadataLookup(1)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> lookup.get(api, "1").block()).hasMessageContaining("temporary");
            assertThat(lookup.get(api, "1").block()).isEqualTo(song);
            assertThat(calls.get()).isEqualTo(2);
        }
    }
    @Test void coalescesAcrossSubscribersAndCachesMetadataWithoutPlayableSideEffects() throws Exception {
        var api = mock(IMusicApiService.class); var calls = new AtomicInteger();
        when(api.getPlatformName()).thenReturn("netease");
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        var song = new Music("1", "Song", List.of("Artist"), 1000, "netease", "");
        when(api.getMusicMetadata("1")).thenReturn(Mono.fromCallable(() -> {
            calls.incrementAndGet(); started.countDown(); finish.await(2, TimeUnit.SECONDS); return song;
        }));
        try (var lookup = new MetadataLookup(2)) {
            CompletableFuture<Music> a = lookup.get(api, "1").toFuture();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Music> b = lookup.get(api, "1").toFuture();
            finish.countDown();
            assertThat(a.get(2, TimeUnit.SECONDS)).isEqualTo(song);
            assertThat(b.get(2, TimeUnit.SECONDS)).isEqualTo(song);
            assertThat(lookup.get(api, "1").block()).isEqualTo(song);
            assertThat(calls.get()).isEqualTo(1);
            verify(api, never()).getPlayableMusic(anyString());
            verify(api, never()).prefetchMusic(anyString());
        }
    }
}
