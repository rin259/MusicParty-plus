package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.dto.AlbumSearchResult;
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
    default void prefetchMusic(String musicId) {};
    default Mono<List<AlbumSearchResult>> searchAlbums(String keyword) { return Mono.just(List.of()); }
    default Mono<List<Music>> getAlbumMusics(String albumId) { return Mono.just(List.of()); }
    default Mono<List<Music>> searchMusic(String keyword, int offset, int limit) { return searchMusic(keyword); }

    /** 由曲目 ID 反查用户可读的源站页面地址；拼不出页面的平台返回空。 */
    default Mono<String> resolvePageUrl(String musicId) { return Mono.empty(); }
}
