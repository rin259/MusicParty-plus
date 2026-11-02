package org.thornex.musicparty.dto;

/**
 * 单曲点歌的服务端回执。按 requestId 与前端请求一一对应，
 * 避免「2 秒后自己猜成功」以及失败无提示。
 */
public record EnqueueResult(
        String requestId,
        String platform,
        String musicId,
        String status,   // QUEUED | DUPLICATE | LIMIT | QUEUE_FULL | DISABLED | TOO_LONG | FAILED
        String message
) {
    public static final String QUEUED = "QUEUED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String LIMIT = "LIMIT";
    public static final String QUEUE_FULL = "QUEUE_FULL";
    public static final String DISABLED = "DISABLED";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String FAILED = "FAILED";
}
