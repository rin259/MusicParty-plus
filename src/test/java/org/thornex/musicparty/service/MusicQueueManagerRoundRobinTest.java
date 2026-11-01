package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 队列公平性：严格轮转（round-robin）。
 *
 * 关切点（2026-11 研判）：共享点歌里一个人连点会在队列中形成「连排」，
 * 其他人要等很久——共享点歌的经典 hogging 问题。规则：
 *  - GLOBAL_TOP 永远最前，且相对顺序不变；
 *  - 其余按用户分组后交替排列；
 *  - 用户之间的先后取「首次出现」的顺序（先点的人先轮）；
 *  - USER_TOP 只在本人子队列内提前，不越过别人的轮次。
 */
class MusicQueueManagerRoundRobinTest {

    private static MusicQueueManager newManager() {
        AppProperties props = new AppProperties();
        props.getQueue().setMaxSize(1000);
        return new MusicQueueManager(props);
    }

    private static Music song(String id, String platform) {
        return new Music(id, "song-" + id, List.of("artist"), 200_000L, platform, null);
    }

    private static UserSummary user(String token) {
        return new UserSummary(token, "sess-" + token, token, false);
    }

    @Test
    void interleavesSongsFromDifferentUsers() {
        MusicQueueManager manager = newManager();

        manager.add(song("a1", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("a2", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("a3", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("b1", "netease"), user("bob"), QueueItemStatus.READY);

        List<MusicQueueItem> queue = manager.getQueueSnapshot();

        assertThat(queue).extracting(item -> item.music().id())
                .containsExactly("a1", "b1", "a2", "a3");
    }

    @Test
    void keepsGlobalTopAtTheFrontAndUntouched() {
        MusicQueueManager manager = newManager();
        manager.add(song("a1", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("a2", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("b1", "netease"), user("bob"), QueueItemStatus.READY);

        String a2 = manager.getQueueSnapshot().stream()
                .filter(item -> item.music().id().equals("a2"))
                .findFirst().orElseThrow().queueId();
        manager.top(a2, org.thornex.musicparty.enums.PlayMode.SEQUENTIAL);

        manager.add(song("b2", "netease"), user("bob"), QueueItemStatus.READY);

        List<MusicQueueItem> queue = manager.getQueueSnapshot();
        assertThat(queue.get(0).music().id()).isEqualTo("a2");
        assertThat(queue.get(0).priority()).isEqualTo(org.thornex.musicparty.enums.Priority.GLOBAL_TOP);
        // 其余仍按轮转
        assertThat(queue).extracting(item -> item.music().id())
                .containsExactly("a2", "a1", "b1", "b2");
    }

    @Test
    void firstComeUserStillGoesFirst() {
        MusicQueueManager manager = newManager();
        manager.add(song("b1", "netease"), user("bob"), QueueItemStatus.READY);
        manager.add(song("a1", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("b2", "netease"), user("bob"), QueueItemStatus.READY);

        assertThat(manager.getQueueSnapshot()).extracting(item -> item.music().id())
                .containsExactly("b1", "a1", "b2");
    }

    @Test
    void singleUserKeepsOriginalOrder() {
        MusicQueueManager manager = newManager();
        manager.add(song("a1", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("a2", "netease"), user("alice"), QueueItemStatus.READY);
        manager.add(song("a3", "netease"), user("alice"), QueueItemStatus.READY);

        assertThat(manager.getQueueSnapshot()).extracting(item -> item.music().id())
                .containsExactly("a1", "a2", "a3");
    }
}
