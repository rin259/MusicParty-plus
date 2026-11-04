package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 绑定的关键不变量：PKCE 挑战正确、state 单次使用（防 CSRF/重放）、/me 解析稳定。
 */
class SoundCloudOAuthServiceTest {

    private static final String RFC7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC7636_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private SoundCloudOAuthService configuredService() {
        AppProperties props = new AppProperties();
        props.getSoundcloudOAuth().setClientId("cid");
        props.getSoundcloudOAuth().setClientSecret("secret");
        props.getSoundcloudOAuth().setRedirectUri("https://example.com/callback");
        return new SoundCloudOAuthService(props, null);
    }

    private static String stateFrom(String authorizeUrl) {
        for (String part : authorizeUrl.split("&")) {
            if (part.startsWith("state=")) return part.substring("state=".length());
        }
        return "";
    }

    @Test
    void pkceChallengeMatchesRfc7636Vector() {
        assertThat(SoundCloudOAuthService.pkceChallenge(RFC7636_VERIFIER)).isEqualTo(RFC7636_CHALLENGE);
    }

    @Test
    void authorizeUrlCarriesClientAndChallenge() {
        String url = configuredService().start();
        assertThat(url).startsWith("https://secure.soundcloud.com/authorize?client_id=cid&")
                .contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback")
                .contains("response_type=code")
                .contains("code_challenge_method=S256")
                .contains("state=");
        assertThat(SoundCloudOAuthService.pkceChallenge(RFC7636_VERIFIER)).isNotBlank();
    }

    @Test
    void stateIsSingleUse() {
        SoundCloudOAuthService service = configuredService();
        String state = stateFrom(service.start());

        // 第一次使用：state 被核销，随后会尝试真实换 token（测试环境必然网络失败）
        String first = null;
        try {
            service.exchange("code", state).block(Duration.ofSeconds(5));
        } catch (Exception error) {
            first = String.valueOf(error.getMessage());
        }

        // 第二次使用同一个 state：必须明确报「无效」，而不是再打一次网络
        String second = null;
        try {
            service.exchange("code", state).block(Duration.ofSeconds(5));
        } catch (Exception error) {
            second = String.valueOf(error.getMessage());
        }
        assertThat(second).contains("state");
    }

    @Test
    void unknownStateIsRejectedImmediately() {
        try {
            configuredService().exchange("code", "bogus-state").block(Duration.ofSeconds(2));
            throw new AssertionError("should have failed");
        } catch (Exception error) {
            assertThat(String.valueOf(error.getMessage())).contains("state");
        }
    }

    @Test
    void parsesMeEndpointIntoProfile() throws Exception {
        String me = "{\"permalink_url\":\"https://soundcloud.com/djtest\",\"username\":\"DJ Test\","
                + "\"avatar_url\":\"https://i1.sndcdn.com/av.jpg\",\"id\":123456}";
        var profile = SoundCloudOAuthService.profileOf(new ObjectMapper().readTree(me));
        assertThat(profile.get("handle")).isEqualTo("djtest");
        assertThat(profile.get("name")).isEqualTo("DJ Test");
        assertThat(profile.get("avatarUrl")).isEqualTo("https://i1.sndcdn.com/av.jpg");
    }

    @Test
    void notConfiguredYieldsNullStart() {
        AppProperties props = new AppProperties();
        assertThat(new SoundCloudOAuthService(props, null).start()).isNull();
    }
}
