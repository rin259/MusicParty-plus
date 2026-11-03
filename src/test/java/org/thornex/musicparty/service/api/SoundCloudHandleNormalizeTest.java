package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.YtDlpResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 绑定入口的输入归一：真实分享里出现的各种 SoundCloud 链接形式都要能落到 handle 上。
 */
class SoundCloudHandleNormalizeTest {

    private SoundCloudMusicApiService service() {
        AppProperties props = new AppProperties();
        return new SoundCloudMusicApiService(
                mock(LocalCacheService.class), mock(YtDlpResolver.class), props, mock(SoundCloudSearchClient.class));
    }

    @Test
    void acceptsPlainHandle() {
        assertThat(service().normalizeHandle("djtest")).isEqualTo("djtest");
    }

    @Test
    void acceptsAtHandle() {
        assertThat(service().normalizeHandle("@djtest")).isEqualTo("djtest");
    }

    @Test
    void acceptsProfileUrl() {
        assertThat(service().normalizeHandle("https://soundcloud.com/djtest")).isEqualTo("djtest");
    }

    @Test
    void acceptsProfileUrlWithTrailingSlashAndQuery() {
        assertThat(service().normalizeHandle("https://soundcloud.com/djtest/?si=abc")).isEqualTo("djtest");
    }

    @Test
    void acceptsSetUrlAndBindsTheOwner() {
        assertThat(service().normalizeHandle("https://soundcloud.com/djtest/sets/live-2026"))
                .isEqualTo("djtest");
    }

    @Test
    void acceptsBareDomainWithoutScheme() {
        assertThat(service().normalizeHandle("soundcloud.com/djtest")).isEqualTo("djtest");
    }

    @Test
    void acceptsMobileDomain() {
        assertThat(service().normalizeHandle("https://m.soundcloud.com/djtest")).isEqualTo("djtest");
    }

    @Test
    void acceptsLegacyNumericUserPath() {
        assertThat(service().normalizeHandle("https://soundcloud.com/user-123456")).isEqualTo("user-123456");
    }

    @Test
    void rejectsSitePagesInsteadOfTreatingThemAsHandles() {
        assertThatThrownBy(() -> service().normalizeHandle("https://soundcloud.com/charts/top"))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("站内页面");
    }

    @Test
    void rejectsForeignUrl() {
        assertThatThrownBy(() -> service().normalizeHandle("https://example.com/djtest"))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("不是 SoundCloud");
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> service().normalizeHandle("   "))
                .isInstanceOf(ApiRequestException.class);
    }
}
