package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.UserSummary;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SoundCloud OAuth 2.1（PKCE）账号绑定。
 *
 * <p>研判（2026-11）：绑定公开主页只需要用户身份，不需要长期 token——
 * 拿到 /me 的 permalink 后按 handle 走既有的公开 Sets 流程即可。
 * 因此 token 用完即弃，服务端不落盘，避免自托管实例保管长期凭据。
 *
 * <p>流程（RFC 7636 authorization code + PKCE S256）：
 * <ol>
 *   <li>{@link #start()} 生成 state 与 code_verifier（内存暂存，10 分钟、单次使用），返回授权跳转地址；</li>
 *   <li>用户在 SoundCloud 授权，回跳到注册的 redirect_uri（/api/soundcloud/callback）；</li>
 *   <li>{@link #exchange(String, String)} 用 code + verifier 换 token，取 /me 得到身份，核销 state。</li>
 * </ol>
 */
@Slf4j
@Service
public class SoundCloudOAuthService {

    private static final String AUTHORIZE_URL = "https://secure.soundcloud.com/authorize";
    private static final String TOKEN_URL = "https://secure.soundcloud.com/oauth/token";
    private static final String ME_URL = "https://api.soundcloud.com/me";
    private static final Pattern STATE_TAKER = Pattern.compile("[A-Za-z0-9_-]{8,}");
    private static final Duration STATE_TTL_MS = Duration.ofMinutes(10);
    private static final int MAX_PENDING = 200;

    private final AppProperties appProperties;
    private final WebClient webClient;
    private final SecureRandom random = new SecureRandom();

    /** state → {verifier, createdAt}。单次使用，超时即弃。 */
    private final Map<String, PendingAuth> pending = new ConcurrentHashMap<>();

    public SoundCloudOAuthService(AppProperties appProperties, WebClient webClient) {
        this.appProperties = appProperties;
        this.webClient = webClient;
    }

    private AppProperties.SoundCloudOAuthConfig config() {
        return appProperties.getSoundcloudOAuth();
    }

    public boolean configured() {
        AppProperties.SoundCloudOAuthConfig cfg = config();
        return notBlank(cfg.getClientId()) && notBlank(cfg.getClientSecret()) && notBlank(cfg.getRedirectUri());
    }

    /** 生成授权跳转地址。未配置 OAuth 时返回 null（前端据此隐藏一键绑定按钮）。 */
    public synchronized String start() {
        if (!configured()) return null;
        trimPending();

        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] verifierBytes = verifier.getBytes(StandardCharsets.US_ASCII);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(verifierBytes);
        } catch (NoSuchAlgorithmException error) {
            return null; // JVM 必带 SHA-256，仅为编译检查
        }
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        String state = newToken();
        pending.put(state, new PendingAuth(verifier, System.currentTimeMillis()));
        return AUTHORIZE_URL
                + "?client_id=" + urlEncode(config().getClientId())
                + "&redirect_uri=" + urlEncode(config().getRedirectUri())
                + "&response_type=code"
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);
    }

    /**
     * 授权回调：换 token、取身份。state 必须存在且未被使用（防 CSRF / 重放）。
     * 返回绑定所需的用户资料（handle = permalink 最后一段）。
     */
    public Mono<Map<String, Object>> exchange(String code, String state) {
        if (!notBlank(code) || !notBlank(state)) {
            return Mono.error(new IllegalStateException("缺少 code 或 state"));
        }
        PendingAuth auth = pending.remove(state);
        if (auth == null) {
            return Mono.error(new IllegalStateException("state 无效或已过期"));
        }
        if (System.currentTimeMillis() - auth.createdAt() > STATE_TTL_MS.toMillis()) {
            return Mono.error(new IllegalStateException("state 已过期，请重新发起绑定"));
        }

        return exchangeToken(code, auth.verifier())
                .flatMap(token -> fetchMe(token))
                .map(SoundCloudOAuthService::profileOf);
    }

    private Mono<String> exchangeToken(String code, String verifier) {
        AppProperties.SoundCloudOAuthConfig cfg = config();
        return webClient.post()
                .uri(URI.create(TOKEN_URL))
                .header("Accept", "application/json; charset=utf-8")
                .bodyValue("grant_type=authorization_code"
                        + "&client_id=" + urlEncode(cfg.getClientId())
                        + "&client_secret=" + urlEncode(cfg.getClientSecret())
                        + "&redirect_uri=" + urlEncode(cfg.getRedirectUri())
                        + "&code=" + urlEncode(code)
                        + "&code_verifier=" + urlEncode(verifier))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(node -> node.path("access_token").asText(""))
                .filter(token -> !token.isBlank())
                .switchIfEmpty(Mono.error(new IllegalStateException("SoundCloud 未返回 access_token")));
    }

    private Mono<JsonNode> fetchMe(String accessToken) {
        return webClient.get()
                .uri(URI.create(ME_URL))
                .header("Authorization", "OAuth " + accessToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10));
    }

    static Map<String, Object> profileOf(JsonNode me) {
        String permalink = me.path("permalink_url").asText("");
        String name = me.path("username").asText(me.path("display_name").asText("SoundCloud 用户"));
        String avatar = me.path("avatar_url").asText("");
        String handle = permalink.startsWith("https://soundcloud.com/")
                ? permalink.substring("https://soundcloud.com/".length()).split("/")[0]
                : permalink;
        return Map.of("handle", handle, "name", name, "avatarUrl", avatar);
    }

    private void trimPending() {
        if (pending.size() <= MAX_PENDING) return;
        long cutoff = System.currentTimeMillis() - STATE_TTL_MS.toMillis();
        pending.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
        if (pending.size() > MAX_PENDING) pending.clear();
    }

    private String newToken() {
        // Base64URL 的输出本来就在 [A-Za-z0-9_-] 内，直接使用；
        // 早前用 STATE_TAKER.matcher(raw).replaceAll("") 会把整串删掉，生成空 state。
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record PendingAuth(String verifier, long createdAt) {}

    // —— 供单测使用的纯函数 ——

    static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
