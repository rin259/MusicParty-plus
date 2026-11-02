package org.thornex.musicparty.dto;

/** requestId 用于把导入进度对应到发起的那次导入。 */
public record EnqueuePlaylistRequest(String platform, String playlistId, String requestId) {
    public EnqueuePlaylistRequest(String platform, String playlistId) {
        this(platform, playlistId, null);
    }
}
