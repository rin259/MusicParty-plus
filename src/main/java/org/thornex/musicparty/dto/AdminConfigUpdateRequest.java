package org.thornex.musicparty.dto;

public record AdminConfigUpdateRequest(
    Integer maxSize,
    Integer historySize,
    Integer maxUserSongs,
    Integer maxPlaylistImportSize,
    Integer maxChatHistorySize,
    Long minChatIntervalMs,
    Integer maxChatMessageLength,
    Boolean neteaseEnabled,
    Boolean bilibiliEnabled,
    Boolean voteSkipEnabled,
    Double voteSkipThreshold,
    Integer voteSkipWaitTime,
    // Dashboard aliases; kept alongside the legacy names above.
    Integer maxQueueSize,
    Integer maxHistorySize,
    Boolean soundcloudEnabled,
    Boolean ytmusicEnabled
) {}
