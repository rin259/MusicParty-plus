package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
        if (!jar.configured() || !jar.sessionReady()) {
            Status next = new Status(true, false, System.currentTimeMillis(),
                    jar.configured()
                            ? "cookies 缺少登录字段：" + String.join("、", jar.missing())
                            : "未配置 YouTube cookies");
            transition(next);
            return;
        }
        try {
            probeRunner.get().block(java.time.Duration.ofSeconds(35));
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

    private void transition(Status next) {
        boolean changed = status.healthy() != next.healthy() || !status.checked();
        status = next;
        if (!changed) return;
        if (next.healthy()) log.info("YouTube cookie health: OK");
        else log.warn("YouTube cookie health: {}", next.message());
    }
}
