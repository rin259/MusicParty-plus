package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class LocalCacheService {

    private final WebClient webClient;
    private static final long DOWNLOAD_COOLDOWN_SECONDS = 3;
    // 单任务硬上限：下载队列串行（concatMap 并发 1），任何一个任务卡住都会堵死后续曲目，
    // 故此处兜底，任何阶段无进展都不会超过此值。
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(4);

    // 内存中维护缓存文件的元数据
    private final Map<String, CacheEntry> cacheIndex = new ConcurrentHashMap<>();
    private final AtomicLong currentTotalSize = new AtomicLong(0);
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final YtDlpResolver ytDlpResolver;
    private final Sinks.Many<DownloadTask> downloadQueue = Sinks.many().unicast().onBackpressureBuffer();
    private Disposable queueSubscription;

    private record DownloadTask(String musicId, Mono<DownloadSource> sourceProvider) {}

    public record DownloadSource(String url, Map<String, String> headers, String extension, boolean useYtDlp) {
        public DownloadSource(String url, Map<String, String> headers, String extension) {
            this(url, headers, extension, false);
        }

        public static DownloadSource ytDlp(String sourceUrl) {
            return new DownloadSource(sourceUrl, Map.of(), "", true);
        }
    }

    public LocalCacheService(WebClient webClient, ApplicationEventPublisher eventPublisher, AppProperties appProperties,
                             YtDlpResolver ytDlpResolver) {
        this.webClient = webClient;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
        this.ytDlpResolver = ytDlpResolver;
    }

    @Data
    public static class CacheEntry {
        private String id;
        private String fileName;
        private CacheStatus status;
        private long size;
        private long lastAccessTime;
        private String originalUrl; // 用于重试或记录
    }

    @PostConstruct
    public void init() {
        // 初始化时扫描目录，重建索引和计算大小
        File dir = new File(LocalResourceConfig.CACHE_DIR);
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                if (f.getName().endsWith(".part")) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    } catch (IOException e) {
                        log.warn("Failed to clean partial cache file {}", f, e);
                    }
                    continue;
                }
                String id = f.getName().split("\\.")[0]; // 假设文件名是 id.mp3
                CacheEntry entry = new CacheEntry();
                entry.setId(id);
                entry.setFileName(f.getName());
                entry.setStatus(CacheStatus.COMPLETED);
                entry.setSize(f.length());
                entry.setLastAccessTime(System.currentTimeMillis());

                cacheIndex.put(id, entry);
                currentTotalSize.addAndGet(f.length());
            }
        }
        log.info("LocalCacheService initialized. Current cache size: {} bytes", currentTotalSize.get());

        this.queueSubscription = downloadQueue.asFlux()
                .concatMap(task ->
                        processTask(task)
                                .onErrorResume(e -> {
                                    log.error("Unexpected error in download queue processing", e);
                                    return Mono.empty(); // 吞掉异常，防止队列崩溃
                                })
                                // 🟢 关键：强制冷却时间，防止风控
                                .then(Mono.delay(Duration.ofSeconds(DOWNLOAD_COOLDOWN_SECONDS)))
                )
                .subscribe();
    }

    @PreDestroy
    public void cleanup() {
        if (queueSubscription != null && !queueSubscription.isDisposed()) {
            queueSubscription.dispose();
        }
    }

    /**
     * 提交下载任务
     * @param musicId 音乐ID（作为文件名）
     * @param urlProvider 提供下载链接的 Mono（因为链接可能是动态获取的）
     * @param headers 下载需要的请求头（Referer, Cookie等）
     * @param extension 文件扩展名 (如 .m4a, .mp3)
     */
    public void submitDownload(String musicId, Mono<String> urlProvider, Map<String, String> headers, String extension) {
        submitDownload(musicId, urlProvider.map(url -> new DownloadSource(url, headers, extension)));
    }

    public void submitDownload(String musicId, Mono<DownloadSource> sourceProvider) {
        if (cacheIndex.containsKey(musicId) && cacheIndex.get(musicId).getStatus() == CacheStatus.COMPLETED) {
            log.info("Music {} already cached.", musicId);
            touch(musicId); // 更新访问时间
            return;
        }

        // 2. 检查是否正在处理或排队 (关键去重)
        if (cacheIndex.containsKey(musicId)) {
            CacheStatus status = cacheIndex.get(musicId).getStatus();
            if (status == CacheStatus.DOWNLOADING || status == CacheStatus.PENDING) {
                log.debug("Task {} is already pending or downloading, skip enqueue.", musicId);
                return; // 直接返回，不要重复 emit
            }
        }

        // 初始化条目
        CacheEntry entry = new CacheEntry();
        entry.setId(musicId);
        entry.setStatus(CacheStatus.PENDING); // 🟢 状态：排队中
        entry.setLastAccessTime(System.currentTimeMillis());
        cacheIndex.put(musicId, entry);

        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));

        Sinks.EmitResult result = downloadQueue.tryEmitNext(new DownloadTask(musicId, sourceProvider));

        if (result.isFailure()) {
            log.error("Failed to enqueue download task for {}", musicId);
            entry.setStatus(CacheStatus.FAILED);
            eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        } else {
            log.info("Download enqueued: {}", musicId);
        }
    }

    private Mono<Void> processTask(DownloadTask task) {
        String musicId = task.musicId();
        CacheEntry entry = cacheIndex.get(musicId);

        // 双重检查：如果任务在排队期间被移除了，就跳过
        if (entry == null) return Mono.empty();

        // 🟢 状态变更：PENDING -> DOWNLOADING
        entry.setStatus(CacheStatus.DOWNLOADING);
        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        log.info("Processing download: {}", musicId);

        return Mono.defer(task::sourceProvider)
                .flatMap(source -> {
                    entry.setOriginalUrl(source.url());
                    if (source.useYtDlp()) {
                        return downloadWithYtDlp(musicId, entry, source.url());
                    }

                    String fileName = musicId + source.extension();
                    entry.setFileName(fileName);
                    Path destPath = Paths.get(LocalResourceConfig.CACHE_DIR, fileName);

                    Path tempPath = Paths.get(LocalResourceConfig.CACHE_DIR, fileName + ".part");
                    return webClient.get()
                            .uri(source.url())
                            .headers(httpHeaders -> source.headers().forEach(httpHeaders::add))
                            .retrieve()
                            .bodyToFlux(DataBuffer.class)
                            .timeout(Duration.ofMinutes(3))
                            .as(data -> DataBufferUtils.write(data, tempPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                            .then(Mono.fromRunnable(() -> completeDownload(musicId, entry, tempPath, destPath, fileName)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnError(error -> {
                                try {
                                    Files.deleteIfExists(tempPath);
                                } catch (IOException cleanupError) {
                                    log.warn("Failed to clean partial cache file {}", tempPath, cleanupError);
                                }
                            });
                })
                // Signed media URLs can resolve to a transiently unavailable CDN node.
                // Resubscribing also obtains a fresh signed URL from the source provider.
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(this::isRetryableDownloadFailure)
                        .doBeforeRetry(signal -> log.warn("Retrying download {} after transient failure (attempt {})",
                                musicId, signal.totalRetries() + 1)))
                // 兜底：串行队列里，任何阶段无进展都不得超过 TASK_TIMEOUT
                .timeout(TASK_TIMEOUT)
                // 错误处理
                .doOnError(error -> {
                    log.error("Download Task failed for {}: {}", musicId, error.getMessage());
                    entry.setStatus(CacheStatus.FAILED);
                    eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
                })
                // 这里的 onErrorResume 保证即使这个任务失败，Flux 链也不会断，会继续执行 delay 和下一个任务
                .onErrorResume(e -> Mono.empty())
                .then(); // 转为 Mono<Void>
    }

    private Mono<Void> downloadWithYtDlp(String musicId, CacheEntry entry, String sourceUrl) {
        Path stagingDirectory = Paths.get(LocalResourceConfig.CACHE_DIR, ".yt-dlp");
        return ytDlpResolver.downloadAudio(sourceUrl, stagingDirectory, musicId)
                .flatMap(downloaded -> Mono.fromRunnable(() -> {
                    String fileName = musicId + "." + downloaded.extension();
                    Path destination = Paths.get(LocalResourceConfig.CACHE_DIR, fileName);
                    completeDownload(musicId, entry, downloaded.file(), destination, fileName);
                }));
    }

    private void completeDownload(String musicId, CacheEntry entry, Path sourcePath, Path destination, String fileName) {
        try {
            try {
                Files.move(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(sourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(destination);
            long previousSize = entry.getSize();
            entry.setFileName(fileName);
            entry.setSize(size);
            entry.setStatus(CacheStatus.COMPLETED);
            currentTotalSize.addAndGet(size - previousSize);
            log.info("Download completed: {}", fileName);
            eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
            ensureCapacity();
        } catch (IOException e) {
            throw new RuntimeException("File write error", e);
        }
    }

    private boolean isRetryableDownloadFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 403 || status == 408 || status == 429 || status >= 500;
            }
            if (cause instanceof WebClientRequestException || cause instanceof IOException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * LRU 清理策略
     */
    private synchronized void ensureCapacity() {
        if (currentTotalSize.get() <= appProperties.getCache().getMaxSize().toBytes()) return;

        log.info("Cache limit exceeded. Cleaning up...");

        // 按最后访问时间排序
        cacheIndex.values().stream()
                .filter(e -> e.getStatus() == CacheStatus.COMPLETED) // 只删已完成的
                .sorted(Comparator.comparingLong(CacheEntry::getLastAccessTime))
                .forEach(entry -> {
                    if (currentTotalSize.get() <= appProperties.getCache().getMaxSize().toBytes()) return; // 容量够了就停

                    try {
                        Path path = Paths.get(LocalResourceConfig.CACHE_DIR, entry.getFileName());
                        Files.deleteIfExists(path);
                        currentTotalSize.addAndGet(-entry.getSize());
                        cacheIndex.remove(entry.getId());
                        log.info("Evicted: {}", entry.getFileName());
                    } catch (IOException e) {
                        log.error("Failed to delete {}", entry.getFileName(), e);
                    }
                });
    }

    /**
     * 获取文件访问 URL
     * 返回: /media/id.ext
     */
    public String getLocalUrl(String musicId) {
        CacheEntry entry = cacheIndex.get(musicId);
        if (entry != null && entry.getStatus() == CacheStatus.COMPLETED) {
            touch(musicId);
            return "/media/" + entry.getFileName();
        }
        return null;
    }

    public CacheStatus getStatus(String musicId) {
        if (!cacheIndex.containsKey(musicId)) return null;
        return cacheIndex.get(musicId).getStatus();
    }
    
    public CacheEntry getCacheEntry(String musicId) {
        return cacheIndex.get(musicId);
    }

    private void touch(String musicId) {
        CacheEntry entry = cacheIndex.get(musicId);
        if (entry != null) {
            entry.setLastAccessTime(System.currentTimeMillis());
        }
    }
}
