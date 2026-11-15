package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.dto.AlbumSearchResult;
import org.thornex.musicparty.dto.ResolvedLink;
import org.thornex.musicparty.dto.SourceHealth;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.LinkResolverService;
import org.thornex.musicparty.service.SourceHealthService;

import java.net.URI;
import org.thornex.musicparty.service.api.IMusicApiService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
@Slf4j
@RestController
@RequestMapping("/api")
public class ApiController {

    private final Map<String, IMusicApiService> apiServiceMap;
    private final AppProperties appProperties;
    private final SourceHealthService sourceHealthService;
    private final LinkResolverService linkResolverService;

    public ApiController(List<IMusicApiService> apiServices, AppProperties appProperties,
                         SourceHealthService sourceHealthService, LinkResolverService linkResolverService) {
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.appProperties = appProperties;
        this.sourceHealthService = sourceHealthService;
        this.linkResolverService = linkResolverService;
    }

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of(
                "authorName", appProperties.getAuthorName(),
                "backWords", appProperties.getBackWords()
        );
    }

    private IMusicApiService getService(String platform) {
        if ("netease".equalsIgnoreCase(platform) && !appProperties.getNetease().isEnabled()) {
            throw new ApiRequestException("网易云音乐源已被禁用");
        }
        if ("bilibili".equalsIgnoreCase(platform) && !appProperties.getBilibili().isEnabled()) {
            throw new ApiRequestException("Bilibili 源已被禁用");
        }
        if ("soundcloud".equalsIgnoreCase(platform) && !appProperties.getSoundcloud().isEnabled()) {
            throw new ApiRequestException("SoundCloud 源已被禁用");
        }
        if ("ytmusic".equalsIgnoreCase(platform) && !appProperties.getYtmusic().isEnabled()) {
            throw new ApiRequestException("YouTube Music 源已被禁用");
        }

        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) {
            throw new ApiRequestException("Platform not supported: " + platform);
        }
        return service;
    }

    @GetMapping("/search/{platform}/{keyword}")
    public Mono<List<Music>> searchMusic(@PathVariable String platform, @PathVariable String keyword,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "30") int limit,
                                         HttpServletResponse response) {
        noStore(response);
        return getService(platform).searchMusic(keyword, offset, limit);
    }

    /**
     * 关键词走 query 参数：旧路径把关键词放在 path 里，歌名含 "/"（如 AC/DC）
     * 会被 Spring 拆段变 404，编码成 %2F 又被容器拒绝为 400。
     */
    @GetMapping("/search")
    public Mono<List<Music>> searchMusicByQuery(@RequestParam String platform,
                                                @RequestParam(name = "q") String keyword,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "30") int limit,
                                                HttpServletResponse response) {
        noStore(response);
        return getService(platform).searchMusic(keyword, offset, limit);
    }

    /** 搜索结果不可缓存：否则切回同一源时浏览器会直接把旧结果当新结果（4ms/30 hits）。 */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }

    /** 四源健康探测（60s 缓存），供搜索界面在发起搜索前展示真实可达性与时延。 */
    @GetMapping("/sources/health")
    public Mono<List<SourceHealth>> sourcesHealth() {
        return sourceHealthService.health();
    }

    /** 粘贴链接解析：四个源各自的 URL 形态收敛到一处。 */
    @GetMapping("/resolve")
    public Mono<ResolvedLink> resolveLink(@RequestParam String url) {
        return linkResolverService.resolve(url);
    }

    /**
     * 由曲目 ID 反查源站页面并 302 过去。
     * 老队列里没有存页面地址（SoundCloud 的数字 ID 拼不出链接），封面跳转用它兜底，
     * 落到 soundcloud.com/artist/track 而不是搜索页。
     */
    @GetMapping("/track/url")
    public Mono<ResponseEntity<Void>> trackPageUrl(@RequestParam String platform, @RequestParam String id) {
        return getService(platform).resolvePageUrl(id)
                .filter(url -> url != null && !url.isBlank())
                .map(url -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).<Void>build())
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/user/playlists/{platform}/{userId}")
    public Mono<List<Playlist>> getUserPlaylists(@PathVariable String platform, @PathVariable String userId) {
        return getService(platform).getUserPlaylists(userId);
    }

    @GetMapping("/playlist/songs/{platform}/{playlistId}")
    public Mono<ResponseEntity<List<Music>>> getPlaylistSongs(@PathVariable String platform,
                                                              @PathVariable String playlistId,
                                                              @RequestParam(defaultValue = "0") int offset,
                                                              @RequestParam(defaultValue = "20") int limit) {
        log.info("playlist songs request: platform={} id={} offset={} limit={}",
                platform, playlistId, offset, limit);
        return getService(platform).getPlaylistMusicsPaged(playlistId, offset, limit)
                .map(page -> {
                    log.info("playlist songs response: platform={} id={} offset={} -> {} items, next={}, hasMore={}",
                            platform, playlistId, offset, page.items().size(), page.nextOffset(), page.hasMore());
                    return ResponseEntity.ok()
                            .header("X-Playlist-Next-Offset", String.valueOf(page.nextOffset()))
                            .header("X-Playlist-Has-More", String.valueOf(page.hasMore()))
                            .body(page.items());
                });
    }

    @GetMapping("/user/search/{platform}/{keyword}")
    public Mono<List<UserSearchResult>> searchUsers(@PathVariable String platform, @PathVariable String keyword) {
        return getService(platform).searchUsers(keyword);
    }

    @GetMapping("/user/search/{platform}")
    public Mono<List<UserSearchResult>> searchUsersByQuery(@PathVariable String platform, @RequestParam String keyword) {
        return getService(platform).searchUsers(keyword);
    }

    @GetMapping("/music/lyric/{platform}/{musicId}")
    public Mono<String> getLyric(@PathVariable String platform, @PathVariable String musicId) {
        return getService(platform).getLyric(musicId);
    }

    @GetMapping("/search/albums/{platform}/{keyword}")
    public Mono<List<AlbumSearchResult>> searchAlbums(@PathVariable String platform, @PathVariable String keyword) {
        return getService(platform).searchAlbums(keyword);
    }

    /** 专辑搜索同样改走 query：专辑名含 "/" 不会再被路径拆段。 */
    @GetMapping("/search/albums")
    public Mono<List<AlbumSearchResult>> searchAlbumsByQuery(@RequestParam String platform,
                                                             @RequestParam(name = "q") String keyword,
                                                             HttpServletResponse response) {
        noStore(response);
        return getService(platform).searchAlbums(keyword);
    }

    @GetMapping("/album/songs/{platform}/{albumId}")
    public Mono<List<Music>> getAlbumSongs(@PathVariable String platform, @PathVariable String albumId) {
        return getService(platform).getAlbumMusics(albumId);
    }
}
