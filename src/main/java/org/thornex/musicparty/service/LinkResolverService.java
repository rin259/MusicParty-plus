package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.ResolvedLink;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 粘贴链接 → (平台, 类型, ID) 的统一解析。四个源各有一套 URL 形态，
 * 前端不应该各写一遍正则，也不应该把 URL 当关键词发出去。
 */
@Service
@Slf4j
public class LinkResolverService {

    private static final Pattern NETEASE = Pattern.compile(
            "music\\.163\\.com/(?:#/)?(song|playlist|album)\\?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI = Pattern.compile(
            "(?:bilibili\\.com/video/)?(BV[0-9A-Za-z]{10})", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?:youtube\\.com/watch\\?[^\\s]*v=|youtu\\.be/)([\\w-]{11})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOUNDCLOUD = Pattern.compile(
            "soundcloud\\.com/[^\\s/]+/[^\\s/?#]+", Pattern.CASE_INSENSITIVE);

    private final YtDlpResolver ytDlpResolver;

    public LinkResolverService(YtDlpResolver ytDlpResolver) {
        this.ytDlpResolver = ytDlpResolver;
    }

    public Mono<ResolvedLink> resolve(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Mono.error(new ApiRequestException("请输入歌曲链接"));
        }
        String url = rawUrl.trim();

        Matcher netease = NETEASE.matcher(url);
        if (netease.find()) {
            String kind = netease.group(1).toLowerCase(Locale.ROOT);
            return Mono.just(new ResolvedLink("netease", "song".equals(kind) ? "track" : kind, netease.group(2), null));
        }

        Matcher bilibili = BILIBILI.matcher(url);
        if (bilibili.find()) {
            return Mono.just(new ResolvedLink("bilibili", "track", bilibili.group(1), null));
        }

        Matcher youtube = YOUTUBE.matcher(url);
        if (youtube.find()) {
            if (url.contains("list=") && !url.contains("watch?v=")) {
                return Mono.error(new ApiRequestException("暂不支持 YouTube 播放列表链接"));
            }
            return Mono.just(new ResolvedLink("ytmusic", "track", youtube.group(1), null));
        }

        if (SOUNDCLOUD.matcher(url).find() && url.contains("soundcloud.com/")) {
            if (url.contains("/sets/")) {
                return Mono.error(new ApiRequestException("SoundCloud 歌单链接暂不支持，请在左侧绑定主页后从歌单导入"));
            }
            return ytDlpResolver.resolveSoundCloudUrl(url)
                    .map(track -> new ResolvedLink("soundcloud", "track", track.id(), track.title()))
                    .onErrorMap(error -> new ApiRequestException("无法解析该 SoundCloud 链接"));
        }

        return Mono.error(new ApiRequestException("暂不支持该链接；支持网易云单曲/歌单/专辑、B站 BV、YouTube、SoundCloud 单曲"));
    }
}
