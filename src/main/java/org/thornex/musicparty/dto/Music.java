package org.thornex.musicparty.dto;

import java.util.List;

/**
 * 曲目。
 *
 * <p>{@code url} 是这首曲目在源头网站上的可读页面地址（例如 SoundCloud 的
 * {@code https://soundcloud.com/artist/track}）。它不一定等于由 id 拼出来的地址：
 * SoundCloud 的数字 id 没有对应的页面路径，用 id 拼出来的链接会跳到「排行榜」。
 * 旧数据没有该字段，前端需回退到各平台的模板链接。
 */
public record Music(
        String id,
        String name,
        List<String> artists,
        long duration, // in milliseconds
        String platform,
        String coverUrl,
        String url
) {
    /** 兼容既有调用方：不提供页面地址时，前端按平台模板拼。 */
    public Music(String id, String name, List<String> artists, long duration, String platform, String coverUrl) {
        this(id, name, artists, duration, platform, coverUrl, null);
    }
}
