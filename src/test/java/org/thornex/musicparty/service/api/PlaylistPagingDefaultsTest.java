package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页默认实现的不变量。
 *
 * <p>回归背景（2026-11）：默认实现曾把 hasMore 写死为 false，而控制器会把它作为
 * X-Playlist-Has-More 下发，导致所有「没有精确分页元数据」的平台（网易云、B站、
 * SoundCloud 的 likes/上传）在超过一页的歌单上立刻被判为到底。
 */
class PlaylistPagingDefaultsTest {

    private static Music song(int i) {
        return new Music(String.valueOf(i), "song-" + i, List.of("a"), 180_000L, "fake", null);
    }

    private static IMusicApiService serviceReturning(int itemCount) {
        return new IMusicApiService() {
            @Override public String getPlatformName() { return "fake"; }
            @Override public Mono<List<Music>> searchMusic(String keyword) { return Mono.just(List.of()); }
            @Override public Mono<PlayableMusic> getPlayableMusic(String musicId) { return Mono.empty(); }
            @Override public Mono<List<Playlist>> getUserPlaylists(String userId) { return Mono.just(List.of()); }
            @Override public Mono<List<UserSearchResult>> searchUsers(String keyword) { return Mono.just(List.of()); }
            @Override public Mono<String> getLyric(String musicId) { return Mono.just(""); }
            @Override public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
                return Mono.just(IntStream.range(0, itemCount).mapToObj(PlaylistPagingDefaultsTest::song).toList());
            }
        };
    }

    @Test
    void fullPageSaysThereIsMore() {
        IMusicApiService.PagedPlaylist page = serviceReturning(50).getPlaylistMusicsPaged("p", 0, 50).block();

        assertThat(page).isNotNull();
        assertThat(page.hasMore()).as("满页必须判为「还有」").isTrue();
        assertThat(page.nextOffset()).isEqualTo(50);
    }

    @Test
    void shortPageSaysEnd() {
        IMusicApiService.PagedPlaylist page = serviceReturning(12).getPlaylistMusicsPaged("p", 0, 50).block();

        assertThat(page).isNotNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextOffset()).isEqualTo(12);
    }

    @Test
    void emptyPageSaysEnd() {
        IMusicApiService.PagedPlaylist page = serviceReturning(0).getPlaylistMusicsPaged("p", 50, 50).block();

        assertThat(page).isNotNull();
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextOffset()).isEqualTo(50);
    }
}
