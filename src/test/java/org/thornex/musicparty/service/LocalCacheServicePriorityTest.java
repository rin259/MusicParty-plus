package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.PriorityBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCacheServicePriorityTest {

    private static LocalCacheService.DownloadTask task(String id, LocalCacheService.DownloadPriority priority, long seq) {
        return new LocalCacheService.DownloadTask(id, Mono.empty(), priority, seq);
    }

    @Test
    void urgentDownloadsJumpAheadOfBackgroundPrefetch() {
        PriorityBlockingQueue<LocalCacheService.DownloadTask> queue = new PriorityBlockingQueue<>();
        queue.add(task("background-1", LocalCacheService.DownloadPriority.BACKGROUND, 1));
        queue.add(task("normal-1", LocalCacheService.DownloadPriority.NORMAL, 2));
        queue.add(task("urgent-1", LocalCacheService.DownloadPriority.URGENT, 3));
        queue.add(task("background-2", LocalCacheService.DownloadPriority.BACKGROUND, 4));

        assertThat(pollIds(queue, 4))
                .containsExactly("urgent-1", "normal-1", "background-1", "background-2");
    }

    @Test
    void samePriorityStaysFifo() {
        PriorityBlockingQueue<LocalCacheService.DownloadTask> queue = new PriorityBlockingQueue<>();
        queue.add(task("normal-1", LocalCacheService.DownloadPriority.NORMAL, 1));
        queue.add(task("normal-2", LocalCacheService.DownloadPriority.NORMAL, 2));
        queue.add(task("normal-3", LocalCacheService.DownloadPriority.NORMAL, 3));

        assertThat(pollIds(queue, 3))
                .containsExactly("normal-1", "normal-2", "normal-3");
    }

    private static java.util.List<String> pollIds(PriorityBlockingQueue<LocalCacheService.DownloadTask> queue, int n) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalCacheService.DownloadTask task = queue.poll();
            if (task != null) ids.add(task.musicId());
        }
        return ids;
    }

    @Test
    void priorityWinsOverSequenceNumber() {
        PriorityBlockingQueue<LocalCacheService.DownloadTask> queue = new PriorityBlockingQueue<>();
        queue.add(task("background-early", LocalCacheService.DownloadPriority.BACKGROUND, 0));
        queue.add(task("urgent-late", LocalCacheService.DownloadPriority.URGENT, 99));

        assertThat(queue.poll().musicId()).isEqualTo("urgent-late");
        assertThat(queue.poll().musicId()).isEqualTo("background-early");
    }
}
