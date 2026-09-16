package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MusicPlayerServiceSeekTest {

    @Test
    void clampsSeekPositionToPlayableRange() {
        assertThat(MusicPlayerService.clampSeekPosition(-1, 180_000)).isZero();
        assertThat(MusicPlayerService.clampSeekPosition(90_000, 180_000)).isEqualTo(90_000);
        assertThat(MusicPlayerService.clampSeekPosition(200_000, 180_000)).isEqualTo(180_000);
    }
}
