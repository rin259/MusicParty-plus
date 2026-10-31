package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.dto.EnqueueResult;

/** 点歌回执：只发给发起者（按 simpSessionId），不污染全房间的系统消息。 */
@Getter
public class EnqueueResultEvent extends ApplicationEvent {

    private final String sessionId;
    private final EnqueueResult result;

    public EnqueueResultEvent(Object source, String sessionId, EnqueueResult result) {
        super(source);
        this.sessionId = sessionId;
        this.result = result;
    }
}
