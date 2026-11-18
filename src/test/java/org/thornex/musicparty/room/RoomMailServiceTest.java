package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomMailServiceTest {
    @TempDir
    Path dataDir;

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final RoomRegistry rooms = mock(RoomRegistry.class);
    private final LinkedBlockingQueue<MimeMessage> sent = new LinkedBlockingQueue<>();
    private AppProperties config;
    private RoomPersistenceService store;
    private RoomMailService service;

    @BeforeEach
    void setUp() {
        config = new AppProperties();
        config.setBaseUrl("http://192.0.2.1:8080/");
        config.getQueue().setPersistenceFile(dataDir.resolve("queue-data.json").toString());
        store = new RoomPersistenceService(new ObjectMapper(), config);
        when(rooms.get("room-a")).thenReturn(new Room("room-a", "一起听歌", "owner", null, null, 0, 0));
        when(rooms.get("room-b")).thenReturn(new Room("room-b", "另一个房间", "owner", null, null, 0, 0));
        when(rooms.verifyPassword("room-a", "secret")).thenReturn(true);
        when(rooms.verifyPassword("room-b", "")).thenReturn(true);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(call -> {
            sent.add(call.getArgument(0));
            return null;
        }).when(sender).send(any(MimeMessage.class));

        service = createService();
    }

    private RoomMailService createService() {
        return new RoomMailService(rooms, store, createSettings());
    }

    private RoomMailSettings createSettings() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", sender);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.mail.host", "smtp.example.test")
                .withProperty("spring.mail.username", "sender@example.test")
                .withProperty("spring.mail.password", "test");
        return new RoomMailSettings(store, beans.getBeanProvider(JavaMailSender.class), config, environment);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void inviteRequiresConfirmationAndStaysScopedToTheRoom() throws Exception {
        assertStatus(() -> service.subscribe("room-a", "listener@example.test", ""), HttpStatus.FORBIDDEN);
        service.subscribe("room-a", "listener@example.test", "secret");
        service.subscribe("room-a", "Listener@example.test", "secret");
        service.subscribe("room-b", "other@example.test", "");

        MimeMessage confirmation = sent.remove();
        String confirmBody = (String) confirmation.getContent();
        String token = tokenFrom(confirmBody);
        assertThat(confirmBody).contains("192.0.2.1:8080/#roomSubscription=confirm&room=room-a");
        assertThat(sent.remove().getAllRecipients()[0].toString()).isEqualTo("other@example.test");
        assertThat(sent).isEmpty();

        assertStatus(() -> service.summon("room-a", ""), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.summon("room-a", "secret"), HttpStatus.CONFLICT);
        assertStatus(() -> service.updateSubscription("room-b", token, true), HttpStatus.GONE);
        service.updateSubscription("room-a", token, true);
        assertThat(service.subscribe("room-a", " Listener@example.test ", "secret"))
                .isEqualTo(RoomMailService.SubscribeResult.ALREADY_SUBSCRIBED);
        assertThat(sent).isEmpty();
        assertThat(service.summon("room-a", "secret")).isEqualTo(1);
        assertStatus(() -> service.summon("room-a", "secret"), HttpStatus.TOO_MANY_REQUESTS);
        MimeMessage invitation = sent.poll(2, TimeUnit.SECONDS);
        assertThat(invitation).isNotNull();
        assertThat(invitation.getAllRecipients()).hasSize(1);
        assertThat(invitation.getAllRecipients()[0].toString()).isEqualTo("listener@example.test");
        String inviteBody = (String) invitation.getContent();
        assertThat(inviteBody).contains("http://192.0.2.1:8080/?room=room-a",
                "输入收到这封邮件的邮箱，再点「退订」");

        assertStatus(() -> service.unsubscribeByEmail("room-a", "listener@example.test", ""), HttpStatus.FORBIDDEN);
        service.unsubscribeByEmail("room-a", " Listener@example.test ", "secret");
        assertStatus(() -> service.updateSubscription("room-a", token, true), HttpStatus.GONE);
        assertThat(service.subscribe("room-a", "listener@example.test", "secret"))
                .isEqualTo(RoomMailService.SubscribeResult.CONFIRMATION_REQUIRED);
        assertThat(tokenFrom((String) sent.remove().getContent())).isNotEqualTo(token);
    }

    @Test
    void defaultCooldownSurvivesRestartAndAppliesPerRoom() throws Exception {
        service.subscribe("room-a", "listener@example.test", "secret");
        service.updateSubscription("room-a", tokenFrom((String) sent.remove().getContent()), true);
        service.subscribe("room-b", "listener@example.test", "");
        service.updateSubscription("room-b", tokenFrom((String) sent.remove().getContent()), true);

        assertThat(service.summon("room-a", "secret")).isEqualTo(1);
        service.shutdown();
        service = createService();
        assertStatus(() -> service.summon("room-a", "secret"), HttpStatus.TOO_MANY_REQUESTS);
        assertThat(service.summon("room-b", "")).isEqualTo(1);
        store.saveLastSummon("room-a", System.currentTimeMillis() - Duration.ofHours(5).toMillis());
        assertStatus(() -> service.summon("room-a", "secret"), HttpStatus.TOO_MANY_REQUESTS);
        store.saveLastSummon("room-a", System.currentTimeMillis() - Duration.ofHours(6).toMillis());
        assertThat(service.summon("room-a", "secret")).isEqualTo(1);
    }

    @Test
    void cooldownSupportsZeroAndTwentyFourHours() throws Exception {
        service.subscribe("room-b", "listener@example.test", "");
        service.updateSubscription("room-b", tokenFrom((String) sent.remove().getContent()), true);
        store.saveLastSummon("room-b", System.currentTimeMillis() - Duration.ofHours(23).toMillis());
        service.shutdown();

        config.getRooms().setSummonCooldownHours(24);
        service = createService();
        assertStatus(() -> service.summon("room-b", ""), HttpStatus.TOO_MANY_REQUESTS);
        service.shutdown();

        config.getRooms().setSummonCooldownHours(0);
        service = createService();
        assertThat(service.summon("room-b", "")).isEqualTo(1);
        assertThat(service.summon("room-b", "")).isEqualTo(1);
    }

    @Test
    void roomOverrideImmediatelyTakesPrecedenceOverTheDefault() throws Exception {
        service.subscribe("room-b", "listener@example.test", "");
        service.updateSubscription("room-b", tokenFrom((String) sent.remove().getContent()), true);
        store.saveLastSummon("room-b", System.currentTimeMillis());

        Room room = rooms.get("room-b");
        assertStatus(() -> service.summon("room-b", ""), HttpStatus.TOO_MANY_REQUESTS);
        when(rooms.get("room-b")).thenReturn(room.withSummonCooldownHours(0));
        assertThat(service.summon("room-b", "")).isEqualTo(1);
        when(rooms.get("room-b")).thenReturn(room);
        assertStatus(() -> service.summon("room-b", ""), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void savedMailSettingsTakeEffectOnTheNextRequestAndKeepInheritedCredentials() throws Exception {
        RoomMailSettings settings = createSettings();
        service.shutdown();
        service = new RoomMailService(rooms, store, settings);
        var updated = settings.update(new RoomMailSettings.Update(null, null, null, "", "https://music.example.test/", 0));
        assertThat(updated.enabled()).isTrue();
        assertThat(updated.passwordConfigured()).isTrue();
        assertThat(store.loadMailSettings().smtpPassword()).isNull();

        service.subscribe("room-b", "listener@example.test", "");
        assertThat(sent.remove().getContent().toString()).contains("https://music.example.test/#roomSubscription");
        assertThat(createSettings().view()).isEqualTo(updated);
        assertThatThrownBy(() -> settings.update(new RoomMailSettings.Update("smtp.other.test", null, null, null,
                null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.view()).isEqualTo(updated);
    }

    @Test
    void failedConfirmationCanBeRetriedImmediately() {
        doThrow(new MailAuthenticationException("Test failure"))
                .doAnswer(call -> { sent.add(call.getArgument(0)); return null; })
                .when(sender).send(any(MimeMessage.class));

        assertStatus(() -> service.subscribe("room-b", "listener@example.test", ""), HttpStatus.BAD_GATEWAY);
        service.subscribe("room-b", "listener@example.test", "");
        assertThat(sent).hasSize(1);
    }

    private static String tokenFrom(String message) {
        var match = Pattern.compile("token=([0-9a-f-]{36})").matcher(message);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private static void assertStatus(Runnable request, HttpStatus status) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
