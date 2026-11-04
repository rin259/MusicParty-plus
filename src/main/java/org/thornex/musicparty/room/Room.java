package org.thornex.musicparty.room;

/**
 * 房间元数据（持久化到 data/rooms/index.json）。
 *
 * <p>房间是临时概念：播放队列、在线用户、聊天都属于运行时，只有这份元数据长期保留。
 * {@code ownerDeviceId} 就是浏览器 localStorage 里的设备凭证（mp_user_token）。
 */
public record Room(
        String id,
        String name,
        String ownerDeviceId,
        String passwordHash,
        long createdAt,
        long lastActiveAt
) {
    public static final String MAIN_ROOM_ID = "main";

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isMain() {
        return MAIN_ROOM_ID.equals(id);
    }

    public boolean ownedBy(String deviceId) {
        return ownerDeviceId != null && ownerDeviceId.equals(deviceId);
    }

    public Room withName(String newName) {
        return new Room(id, newName, ownerDeviceId, passwordHash, createdAt, lastActiveAt);
    }

    public Room withPasswordHash(String newHash) {
        return new Room(id, name, ownerDeviceId, newHash, createdAt, lastActiveAt);
    }

    public Room touched(long at) {
        return new Room(id, name, ownerDeviceId, passwordHash, createdAt, at);
    }
}
