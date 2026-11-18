package org.thornex.musicparty.room;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.room.RoomPersistenceService.EmailSubscription;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RoomMailService {
    private static final long CONFIRM_TTL_MS = Duration.ofDays(1).toMillis();
    private static final long RESEND_DELAY_MS = Duration.ofMinutes(5).toMillis();
    private static final long CONFIRMATION_WINDOW_MS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_CONFIRMATIONS_PER_MINUTE = 10;
    private static final int MAX_SUBSCRIPTIONS = 50;

    private final RoomRegistry rooms;
    private final RoomPersistenceService persistence;
    private final RoomMailSettings settings;

    // Guarded by the registry lock, together with subscription changes and room deletion.
    private final ArrayDeque<Long> recentConfirmations = new ArrayDeque<>();
    private final ThreadPoolExecutor invitations = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            task -> new Thread(task, "room-invitation-mail"));

    public RoomMailService(RoomRegistry rooms, RoomPersistenceService persistence, RoomMailSettings settings) {
        this.rooms = rooms;
        this.persistence = persistence;
        this.settings = settings;
    }

    public boolean enabled() {
        return settings.view().enabled();
    }

    public enum SubscribeResult {
        CONFIRMATION_REQUIRED, ALREADY_SUBSCRIBED
    }

    public SubscribeResult subscribe(String roomId, String address, String roomPassword) {
        RoomMailSettings.Delivery delivery = requireMail();
        String email = validateEmail(address);
        Room room;
        EmailSubscription pending;
        synchronized (rooms) {
            room = requireRoomAccess(roomId, roomPassword);
            long now = System.currentTimeMillis();
            List<EmailSubscription> subscriptions = read(roomId);
            subscriptions.removeIf(subscription -> expired(subscription, now));
            EmailSubscription existing = subscriptions.stream()
                    .filter(subscription -> subscription.email().equalsIgnoreCase(email))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (existing.confirmed()) return SubscribeResult.ALREADY_SUBSCRIBED;
                if (now - existing.requestedAt() < RESEND_DELAY_MS) return SubscribeResult.CONFIRMATION_REQUIRED;
            }
            if (existing == null && subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "房间订阅名额已满");
            }
            while (!recentConfirmations.isEmpty() && now - recentConfirmations.peekFirst() >= CONFIRMATION_WINDOW_MS) {
                recentConfirmations.removeFirst();
            }
            if (recentConfirmations.size() >= MAX_CONFIRMATIONS_PER_MINUTE) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "确认邮件发送过于频繁，请稍后再试");
            }
            pending = new EmailSubscription(email,
                    existing == null ? UUID.randomUUID().toString() : existing.token(), now, false);
            subscriptions.remove(existing);
            subscriptions.add(pending);
            save(roomId, subscriptions);
            recentConfirmations.addLast(now);
        }

        String confirmLink = UriComponentsBuilder.fromUri(delivery.baseUrl())
                .fragment("roomSubscription=confirm&room=" + roomId + "&token=" + pending.token())
                .toUriString();
        try {
            send(delivery, pending.email(), "MusicParty 房间订阅确认", """
                    有人请求订阅「%s」的听歌邀请。

                    确认订阅：%s

                    链接在 24 小时内有效。如果不是你发起的，可以忽略此邮件。
                    """.formatted(room.name(), confirmLink));
        } catch (MailException | MessagingException | UnsupportedEncodingException error) {
            log.warn("Could not send subscription confirmation for room {} ({})",
                    roomId, error.getClass().getSimpleName());
            synchronized (rooms) {
                if (rooms.get(roomId) != null) {
                    List<EmailSubscription> subscriptions = read(roomId);
                    if (subscriptions.remove(pending)) save(roomId, subscriptions);
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "确认邮件发送失败，请稍后重试");
        }
        return SubscribeResult.CONFIRMATION_REQUIRED;
    }

    public void unsubscribeByEmail(String roomId, String address, String roomPassword) {
        String email = validateEmail(address);
        synchronized (rooms) {
            requireRoomAccess(roomId, roomPassword);
            List<EmailSubscription> subscriptions = read(roomId);
            if (subscriptions.removeIf(subscription -> subscription.confirmed()
                    && subscription.email().equalsIgnoreCase(email))) {
                save(roomId, subscriptions);
            }
        }
    }

    public void updateSubscription(String roomId, String token, boolean confirm) {
        synchronized (rooms) {
            requireRoom(roomId);
            if (token == null || token.length() != 36) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订阅链接无效");
            }
            List<EmailSubscription> subscriptions = read(roomId);
            if (!confirm) {
                if (subscriptions.removeIf(subscription -> subscription.token().equals(token))) {
                    save(roomId, subscriptions);
                }
                return;
            }
            for (int i = 0; i < subscriptions.size(); i++) {
                EmailSubscription subscription = subscriptions.get(i);
                if (!subscription.token().equals(token)) continue;
                if (expired(subscription, System.currentTimeMillis())) break;
                if (!subscription.confirmed()) {
                    subscriptions.set(i, subscription.confirm());
                    save(roomId, subscriptions);
                }
                return;
            }
            throw new ResponseStatusException(HttpStatus.GONE, "订阅链接已失效，请在房间内重新订阅");
        }
    }

    public int summon(String roomId, String roomPassword) {
        RoomMailSettings.Delivery delivery = requireMail();
        synchronized (rooms) {
            Room room = requireRoomAccess(roomId, roomPassword);
            long now = System.currentTimeMillis();
            checkSummonCooldown(room, now, delivery.summonCooldownHours());
            List<EmailSubscription> recipients = read(roomId).stream().filter(EmailSubscription::confirmed).toList();
            if (recipients.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "还没有确认订阅的邮箱");
            }
            if (invitations.isShutdown() || invitations.getQueue().remainingCapacity() == 0) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邀请邮件正在发送，请稍后再试");
            }
            try {
                persistence.saveLastSummon(roomId, now);
            } catch (IOException error) {
                log.warn("Failed to record summon for room {}", roomId);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "暂时无法记录召唤，请稍后重试");
            }
            invitations.execute(() -> sendInvitations(delivery, roomId, recipients));
            return recipients.size();
        }
    }

    private void checkSummonCooldown(Room room, long now, int defaultSummonCooldownHours) {
        int hours = room.summonCooldownHours() != null ? room.summonCooldownHours() : defaultSummonCooldownHours;
        if (hours == 0) return;
        String roomId = room.id();
        long lastSummon;
        try {
            lastSummon = persistence.loadLastSummon(roomId);
        } catch (IOException error) {
            log.warn("Failed to read summon cooldown for room {}", roomId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "暂时无法读取召唤记录，请稍后重试");
        }
        long remaining = Duration.ofHours(hours).toMillis() - (now - lastSummon);
        if (remaining > 0) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining - 1) + 1;
            String wait = minutes >= 60 ? (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟" : minutes + " 分钟";
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "这个房间刚发起过召唤，请在 " + wait + "后再试");
        }
    }

    private void sendInvitations(RoomMailSettings.Delivery delivery, String roomId, List<EmailSubscription> recipients) {
        int failed = 0;
        for (EmailSubscription recipient : recipients) {
            Room room;
            try {
                synchronized (rooms) {
                    room = rooms.get(roomId);
                    if (room == null) break;
                    if (!read(roomId).contains(recipient)) continue;
                }
                String inviteLink = UriComponentsBuilder.fromUri(delivery.baseUrl()).queryParam("room", roomId).toUriString();
                send(delivery, recipient.email(), "MusicParty 听歌邀请", """
                        「%s」发来了听歌邀请。

                        加入房间：%s

                        退订：进入房间后点「订阅」，输入收到这封邮件的邮箱，再点「退订」。
                        """.formatted(room.name(), inviteLink));
            } catch (MailException | MessagingException | UnsupportedEncodingException | ResponseStatusException error) {
                failed++;
            }
        }
        if (failed > 0) log.warn("Failed to send {} invitation(s) for room {}", failed, roomId);
    }

    private void send(RoomMailSettings.Delivery delivery, String email, String subject, String body)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessageHelper message = new MimeMessageHelper(delivery.sender().createMimeMessage(), "UTF-8");
        message.setFrom(delivery.from(), "MusicParty");
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        delivery.sender().send(message.getMimeMessage());
    }

    private static String validateEmail(String address) {
        String email = address == null ? "" : address.strip();
        if (!isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的邮箱地址");
        }
        return email;
    }

    static boolean isValidEmail(String email) {
        try {
            if (email.length() > 254 || email.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                return false;
            }
            InternetAddress parsed = new InternetAddress(email, true);
            parsed.validate();
            return email.equals(parsed.getAddress()) && !parsed.isGroup();
        } catch (AddressException error) {
            return false;
        }
    }

    private static boolean expired(EmailSubscription subscription, long now) {
        return !subscription.confirmed() && now - subscription.requestedAt() >= CONFIRM_TTL_MS;
    }

    private Room requireRoom(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在或已关闭");
        return room;
    }

    private Room requireRoomAccess(String roomId, String password) {
        Room room = requireRoom(roomId);
        if (!rooms.verifyPassword(roomId, password)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "房间密码已失效，请重新进入房间");
        }
        return room;
    }

    private RoomMailSettings.Delivery requireMail() {
        RoomMailSettings.Delivery delivery = settings.delivery();
        if (delivery.sender() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "这个站点尚未启用邮件通知");
        }
        return delivery;
    }

    private List<EmailSubscription> read(String roomId) {
        try {
            return persistence.loadSubscriptions(roomId);
        } catch (IOException error) {
            log.warn("Failed to read email subscriptions for room {}", roomId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "暂时无法读取房间订阅，请稍后重试");
        }
    }

    private void save(String roomId, List<EmailSubscription> subscriptions) {
        try {
            persistence.saveSubscriptions(roomId, subscriptions);
        } catch (IOException error) {
            log.warn("Failed to save email subscriptions for room {}", roomId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "暂时无法保存房间订阅，请稍后重试");
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (rooms) {
            invitations.shutdownNow();
        }
    }
}
