package org.thornex.musicparty.event;

/** Per-session vote receipt: the room topic only carries totals. */
public record VoteStateEvent(Object source, String sessionId, String roomId, boolean voted) {
    public VoteStateEvent(String sessionId, String roomId, boolean voted) {
        this(new Object(), sessionId, roomId, voted);
    }
}
