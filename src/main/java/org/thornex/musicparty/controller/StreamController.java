package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.service.stream.StreamTokenService;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.OutputStream;

@RestController
@RequestMapping("/radio")
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    private final LiveStreamService liveStreamService;
    private final StreamTokenService streamTokenService;

    @GetMapping(value = "/stream", produces = "audio/mpeg")
    public void streamAudio(HttpServletRequest request, HttpServletResponse response, @RequestParam(name = "key", required = false) String key) {
        if (!liveStreamService.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        if (!streamTokenService.validateToken(key)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        response.setContentType("audio/mpeg");
        response.setHeader("Transfer-Encoding", "chunked");
        response.setHeader("Connection", "keep-alive");
        // 这是一个伪直播，不应该被缓存
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        String remoteAddr = getClientIp(request);
        OutputStream os = null;
        try {
            os = response.getOutputStream();
            liveStreamService.addListener(os, remoteAddr);

            // 原实现 os.wait() 无限等待且全服务无任何 notify/broadcast 唤醒，
            // broadcaster 因写失败移除监听后本线程仍永久 park，
            // 每个听众泄漏一根 Tomcat 线程直至重启。改为：监听被移除即退出，
            // 60s 一轮保底唤醒兜底。
            synchronized (os) {
                while (liveStreamService.isListenerActive(os)) {
                    os.wait(60_000);
                }
            }

        } catch (IOException | InterruptedException e) {
            log.debug("Stream client disconnected: {}", e.getMessage());
        } finally {
            if (os != null) {
                liveStreamService.removeListener(os, remoteAddr);
            }
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多级代理情况，取第一个非 unknown 的 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
