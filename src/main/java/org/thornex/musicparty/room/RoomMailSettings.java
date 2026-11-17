package org.thornex.musicparty.room;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thornex.musicparty.config.AppProperties;

import java.io.IOException;
import java.net.URI;
import java.util.Properties;

@Service
@Slf4j
public class RoomMailSettings {
    private final RoomPersistenceService persistence;
    private final JavaMailSender startupSender;
    private final Properties smtpProperties = new Properties();
    private final Update defaults;
    private Update overrides = new Update(null, null, null, null, null, null);
    private volatile Active active;

    public RoomMailSettings(RoomPersistenceService persistence, ObjectProvider<JavaMailSender> sender,
                            AppProperties appProperties, Environment environment) {
        this.persistence = persistence;
        this.startupSender = sender.getIfAvailable();
        MailProperties smtp = Binder.get(environment).bind("spring.mail", MailProperties.class)
                .orElseGet(MailProperties::new);
        smtpProperties.putAll(smtp.getProperties());
        int cooldown = appProperties.getRooms().getSummonCooldownHours();
        if (!Room.isValidSummonCooldownHours(cooldown)) {
            throw new IllegalArgumentException("ROOM_SUMMON_COOLDOWN_HOURS must be between 0 and 24");
        }
        defaults = new Update(or(smtp.getHost(), ""), or(smtp.getPort(), 587), or(smtp.getUsername(), ""),
                or(smtp.getPassword(), ""), or(appProperties.getBaseUrl(), ""), cooldown);
        active = activate(overrides);
        try {
            Update saved = persistence.loadMailSettings();
            if (saved != null) {
                validate(merge(defaults, saved));
                active = activate(saved);
                overrides = saved;
            }
        } catch (IOException | IllegalArgumentException error) {
            log.warn("Unable to load saved room mail settings; using deployment defaults");
        }
    }

    public View view() {
        return active.view();
    }

    Delivery delivery() {
        return active.delivery();
    }

    public synchronized View update(Update request) throws IOException {
        Update next = merge(overrides, request);
        Update effective = merge(defaults, next);
        validate(effective);

        View current = view();
        boolean destinationChanged = !current.smtpHost().equalsIgnoreCase(effective.smtpHost())
                || current.smtpPort() != effective.smtpPort()
                || !current.smtpUsername().equals(effective.smtpUsername());
        if (destinationChanged && StringUtils.hasText(effective.smtpHost())
                && StringUtils.hasText(effective.smtpUsername()) && !StringUtils.hasText(request.smtpPassword())) {
            throw new IllegalArgumentException("更改 SMTP 服务器、端口或发件邮箱时，请同时填写对应的 SMTP 密码");
        }
        if (!next.equals(overrides)) {
            Active candidate = activate(next);
            persistence.saveMailSettings(next);
            overrides = next;
            active = candidate;
        }
        return view();
    }

    private Active activate(Update overrides) {
        Update settings = merge(defaults, overrides);
        URI baseUrl = parseBaseUrl(settings.baseUrl());
        boolean hasPassword = StringUtils.hasText(settings.smtpPassword());
        JavaMailSender sender = null;
        if (baseUrl != null && hasPassword && StringUtils.hasText(settings.smtpHost())
                && StringUtils.hasText(settings.smtpUsername())) {
            if (overrides.smtpHost() == null && overrides.smtpPort() == null
                    && overrides.smtpUsername() == null && overrides.smtpPassword() == null) {
                sender = startupSender;
            } else {
                JavaMailSenderImpl configured = new JavaMailSenderImpl();
                configured.setHost(settings.smtpHost());
                configured.setPort(settings.smtpPort());
                configured.setUsername(settings.smtpUsername());
                String password = settings.smtpPassword();
                if ("smtp.gmail.com".equalsIgnoreCase(settings.smtpHost())) password = password.replace(" ", "");
                configured.setPassword(password);
                configured.setJavaMailProperties(smtpProperties);
                sender = configured;
            }
        }
        View view = new View(settings.smtpHost(), settings.smtpPort(), settings.smtpUsername(), hasPassword,
                settings.baseUrl(), settings.summonCooldownHours(), sender != null);
        return new Active(view, new Delivery(sender, settings.smtpUsername(), baseUrl, settings.summonCooldownHours()));
    }

    private static void validate(Update settings) {
        String host = settings.smtpHost();
        if (!host.isEmpty() && !host.matches("[a-zA-Z0-9.-]{1,253}|[0-9a-fA-F:]{2,39}")) {
            throw new IllegalArgumentException("SMTP 服务器请填写主机名或 IP 地址");
        }
        if (settings.smtpPort() < 1 || settings.smtpPort() > 65535) {
            throw new IllegalArgumentException("SMTP 端口须在 1 到 65535 之间");
        }
        if (!settings.smtpUsername().isEmpty() && !RoomMailService.isValidEmail(settings.smtpUsername())) {
            throw new IllegalArgumentException("请输入完整有效的发件邮箱");
        }
        if (parseBaseUrl(settings.baseUrl()) == null) {
            throw new IllegalArgumentException("邮件链接地址须为不带查询参数或片段的 HTTP 或 HTTPS 网址");
        }
        if (!Room.isValidSummonCooldownHours(settings.summonCooldownHours())) {
            throw new IllegalArgumentException("默认召唤冷却须为 0 到 24 小时的整数");
        }
    }

    private static Update merge(Update current, Update requested) {
        return new Update(or(strip(requested.smtpHost()), current.smtpHost()),
                or(requested.smtpPort(), current.smtpPort()),
                or(strip(requested.smtpUsername()), current.smtpUsername()),
                StringUtils.hasText(requested.smtpPassword()) ? requested.smtpPassword() : current.smtpPassword(),
                or(strip(requested.baseUrl()), current.baseUrl()),
                or(requested.summonCooldownHours(), current.summonCooldownHours()));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static URI parseBaseUrl(String url) {
        try {
            URI parsed = URI.create(url);
            if (parsed.getHost() != null && parsed.getUserInfo() == null && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null
                    && ("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URLs are reported by validate().
        }
        return null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Update(String smtpHost, Integer smtpPort, String smtpUsername, String smtpPassword,
                         String baseUrl, Integer summonCooldownHours) {}

    public record View(String smtpHost, int smtpPort, String smtpUsername, boolean passwordConfigured,
                       String baseUrl, int summonCooldownHours, boolean enabled) {}

    record Delivery(JavaMailSender sender, String from, URI baseUrl, int summonCooldownHours) {}

    private record Active(View view, Delivery delivery) {}
}
