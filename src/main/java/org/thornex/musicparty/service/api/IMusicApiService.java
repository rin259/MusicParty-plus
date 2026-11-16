package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.dto.AlbumSearchResult;
import org.thornex.musicparty.service.LocalCacheService.DownloadPriority;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IMusicApiService {
    String getPlatformName();
    Mono<List<Music>> searchMusic(String keyword);
    Mono<PlayableMusic> getPlayableMusic(String musicId);
    Mono<List<Playlist>> getUserPlaylists(String userId);
    Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit);
    Mono<List<UserSearchResult>> searchUsers(String keyword);
    Mono<String> getLyric(String musicId);
    default void prefetchMusic(String musicId) { prefetchMusic(musicId, DownloadPriority.NORMAL); }
    default void prefetchMusic(String musicId, DownloadPriority priority) {};
    default Mono<List<AlbumSearchResult>> searchAlbums(String keyword) { return Mono.just(List.of()); }
    default Mono<List<Music>> getAlbumMusics(String albumId) { return Mono.just(List.of()); }
    default Mono<List<Music>> searchMusic(String keyword, int offset, int limit) { return searchMusic(keyword); }

    /** 歌单分页结果：items + 下一页的偏移 + 是否还有（应对「部分曲目不可用」导致的短页）。 */
    record PagedPlaylist(java.util.List<Music> items, int nextOffset, boolean hasMore) {
        /** 统一语义：本页是否满 = 是否还有下一页。手写 hasMore 是这类 bug 的温床。 */
        public static PagedPlaylist of(java.util.List<Music> items, int offset, int limit) {
            int size = items == null ? 0 : items.size();
            return new PagedPlaylist(items == null ? java.util.List.of() : items,
                    offset + size, size >= Math.max(1, limit));
        }
    }

    default Mono<PagedPlaylist> getPlaylistMusicsPaged(String playlistId, int offset, int limit) {
        return getPlaylistMusicsPaged(playlistId, offset, limit, false);
    }

    default Mono<PagedPlaylist> getPlaylistMusicsPaged(String playlistId, int offset, int limit, boolean refresh) {
        return getPlaylistMusics(playlistId, offset, limit)
                // 没有精确元数据的平台：按「本页是否满」推断，绝不能写死 false，
                // 否则网易云/B站这类超过一页的歌单会立刻被判为到底。
                .map(items -> PagedPlaylist.of(items, offset, limit));
    }

    /** 由曲目 ID 反查用户可读的源站页面地址；拼不出页面的平台返回空。 */
    default Mono<String> resolvePageUrl(String musicId) { return Mono.empty(); }
}
