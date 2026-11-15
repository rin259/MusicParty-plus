package org.thornex.musicparty.service.stream;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.PlayableMusic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveStreamServiceTest {

    private static PlayableMusic music(String id, String platform) {
        return new PlayableMusic(id, "track", List.of("artist"), 1000L, platform, "/media/x.mp3", null, false);
    }

    @Test
    void usesThePlatformPrefixedCacheKeyUsedByLocalCacheService() {
        assertThat(LiveStreamService.cacheIdFor(music("1967169450", "netease")))
                .isEqualTo("netease_1967169450");
        assertThat(LiveStreamService.cacheIdFor(music("345336009", "soundcloud")))
                .isEqualTo("soundcloud_345336009");
        assertThat(LiveStreamService.cacheIdFor(music("abc123", "ytmusic")))
                .isEqualTo("ytmusic_abc123");
    }

    @Test
    void keepsBilibiliBvidUnprefixed() {
        assertThat(LiveStreamService.cacheIdFor(music("BV1xx411c7mD", "bilibili")))
                .isEqualTo("BV1xx411c7mD");
        assertThat(LiveStreamService.cacheIdFor(music("BV1xx411c7mD", "BILIBILI")))
                .isEqualTo("BV1xx411c7mD");
    }

    @Test
    void ignoresMissingMusicOrIds() {
        assertThat(LiveStreamService.cacheIdFor(null)).isNull();
        assertThat(LiveStreamService.cacheIdFor(music(null, "netease"))).isNull();
        assertThat(LiveStreamService.cacheIdFor(music("  ", "netease"))).isNull();
    }
}
