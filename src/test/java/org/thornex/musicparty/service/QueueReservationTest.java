package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class QueueReservationTest {
    @Test void reservesCapacityAndQuotaBeforeAsyncMetadataAndRejectsImportsAgainstThem() {
        AppProperties props = new AppProperties(); props.getQueue().setMaxUserSongs(1);
        MusicQueueManager queue = new MusicQueueManager(props);
        UserSummary user = new UserSummary("alice", "s", "Alice", false);
        Music one = new Music("1", "one", List.of("artist"), 1000, "netease", "");
        Music two = new Music("2", "two", List.of("artist"), 1000, "netease", "");
        assertThat(queue.reserve("netease:1", "alice", "ticket")).isNull();
        assertThat(queue.reserve("netease:1", "bob", "duplicate")).isEqualTo(MusicQueueManager.AddFailure.DUPLICATE);
        assertThat(queue.add(two, user, QueueItemStatus.PENDING).failure()).isEqualTo(MusicQueueManager.AddFailure.USER_LIMIT);
        assertThat(queue.commitReserved("netease:1", "ticket", one, user, QueueItemStatus.PENDING).ok()).isTrue();
        assertThat(queue.getQueueSnapshot()).hasSize(1);
    }
    @Test void staleCompletionCannotRemoveOrCommitNewReservationAfterClear() {
        MusicQueueManager queue = new MusicQueueManager(new AppProperties());
        queue.reserve("netease:1", "alice", "old"); queue.clearPendingQueue();
        assertThat(queue.reserve("netease:1", "alice", "new")).isNull();
        queue.releaseReservation("netease:1", "old");
        var music = new Music("1", "one", List.of("artist"), 1000, "netease", "");
        var user = new UserSummary("alice", "s", "Alice", false);
        assertThat(queue.commitReserved("netease:1", "old", music, user, QueueItemStatus.PENDING).ok()).isFalse();
        assertThat(queue.commitReserved("netease:1", "new", music, user, QueueItemStatus.PENDING).ok()).isTrue();
    }
}
