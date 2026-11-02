package org.thornex.musicparty.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.event.UserCountChangeEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

class UserServiceTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        userService.shutdown();
    }

    @Test
    void keepsRecentlyDisconnectedUserOnlineDuringReconnectGracePeriod() {
        userService.handleConnect("session-1", "user-token", "Alice");

        userService.disconnectUser("session-1");

        assertThat(userService.getOnlineUserSummaries()).hasSize(1);

        userService.handleConnect("session-2", "user-token", "Alice");

        assertThat(userService.getOnlineUserSummaries())
                .singleElement()
                .extracting(summary -> summary.sessionId())
                .isEqualTo("session-2");
    }

    @Test
    void confirmsTheLeaveAndPublishesZeroUsersAfterGracePeriod() throws InterruptedException {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CountDownLatch confirmedLeave = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object event = invocation.getArgument(0);
            if (event instanceof UserCountChangeEvent userCountChangeEvent
                    && userCountChangeEvent.getOnlineUserCount() == 0) {
                confirmedLeave.countDown();
            }
            return null;
        }).when(publisher).publishEvent(any());
        userService.shutdown();
        userService = new UserService(publisher, 1);

        userService.handleConnect("session-1", "user-token", "Alice");
        userService.disconnectUser("session-1");

        assertThat(confirmedLeave.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(userService.getOnlineUserSummaries()).isEmpty();
    }
}
