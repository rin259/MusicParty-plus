package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.thornex.musicparty.service.LocalCacheService.DownloadPriority.*;

class LocalCacheServicePriorityTest {
    @Test void urgentDownloadsJumpAheadOfBackgroundPrefetch() {
        var scheduler = new PreparationScheduler<String>(4, 2, 2, 32, () -> 0L);
        scheduler.submit("background-1", "background-1", BACKGROUND);
        scheduler.submit("normal-1", "normal-1", NORMAL);
        scheduler.submit("urgent-1", "urgent-1", URGENT);
        scheduler.submit("background-2", "background-2", BACKGROUND);

        assertThat(drain(scheduler)).containsExactly("urgent-1", "normal-1", "background-1", "background-2");
    }

    @Test void samePriorityStaysFifoForTheSameUser() {
        var scheduler = new PreparationScheduler<String>(4, 2, 2, 32, () -> 0L);
        var ids = List.of("normal-1", "normal-2", "normal-3");
        scheduler.replace("room", ids.stream()
                .map(id -> new PreparationScheduler.Demand(id, "room", "alice", NORMAL)).toList());
        ids.forEach(id -> scheduler.submit(id, id, NORMAL));

        assertThat(drain(scheduler)).containsExactlyElementsOf(ids);
    }

    private static List<String> drain(PreparationScheduler<String> scheduler) {
        List<String> ids = new ArrayList<>();
        for (var next = scheduler.poll(); next != null; next = scheduler.poll()) {
            ids.add(next.id());
            scheduler.complete(next.id(), 0);
        }
        return ids;
    }
}
