package org.thornex.musicparty.event;

import org.springframework.context.ApplicationEvent;

public class RuntimeConfigChangedEvent extends ApplicationEvent {
    public RuntimeConfigChangedEvent(Object source) {
        super(source);
    }
}
