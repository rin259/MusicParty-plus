package org.thornex.musicparty.service;

import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;

/** Metadata only: bounded work, single-flight across rooms, bounded TTL cache. */
final class MetadataLookup implements AutoCloseable {
    private record Cached(Music music, long expires) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final Map<String, Mono<Music>> inFlight = new HashMap<>();
    private final Scheduler executor;
    MetadataLookup(int concurrency) {
        executor = Schedulers.newBoundedElastic(Math.max(1, concurrency), 8, "metadata-lookup");
    }
    Mono<Music> get(IMusicApiService api, String id) {
        return Mono.defer(() -> lookup(api, id));
    }
    private synchronized Mono<Music> lookup(IMusicApiService api, String id) {
        String key = api.getPlatformName() + ":" + id;
        Cached hit = cache.get(key);
        if (hit != null && hit.expires > System.currentTimeMillis()) return Mono.just(hit.music);
        if (inFlight.containsKey(key)) return inFlight.get(key);
        if (inFlight.size() >= 64) return Mono.error(new IllegalStateException("元数据请求繁忙，请稍后重试"));
        Mono<Music> request = Mono.fromCallable(() -> api.getMusicMetadata(id).block(Duration.ofSeconds(35)))
                .subscribeOn(executor).timeout(Duration.ofSeconds(40))
                .doOnNext(music -> { synchronized (this) {
                    cache.put(key, new Cached(music, System.currentTimeMillis() + 300_000));
                    while (cache.size() > 512) cache.remove(cache.keySet().iterator().next());
                } })
                .doOnEach(signal -> {
                    if (signal.isOnComplete() || signal.isOnError()) {
                        synchronized (this) { inFlight.remove(key); }
                    }
                })
                .cache();
        inFlight.put(key, request);
        return request;
    }
    public void close() { executor.dispose(); }
}
