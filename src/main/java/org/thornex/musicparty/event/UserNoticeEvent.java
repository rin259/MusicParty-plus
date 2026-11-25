package org.thornex.musicparty.event;

/**
 * A message for one session only: throttle rejections, vote receipts and other
 * feedback that must not be broadcast to the whole room.
 */
public record UserNoticeEvent(Object source, String sessionId, String roomId,
                              String action, String message) {
    public UserNoticeEvent(String sessionId, String roomId, String action, String message) {
        this(new Object(), sessionId, roomId, action, message);
    }
}
