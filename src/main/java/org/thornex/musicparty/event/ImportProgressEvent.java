package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.dto.ImportProgress;

/** 歌单/专辑导入进度：只发给发起者。 */
@Getter
public class ImportProgressEvent extends ApplicationEvent {

    private final String sessionId;
    private final ImportProgress progress;

    public ImportProgressEvent(Object source, String sessionId, ImportProgress progress) {
        super(source);
        this.sessionId = sessionId;
        this.progress = progress;
    }
}
