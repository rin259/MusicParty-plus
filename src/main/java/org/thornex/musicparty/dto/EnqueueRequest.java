package org.thornex.musicparty.dto;

/** requestId 由前端生成，用于把服务端回执对应到具体那一行歌曲。 */
public record EnqueueRequest(String platform, String musicId, String requestId) {
    public EnqueueRequest(String platform, String musicId) {
        this(platform, musicId, null);
    }
}
