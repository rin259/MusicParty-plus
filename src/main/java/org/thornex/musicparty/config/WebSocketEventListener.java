package org.thornex.musicparty.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.thornex.musicparty.room.Room;
import org.thornex.musicparty.room.RoomRegistry;
import org.thornex.musicparty.room.RoomRuntime;
import org.thornex.musicparty.room.RoomSessions;

/**
 * WebSocket 连接生命周期：按房间创建/注销用户。
 */
@Component
@Slf4j
public class WebSocketEventListener {

    private final RoomRegistry roomRegistry;
    private final RoomSessions roomSessions;

    public WebSocketEventListener(RoomRegistry roomRegistry, RoomSessions roomSessions) {
        this.roomRegistry = roomRegistry;
        this.roomSessions = roomSessions;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        String roomId = accessor.getFirstNativeHeader("room-id");
        if (roomId == null || roomId.isBlank()) roomId = roomSessions.roomOf(sessionId);
        if (roomId == null || roomId.isBlank()) roomId = Room.MAIN_ROOM_ID;

        String initialName = accessor.getFirstNativeHeader("user-name");
        String deviceId = accessor.getFirstNativeHeader("device-id");
        if (deviceId == null || deviceId.isBlank()) deviceId = accessor.getFirstNativeHeader("user-token");

        if (sessionId == null) return;
        try {
            RoomRuntime runtime = roomRegistry.getOrCreateRuntime(roomId);
            roomSessions.bind(sessionId, roomId);
            runtime.users().handleConnect(sessionId, deviceId, initialName);
            runtime.player().broadcastOnlineUsers();
        } catch (RuntimeException error) {
            log.warn("Unable to register session {} in room {}: {}", sessionId, roomId, error.toString());
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;

        String roomId = roomSessions.roomOf(sessionId);
        try {
            RoomRuntime runtime = roomRegistry.runtimeQuietly(roomId);
            if (runtime != null) {
                runtime.users().disconnectUser(sessionId);
                runtime.player().broadcastOnlineUsers();
            }
        } catch (RuntimeException error) {
            log.warn("Unable to unregister session {} from room {}: {}", sessionId, roomId, error.toString());
        } finally {
            roomSessions.unbind(sessionId);
        }
    }
}
