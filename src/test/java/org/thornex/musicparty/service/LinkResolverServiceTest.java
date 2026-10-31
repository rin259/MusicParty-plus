package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.ResolvedLink;
import org.thornex.musicparty.exception.ApiRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkResolverServiceTest {

    // 正则分支不需要 yt-dlp；SoundCloud 分支另行手工验证。
    private final LinkResolverService service = new LinkResolverService(null);

    @Test
    void resolvesNeteaseSongPlaylistAndAlbum() {
        assertThat(service.resolve("https://music.163.com/song?id=2011881265").block())
                .isEqualTo(new ResolvedLink("netease", "track", "2011881265", null));
        assertThat(service.resolve("https://music.163.com/#/playlist?id=3778678").block())
                .isEqualTo(new ResolvedLink("netease", "playlist", "3778678", null));
        assertThat(service.resolve("https://music.163.com/album?id=32311").block())
                .isEqualTo(new ResolvedLink("netease", "album", "32311", null));
    }

    @Test
    void resolvesBilibiliBvFromBareIdAndUrl() {
        assertThat(service.resolve("BV1xvxi6LEfz").block())
                .isEqualTo(new ResolvedLink("bilibili", "track", "BV1xvxi6LEfz", null));
        assertThat(service.resolve("https://www.bilibili.com/video/BV1xvxi6LEfz?p=2").block())
                .isEqualTo(new ResolvedLink("bilibili", "track", "BV1xvxi6LEfz", null));
    }

    @Test
    void resolvesYoutubeWatchAndShortLinks() {
        assertThat(service.resolve("https://music.youtube.com/watch?v=YQHsXMglC9A").block())
                .isEqualTo(new ResolvedLink("ytmusic", "track", "YQHsXMglC9A", null));
        assertThat(service.resolve("https://youtu.be/YQHsXMglC9A").block())
                .isEqualTo(new ResolvedLink("ytmusic", "track", "YQHsXMglC9A", null));
    }

    @Test
    void rejectsUnsupportedAndEmptyInput() {
        assertThatThrownBy(() -> service.resolve("https://example.com/whatever").block())
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("暂不支持");
        assertThatThrownBy(() -> service.resolve("   ").block())
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("请输入歌曲链接");
    }
}
