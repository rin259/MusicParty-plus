package org.thornex.musicparty.room;

/**
 * 带有房间归属的应用事件。
 *
 * <p>每个房间有一份自己的 MusicPlayerService / UserService / ChatService，它们通过
 * {@link RoomScopedPublisher} 发布事件时会被包上 roomId；单例的 RoomEventBridge
 * 再按 roomId 分发到对应的 WebSocket topic。
 */
public record RoomEvent(String roomId, Object payload) {
}
