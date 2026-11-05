package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.YtDlpResolver;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

abstract class YtDlpMusicApiService implements IMusicApiService {
    private final String platform;
    private final LocalCacheService localCacheService;
    protected final YtDlpResolver resolver;
    private final int searchLimit;
    private final int fallbackFetchLimit;
    // 有界 LRU：此前两个 ConcurrentHashMap 无上限，长期运行会持续吃内存。
    private final Map<String, YtDlpResolver.TrackMetadata> metadataById = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, YtDlpResolver.TrackMetadata> eldest) {
                    return size() > 256;
                }
            });
    private final Map<String, List<YtDlpResolver.TrackMetadata>> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<YtDlpResolver.TrackMetadata>> eldest) {
                    return size() > 64;
                }
            });

    protected YtDlpMusicApiService(String platform, LocalCacheService localCacheService, YtDlpResolver resolver,
                                   int searchLimit, int fallbackFetchLimit) {
        this.platform = platform;
        this.localCacheService = localCacheService;
        this.resolver = resolver;
        this.searchLimit = searchLimit;
        this.fallbackFetchLimit = Math.max(searchLimit, fallbackFetchLimit);
    }

    @Override public String getPlatformName() { return platform; }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        return searchMusic(keyword, 0, searchLimit);
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword, int offset, int limit) {
        return searchPage(keyword, offset, limit)
                .doOnNext(tracks -> tracks.forEach(track -> metadataById.put(track.id(), track)))
                .map(tracks -> tracks.stream().map(this::toMusic).toList());
    }

    /**
     * Fetches a single page of results.
     *
     * <p>The default implementation keeps the historical yt-dlp behaviour: one larger
     * batch per keyword is fetched, cached, and sliced locally. Subclasses backed by
     * an offset/continuation aware HTTP API override this so that scrolling can keep
     * pulling fresh pages instead of being capped by the first fetch.
     */
    protected Mono<List<YtDlpResolver.TrackMetadata>> searchPage(String keyword, int offset, int limit) {
        String cacheKey = keyword.toLowerCase();
        List<YtDlpResolver.TrackMetadata> cached = searchCache.get(cacheKey);
        if (cached != null) {
            return Mono.just(slice(cached, offset, limit));
        }
        return search(keyword, fallbackFetchLimit)
                .doOnNext(tracks -> searchCache.put(cacheKey, tracks))
                .map(tracks -> slice(tracks, offset, limit));
    }

    private List<YtDlpResolver.TrackMetadata> slice(List<YtDlpResolver.TrackMetadata> tracks, int offset, int limit) {
        int from = Math.min(Math.max(offset, 0), tracks.size());
        int to = Math.min(Math.max(offset, 0) + Math.max(limit, 0), tracks.size());
        return new ArrayList<>(tracks.subList(from, to));
    }

    @Override
    public void prefetchMusic(String musicId) {
        submitPrefetch(musicId, getMetadata(musicId)
                .map(metadata -> LocalCacheService.DownloadSource.ytDlp(metadata.sourceUrl())));
    }

    /**
     * 已知稳定下载地址的子类可跳过 yt-dlp 元数据解析（SoundCloud 的 api/tracks/&lt;id&gt;
     * 就是这种地址）。批量导入时每首歌原本都要多跑一次 ~3s 的 yt-dlp，串行叠加很可观。
     */
    protected void submitPrefetch(String musicId, Mono<LocalCacheService.DownloadSource> sourceProvider) {
        CacheStatus status = localCacheService.getStatus(cacheId(musicId));
        if (status == CacheStatus.COMPLETED || status == CacheStatus.PENDING || status == CacheStatus.DOWNLOADING) return;
        localCacheService.submitDownload(cacheId(musicId), sourceProvider);
    }

    /** 让后续 prefetch/播放不再为同一首歌重复拉元数据。 */
    protected void rememberMetadata(YtDlpResolver.TrackMetadata track) {
        if (track == null || track.id() == null || track.id().isBlank()) return;
        metadataById.put(track.id(), track);
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String musicId) {
        return getMetadata(musicId).flatMap(metadata -> {
            String localUrl = localCacheService.getLocalUrl(cacheId(musicId));
            if (localUrl != null) {
                return Mono.just(playableOf(metadata, localUrl));
            }
            // 先起下载、再等校验：SoundCloud 的 DRM 校验本身要跑一次 yt-dlp（~3s），
            // 串行执行等于每首新歌都白等这 3s。校验失败时下载也会失败，不额外污染队列。
            prefetchMusic(musicId);
            return validateBeforeDownload(metadata)
                    .then(Mono.fromSupplier(() -> playableOf(metadata, "PENDING_DOWNLOAD")));
        });
    }

    private PlayableMusic playableOf(YtDlpResolver.TrackMetadata metadata, String url) {
        return new PlayableMusic(metadata.id(), metadata.title(), List.of(metadata.artist()), metadata.durationMs(), platform,
                url, metadata.thumbnail(), false);
    }

    protected Mono<Void> validateBeforeDownload(YtDlpResolver.TrackMetadata metadata) { return Mono.empty(); }

    @Override public Mono<List<Playlist>> getUserPlaylists(String userId) { return Mono.just(List.of()); }
    @Override public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) { return Mono.just(List.of()); }
    @Override public Mono<List<UserSearchResult>> searchUsers(String keyword) { return Mono.just(List.of()); }
    @Override public Mono<String> getLyric(String musicId) { return Mono.just(""); }

    private Mono<YtDlpResolver.TrackMetadata> getMetadata(String musicId) {
        YtDlpResolver.TrackMetadata cached = metadataById.get(musicId);
        return cached != null ? Mono.just(cached) : resolveMetadata(musicId).doOnNext(track -> metadataById.put(track.id(), track));
    }

    private Music toMusic(YtDlpResolver.TrackMetadata track) {
        // sourceUrl 是 yt-dlp 给出的可读页面地址；数字 ID 拼不出页面（SoundCloud 尤其如此）
        return new Music(track.id(), track.title(), List.of(track.artist()), track.durationMs(), platform, track.thumbnail(), track.pageUrl());
    }

    private String cacheId(String musicId) { return platform + "_" + musicId; }

    protected abstract Mono<List<YtDlpResolver.TrackMetadata>> search(String keyword, int limit);
    protected abstract Mono<YtDlpResolver.TrackMetadata> resolveMetadata(String musicId);
}
