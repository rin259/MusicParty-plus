package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class SoundCloudMusicApiService extends YtDlpMusicApiService {
    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public SoundCloudMusicApiService(LocalCacheService localCacheService, YtDlpResolver resolver, AppProperties properties) {
        super("soundcloud", localCacheService, resolver, properties.getSoundcloud().getSearchLimit());
    }

    @Override protected Mono<List<YtDlpResolver.TrackMetadata>> search(String keyword, int limit) { return resolver.searchSoundCloud(keyword, limit); }
    @Override protected Mono<YtDlpResolver.TrackMetadata> resolveMetadata(String musicId) { return resolver.resolveSoundCloudMetadata(musicId); }

    @Override
    protected Mono<Void> validateBeforeDownload(YtDlpResolver.TrackMetadata metadata) {
        return resolver.validateSoundCloudAudio(metadata.sourceUrl())
                .onErrorMap(error -> {
                    String message = error.getMessage() == null ? "" : error.getMessage();
                    if (message.toLowerCase().contains("drm protected")) {
                        return new ApiRequestException("该 SoundCloud 曲目受 DRM 保护，无法添加");
                    }
                    return new ApiRequestException("SoundCloud 曲目当前无法解析或下载");
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        String handle = normalizeHandle(keyword);
        return resolver.resolveSoundCloudUser(handle)
                .map(user -> List.of(new UserSearchResult(user.handle(), user.name(), user.avatarUrl(), getPlatformName())));
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        String handle = normalizeHandle(userId);
        return resolver.getSoundCloudUserSets(handle)
                .map(playlists -> playlists.stream()
                        .map(playlist -> new Playlist(playlist.id(), playlist.name(), playlist.coverUrl(),
                                playlist.trackCount(), getPlatformName()))
                        .toList());
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        if (!playlistId.matches("\\d+")) {
            return Mono.error(new ApiRequestException("SoundCloud 歌单 ID 无效"));
        }
        return resolver.getSoundCloudPlaylistTracks(playlistId, offset, limit)
                .map(tracks -> tracks.stream()
                        .map(track -> new Music(track.id(), track.title(), List.of(track.artist()), track.durationMs(),
                                getPlatformName(), track.thumbnail()))
                        .toList());
    }

    private String normalizeHandle(String value) {
        if (value == null || value.isBlank()) throw new ApiRequestException("请输入 SoundCloud 公开主页 URL 或 @handle");

        String candidate = value.trim();
        int domainIndex = candidate.toLowerCase().indexOf("soundcloud.com/");
        if (domainIndex >= 0) candidate = candidate.substring(domainIndex + "soundcloud.com/".length());
        if (candidate.startsWith("@")) candidate = candidate.substring(1);
        candidate = candidate.split("[/?#]", 2)[0];

        if (!HANDLE_PATTERN.matcher(candidate).matches()) {
            throw new ApiRequestException("请输入有效的 SoundCloud 公开主页 URL 或 @handle");
        }
        return candidate;
    }
}
