package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class LocalCacheService {

    private final WebClient webClient;
    /**
     * 任务间隔冷却。原来固定 3s，100 首批量导入光等待就 5 分钟。
     * yt-dlp 源（SoundCloud / YouTube Music）默认 1s（可配）；直链源（网易云 / B 站）
     * 仍保留原来的 3s，避免改动它们既有的防风控节奏。
     */
    private final long downloadCooldownMs;
    private final long directSourceCooldownMs;
    /**
     * 音频流的单次读间隔上限。CDN 节点挂掉时表现为「连着但不再给数据」，
     * 原来给 3 分钟，等于让队列干等 3 分钟；30s 足以覆盖正常的慢速读，
     * 超时后交给 retryWhen 重新取一个新鲜签名地址。
     */
    private static final Duration TRANSFER_IDLE_TIMEOUT = Duration.ofSeconds(30);

    // 内存中维护缓存文件的元数据
    private final Map<String, CacheEntry> cacheIndex = new ConcurrentHashMap<>();
    private final AtomicLong currentTotalSize = new AtomicLong(0);
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final YtDlpResolver ytDlpResolver;
    private Path cacheDirectory = Path.of(LocalResourceConfig.CACHE_DIR);
    private final PreparationScheduler<DownloadTask> scheduler;
    private final MetadataLookup metadataLookup;
    private final Object admissionLock = new Object();
    private final Map<String, Thread> activeWorkers = new ConcurrentHashMap<>();
    private final AtomicLong completedTasks = new AtomicLong(), failedTasks = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong(), mergedTasks = new AtomicLong();
    private final AtomicLong totalWaitMillis = new AtomicLong(), totalWorkMillis = new AtomicLong();
    private volatile boolean running = true;
    private Thread downloadWorker;

    /**
     * 下载队列的优先级。批量导入 / 恢复队列的预取不能把用户刚点的歌堵在最后：
     * 串行队列下，OBS/歌单预取 100 首会让单曲等上几十分钟。
     */
    public enum DownloadPriority {
        URGENT(0), NORMAL(1), BACKGROUND(2);

        final int rank;
        DownloadPriority(int rank) { this.rank = rank; }
    }

    private record DownloadTask(String musicId, Mono<DownloadSource> sourceProvider) {}
    private record StagedDownload(Path path, String fileName) {}

    public record DownloadSource(String url, Map<String, String> headers, String extension, boolean useYtDlp) {
        public DownloadSource(String url, Map<String, String> headers, String extension) {
            this(url, headers, extension, false);
        }

        public static DownloadSource ytDlp(String sourceUrl) {
            return new DownloadSource(sourceUrl, Map.of(), "", true);
        }
    }

    @Autowired
    public LocalCacheService(WebClient webClient, ApplicationEventPublisher eventPublisher, AppProperties appProperties,
                             YtDlpResolver ytDlpResolver) {
        this.webClient = webClient;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
        this.ytDlpResolver = ytDlpResolver;
        this.downloadCooldownMs = Math.max(0L, appProperties.getYtDlp().getDownloadCooldownMs());
        this.directSourceCooldownMs = Math.max(0L, appProperties.getPreparation().getDirectSourceCooldownMs());
        AppProperties.PreparationConfig config = appProperties.getPreparation();
        this.scheduler = new PreparationScheduler<>(config.getConcurrency(), config.getPerSourceConcurrency(),
                config.getYtDlpConcurrency(), config.getMaxPending(), System::currentTimeMillis);
        this.metadataLookup = new MetadataLookup(config.getMetadataConcurrency());
    }

    // Isolated filesystem for integration tests; production keeps its existing cache directory.
    LocalCacheService(WebClient client, ApplicationEventPublisher publisher, AppProperties properties,
                      YtDlpResolver resolver, Path directory) {
        this(client, publisher, properties, resolver);
        this.cacheDirectory = directory;
    }
    public java.util.Set<String> interestedRooms(String id) { return scheduler.interestedRooms(id); }

    public Mono<org.thornex.musicparty.dto.Music> lookupMetadata(
            org.thornex.musicparty.service.api.IMusicApiService api, String id) {
        return metadataLookup.get(api, id);
    }

    public record PreparationRequest(String id, String user, DownloadPriority priority, Runnable submit) {}

    /** Replace demand, cancel waiting orphans, then admit only selected window entries. */
    public void updatePreparationWindow(String room, Collection<PreparationRequest> requests) {
        synchronized (admissionLock) {
            List<String> removed = scheduler.replace(room, requests.stream()
                    .map(r -> new PreparationScheduler.Demand(r.id(), room, r.user(), r.priority())).toList());
            for (String id : removed) {
                CacheEntry entry = cacheIndex.get(id);
                if (entry != null && entry.getStatus() == CacheStatus.PENDING) cacheIndex.remove(id, entry);
            }
            // Admission callbacks only create cold providers, never fetch upstream.
            for (PreparationRequest request : requests) {
                CacheStatus status = getStatus(request.id());
                if (status == null) {
                    try { request.submit().run(); }
                    catch (RuntimeException error) { log.warn("Could not admit {}: {}", request.id(), error.toString()); }
                }
            }
        }
    }

    public record TaskTiming(String id, CacheStatus status, String phase, long queuedMs, long resolveMs,
                             long transferMs, String lastError) {}
    public record PreparationStats(int waiting, int active, long completed, long failed,
                                   long cacheHits, long merged, long totalWaitMs, long totalWorkMs,
                                   Map<String, Integer> activeBySource, List<TaskTiming> recentTasks,
                                   int waitingForLane, int retryBackoff, int sourceCooldown,
                                   long admissionDeferrals) {}
    private final AtomicLong admissionDeferrals = new AtomicLong();
    public PreparationStats preparationStats() {
        var counts = scheduler.snapshot();
        return new PreparationStats(counts.waiting(), counts.active(), completedTasks.get(), failedTasks.get(),
                cacheHits.get(), mergedTasks.get(), totalWaitMillis.get(), totalWorkMillis.get(), counts.activeBySource(),
                cacheIndex.values().stream().sorted(Comparator.comparingLong(CacheEntry::getLastAccessTime).reversed())
                        .limit(64).map(e -> new TaskTiming(e.getId(), e.getStatus(), e.getPhase(),
                                e.getQueuedMillis(), e.getResolveMillis(), e.getTransferMillis(), e.getLastError())).toList(),
                counts.waiting() - counts.retryBackoff() - counts.sourceCooldown(), counts.retryBackoff(), counts.sourceCooldown(),
                admissionDeferrals.get());
    }
    public void promoteDownload(String id, DownloadPriority priority) {
        synchronized (admissionLock) {
            if (scheduler.contains(id)) scheduler.submit(id, null, priority);
        }
    }

    @Data
    public static class CacheEntry {
        private String id;
        private String fileName;
        private volatile CacheStatus status;
        private long size;
        private long lastAccessTime;
        private String originalUrl; // 用于重试或记录
        private volatile String phase = "waiting";
        private volatile long queuedMillis;
        private volatile long resolveMillis;
        private volatile long transferMillis;
        private volatile int retryCount;
        private volatile long startedAtNanos;
        private volatile String lastError;
    }

    @PostConstruct
    public void init() {
        // 初始化时扫描目录，重建索引和计算大小
        File dir = cacheDirectory.toFile();
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
                entry.setPhase("ready");
                entry.setSize(f.length());
                entry.setLastAccessTime(System.currentTimeMillis());

                cacheIndex.put(id, entry);
                currentTotalSize.addAndGet(f.length());
            }
        }
        log.info("LocalCacheService initialized. Current cache size: {} bytes", currentTotalSize.get());

        this.downloadWorker = Thread.ofVirtual().name("download-worker").start(this::drainDownloadQueue);
    }

    @PreDestroy
    public void cleanup() {
        List<Thread> workers;
        synchronized (admissionLock) {
            running = false;
            if (downloadWorker != null) downloadWorker.interrupt();
            workers = List.copyOf(activeWorkers.values());
        }
        workers.forEach(Thread::interrupt);
        for (Thread worker : workers) {
            try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        metadataLookup.close();
    }

    private void drainDownloadQueue() {
        log.info("Download dispatcher started (bounded, source-isolated fair queue)");
        while (running) {
            synchronized (admissionLock) {
                if (!running) break;
                var dispatch = scheduler.poll();
                if (dispatch != null) {
                    DownloadTask task = dispatch.payload();
                    CacheEntry entry = cacheIndex.get(task.musicId());
                    entry.setQueuedMillis(entry.getQueuedMillis() + dispatch.queuedMillis());
                    totalWaitMillis.addAndGet(dispatch.queuedMillis());
                    Thread worker = Thread.ofVirtual().name("prepare-" + task.musicId())
                            .unstarted(() -> runAttempt(task, entry));
                    activeWorkers.put(task.musicId(), worker);
                    worker.start();
                    continue;
                }
            }
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        log.info("Download dispatcher stopped");
    }

    private long taskBudgetMillis() {
        return Duration.ofSeconds(Math.max(1, appProperties.getPreparation().getTaskTimeoutSeconds())).toMillis();
    }

    private void runAttempt(DownloadTask task, CacheEntry entry) {
        String id = task.musicId();
        long start = System.nanoTime();
        if (entry.getStartedAtNanos() == 0) entry.setStartedAtNanos(start);
        Throwable failure = null;
        Path partial = null;
        try {
            var remaining = taskBudgetMillis() - elapsedMillis(entry.getStartedAtNanos());
            if (remaining <= 0) throw new TimeoutException("Preparation retry budget exhausted");
            partial = Files.createTempFile(cacheDirectory, "prepare-", ".part");
            var staged = processTask(task, entry, partial, remaining).block();
            if (staged == null) throw new IllegalStateException("Preparation returned no media file");
            completeDownload(id, entry, staged.path(), cacheDirectory.resolve(staged.fileName()), staged.fileName());
        } catch (Exception | Error error) {
            failure = error;
            if (error instanceof Error fatal) throw fatal;
        } finally {
            if (partial != null) {
                try { Files.deleteIfExists(partial); }
                catch (IOException error) { log.warn("Failed to clean partial cache file {}", partial, error); }
            }
            totalWorkMillis.addAndGet(elapsedMillis(start));
            boolean deferred = finishAttempt(id, entry, failure);
            if (failure != null && running) {
                if (deferred) log.info("Preparation deferred: {} (retry {})", id, entry.getRetryCount());
                else log.warn("Preparation failed for {}: {}", id, failure.toString());
                eventPublisher.publishEvent(new DownloadStatusEvent(this, id));
            }
        }
    }

    private boolean finishAttempt(String id, CacheEntry entry, Throwable failure) {
        synchronized (admissionLock) {
            activeWorkers.remove(id, Thread.currentThread());
            // Release the scheduler job and its worker registration atomically with respect
            // to same-ID admission, dispatcher selection and room-window cancellation.
            long cooldown = cooldownFor(id);
            if (failure != null && entry.getStatus() != CacheStatus.COMPLETED) {
                long delay = retryDelay(failure, entry.getRetryCount());
                if (isRateLimit(failure)) scheduler.penalize(id, delay);
                if (running && entry.getRetryCount() < 2 && isRetryableDownloadFailure(failure)
                        && elapsedMillis(entry.getStartedAtNanos()) + delay < taskBudgetMillis()
                        && scheduler.defer(id, delay, cooldown)) {
                    entry.setRetryCount(entry.getRetryCount() + 1);
                    entry.setPhase("retry-wait");
                    entry.setStatus(CacheStatus.PENDING);
                    return true;
                }
                entry.setPhase("failed");
                entry.setStatus(CacheStatus.FAILED);
                entry.setLastError(briefFailure(failure));
                failedTasks.incrementAndGet();
            }
            scheduler.complete(id, cooldown);
            return false;
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
        submitDownload(musicId, urlProvider, headers, extension, DownloadPriority.NORMAL);
    }

    public void submitDownload(String musicId, Mono<String> urlProvider, Map<String, String> headers,
                               String extension, DownloadPriority priority) {
        submitDownload(musicId, urlProvider.map(url -> new DownloadSource(url, headers, extension)), priority);
    }

    public void submitDownload(String musicId, Mono<DownloadSource> sourceProvider) {
        submitDownload(musicId, sourceProvider, DownloadPriority.NORMAL);
    }

    public void submitDownload(String musicId, Mono<DownloadSource> sourceProvider, DownloadPriority priority) {
        synchronized (admissionLock) {
            if (!running) return;
            CacheEntry existing = cacheIndex.get(musicId);
            if (existing != null && existing.getStatus() == CacheStatus.COMPLETED) {
                cacheHits.incrementAndGet(); touch(musicId); return;
            }
            DownloadPriority safePriority = priority == null ? DownloadPriority.NORMAL : priority;
            if (scheduler.contains(musicId)) {
                scheduler.submit(musicId, null, safePriority); // atomic merge / priority promotion
                mergedTasks.incrementAndGet(); return;
            }
            CacheEntry entry = new CacheEntry();
            entry.setId(musicId); entry.setStatus(CacheStatus.PENDING);
            entry.setLastAccessTime(System.currentTimeMillis());
            cacheIndex.put(musicId, entry);
            DownloadTask task = new DownloadTask(musicId, sourceProvider);
            if (!scheduler.submit(musicId, task, safePriority)) {
                cacheIndex.remove(musicId, entry);
                admissionDeferrals.incrementAndGet();
                log.debug("Preparation queue is full, deferring {}", musicId);
                return; // window reconciliation retries admission; not a permanent media failure
            }
        }
        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        log.info("Download enqueued: {} ({})", musicId, priority);
    }

    private long cooldownFor(String musicId) {
        boolean ytDlpSource = musicId.startsWith("soundcloud_") || musicId.startsWith("ytmusic_");
        return ytDlpSource ? downloadCooldownMs : directSourceCooldownMs;
    }

    private Mono<StagedDownload> processTask(DownloadTask task, CacheEntry entry, Path partial, long remaining) {
        String musicId = task.musicId();
        entry.setPhase("resolve-source");
        entry.setResolveMillis(0);
        entry.setTransferMillis(0);
        entry.setStatus(CacheStatus.DOWNLOADING);
        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        log.info("Processing download: {}", musicId);

        // 卡住时要能一眼看出卡在「解析地址」还是「传输字节」，
        // 否则日志里只有 Processing / failed 两行，无法定位。
        long startedAt = System.nanoTime();
        AtomicReference<String> phase = new AtomicReference<>("resolve-source");
        AtomicLong phaseAt = new AtomicLong(startedAt);

        return Mono.defer(task::sourceProvider)
                .switchIfEmpty(Mono.error(new IllegalStateException("Source provider returned no audio source")))
                .timeout(Duration.ofSeconds(Math.max(1, appProperties.getPreparation().getResolveTimeoutSeconds())))
                .flatMap(source -> {
                    entry.setResolveMillis(elapsedMillis(phaseAt.get()));
                    phaseAt.set(System.nanoTime());
                    entry.setOriginalUrl(source.url());
                    if (source.useYtDlp()) {
                        phase.set("yt-dlp");
                        entry.setPhase("yt-dlp");
                        return downloadWithYtDlp(musicId, source.url());
                    }
                    phase.set("transfer");
                    entry.setPhase("transfer");

                    String fileName = musicId + source.extension();
                    return webClient.get()
                            .uri(source.url())
                            .headers(httpHeaders -> source.headers().forEach(httpHeaders::add))
                            .retrieve()
                            .bodyToFlux(DataBuffer.class)
                            .timeout(TRANSFER_IDLE_TIMEOUT)
                            .as(data -> DataBufferUtils.write(data, partial, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                            // A trickle of bytes defeats an idle timeout. Bound the entire
                            // transfer too, so 2B/s downloads cannot monopolize the source.
                            .timeout(Duration.ofSeconds(Math.max(1, appProperties.getPreparation().getTransferTimeoutSeconds())))
                            .thenReturn(new StagedDownload(partial, fileName))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                // Deadline includes every attempt plus time spent in deferred backoff.
                .timeout(Duration.ofMillis(remaining))
                .doOnError(error -> {
                    if ("resolve-source".equals(phase.get())) entry.setResolveMillis(elapsedMillis(startedAt));
                    else entry.setTransferMillis(elapsedMillis(phaseAt.get()));
                    log.debug("Preparation attempt failed for {} (phase={}, {}ms): {}", musicId,
                            phase.get(), elapsedMillis(startedAt), error.toString());
                })
                .doOnNext(staged -> entry.setTransferMillis(elapsedMillis(phaseAt.get())));
    }

    private boolean isRateLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response && response.getStatusCode().value() == 429) return true;
        }
        return false;
    }
    private long retryDelay(Throwable error, int retries) {
        long delay = (1000L << retries) + java.util.concurrent.ThreadLocalRandom.current().nextLong(250);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response && response.getStatusCode().value() == 429) {
                delay = 10_000L;
                try { delay = Math.max(delay, Long.parseLong(response.getHeaders().getFirst("Retry-After")) * 1000L); }
                catch (RuntimeException ignored) { /* missing/date-form header */ }
                return Math.min(120_000L, delay);
            }
        }
        return delay;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private Mono<StagedDownload> downloadWithYtDlp(String musicId, String sourceUrl) {
        Path stagingDirectory = cacheDirectory.resolve(".yt-dlp");
        return ytDlpResolver.downloadAudio(sourceUrl, stagingDirectory, musicId)
                .map(downloaded -> new StagedDownload(downloaded.file(), musicId + "." + downloaded.extension()));
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
            entry.setPhase("ready");
            completedTasks.incrementAndGet();
            currentTotalSize.addAndGet(size - previousSize);
            log.info("Download completed: {} (wait={}ms resolve={}ms transfer={}ms)", fileName,
                    entry.getQueuedMillis(), entry.getResolveMillis(), entry.getTransferMillis());
            eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
            ensureCapacity();
        } catch (IOException e) {
            throw new RuntimeException("File write error", e);
        }
    }

    /** Short, user-facing reason for a terminal failure. */
    private static String briefFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            String text = message.toLowerCase(java.util.Locale.ROOT);
            if (text.contains("sign in to confirm") || text.contains("cookies for the authentication")) {
                return "YouTube 需要登录 cookies（data/yt-cookies.txt）";
            }
            if (text.contains("private video") || text.contains("video unavailable")) {
                return "视频不可用或已被删除";
            }
            String single = message.replaceAll("\\s+", " ").trim();
            if (!single.isEmpty()) return single.length() > 160 ? single.substring(0, 160) : single;
        }
        return null;
    }

    public String failureReason(String musicId) {
        CacheEntry entry = cacheIndex.get(musicId);
        return entry == null ? null : entry.getLastError();
    }

    /** Permanent upstream rejections must not burn two retries and a cool-down window. */
    private static boolean isPermanentFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            String text = message.toLowerCase(java.util.Locale.ROOT);
            if (text.contains("sign in to confirm") || text.contains("cookies for the authentication")
                    || text.contains("private video") || text.contains("video unavailable")
                    || text.contains("drm protected")) return true;
        }
        return false;
    }

    boolean isRetryableDownloadFailure(Throwable error) {
        if (isPermanentFailure(error)) return false;
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
                .filter(e -> !scheduler.demanded(e.getId())) // active room windows pin their near-playback files
                .sorted(Comparator.comparingLong(CacheEntry::getLastAccessTime))
                .forEach(entry -> {
                    if (currentTotalSize.get() <= appProperties.getCache().getMaxSize().toBytes()) return; // 容量够了就停

                    try {
                        Path path = cacheDirectory.resolve(entry.getFileName());
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
        CacheEntry entry = cacheIndex.get(musicId);
        return entry == null ? null : entry.getStatus();
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
