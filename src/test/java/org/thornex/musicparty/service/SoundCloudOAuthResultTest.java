package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 结果轮询的存储语义。
 *
 * <p>背景：回跳域名固定在 redirect_uri（ajdgsu.online），但用户可能从别的域名
 * （music.rin.red）打开站点；此时 window.opener/postMessage 不可用。
 * 前端改为按 state 轮询，因此服务端结果必须「单次消费、带错误信息」。
 */
class SoundCloudOAuthResultTest {

    private SoundCloudOAuthService service() {
        return new SoundCloudOAuthService(new AppProperties(), WebClient.builder().build());
    }

    @Test
    void successResultIsConsumedExactlyOnce() {
        SoundCloudOAuthService service = service();
        service.rememberResult("state-1", true, Map.of("handle", "alice", "name", "Alice"), null);

        SoundCloudOAuthService.OAuthResult first = service.consumeResult("state-1");
        assertThat(first).isNotNull();
        assertThat(first.ok()).isTrue();
        assertThat(first.profile()).containsEntry("handle", "alice");

        assertThat(service.consumeResult("state-1")).as("结果只能取一次").isNull();
    }

    @Test
    void failureResultCarriesTheReason() {
        SoundCloudOAuthService service = service();
        service.rememberResult("state-2", false, null, "用户取消了授权");

        SoundCloudOAuthService.OAuthResult result = service.consumeResult("state-2");
        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("用户取消了授权");
    }

    @Test
    void unknownStateIsStillPending() {
        assertThat(service().consumeResult("nobody")).isNull();
        assertThat(service().consumeResult(null)).isNull();
    }

    @Test
    void startIsUnavailableWithoutOAuthConfiguration() {
        assertThat(service().startSession()).isNull();
        assertThat(service().start()).isNull();
    }
}
