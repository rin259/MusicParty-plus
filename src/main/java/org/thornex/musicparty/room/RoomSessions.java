package org.thornex.musicparty.room;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话 -> 房间 的映射。
 *
 * <p>房间归属在 CONNECT 帧里确定（拦截器写入），之后所有 @MessageMapping 都从这里取，
 * 绝不相信客户端在消息体里自报的 roomId。
 */
@Component
public class RoomSessions {

    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();

    public void bind(String sessionId, String roomId) {
        if (sessionId == null) return;
        sessionToRoom.put(sessionId, roomId == null || roomId.isBlank() ? Room.MAIN_ROOM_ID : roomId);
    }

    public String roomOf(String sessionId) {
        if (sessionId == null) return Room.MAIN_ROOM_ID;
        return sessionToRoom.getOrDefault(sessionId, Room.MAIN_ROOM_ID);
    }

    public void unbind(String sessionId) {
        if (sessionId != null) sessionToRoom.remove(sessionId);
    }
}
