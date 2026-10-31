package org.thornex.musicparty.dto;

/**
 * 歌单/专辑导入进度。服务端逐条入队，前端据此显示真实进度与差额原因，
 * 而不是「点一下、关窗、等一条系统消息」。
 */
public record ImportProgress(
        String requestId,
        String platform,
        String listId,
        String kind,     // playlist | album
        int done,
        int total,
        int added,
        int skipped,
        boolean finished,
        String message
) {}
