package org.thornex.musicparty.service.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.StreamStatusEvent;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.enums.CacheStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class LiveStreamService {

    private final LocalCacheService localCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    
    // 开关状态 (可以通过 AdminController 修改)
    private final AtomicBoolean isEnabled = new AtomicBoolean(false);
    
    // 是否有客户端连接
    private final AtomicBoolean hasListeners = new AtomicBoolean(false);

    // 播放器状态
    private volatile PlayableMusic currentMusic;
    private volatile boolean isPaused = true;
    private volatile long currentPosition = 0;

    // FFmpeg 进程管理
    private Process transcoderProcess;
    private ExecutorService streamExecutor;
    // 当前 ffmpeg 的输入与启动位置：用来区分「同一首歌的普通状态广播」和「切歌/seek」
    private String streamInputSource;
    private long streamStartedPosition;
    private long streamStartedAt;
    
    // 广播器：负责将转码后的数据分发给所有 HTTP 客户端
    private final StreamBroadcaster broadcaster = new StreamBroadcaster();

    // 统计唯一收听人数 (按 IP 地址去重)
    private final java.util.Map<String, Integer> ipConnectionCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<OutputStream, String> streamToIp = new java.util.concurrent.ConcurrentHashMap<>();

    public LiveStreamService(LocalCacheService localCacheService, ApplicationEventPublisher eventPublisher, AppProperties appProperties) {
        this.localCacheService = localCacheService;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        streamExecutor = Executors.newCachedThreadPool();
        broadcaster.setOnClientRemoved(this::handleClientRemoved);
    }

    private void handleClientRemoved(OutputStream os) {
        // 广播线程写失败时把等待中的 StreamController 线程立刻叫醒，不用再等 60s 保底
        synchronized (os) {
            os.notifyAll();
        }
        String ip = streamToIp.remove(os);
        if (ip != null) {
            ipConnectionCount.computeIfPresent(ip, (k, v) -> v > 1 ? v - 1 : null);
            int currentCount = getStreamListenerCount();
            if (currentCount == 0) {
                if (hasListeners.compareAndSet(true, false)) {
                    eventPublisher.publishEvent(new StreamStatusEvent(this, false, 0));
                }
                checkState();
            } else {
                eventPublisher.publishEvent(new StreamStatusEvent(this, true, currentCount));
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        stopTranscoding();
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
        }
    }

    // --- Public Control Methods ---

    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        log.info("LiveStreamService enabled: {}", enabled);
        checkState();
    }
    
    public boolean isEnabled() {
        return isEnabled.get();
    }

    public int getStreamListenerCount() {
        return ipConnectionCount.size();
    }

    /** 该监听是否仍在 broadcaster 的活跃集合中（客户端断开被移除后为 false）。 */
    public boolean isListenerActive(OutputStream outputStream) {
        return broadcaster.hasClient(outputStream);
    }

    public void addListener(OutputStream outputStream, String remoteAddr) {
        hasListeners.set(true);
        broadcaster.addClient(outputStream);
        
        if (remoteAddr != null) {
            streamToIp.put(outputStream, remoteAddr);
            ipConnectionCount.merge(remoteAddr, 1, Integer::sum);
        }
        
        eventPublisher.publishEvent(new StreamStatusEvent(this, true, getStreamListenerCount()));
        
        checkState();
    }

    public void removeListener(OutputStream outputStream, String remoteAddr) {
        broadcaster.removeClient(outputStream);
        // 注意：broadcaster.removeClient 会触发 handleClientRemoved，
        // 而 handleClientRemoved 已经处理了 ipConnectionCount 和事件发布。
        // 这里主要作为一个兜底，确保即便 broadcaster 没调到（比如连接还没加入 broadcaster 就断了），也能清理状态。
        handleClientRemoved(outputStream);
    }

    // --- Event Handling ---

    @EventListener
    public void onPlayerState(PlayerStateEvent event) {
        if (!isEnabled.get()) return;

        var state = event.getState();
        this.isPaused = state.isPaused();
        
        if (state.nowPlaying() != null) {
            this.currentMusic = state.nowPlaying().music();
            this.currentPosition = state.nowPlaying().currentPosition();
        } else {
            this.currentMusic = null;
            this.currentPosition = 0;
        }

        checkState();
    }

    // --- Core Logic ---

    private synchronized void checkState() {
        boolean shouldRun = isEnabled.get() && hasListeners.get() && currentMusic != null && !isPaused;

        if (shouldRun) {
            startTranscodingIfNotRunning();
        } else {
            stopTranscoding();
            // 如果是因为暂停导致的停止，我们可以发送静音帧来保持连接不断开
            // 但为了简化，暂且让它静默 (StreamBroadcaster 会处理空闲时的静音数据填充)
            if (hasListeners.get()) {
                 broadcaster.sendSilence();
            }
        }
    }

    private synchronized void startTranscodingIfNotRunning() {
        if (currentMusic == null) return;

        String inputSource = resolveInputSource();
        if (inputSource == null) {
            stopTranscoding();
            return;
        }

        java.util.Map<String, String> httpHeaders = new java.util.HashMap<>();
        if (inputSource.startsWith("http") && "bilibili".equals(currentMusic.platform())) {
            httpHeaders.put("Referer", "https://www.bilibili.com/");
            httpHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }

        // 同一个输入 + 没有明显 seek 时，别因为点赞/用户列表之类的状态广播重启 ffmpeg。
        // 允许 2s 误差：状态包里的 currentPosition 与转码进程的实际进度不会完全同步。
        if (inputSource.equals(streamInputSource) && transcoderProcess != null && transcoderProcess.isAlive()) {
            long expected = streamStartedPosition + (System.currentTimeMillis() - streamStartedAt);
            if (Math.abs(expected - currentPosition) < 2000) {
                return;
            }
        }

        stopTranscoding(); // 先杀掉旧的

        log.info("Stream: Starting transcoding for {} at {}ms", currentMusic.name(), currentPosition);

        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(appProperties.getFfmpegPath());
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-nostdin");

            // 如果是网络流，添加 Headers
            if (inputSource.startsWith("http") && !httpHeaders.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                httpHeaders.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
                command.add("-headers");
                command.add(sb.toString());
            }

            double startSeconds = currentPosition / 1000.0;
            command.add("-ss");
            command.add(String.format(java.util.Locale.US, "%.2f", startSeconds));
            command.add("-re");
            command.add("-i");
            command.add(inputSource);

            // 输出参数
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-ab");
            command.add("128k");
            command.add("-ac");
            command.add("2");
            command.add("-ar");
            command.add("44100");
            command.add("-f");
            command.add("mp3");
            command.add("pipe:1");

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            transcoderProcess = process;
            streamInputSource = inputSource;
            streamStartedPosition = currentPosition;
            streamStartedAt = System.currentTimeMillis();

            // 两块流都要有人读：stdout 是音频，stderr 是错误。
            // 之前 stderr 直接 DISCARD，输入路径错时整条流会静默失败、HTTP 永远没有响应头。
            streamExecutor.submit(() -> logFfmpegErrors(process));
            streamExecutor.submit(() -> pumpAudio(process));

        } catch (IOException e) {
            transcoderProcess = null;
            log.error("Stream: Failed to start ffmpeg", e);
        }
    }

    /**
     * 解析当前曲目的可读输入。
     *
     * <p>优先用本地缓存。缓存索引的 key 是 platform_id（B 站是裸 bvid），
     * 不是 currentMusic.id()；旧代码用错 key 导致查不到缓存，退回
     * currentMusic.url() 的 /media/...（站内相对地址），ffmpeg 直接失败。
     */
    private String resolveInputSource() {
        if (currentMusic == null) return null;

        String cacheId = cacheIdFor(currentMusic);
        if (cacheId != null) {
            LocalCacheService.CacheEntry entry = localCacheService.getCacheEntry(cacheId);
            if (entry != null && entry.getStatus() == CacheStatus.COMPLETED
                    && entry.getFileName() != null && !entry.getFileName().isBlank()) {
                Path filePath = Paths.get(LocalResourceConfig.CACHE_DIR, entry.getFileName());
                if (Files.exists(filePath)) {
                    log.debug("Stream: Using local file for {} ({})", currentMusic.name(), cacheId);
                    return filePath.toAbsolutePath().toString();
                }
            }
        }

        String url = currentMusic.url();
        if (url == null || url.isBlank() || "PENDING_DOWNLOAD".equals(url)) {
            log.warn("Stream: Music source not ready for {}", currentMusic.name());
            return null;
        }

        // 兼容旧状态：/media/<file> 是站内 URL，不是文件系统路径
        if (url.startsWith("/media/")) {
            Path cached = Paths.get(LocalResourceConfig.CACHE_DIR, url.substring("/media/".length()));
            if (Files.exists(cached)) {
                log.debug("Stream: Resolved media URL to {}", cached);
                return cached.toAbsolutePath().toString();
            }
            log.warn("Stream: media URL {} has no cached file", url);
            return null;
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            log.debug("Stream: Using network URL for {}", currentMusic.name());
            return url;
        }

        log.warn("Stream: Unsupported input source for {}: {}", currentMusic.name(), url);
        return null;
    }

    /** 缓存 key：普通平台是 platform_id，B 站沿用裸 bvid。 */
    static String cacheIdFor(PlayableMusic music) {
        if (music == null || music.id() == null || music.id().isBlank()) return null;
        String platform = music.platform() == null ? "" : music.platform();
        return "bilibili".equalsIgnoreCase(platform) ? music.id() : platform + "_" + music.id();
    }

    private void logFfmpegErrors(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) log.warn("Stream ffmpeg: {}", line);
            }
        } catch (IOException ignored) {
            // 进程被停止时读流会关闭
        }
    }

    private void pumpAudio(Process process) {
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) break;
                broadcaster.broadcast(buffer, bytesRead);
            }
        } catch (IOException e) {
            // 进程被杀时会抛出 Stream closed，忽略
        } finally {
            try {
                int exit = process.waitFor();
                // 主动 stopTranscoding() 会先把字段清空/替换；这种非零退出是预期的，
                // 只有「当前进程自己退出」才值得报错。
                boolean current = transcoderProcess == process;
                if (current) transcoderProcess = null;
                if (exit != 0 && current && !Thread.currentThread().isInterrupted()) {
                    log.error("Stream: ffmpeg exited with code {} for {}",
                            exit, currentMusic == null ? "?" : currentMusic.name());
                } else {
                    log.debug("Stream: Transcoding finished.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void stopTranscoding() {
        if (transcoderProcess != null) {
            if (transcoderProcess.isAlive()) {
                transcoderProcess.destroy();
            }
            transcoderProcess = null;
        }
        streamInputSource = null;
        streamStartedPosition = 0;
        streamStartedAt = 0;
    }
}
