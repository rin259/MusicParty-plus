package org.thornex.musicparty.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.room.Room;
import org.thornex.musicparty.room.RoomRegistry;
import org.thornex.musicparty.room.RoomSessions;

/**
 * CONNECT 帧校验：房间必须存在，密码（若有）必须正确，并把 session→room 绑定起来。
 *
 * <p>房间号只在这里从 CONNECT header 读取一次；后续所有消息都以这个绑定为准，
 * 客户端无法通过消息体把操作发到别的房间。
 */
@Component
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final RoomRegistry roomRegistry;
    private final RoomSessions roomSessions;
    private final String adminPassword;

    public WebSocketAuthInterceptor(RoomRegistry roomRegistry, RoomSessions roomSessions,
                                    AppProperties appProperties) {
        this.roomRegistry = roomRegistry;
        this.roomSessions = roomSessions;
        this.adminPassword = appProperties.getAdminPassword();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String roomId = accessor.getFirstNativeHeader("room-id");
        if (roomId == null || roomId.isBlank()) roomId = Room.MAIN_ROOM_ID;
        if (roomRegistry.get(roomId) == null) {
            log.warn("WebSocket Connection Refused: unknown room {} (session {})", roomId, accessor.getSessionId());
            throw new MessageDeliveryException("ROOM_NOT_FOUND");
        }

        String inputPassword = accessor.getFirstNativeHeader("room-password");
        if (!isPasswordValid(roomId, inputPassword)) {
            log.warn("WebSocket Connection Refused: invalid room password for {} (session {})",
                    roomId, accessor.getSessionId());
            throw new MessageDeliveryException("INVALID_ROOM_PASSWORD");
        }

        roomSessions.bind(accessor.getSessionId(), roomId);
        log.info("WebSocket Authenticated: session {} -> room {}", accessor.getSessionId(), roomId);
        return message;
    }

    private boolean isPasswordValid(String roomId, String input) {
        if (adminPassword != null && adminPassword.equals(input)) return true;   // 管理员万能钥匙
        return roomRegistry.verifyPassword(roomId, input);
    }
}
