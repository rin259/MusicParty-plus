package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * permalink → handle：必须只取路径第一段。
 * 曾经直接 substring 再 split("/")[0]，遇到 ?si=... 会拼出 "dj-test?si=xxx" 这种非法 handle，
 * 导致后续 getUserPlaylists / diagnose 全部失败。
 */
class SoundCloudHandleFromPermalinkTest {

    @Test
    void plainProfileUrl() {
        assertThat(SoundCloudOAuthService.handleFromPermalink("https://soundcloud.com/dj-test"))
                .isEqualTo("dj-test");
    }

    @Test
    void stripsQueryAndTrailingSlash() {
        assertThat(SoundCloudOAuthService.handleFromPermalink("https://soundcloud.com/dj-test/?si=abc123"))
                .isEqualTo("dj-test");
        assertThat(SoundCloudOAuthService.handleFromPermalink("https://soundcloud.com/dj-test?si=abc"))
                .isEqualTo("dj-test");
    }

    @Test
    void handlesTrackOrSetLinks() {
        assertThat(SoundCloudOAuthService.handleFromPermalink("https://soundcloud.com/dj-test/sets/live"))
                .isEqualTo("dj-test");
    }

    @Test
    void blankOrOddInputs() {
        assertThat(SoundCloudOAuthService.handleFromPermalink("")).isEmpty();
        assertThat(SoundCloudOAuthService.handleFromPermalink(null)).isEmpty();
    }
}
