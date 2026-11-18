package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class PreparationWindowTest {
    @TempDir Path directory;

    @SuppressWarnings("unchecked")
    private static java.util.List<String> ids(org.mockito.ArgumentCaptor<Collection> window) {
        return ((Collection<LocalCacheService.PreparationRequest>) window.getValue()).stream()
                .map(LocalCacheService.PreparationRequest::id).toList();
    }

    @Test void readyEntriesNoLongerBlockAdmissionOfALaterImport() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig()); props.setYtmusic(new AppProperties.YtDlpSourceConfig());
        props.getPreparation().setWindowSize(4);
        var queue = new MusicQueueManager(props);
        var users = mock(UserService.class);
        var alice = new UserSummary("alice", "s", "Alice", false);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(alice));
        var cache = mock(LocalCacheService.class);
        var netease = mock(IMusicApiService.class); when(netease.getPlatformName()).thenReturn("netease");
        var ytmusic = mock(IMusicApiService.class); when(ytmusic.getPlatformName()).thenReturn("ytmusic");
        var player = new MusicPlayerService(List.of(netease, ytmusic), users, cache, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), props, "room");
        try {
            for (int i = 0; i < 6; i++) {
                String id = "ready" + i;
                queue.add(new Music(id, "done", List.of("artist"), 1000, "netease", ""), alice, QueueItemStatus.READY);
                when(cache.getStatus("netease_" + id)).thenReturn(CacheStatus.COMPLETED);
            }
            queue.add(new Music("yt1", "new import", List.of("artist"), 1000, "ytmusic", ""), alice, QueueItemStatus.PENDING);
            player.flushQueueUpdate();
            var window = org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(cache).updatePreparationWindow(eq("room"), window.capture());
            assertThat(ids(window)).containsExactly("ytmusic_yt1");
        } finally { player.shutdown(); }
    }

    @Test void finishedSongsOfOneUserDoNotExhaustThatUsersSlots() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig()); props.setYtmusic(new AppProperties.YtDlpSourceConfig());
        var queue = new MusicQueueManager(props);
        var users = mock(UserService.class);
        var alice = new UserSummary("alice", "s", "Alice", false);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(alice));
        var cache = mock(LocalCacheService.class);
        var netease = mock(IMusicApiService.class); when(netease.getPlatformName()).thenReturn("netease");
        var ytmusic = mock(IMusicApiService.class); when(ytmusic.getPlatformName()).thenReturn("ytmusic");
        var player = new MusicPlayerService(List.of(netease, ytmusic), users, cache, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), props, "room");
        try {
            for (int i = 0; i < 2; i++) {
                String id = "played" + i;
                queue.add(new Music(id, "done", List.of("artist"), 1000, "netease", ""), alice, QueueItemStatus.READY);
                when(cache.getStatus("netease_" + id)).thenReturn(CacheStatus.COMPLETED);
            }
            queue.add(new Music("yt2", "first pending", List.of("artist"), 1000, "ytmusic", ""), alice, QueueItemStatus.PENDING);
            queue.add(new Music("yt3", "second pending", List.of("artist"), 1000, "ytmusic", ""), alice, QueueItemStatus.PENDING);
            player.flushQueueUpdate();
            var window = org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(cache).updatePreparationWindow(eq("room"), window.capture());
            assertThat(ids(window)).containsExactly("ytmusic_yt2", "ytmusic_yt3");
        } finally { player.shutdown(); }
    }

    @Test void adminCommandsBypassTheUserControlCooldown() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig());
        var queue = new MusicQueueManager(props);
        var users = mock(UserService.class);
        var cache = mock(LocalCacheService.class);
        var api = mock(IMusicApiService.class); when(api.getPlatformName()).thenReturn("netease");
        var player = new MusicPlayerService(List.of(api), users, cache, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), props, "room");
        try {
            boolean before = player.getCurrentPlayerState().isFairShuffle();
            player.toggleFairShuffle("SYSTEM");
            assertThat(player.getCurrentPlayerState().isFairShuffle()).isNotEqualTo(before);
            player.toggleFairShuffle("SYSTEM");
            assertThat(player.getCurrentPlayerState().isFairShuffle()).isEqualTo(before);
            // A user command inside the same second is still throttled.
            player.toggleFairShuffle("user-token");
            assertThat(player.getCurrentPlayerState().isFairShuffle()).isEqualTo(before);
        } finally { player.shutdown(); }
    }

    @Test void roomReclaimsCapacityOnTickEvenWithoutItsOwnDownloadEvent() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig());
        props.getPreparation().setMaxPending(1);
        var queue = new MusicQueueManager(props);
        var users = mock(UserService.class);
        var alice = new UserSummary("alice", "s", "Alice", false);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(alice));
        var cache = new LocalCacheService(WebClient.create(), event -> {}, props, null, directory);
        var api = mock(IMusicApiService.class); when(api.getPlatformName()).thenReturn("netease");
        doAnswer(call -> {
            cache.submitDownload("netease_" + call.getArgument(0), Mono.never(), call.getArgument(1));
            return null;
        }).when(api).prefetchMusic(anyString(), any());
        var player = new MusicPlayerService(List.of(api), users, cache, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), props, "waiting-room");
        var urgent = LocalCacheService.DownloadPriority.URGENT;
        try {
            cache.updatePreparationWindow("other-room", List.of(new LocalCacheService.PreparationRequest(
                    "netease_blocker", "bob", urgent, () -> cache.submitDownload("netease_blocker", Mono.never(), urgent))));
            queue.add(new Music("next", "next", List.of("artist"), 1000, "netease", ""), alice, QueueItemStatus.PENDING);
            player.flushQueueUpdate();
            assertThat(cache.getStatus("netease_next")).isNull();
            assertThat(cache.preparationStats().admissionDeferrals()).isEqualTo(1);
            cache.updatePreparationWindow("other-room", List.of());
            player.tick();
            assertThat(cache.getStatus("netease_next")).isEqualTo(CacheStatus.PENDING);
        } finally { player.shutdown(); cache.cleanup(); }
    }

    @Test void hundredSongImportOnlyPreparesWindowAndCoalescesBroadcasts() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig());
        var queue = new MusicQueueManager(props);
        var users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(new UserSummary("alice", "s", "Alice", false)));
        var cache = mock(LocalCacheService.class);
        var api = mock(IMusicApiService.class); when(api.getPlatformName()).thenReturn("netease");
        var publisher = mock(ApplicationEventPublisher.class);
        var player = new MusicPlayerService(List.of(api), users, cache, mock(LiveStreamService.class), queue, publisher, props, "a");
        for (int i = 0; i < 100; i++) queue.add(new Music("a" + i, "song", List.of("artist"), 1000, "netease", ""), new UserSummary("alice", "s", "Alice", false), QueueItemStatus.PENDING);
        queue.add(new Music("b", "bob song", List.of("artist"), 1000, "netease", ""), new UserSummary("bob", "b", "Bob", false), QueueItemStatus.PENDING);
        for (int i = 0; i < 50; i++) player.broadcastQueueUpdate();
        verifyNoInteractions(cache); // mutations mark dirty, not one upstream/scan/broadcast per status change
        player.flushQueueUpdate();
        var window = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(cache).updatePreparationWindow(eq("a"), window.capture());
        assertThat(window.getValue()).hasSize(3); // alice's next 2 + bob's first
        verify(publisher, times(1)).publishEvent(any(QueueUpdateEvent.class));
        player.flushQueueUpdate();
        verify(publisher, times(1)).publishEvent(any(QueueUpdateEvent.class));
        verify(api, never()).prefetchMusic(anyString()); // cache scheduler owns execution of callbacks
        player.shutdown();
    }
}
