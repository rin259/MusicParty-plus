package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.Music;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 换源重试的匹配规则必须保守：宁可保持 FAILED，也不要把翻唱/不同版本换进来。
 */
class FailoverMatchTest {

    private static Music music(String id, String name, List<String> artists, long durationMs, String platform) {
        return new Music(id, name, artists, durationMs, platform, null);
    }

    @Test
    void matchesSameSongWhenDurationClose() {
        Music failed = music("1", "夜に駆ける", List.of("YOASOBI"), 261_000L, "bilibili");
        Music candidate = music("2", "夜に駆ける", List.of("YOASOBI"), 259_000L, "netease");

        assertThat(MusicPlayerService.pickFailoverMatch(failed, List.of(candidate)))
                .contains(candidate);
    }

    @Test
    void rejectsWhenDurationDiffersTooMuch() {
        Music failed = music("1", "夜に駆ける", List.of("YOASOBI"), 261_000L, "bilibili");
        Music candidate = music("2", "夜に駆ける", List.of("YOASOBI"), 320_000L, "netease");

        assertThat(MusicPlayerService.pickFailoverMatch(failed, List.of(candidate))).isEmpty();
    }

    @Test
    void rejectsDifferentArtist() {
        Music failed = music("1", "Lemon", List.of("米津玄師"), 255_000L, "bilibili");
        Music candidate = music("2", "Lemon", List.of("Some Cover Band"), 255_000L, "netease");

        assertThat(MusicPlayerService.pickFailoverMatch(failed, List.of(candidate))).isEmpty();
    }

    @Test
    void toleranceForPunctuationAndSuffix() {
        Music failed = music("1", "Lemon (Official Video)", List.of("米津玄師"), 255_000L, "bilibili");
        Music candidate = music("2", "Lemon", List.of("米津玄師"), 254_000L, "netease");

        assertThat(MusicPlayerService.pickFailoverMatch(failed, List.of(candidate)))
                .contains(candidate);
    }

    @Test
    void picksFirstAcceptableCandidate() {
        Music failed = music("1", "Lemon", List.of("米津玄師"), 255_000L, "bilibili");
        Music bad = music("2", "Lemon", List.of("米津玄師"), 400_000L, "netease");
        Music good = music("3", "Lemon", List.of("米津玄師"), 256_000L, "soundcloud");

        assertThat(MusicPlayerService.pickFailoverMatch(failed, List.of(bad, good)))
                .contains(good);
    }
}
