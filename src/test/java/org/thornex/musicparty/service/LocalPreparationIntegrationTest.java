package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.enums.CacheStatus;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.thornex.musicparty.service.LocalCacheService.DownloadPriority.*;

class LocalPreparationIntegrationTest {
    @TempDir Path directory;
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @Test void stalledSoundcloudDoesNotBlockDirectSourceAndDuplicateRequestsOnlyDownloadOnce() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger transfers = new AtomicInteger();
        server.createContext("/audio", exchange -> {
            transfers.incrementAndGet(); byte[] bytes = new byte[256];
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        AppProperties props = new AppProperties(); props.getYtDlp().setDownloadCooldownMs(0);
        var cache = new LocalCacheService(WebClient.create(), event -> {}, props, null, directory);
        CountDownLatch slowStarted = new CountDownLatch(1);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/audio";
        try {
            cache.init();
            cache.submitDownload("soundcloud_stalled", Mono.defer(() -> { slowStarted.countDown(); return Mono.never(); }), URGENT);
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> submissions = new ArrayList<>();
                for (int i = 0; i < 50; i++) submissions.add(pool.submit(() -> cache.submitDownload("netease_same",
                        Mono.just(new LocalCacheService.DownloadSource(url, Map.of(), ".mp3")), URGENT)));
                for (Future<?> work : submissions) work.get(2, TimeUnit.SECONDS);
            }
            await(() -> cache.getStatus("netease_same") == CacheStatus.COMPLETED);
            assertThat(transfers.get()).isEqualTo(1);
            assertThat(cache.getStatus("soundcloud_stalled")).isEqualTo(CacheStatus.DOWNLOADING);
            assertThat(cache.preparationStats().merged() + cache.preparationStats().cacheHits()).isGreaterThan(0);
            assertThat(java.nio.file.Files.size(directory.resolve("netease_same.mp3"))).isEqualTo(256);
        } finally { cache.cleanup(); server.stop(0); }
    }
    @Test void queuedSharedDemandSurvivesRoomRemovalAndWaitingOrphanIsDiscarded() {
        var cache = new LocalCacheService(WebClient.create(), event -> {}, new AppProperties(), null, directory);
        Runnable shared = () -> cache.submitDownload("netease_shared", Mono.never(), NORMAL);
        Runnable orphan = () -> cache.submitDownload("netease_orphan", Mono.never(), NORMAL);
        var one = new LocalCacheService.PreparationRequest("netease_shared", "alice", NORMAL, shared);
        var two = new LocalCacheService.PreparationRequest("netease_orphan", "alice", NORMAL, orphan);
        try {
            cache.updatePreparationWindow("a", List.of(one, two));
            cache.updatePreparationWindow("b", List.of(new LocalCacheService.PreparationRequest("netease_shared", "bob", URGENT, shared)));
            cache.updatePreparationWindow("a", List.of());
            assertThat(cache.getStatus("netease_orphan")).isNull();
            assertThat(cache.getStatus("netease_shared")).isEqualTo(CacheStatus.PENDING);
            cache.updatePreparationWindow("b", List.of());
            assertThat(cache.getStatus("netease_shared")).isNull();
        } finally { cache.cleanup(); }
    }

    @Test void tricklingTransferReleasesItsLaneAndRetryFetchesAFreshSource() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseTrickle = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger(), resolutions = new AtomicInteger();
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                while (!releaseTrickle.await(50, TimeUnit.MILLISECONDS)) {
                    output.write(new byte[128]);
                    output.flush();
                    chunks.incrementAndGet();
                }
            } catch (java.io.IOException ignored) {
                // The client cancels the response at the total transfer deadline.
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        server.createContext("/fast", exchange -> {
            exchange.sendResponseHeaders(200, 256);
            try (var output = exchange.getResponseBody()) { output.write(new byte[256]); }
        });
        AppProperties props = new AppProperties();
        props.getPreparation().setConcurrency(2);
        props.getPreparation().setPerSourceConcurrency(1);
        props.getPreparation().setTransferTimeoutSeconds(1);
        props.getYtDlp().setDownloadCooldownMs(0);
        var cache = new LocalCacheService(WebClient.create(), event -> {}, props, null, directory);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var fast = new LocalCacheService.DownloadSource(base + "/fast", Map.of(), ".mp3");
            try {
                cache.init();
                cache.submitDownload("soundcloud_trickle", Mono.fromSupplier(() -> resolutions.incrementAndGet() == 1
                        ? new LocalCacheService.DownloadSource(base + "/trickle", Map.of(), ".mp3") : fast), URGENT);
                await(() -> chunks.get() >= 2);
                cache.submitDownload("soundcloud_waiter", Mono.just(fast), URGENT);
                cache.submitDownload("BVfast", Mono.just(fast), URGENT);
                await(() -> cache.getStatus("BVfast") == CacheStatus.COMPLETED);
                await(() -> cache.getStatus("soundcloud_waiter") == CacheStatus.COMPLETED);
                // Same-source waiter completes during the trickling track's backoff.
                assertThat(cache.getStatus("soundcloud_trickle")).isEqualTo(CacheStatus.PENDING);
                assertThat(cache.preparationStats().retryBackoff()).isEqualTo(1);
                assertThat(chunks.get()).isGreaterThan(2);
                await(() -> cache.getStatus("soundcloud_trickle") == CacheStatus.COMPLETED);
                assertThat(resolutions.get()).isEqualTo(2);
                assertThat(java.nio.file.Files.size(directory.resolve("soundcloud_trickle.mp3"))).isEqualTo(256);
                try (var files = java.nio.file.Files.list(directory)) {
                    assertThat(files.noneMatch(path -> path.toString().endsWith(".part"))).isTrue();
                }
            } finally { releaseTrickle.countDown(); cache.cleanup(); server.stop(0); }
        }
    }

    @Test void sameSourceSongsRunConcurrentlyInsteadOfOneAtATime() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        server.createContext("/audio", exchange -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
                byte[] bytes = new byte[64];
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        server.setExecutor(executor);
        server.start();
        AppProperties props = new AppProperties();
        props.getPreparation().setConcurrency(4);
        props.getPreparation().setPerSourceConcurrency(2);
        props.getPreparation().setDirectSourceCooldownMs(0);
        props.getYtDlp().setDownloadCooldownMs(0);
        var cache = new LocalCacheService(WebClient.create(), event -> {}, props, null, directory);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/audio";
        try {
            cache.init();
            for (int i = 1; i <= 3; i++) cache.submitDownload("netease_serial" + i,
                    Mono.just(new LocalCacheService.DownloadSource(url, Map.of(), ".mp3")), URGENT);
            await(() -> java.util.stream.IntStream.rangeClosed(1, 3)
                    .allMatch(i -> cache.getStatus("netease_serial" + i) == CacheStatus.COMPLETED));
            assertThat(maxActive.get()).isGreaterThanOrEqualTo(2);
        } finally { cache.cleanup(); server.stop(0); }
        }
    }
}
