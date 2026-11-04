package org.thornex.musicparty.dto;

/** 房间对外的公开摘要（不含密码哈希与房主设备凭证）。 */
public record RoomSummary(
        String id,
        String name,
        boolean hasPassword,
        int onlineCount,
        boolean owner,
        boolean mainRoom
) {
}
