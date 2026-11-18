package org.thornex.musicparty.room;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.DownloadStatusEvent;
import org.thornex.musicparty.event.EnqueueResultEvent;
import org.thornex.musicparty.event.ImportProgressEvent;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.SeekEvent;
import org.thornex.musicparty.event.StreamStatusEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.event.UserCountChangeEvent;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.util.MessageFormatter;

/**
 * 事件 -> 房间 topic 的路由。
 *
 * <p>取代原来的 WebSocketBroadcaster：所有广播都带房间前缀
 * {@code /topic/r/{roomId}/...}，跨房间串消息在结构上不可能发生。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomEventBridge {

    private final RoomRegistry registry;
    private final SimpMessagingTemplate messagingTemplate;
    private final LiveStreamService liveStreamService;
    private final org.thornex.musicparty.service.LocalCacheService localCacheService;

    private String base(String roomId) {
        return "/topic/r/" + roomId;
    }

    @EventListener
    public void onRoomEvent(RoomEvent event) {
        String roomId = event.roomId();
        Object payload = event.payload();
        if (roomId == null) return;

        if (payload instanceof PlayerStateEvent state) {
            // 直播已搁置，但默认房的 /radio/stream 仍保持可用
            if (Room.MAIN_ROOM_ID.equals(roomId)) {
                liveStreamService.onPlayerState(state);
            }
            messagingTemplate.convertAndSend(base(roomId) + "/player/state", state.getState());
        } else if (payload instanceof QueueUpdateEvent queue) {
            messagingTemplate.convertAndSend(base(roomId) + "/player/queue", queue.getQueue());
        } else if (payload instanceof SeekEvent seek) {
            messagingTemplate.convertAndSend(base(roomId) + "/player/seek", seek.getPositionMs());
        } else if (payload instanceof EnqueueResultEvent result) {
            messagingTemplate.convertAndSendToUser(result.getSessionId(), "/queue/enqueue",
                    result.getResult(), sessionHeaders(result.getSessionId()));
        } else if (payload instanceof ImportProgressEvent progress) {
            messagingTemplate.convertAndSendToUser(progress.getSessionId(), "/queue/import",
                    progress.getProgress(), sessionHeaders(progress.getSessionId()));
        } else if (payload instanceof SystemMessageEvent message) {
            RoomRuntime runtime = registry.runtimeQuietly(roomId);
            if (runtime != null) {
                runtime.chat().onSystemEvent(message);
                broadcastPlayerEvent(roomId, message, runtime);
            }
        } else if (payload instanceof UserCountChangeEvent count) {
            RoomRuntime runtime = registry.runtimeQuietly(roomId);
            if (runtime != null) runtime.player().onUserCountChanged(count);
        }
    }

    /** 下载状态是全局的（共享缓存），每个房间自己看有没有相关队列项。 */
    @EventListener
    public void onDownloadStatus(DownloadStatusEvent event) {
        for (String roomId : localCacheService.interestedRooms(event.getMusicId())) {
            RoomRuntime runtime = registry.runtimeQuietly(roomId);
            if (runtime == null) continue;
            try {
                runtime.player().handleDownloadEvent(event);
            } catch (RuntimeException error) {
                log.warn("Room {} download handling failed: {}", runtime.roomId(), error.toString());
            }
        }
    }

    /** 直播已搁置：流状态只影响默认房。 */
    @EventListener
    public void onStreamStatus(StreamStatusEvent event) {
        RoomRuntime main = registry.mainRuntimeQuietly();
        if (main != null) main.player().onStreamStatusChanged(event);
    }

    private void broadcastPlayerEvent(String roomId, SystemMessageEvent event, RoomRuntime runtime) {
        String actionCode = event.getAction() != null ? event.getAction().name() : "";
        String type = event.getLevel().name();

        String userName = "SYSTEM";
        if (!"SYSTEM".equals(event.getUserId()) && runtime != null) {
            userName = runtime.users().getUserByToken(event.getUserId())
                    .map(User::getName)
                    .orElse("Unknown");
        }

        String formattedMessage = MessageFormatter.format(event, userName);

        if (event.getAction() == PlayerAction.SYSTEM_MESSAGE
                && event.getPayload() != null && event.getPayload().startsWith("房间密码")) {
            actionCode = "PASSWORD_CHANGED";
            type = "ERROR";
        }

        PlayerEvent playerEvent = new PlayerEvent(
                type,
                actionCode,
                event.getUserId(),
                formattedMessage,
                event.getPayload()
        );
        messagingTemplate.convertAndSend(base(roomId) + "/player/events", playerEvent);
    }

    private MessageHeaders sessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
