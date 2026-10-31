package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.SourceHealth;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 音乐源健康探测：真正发一次最小搜索，量出时延与可达性。
 *
 * 目的不是「监控仪表盘」，而是把「搜完才知道源挂了」提前到打开搜索时：
 *  - 每源 60s 缓存，避免频繁请求上游；
 *  - 探测使用最小 limit（1），成本与一次真实搜索同量级；
 *  - YT Music 走 yt-dlp，天然慢，给 9s 预算，其余 5s；
 *  - 失败如实返回 error，不伪造成功。
 */
@Service
@Slf4j
public class SourceHealthService {

    private static final long CACHE_MS = 60_000L;
    private static final String PROBE_KEYWORD = "hello";

    private final List<IMusicApiService> services;
    private final AppProperties appProperties;
    private final ConcurrentHashMap<String, SourceHealth> cache = new ConcurrentHashMap<>();

    public SourceHealthService(List<IMusicApiService> services, AppProperties appProperties) {
        this.services = services;
        this.appProperties = appProperties;
    }

    public Mono<List<SourceHealth>> health() {
        long now = System.currentTimeMillis();
        List<Mono<SourceHealth>> probes = services.stream()
                .map(service -> {
                    String platform = service.getPlatformName();
                    SourceHealth cached = cache.get(platform);
                    // 成功缓存 60s；失败只缓存 15s，避免一次抖动把源标红一分钟。
                    long ttl = cached != null && cached.ok() ? CACHE_MS : 15_000L;
                    if (cached != null && now - cached.checkedAt() < ttl) {
                        return Mono.just(cached);
                    }
                    return probe(platform, service);
                })
                .collect(Collectors.toList());
        return Flux.fromIterable(probes)
                .flatMap(Function.identity())
                .collectList()
                .map(list -> {
                    list.sort(java.util.Comparator.comparingInt(h -> platformOrder(h.platform())));
                    list.forEach(h -> cache.put(h.platform(), h));
                    return list;
                });
    }

    private Mono<SourceHealth> probe(String platform, IMusicApiService service) {
        boolean enabled = isEnabled(platform);
        if (!enabled) {
            return Mono.just(new SourceHealth(platform, false, false, 0, 0, "disabled", System.currentTimeMillis()));
        }
        long started = System.nanoTime();
        long budget = "ytmusic".equals(platform) ? 9_000L : 5_000L;
        return service.searchMusic(PROBE_KEYWORD, 0, 1)
                .timeout(Duration.ofMillis(budget))
                .map(list -> new SourceHealth(platform, true, true,
                        (System.nanoTime() - started) / 1_000_000, list == null ? 0 : list.size(),
                        null, System.currentTimeMillis()))
                .onErrorResume(error -> {
                    log.debug("Source probe failed for {}: {}", platform, error.toString());
                    return Mono.just(new SourceHealth(platform, true, false,
                            (System.nanoTime() - started) / 1_000_000, 0,
                            shortError(error), System.currentTimeMillis()));
                });
    }

    private boolean isEnabled(String platform) {
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "netease" -> appProperties.getNetease().isEnabled();
            case "bilibili" -> appProperties.getBilibili().isEnabled();
            case "soundcloud" -> appProperties.getSoundcloud().isEnabled();
            case "ytmusic" -> appProperties.getYtmusic().isEnabled();
            default -> false;
        };
    }

    private static String shortError(Throwable error) {
        if (error == null) return "unknown";
        if (error instanceof java.util.concurrent.TimeoutException) return "timeout";
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static int platformOrder(String platform) {
        return switch (platform) {
            case "netease" -> 0;
            case "bilibili" -> 1;
            case "soundcloud" -> 2;
            case "ytmusic" -> 3;
            default -> 9;
        };
    }

    /** 供测试/诊断：强制下次重新探测。 */
    public void invalidate() {
        cache.clear();
    }
}
