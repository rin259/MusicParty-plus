package org.thornex.musicparty.room;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.event.EnqueueResultEvent;
import org.thornex.musicparty.event.ImportProgressEvent;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.SeekEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.event.UserCountChangeEvent;
import org.thornex.musicparty.event.UserNoticeEvent;
import org.thornex.musicparty.event.VoteStateEvent;

/**
 * 房间作用域的事件发布器：需要路由到房间的事件包成 {@link RoomEvent}，
 * 全局事件（运行时配置变更等）保持原样发布。
 *
 * <p>这样每个房间的服务代码仍然写 {@code eventPublisher.publishEvent(new XxxEvent(this, ...))}，
 * 不需要在几十个调用点手工补 roomId，也就不会漏。
 */
public class RoomScopedPublisher implements ApplicationEventPublisher {

    private final ApplicationEventPublisher delegate;
    private final String roomId;

    public RoomScopedPublisher(ApplicationEventPublisher delegate, String roomId) {
        this.delegate = delegate;
        this.roomId = roomId;
    }

    @Override
    public void publishEvent(Object event) {
        if (needsRoomRouting(event)) {
            delegate.publishEvent(new RoomEvent(roomId, event));
        } else {
            delegate.publishEvent(event);
        }
    }

    private static boolean needsRoomRouting(Object event) {
        return event instanceof PlayerStateEvent
                || event instanceof QueueUpdateEvent
                || event instanceof SeekEvent
                || event instanceof EnqueueResultEvent
                || event instanceof ImportProgressEvent
                || event instanceof SystemMessageEvent
                || event instanceof UserCountChangeEvent
                || event instanceof UserNoticeEvent
                || event instanceof VoteStateEvent;
    }
}
