package org.thornex.musicparty.controller;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.room.RoomRegistry;
import org.thornex.musicparty.room.RoomRuntime;
import org.thornex.musicparty.room.RoomSessions;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.UserService;

import java.util.List;

/**
 * 所有 WebSocket 指令都按「session 所属房间」执行；房间号来自 CONNECT 帧，
 * 不从消息体读取（防止跨房操作）。
 */
@Controller
public class MusicSocketController {

    private final RoomRegistry roomRegistry;
    private final RoomSessions roomSessions;
    private final SimpMessagingTemplate messagingTemplate;

    public MusicSocketController(RoomRegistry roomRegistry, RoomSessions roomSessions,
                                 SimpMessagingTemplate messagingTemplate) {
        this.roomRegistry = roomRegistry;
        this.roomSessions = roomSessions;
        this.messagingTemplate = messagingTemplate;
    }

    private RoomRuntime runtime(String sessionId) {
        String roomId = roomSessions.roomOf(sessionId);
        try {
            return roomRegistry.getOrCreateRuntime(roomId);
        } catch (ApiRequestException error) {
            return null;
        }
    }

    @MessageMapping("/player/resync")
    public void requestResync(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime != null) runtime.player().broadcastFullPlayerState();
    }

    @MessageMapping("/enqueue")
    public void enqueue(EnqueueRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().enqueue(request, sessionId);
    }

    @MessageMapping("/enqueue/playlist")
    public void enqueuePlaylist(EnqueuePlaylistRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().enqueuePlaylist(request, sessionId);
    }

    @MessageMapping("/enqueue/album")
    public void enqueueAlbum(EnqueuePlaylistRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().enqueueAlbum(request, sessionId);
    }

    @MessageMapping("/control/next")
    public void nextSong(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().skipToNext(sessionId);
    }

    @MessageMapping("/control/toggle-shuffle")
    public void cyclePlayMode(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().cyclePlayMode(sessionId);
    }

    @MessageMapping("/control/toggle-pause")
    public void togglePause(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().togglePause(sessionId);
    }

    @MessageMapping("/control/seek")
    public void seek(@Payload SeekRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().seekTo(request.positionMs(), sessionId);
    }

    @MessageMapping("/queue/top")
    public void topSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().topSong(request.queueId(), sessionId);
    }

    @MessageMapping("/queue/remove")
    public void removeSong(@Payload QueueActionRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().removeSongFromQueue(request.queueId(), sessionId);
    }

    @MessageMapping("/control/like")
    public void likeSong(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;
        runtime.player().likeSong(sessionId);
    }

    @MessageMapping("/user/rename")
    public void rename(RenameRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null) return;
        UserService users = runtime.users();
        if (users.renameUser(sessionId, request.newName())) {
            runtime.player().broadcastOnlineUsers();
            users.getUser(sessionId).ifPresent(user -> {
                UserSummary summary = new UserSummary(user.getToken(), user.getSessionId(), user.getName(), user.isGuest());
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/me", summary, createSessionHeaders(sessionId));
            });
        } else {
            users.getUser(sessionId).ifPresent(user -> {
                PlayerEvent errorEvent = new PlayerEvent("ERROR", "RENAME_FAILED", user.getToken(),
                        "该名称已被占用或包含非法字符，请更换。", null);
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/events", errorEvent,
                        createSessionHeaders(sessionId));
            });
        }
    }

    @MessageMapping("/user/bind")
    public void bindAccount(BindRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime != null) runtime.users().bindAccount(sessionId, request.platform(), request.accountId());
    }

    @SubscribeMapping("/topic/r/{roomId}/player/state")
    public PlayerState getInitialPlayerState(@DestinationVariable String roomId,
                                             @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtimeForSubscription(roomId, sessionId);
        return runtime == null ? null : runtime.player().getCurrentPlayerState();
    }

    @SubscribeMapping("/topic/r/{roomId}/users/online")
    public List<UserSummary> getInitialOnlineUsers(@DestinationVariable String roomId,
                                                   @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtimeForSubscription(roomId, sessionId);
        return runtime == null ? List.of() : runtime.users().getOnlineUserSummaries();
    }

    @SubscribeMapping("/user/me")
    public UserSummary getMyUserInfo(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null) return new UserSummary(sessionId, sessionId, "Unknown", true);
        return runtime.users().getUser(sessionId)
                .map(u -> new UserSummary(u.getToken(), u.getSessionId(), u.getName(), u.isGuest()))
                .orElse(new UserSummary(sessionId, sessionId, "Unknown", true));
    }

    private RoomRuntime runtimeForSubscription(String requestedRoomId, String sessionId) {
        // 只允许订阅自己所属房间的快照
        if (requestedRoomId == null || !requestedRoomId.equals(roomSessions.roomOf(sessionId))) {
            return null;
        }
        return runtime(sessionId);
    }

    private boolean isGuest(RoomRuntime runtime, String sessionId) {
        return runtime.users().getUser(sessionId).map(User::isGuest).orElse(true);
    }

    @MessageMapping("/chat")
    public void handleChat(ChatRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null || isGuest(runtime, sessionId)) return;

        if (request.content() == null || request.content().trim().isEmpty()) return;
        ChatService chatService = runtime.chat();
        if (!chatService.isMessageLengthValid(request.content())) return;

        if (chatService.processIncomingMessage(sessionId, request.content().trim())) return;

        runtime.users().getUser(sessionId).ifPresent(user -> {
            if (!chatService.canUserSendMessage(user.getToken())) return;

            ChatMessage message = new ChatMessage(
                    java.util.UUID.randomUUID().toString(),
                    user.getToken(),
                    user.getName(),
                    request.content().trim(),
                    System.currentTimeMillis(),
                    MessageType.CHAT
            );

            chatService.addMessage(message);
            messagingTemplate.convertAndSend("/topic/r/" + runtime.roomId() + "/chat", message);
        });
    }

    @SubscribeMapping("/chat/history")
    public List<ChatMessage> getChatHistory(@Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        return runtime == null ? List.of() : runtime.chat().getHistory(0, 50);
    }

    @MessageMapping("/chat/history/fetch")
    public void fetchChatHistory(@Payload ChatHistoryFetchRequest request, @Header("simpSessionId") String sessionId) {
        RoomRuntime runtime = runtime(sessionId);
        if (runtime == null) return;
        List<ChatMessage> history = runtime.chat().getHistory(request.offset(), request.limit());
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/chat/history", history,
                createSessionHeaders(sessionId));
    }

    private MessageHeaders createSessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
}
