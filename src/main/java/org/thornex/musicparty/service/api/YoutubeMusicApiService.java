package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class YoutubeMusicApiService extends YtDlpMusicApiService {
    public YoutubeMusicApiService(LocalCacheService localCacheService, YtDlpResolver resolver, AppProperties properties) {
        super("ytmusic", localCacheService, resolver, properties.getYtmusic().getSearchLimit());
    }

    @Override protected Mono<List<YtDlpResolver.TrackMetadata>> search(String keyword, int limit) { return resolver.searchYoutubeMusic(keyword, limit); }
    @Override protected Mono<YtDlpResolver.TrackMetadata> resolveMetadata(String musicId) { return resolver.resolveYoutubeMusicMetadata(musicId); }
}
