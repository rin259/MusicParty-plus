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
        String visibility,
        long createdAt,
        long lastActiveAt,
        Integer summonCooldownHours
) {
    public static final String MAIN_ROOM_ID = "main";
    public static final String VISIBILITY_PUBLIC = "public";
    public static final String VISIBILITY_UNLISTED = "unlisted";

    public Room {
        visibility = visibility == null || visibility.isBlank()
                ? VISIBILITY_PUBLIC
                : visibility.toLowerCase(java.util.Locale.ROOT);
    }

    public Room(String id, String name, String ownerDeviceId, String passwordHash, String visibility,
                long createdAt, long lastActiveAt) {
        this(id, name, ownerDeviceId, passwordHash, visibility, createdAt, lastActiveAt, null);
    }

    public static boolean isValidSummonCooldownHours(int hours) {
        return hours >= 0 && hours <= 24;
    }

    /** public 会出现在房间目录；unlisted 只能通过房间码/邀请链接进入。 */
    public boolean listed() {
        return !VISIBILITY_UNLISTED.equals(visibility);
    }

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
        return new Room(id, newName, ownerDeviceId, passwordHash, visibility, createdAt, lastActiveAt, summonCooldownHours);
    }

    public Room withPasswordHash(String newHash) {
        return new Room(id, name, ownerDeviceId, newHash, visibility, createdAt, lastActiveAt, summonCooldownHours);
    }

    public Room withVisibility(String newVisibility) {
        return new Room(id, name, ownerDeviceId, passwordHash, newVisibility, createdAt, lastActiveAt, summonCooldownHours);
    }

    public Room withSummonCooldownHours(Integer hours) {
        return new Room(id, name, ownerDeviceId, passwordHash, visibility, createdAt, lastActiveAt, hours);
    }

    public Room touched(long at) {
        return new Room(id, name, ownerDeviceId, passwordHash, visibility, createdAt, at, summonCooldownHours);
    }
}
