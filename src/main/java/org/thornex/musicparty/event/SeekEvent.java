package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class SeekEvent extends ApplicationEvent {
    private final long positionMs;

    public SeekEvent(Object source, long positionMs) {
        super(source);
        this.positionMs = positionMs;
    }
}
