package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.SoundCloudOAuthService;
import org.thornex.musicparty.service.api.SoundCloudMusicApiService;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * SoundCloud 一键绑定（OAuth 2.1 + PKCE）。
 *
 * <p>流程走弹窗：
 * <ol>
 *   <li>前端打开 {@code /api/soundcloud/oauth/start} → 302 到 SoundCloud 授权页；</li>
 *   <li>用户授权后回跳 {@code /api/soundcloud/callback}，后端用 code 换 token、取 /me 身份，
 *       然后返回一段内联 HTML：通过 postMessage 把身份交给 opener 并自动关闭；</li>
 *   <li>弹窗校验 event.origin 与本站一致后才落绑定，伪造来源的消息会被丢弃。</li>
 * </ol>
 * client_secret 只在服务端使用；token 用完即弃，不落盘。
 */
@Slf4j
@RestController
@RequestMapping("/api/soundcloud")
public class SoundCloudOAuthController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SoundCloudOAuthService oauthService;
    private final SoundCloudMusicApiService soundCloudApi;
    private final AppProperties appProperties;

    public SoundCloudOAuthController(SoundCloudOAuthService oauthService,
                                     SoundCloudMusicApiService soundCloudApi,
                                     AppProperties appProperties) {
        this.oauthService = oauthService;
        this.soundCloudApi = soundCloudApi;
        this.appProperties = appProperties;
    }

    /**
     * 绑定诊断（管理员）：用已存的 token 逐个打真实接口，回报状态/计数。
     * 用来一次性确认「鉴权头、歌单总数、私密数」等事实，而不是靠猜。
     */
    @GetMapping("/oauth/diagnose")
    public Mono<ResponseEntity<Map<String, Object>>> diagnose(
            @RequestParam(required = false) String handle,
            @RequestParam(required = false) String playlist,
            @RequestHeader(value = "X-Admin-Password", required = false) String password) {
        if (password == null || !password.equals(appProperties.getAdminPassword())) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.<String, Object>of("message", "ACCESS DENIED")));
        }
        if (playlist != null && !playlist.isBlank()) {
            return soundCloudApi.diagnosePlaylist(handle == null ? "" : handle, playlist).map(ResponseEntity::ok);
        }
        return (handle == null || handle.isBlank() ? soundCloudApi.diagnoseAll() : soundCloudApi.diagnose(handle))
                .map(ResponseEntity::ok);
    }

    /** 前端探测：OAuth 是否已配置（决定「一键绑定」按钮是否显示） */
    @GetMapping("/oauth/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", oauthService.configured());
    }

    /**
     * 前端使用的 JSON 版本：返回 {url, state}，前端打开弹窗后按 state 轮询结果。
     * 这样不依赖 window.opener / postMessage 的跨域与 COOP 行为。
     */
    @GetMapping("/oauth/start/json")
    public ResponseEntity<Map<String, String>> startJson() {
        SoundCloudOAuthService.StartSession session = oauthService.startSession();
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
        return ResponseEntity.ok(Map.of("url", session.url(), "state", session.state()));
    }

    /** 绑定结果轮询：done=false 表示还在授权中；done=true 时结果只返回一次。 */
    @GetMapping("/oauth/result")
    public Map<String, Object> result(@RequestParam(required = false) String state) {
        SoundCloudOAuthService.OAuthResult result = oauthService.consumeResult(state);
        if (result == null) return Map.of("done", false);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("done", true);
        out.put("ok", result.ok());
        if (result.profile() != null) out.put("profile", result.profile());
        if (result.message() != null) out.put("message", result.message());
        return out;
    }

    /** 生成授权跳转地址并 302。未配置 OAuth 时返回 501，前端会隐藏一键绑定入口。 */
    @GetMapping("/oauth/start")
    public Mono<ResponseEntity<Void>> start() {
        return Mono.fromSupplier(oauthService::start)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .<Void>build())
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).<Void>build());
    }

    /** SoundCloud 授权回跳：内联 HTML 通过 postMessage 把身份交给 opener 后自关。 */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> callback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) String error,
                                 @RequestParam(value = "error_description", required = false) String errorDescription) {
        if (notBlank(error)) {
            String message = notBlank(errorDescription) ? errorDescription : "用户取消了授权";
            oauthService.rememberResult(state, false, null, message);
            return Mono.just(page(false, message));
        }
        return oauthService.exchange(code, state)
                .map(profile -> {
                    oauthService.rememberResult(state, true, profile, null);
                    return page(true, jsonString(profile));
                })
                .onErrorResume(failure -> {
                    String message = friendlyOAuthError(failure.getMessage());
                    log.warn("SoundCloud OAuth callback failed: {}", failure.getMessage());
                    oauthService.rememberResult(state, false, null, message);
                    return Mono.just(page(false, message));
                });
    }

    private static String page(boolean ok, String messageOrProfileJson) {
        String payload;
        if (ok) {
            payload = "{ ok: true, profile: " + messageOrProfileJson + " }";
        } else {
            payload = "{ ok: false, message: " + jsonString(messageOrProfileJson) + " }";
        }
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<title>SoundCloud 绑定</title>"
                + "<style>body{background:#111;color:#eee;font-family:ui-monospace,monospace;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}</style>"
                + "</head><body><div id=\"msg\">绑定完成，窗口即将关闭…</div>"
                + "<script>"
                + "var payload = " + payload + ";"
                + "try {"
                + "  if (window.opener && !window.opener.closed) {"
                + "    window.opener.postMessage({ source: 'soundcloud-oauth', payload: payload }, window.location.origin);"
                + "  } else {"
                + "    document.getElementById('msg').textContent = payload.ok === false"
                + "      ? ('绑定失败：' + (payload.message || '未知错误'))"
                + "      : ('已绑定：' + (payload.profile ? payload.profile.name || payload.profile.handle : ''));"
                + "  }"
                + "} catch (e) { document.getElementById('msg').textContent = '绑定完成'; }"
                + "setTimeout(function(){ try { window.close(); } catch (e) {} }, 400);"
                + "</script></body></html>";
    }

    /** 把 WebClient 的技术错误翻译成用户能理解的一句话。 */
    private static String friendlyOAuthError(String raw) {
        if (raw == null || raw.isBlank()) return "绑定失败，请重试";
        if (raw.contains("400 Bad Request")) return "授权已失效或已被使用，请重新发起绑定";
        if (raw.contains("state")) return "授权链接已过期，请重新发起绑定";
        return raw.length() > 160 ? raw.substring(0, 160) + "…" : raw;
    }

    private static String jsonString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return "\"绑定失败\"";
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
