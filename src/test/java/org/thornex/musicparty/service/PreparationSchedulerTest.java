package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.thornex.musicparty.service.LocalCacheService.DownloadPriority.*;

class PreparationSchedulerTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private PreparationScheduler<String> scheduler(int total, int source, int yt, int pending) {
        return new PreparationScheduler<>(total, source, yt, pending, clock::get);
    }
    private PreparationScheduler.Demand d(String id, String room, String user, LocalCacheService.DownloadPriority p) {
        return new PreparationScheduler.Demand(id, room, user, p);
    }
    @Test void boundsActiveWorkAndPreservesAnUrgentLane() {
        var s = scheduler(4, 2, 2, 100);
        for (String id : List.of("netease_1", "netease_2", "BV1", "BV2")) s.submit(id, id, NORMAL);
        assertThat(s.poll()).isNotNull(); assertThat(s.poll()).isNotNull(); assertThat(s.poll()).isNotNull();
        assertThat(s.poll()).isNull(); // three normal tasks cannot occupy the reserved fourth lane
        s.submit("ytmusic_urgent", "u", URGENT);
        assertThat(s.poll().id()).isEqualTo("ytmusic_urgent");
        assertThat(s.active()).isEqualTo(4); assertThat(s.poll()).isNull();
    }
    @Test void aBlockedSourceDoesNotBlockOtherSourcesAndYtHasIndependentCap() {
        var s = scheduler(6, 1, 1, 100);
        for (String id : List.of("soundcloud_1", "soundcloud_2", "ytmusic_1", "netease_1", "BV1")) s.submit(id, id, URGENT);
        assertThat(s.poll().id()).isEqualTo("soundcloud_1");
        assertThat(s.poll().id()).isEqualTo("netease_1");
        assertThat(s.poll().id()).isEqualTo("BV1");
        assertThat(s.poll()).isNull();
    }
    @Test void roundRobinsRoomsThenUsersNotTheirImportSizes() {
        var s = scheduler(4, 4, 2, 100);
        s.replace("a", List.of(d("netease_a1", "a", "alice", NORMAL), d("netease_a2", "a", "alice", NORMAL), d("netease_b1", "a", "bob", NORMAL)));
        s.replace("b", List.of(d("netease_c1", "b", "charlie", NORMAL)));
        for (String id : List.of("netease_a1", "netease_a2", "netease_b1", "netease_c1")) s.submit(id, id, NORMAL);
        assertThat(s.poll().id()).isEqualTo("netease_a1");
        assertThat(s.poll().id()).isEqualTo("netease_c1");
        assertThat(s.poll().id()).isEqualTo("netease_b1");
    }
    @Test void promotesExistingJobWithoutDuplicatingOrChangingProvider() {
        var s = scheduler(4, 2, 2, 100);
        s.replace("a", List.of(d("netease_old", "a", "alice", BACKGROUND)));
        s.submit("netease_old", "original-provider", BACKGROUND);
        s.submit("BVnew", "b", NORMAL);
        s.replace("a", List.of(d("netease_old", "a", "alice", URGENT)));
        assertThat(s.poll().payload()).isEqualTo("original-provider");
        assertThat(s.pending()).isEqualTo(1);
    }
    @Test void roomRemovalDropsWaitingOrphansButRetainsSharedAndActiveJobs() {
        var s = scheduler(4, 2, 2, 100);
        s.replace("a", List.of(d("netease_shared", "a", "alice", URGENT), d("netease_orphan", "a", "alice", NORMAL)));
        s.replace("b", List.of(d("netease_shared", "b", "bob", NORMAL)));
        s.submit("netease_shared", "s", URGENT); s.submit("netease_orphan", "o", NORMAL);
        assertThat(s.replace("a", List.of())).containsExactly("netease_orphan");
        assertThat(s.poll().id()).isEqualTo("netease_shared");
        assertThat(s.replace("b", List.of())).isEmpty(); // don't kill an active shared download
        s.complete("netease_shared", 3000);
        assertThat(s.active()).isZero(); assertThat(s.pending()).isZero();
    }
    @Test void cooldownAndBackoffAreSourceLocalAndPendingAdmissionIsBounded() {
        var s = scheduler(4, 1, 2, 2);
        s.submit("netease_1", "n1", URGENT); s.submit("netease_2", "n2", URGENT);
        assertThat(s.submit("BVfull", "b", URGENT)).isFalse();
        s.poll(); s.complete("netease_1", 3000);
        s.submit("BVok", "b", URGENT);
        assertThat(s.poll().id()).isEqualTo("BVok");
        assertThat(s.poll()).isNull();
        clock.addAndGet(3001); assertThat(s.poll().id()).isEqualTo("netease_2");
    }
    @Test void concurrentSameSongSubmissionsUseOneSlot() throws Exception {
        var s = scheduler(4, 2, 2, 100);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 100; i++) work.add(pool.submit(() -> s.submit("netease_same", "provider", URGENT)));
            for (Future<?> future : work) future.get(2, TimeUnit.SECONDS);
        }
        assertThat(s.pending()).isEqualTo(1);
        assertThat(s.poll().id()).isEqualTo("netease_same");
        assertThat(s.poll()).isNull();
    }

    @Test void deferredAttemptDoesNotOverflowPendingAdmission() {
        var s = scheduler(4, 2, 2, 1);
        s.submit("netease_retry", "retry", URGENT);
        assertThat(s.poll().id()).isEqualTo("netease_retry");
        s.submit("BVnext", "fresh", URGENT);
        assertThat(s.defer("netease_retry", 1000, 0)).isFalse();
        s.complete("netease_retry", 0);
        assertThat(s.pending()).isEqualTo(1);
        assertThat(s.poll().id()).isEqualTo("BVnext");
    }

    @Test void retryYieldsToFreshWorkButCanStillUseThePlaybackLane() {
        var s = scheduler(2, 2, 2, 8);
        s.submit("netease_old", "old", URGENT);
        assertThat(s.poll().id()).isEqualTo("netease_old");
        assertThat(s.defer("netease_old", 1000, 0)).isTrue();
        s.submit("BVnormal", "normal", NORMAL);
        assertThat(s.poll().id()).isEqualTo("BVnormal");
        clock.addAndGet(1000);
        s.submit("soundcloud_new", "new", URGENT);
        assertThat(s.poll().id()).isEqualTo("soundcloud_new");
        s.complete("soundcloud_new", 0);
        var retry = s.poll();
        assertThat(retry.id()).isEqualTo("netease_old");
        assertThat(retry.queuedMillis()).isEqualTo(1000);
    }
}
