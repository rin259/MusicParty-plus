package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class EnqueueAdmissionTest {
    @Test void duplicateAndOverQuotaRequestsDoNotStartMetadataOrPrefetchAndDoNotRaceImport() {
        var props = new AppProperties(); props.setNetease(new AppProperties.NeteaseApiConfig());
        props.setBilibili(new AppProperties.BilibiliApiConfig()); props.getQueue().setMaxUserSongs(1);
        var queue = new MusicQueueManager(props); var users = mock(UserService.class);
        when(users.getUser("session")).thenReturn(Optional.of(new User("alice", "session", "Alice")));
        var cache = mock(LocalCacheService.class);
        var api = mock(IMusicApiService.class); when(api.getPlatformName()).thenReturn("netease");
        when(cache.lookupMetadata(api, "1")).thenReturn(Mono.never());
        var player = new MusicPlayerService(List.of(api), users, cache, mock(LiveStreamService.class), queue,
                mock(ApplicationEventPublisher.class), props, "room");
        player.enqueue(new EnqueueRequest("netease", "1", "one"), "session");
        player.enqueue(new EnqueueRequest("netease", "1", "duplicate"), "session");
        player.enqueue(new EnqueueRequest("netease", "2", "over-quota"), "session");
        verify(cache, times(1)).lookupMetadata(api, "1");
        verify(cache, never()).lookupMetadata(api, "2");
        verify(api, never()).getPlayableMusic(anyString());
        verify(api, never()).prefetchMusic(anyString());
        assertThat(queue.getQueueSnapshot()).isEmpty();
        player.shutdown();
    }
}
