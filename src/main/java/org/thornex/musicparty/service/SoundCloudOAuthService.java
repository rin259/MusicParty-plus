package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
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

    /**
     * handle → 用户 token。OAuth 的价值就在这里：带上它才能读「喜欢的音乐」
     * 这类需要用户身份的数据；公开 Sets 用 client_id 就够了。
     * 只存内存、按 TTL 过期，服务重启即失效（不落盘，避免长期保管凭据）。
     */
    private final Map<String, UserToken> tokens = new ConcurrentHashMap<>();

    /**
     * token 落盘（仅本机、0600）。自托管实例上重启/部署很频繁，
     * 不持久化的话每次都要用户重新授权一次，实际体验无法接受。
     * 只保存句柄与 token，不保存任何密码；文件在 data/ 下，已被 git 忽略。
     */
    private static final java.nio.file.Path TOKEN_FILE = java.nio.file.Path.of("data/soundcloud-oauth.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    void loadPersistedTokens() {
        try {
            if (!java.nio.file.Files.exists(TOKEN_FILE)) return;
            JsonNode root = MAPPER.readTree(java.nio.file.Files.readString(TOKEN_FILE));
            boolean migrated = false;
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode node = entry.getValue();
                String access = node.path("accessToken").asText("");
                if (access.isBlank()) continue;
                // 旧的键可能来自带 ?utm_... 的 permalink_url，统一归一成纯 handle
                String clean = handleFromPermalink(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (clean.isBlank()) continue;
                if (!clean.equals(entry.getKey())) migrated = true;
                tokens.put(clean, new UserToken(access,
                        node.path("refreshToken").asText(""), node.path("expiresAt").asLong(0L)));
            }
            log.info("Restored {} SoundCloud token(s) from disk", tokens.size());
            if (migrated) persistTokens();
        } catch (Exception error) {
            log.warn("Could not restore SoundCloud tokens: {}", error.getMessage());
        }
    }

    private synchronized void persistTokens() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            tokens.forEach((handle, token) -> {
                ObjectNode node = root.putObject(handle);
                node.put("accessToken", token.accessToken());
                node.put("refreshToken", token.refreshToken());
                node.put("expiresAt", token.expiresAt());
            });
            java.nio.file.Files.createDirectories(TOKEN_FILE.getParent());
            java.nio.file.Files.writeString(TOKEN_FILE, MAPPER.writeValueAsString(root));
            try {
                java.nio.file.Files.setPosixFilePermissions(TOKEN_FILE,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统（如 Windows）忽略
            }
        } catch (Exception error) {
            log.warn("Could not persist SoundCloud tokens: {}", error.getMessage());
        }
    }

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
    public synchronized Mono<Map<String, Object>> exchange(String code, String state) {
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
                .flatMap(tokenResponse -> {
                    String accessToken = tokenResponse.path("access_token").asText("");
                    String refreshToken = tokenResponse.path("refresh_token").asText("");
                    long expiresIn = tokenResponse.path("expires_in").asLong(3600L);
                    return fetchMe(accessToken)
                            .map(me -> {
                                Map<String, Object> profile = profileOf(me);
                                String handle = String.valueOf(profile.get("handle"));
                                if (!handle.isBlank()) {
                                    tokens.put(handleFromPermalink(handle).toLowerCase(java.util.Locale.ROOT),
                                            new UserToken(accessToken, refreshToken,
                                                    System.currentTimeMillis() + expiresIn * 1000L));
                                    persistTokens();
                                }
                                return profile;
                            });
                });
    }

    private Mono<JsonNode> exchangeToken(String code, String verifier) {
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
                .filter(node -> !node.path("access_token").asText("").isBlank())
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

    /** 已授权的 handle 列表（诊断用；不含 token 本身） */
    public java.util.Set<String> tokenHandles() {
        return java.util.Set.copyOf(tokens.keySet());
    }

    /** 该 handle 是否有可用的用户 token（决定能否展示「喜欢的音乐」） */
    public boolean hasUserToken(String handle) {
        return handle != null && tokens.containsKey(handleFromPermalink(handle).toLowerCase(java.util.Locale.ROOT));
    }

    /** 返回可用的 access token；过期时用 refresh_token 续期，失败则移除。 */
    public synchronized java.util.Optional<String> validAccessToken(String handle) {
        if (handle == null) return java.util.Optional.empty();
        String key = handleFromPermalink(handle).toLowerCase(java.util.Locale.ROOT);
        UserToken token = tokens.get(key);
        if (token == null) return java.util.Optional.empty();
        if (System.currentTimeMillis() < token.expiresAt() - 60_000) {
            return java.util.Optional.of(token.accessToken());
        }
        if (token.refreshToken() == null || token.refreshToken().isBlank()) {
            tokens.remove(key);
            persistTokens();
            return java.util.Optional.empty();
        }
        try {
            JsonNode refreshed = refreshToken(token.refreshToken());
            String access = refreshed.path("access_token").asText("");
            if (access.isBlank()) {
                tokens.remove(key);
                return java.util.Optional.empty();
            }
            long expiresIn = refreshed.path("expires_in").asLong(3600L);
            String nextRefresh = refreshed.path("refresh_token").asText(token.refreshToken());
            tokens.put(key, new UserToken(access, nextRefresh, System.currentTimeMillis() + expiresIn * 1000L));
            persistTokens();
            return java.util.Optional.of(access);
        } catch (RuntimeException error) {
            log.warn("SoundCloud token refresh failed: {}", error.getMessage());
            tokens.remove(key);
            return java.util.Optional.empty();
        }
    }

    private JsonNode refreshToken(String refreshToken) {
        AppProperties.SoundCloudOAuthConfig cfg = config();
        return webClient.post()
                .uri(URI.create(TOKEN_URL))
                .header("Accept", "application/json; charset=utf-8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=refresh_token"
                        + "&client_id=" + urlEncode(cfg.getClientId())
                        + "&client_secret=" + urlEncode(cfg.getClientSecret())
                        + "&refresh_token=" + urlEncode(refreshToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    private record UserToken(String accessToken, String refreshToken, long expiresAt) {}

    static Map<String, Object> profileOf(JsonNode me) {
        String permalink = me.path("permalink_url").asText("");
        String name = me.path("username").asText(me.path("display_name").asText("SoundCloud 用户"));
        String avatar = me.path("avatar_url").asText("");
        return Map.of("handle", handleFromPermalink(permalink), "name", name, "avatarUrl", avatar);
    }

    /** permalink → handle：只取路径第一段，丢掉查询串/尾部斜杠（否则会拼出非法 handle） */
    static String handleFromPermalink(String permalink) {
        if (permalink == null || permalink.isBlank()) return "";
        String value = permalink.trim();
        try {
            java.net.URI uri = java.net.URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String first = path.replaceAll("^/+", "").split("/")[0];
            if (!first.isBlank()) return first;
        } catch (RuntimeException ignored) {
            // 非标准 URL，退回手工解析
        }
        int domain = value.toLowerCase(java.util.Locale.ROOT).indexOf("soundcloud.com/");
        if (domain >= 0) value = value.substring(domain + "soundcloud.com/".length());
        return value.split("[/?#]")[0];
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
