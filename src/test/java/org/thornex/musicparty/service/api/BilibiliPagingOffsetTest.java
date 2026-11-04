package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 站按页码（pn）取数，前端按 offset 翻页。下一页偏移必须对齐到页边界，
 * 否则「本页条数 < ps」时 pn 会重复取同一页（表现为滚动时重复内容）。
 */
class BilibiliPagingOffsetTest {

    @Test
    void advancesByPageEvenWhenThePageWasShort() {
        // 一页 20 条里有效 13 条：不能按 offset+13=13 继续，否则 pn 仍是第 1 页
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(0, 20)).isEqualTo(20);
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(20, 20)).isEqualTo(40);
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(40, 20)).isEqualTo(60);
    }

    @Test
    void recoversFromALegacyMisalignedOffset() {
        // 旧版本可能写出 35 这种偏移；必须回到页边界，而不是重取第 1 页
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(35, 20)).isEqualTo(40);
    }

    @Test
    void clampsTheLimitToBilibiliMaximum() {
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(0, 100)).isEqualTo(20);
        assertThat(BilibiliMusicApiService.nextOffsetAlignedToPage(20, 1)).isEqualTo(21);
    }
}
