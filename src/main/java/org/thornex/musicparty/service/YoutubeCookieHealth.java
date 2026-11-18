package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Periodically proves the stored YouTube cookies can still stream.
 *
 * <p>A cookie export dies silently: the file is still there and looks complete,
 * but Google has dropped the session. Probing keeps the session warm (each run
 * persists the rotated {@code __Secure-*PSIDTS} tokens through
 * {@link YoutubeCookieJar}) and turns "users notice the queue is stuck" into a
 * single log line plus a status flag in the admin panel.</p>
 */
@Service
@Slf4j
public class YoutubeCookieHealth {
    public record Status(boolean checked, boolean healthy, long checkedAt, String message) {}

    private final java.util.function.Supplier<reactor.core.publisher.Mono<Void>> probeRunner;
    private final YoutubeCookiesAdmin cookies;

    private volatile Status status = new Status(false, false, 0, "尚未检测");

    @org.springframework.beans.factory.annotation.Autowired
    public YoutubeCookieHealth(YtDlpResolver resolver, YoutubeCookiesAdmin cookies) {
        this(resolver::verifyYoutubeStreaming, cookies);
    }

    YoutubeCookieHealth(java.util.function.Supplier<reactor.core.publisher.Mono<Void>> probeRunner,
                        YoutubeCookiesAdmin cookies) {
        this.probeRunner = probeRunner;
        this.cookies = cookies;
    }

    public Status status() {
        return status;
    }

    /** Probe on startup (2 minutes in) so a stale export is reported the same day it breaks. */
    @Scheduled(initialDelayString = "${app.yt-dlp.cookie-health-initial-delay-ms:120000}",
            fixedDelayString = "${app.yt-dlp.cookie-health-interval-ms:2700000}")
    public void probe() {
        YoutubeCookiesAdmin.Status jar = cookies.status();
        if (!jar.configured()) {
            transition(new Status(true, false, System.currentTimeMillis(), "未配置 YouTube cookies"));
            return;
        }
        if (!jar.sessionReady()) {
            if (restoreLastGood()) jar = cookies.status();
            if (!jar.sessionReady()) {
                transition(new Status(true, false, System.currentTimeMillis(),
                        "cookies 缺少登录字段：" + String.join("、", jar.missing())));
                return;
            }
        }
        try {
            probeRunner.get().block(java.time.Duration.ofSeconds(35));
            keepLastGood();
            transition(new Status(true, true, System.currentTimeMillis(), "取流可用"));
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? "" : error.getMessage();
            if (message.contains("yt-dlp busy")) {
                // Saturated lanes are a queue condition, not a verdict on the cookies.
                // Keep the last known state instead of flapping the panel to "broken".
                return;
            }
            String reason = message.contains("登录校验")
                    ? "cookies 登录态已失效，需要重新导出"
                    : "取流失败：" + message;
            transition(new Status(true, false, System.currentTimeMillis(), reason));
        }
    }

    /** Immediate signal from a failed fetch, so the panel does not wait for the next probe. */
    @org.springframework.context.event.EventListener
    public void onAuthExpired(YoutubeAuthExpiredEvent event) {
        transition(new Status(true, false, System.currentTimeMillis(),
                "cookies 登录态已失效，需要重新导出"));
    }

    /**
     * Remembers the last jar that actually streamed. yt-dlp can write a jar that
     * has lost its login cookies (the server answers a rejected session with
     * Set-Cookie clears), and without a copy that damage is permanent.
     */
    private void keepLastGood() {
        Path source = cookies.file();
        if (!cookies.status().sessionReady()) return;
        try {
            Path good = source.resolveSibling("yt-cookies.good.txt");
            Files.copy(source, good, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(good, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystems keep their defaults.
            }
        } catch (IOException error) {
            log.warn("Could not store the last good YouTube cookie copy: {}", error.toString());
        }
    }

    /** Puts the last working jar back when a run truncated the login cookies. */
    private boolean restoreLastGood() {
        Path canonical = cookies.file();
        Path good = canonical.resolveSibling("yt-cookies.good.txt");
        if (!Files.isRegularFile(good)) return false;
        try {
            String content = Files.readString(good);
            if (!YoutubeCookiesAdmin.missingAuthCookies(content).isEmpty()) return false;
            Files.copy(good, canonical, StandardCopyOption.REPLACE_EXISTING);
            log.warn("YouTube cookie file lost its login cookies; restored the last working copy");
            return true;
        } catch (IOException error) {
            log.warn("Could not restore the last good YouTube cookies: {}", error.toString());
            return false;
        }
    }

    private void transition(Status next) {
        boolean changed = status.healthy() != next.healthy() || !status.checked();
        status = next;
        if (!changed) return;
        if (next.healthy()) log.info("YouTube cookie health: OK");
        else log.warn("YouTube cookie health: {}", next.message());
    }
}
