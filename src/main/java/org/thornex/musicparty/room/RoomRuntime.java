package org.thornex.musicparty.room;

import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.MusicQueueManager;
import org.thornex.musicparty.service.UserService;

/**
 * 一个房间的完整运行时对象图。所有可变状态都在这里，房间卸载 = 丢掉这个对象。
 */
public final class RoomRuntime {

    private final String roomId;
    private final MusicPlayerService player;
    private final MusicQueueManager queue;
    private final UserService users;
    private final ChatService chat;
    private volatile long lastActiveAt = System.currentTimeMillis();

    public RoomRuntime(String roomId, MusicPlayerService player, MusicQueueManager queue,
                       UserService users, ChatService chat) {
        this.roomId = roomId;
        this.player = player;
        this.queue = queue;
        this.users = users;
        this.chat = chat;
    }

    public String roomId() { return roomId; }
    public MusicPlayerService player() { return player; }
    public MusicQueueManager queue() { return queue; }
    public UserService users() { return users; }
    public ChatService chat() { return chat; }

    public long lastActiveAt() { return lastActiveAt; }

    /** 每秒驱动一次播放器状态机。 */
    public void tick() {
        player.tick();
        if (!users.getOnlineUserSummaries().isEmpty()) {
            lastActiveAt = System.currentTimeMillis();
        }
    }

    public void shutdown() {
        users.shutdown();
    }
}
