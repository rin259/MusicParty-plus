package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.event.RuntimeConfigChangedEvent;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeConfigService {
    private static final String FILE_NAME = "runtime-config.json";

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final AuthController authController;
    private final MusicPlayerService musicPlayerService;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;
    private final LiveStreamService liveStreamService;

    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void restoreAtStartup() {
        RuntimeConfig config = read();
        if (config != null) {
            apply(config);
            log.info("Runtime configuration restored from {}", getFile().getAbsolutePath());
        }
        musicPlayerService.prefetchRestoredQueue();
        ready = true;
        save();
    }

    @EventListener
    public void onRuntimeConfigChanged(RuntimeConfigChangedEvent ignored) {
        save();
    }

    public synchronized void save() {
        if (!ready) {
            return;
        }

        File target = getFile();
        File parent = target.getParentFile();
        try {
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File temporary = new File(target.getAbsolutePath() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary, snapshot());
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            restrictPermissions(target);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                File temporary = new File(target.getAbsolutePath() + ".tmp");
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                restrictPermissions(target);
            } catch (IOException moveError) {
                log.error("Failed to persist runtime configuration", moveError);
            }
        } catch (IOException e) {
            log.error("Failed to persist runtime configuration", e);
        }
    }

    private void restrictPermissions(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(),
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file systems do not expose Unix permission bits.
        }
    }

    private RuntimeConfig read() {
        File file = getFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, RuntimeConfig.class);
        } catch (IOException e) {
            log.error("Failed to read runtime configuration from {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    private RuntimeConfig snapshot() {
        RuntimeConfig config = new RuntimeConfig();
        config.setNeteaseCookie(neteaseMusicApiService.getCurrentCookie());
        config.setBilibiliSessdata(bilibiliMusicApiService.getSessdata());
        config.setRoomInitialized(authController.getRawPassword() != null);
        config.setRoomPassword(authController.getRawPassword());
        config.setStreamEnabled(liveStreamService.isEnabled());
        config.setPlayer(musicPlayerService.getRuntimeSettings());

        config.setQueueMaxSize(appProperties.getQueue().getMaxSize());
        config.setQueueHistorySize(appProperties.getQueue().getHistorySize());
        config.setQueueMaxUserSongs(appProperties.getQueue().getMaxUserSongs());
        config.setPlaylistImportSize(appProperties.getPlayer().getMaxPlaylistImportSize());
        config.setChatHistorySize(appProperties.getChat().getMaxHistorySize());
        config.setChatMinIntervalMs(appProperties.getChat().getMinIntervalMs());
        config.setChatMaxMessageLength(appProperties.getChat().getMaxMessageLength());
        config.setNeteaseEnabled(appProperties.getNetease().isEnabled());
        config.setBilibiliEnabled(appProperties.getBilibili().isEnabled());
        return config;
    }

    private void apply(RuntimeConfig config) {
        applyIfPresent(config.getQueueMaxSize(), appProperties.getQueue()::setMaxSize);
        applyIfPresent(config.getQueueHistorySize(), appProperties.getQueue()::setHistorySize);
        applyIfPresent(config.getQueueMaxUserSongs(), appProperties.getQueue()::setMaxUserSongs);
        applyIfPresent(config.getPlaylistImportSize(), appProperties.getPlayer()::setMaxPlaylistImportSize);
        applyIfPresent(config.getChatHistorySize(), appProperties.getChat()::setMaxHistorySize);
        applyIfPresent(config.getChatMinIntervalMs(), appProperties.getChat()::setMinIntervalMs);
        applyIfPresent(config.getChatMaxMessageLength(), appProperties.getChat()::setMaxMessageLength);
        applyIfPresent(config.getNeteaseEnabled(), appProperties.getNetease()::setEnabled);
        applyIfPresent(config.getBilibiliEnabled(), appProperties.getBilibili()::setEnabled);

        if (config.getNeteaseCookie() != null) {
            neteaseMusicApiService.updateCookie(config.getNeteaseCookie());
        }
        if (config.getBilibiliSessdata() != null) {
            bilibiliMusicApiService.updateSessdata(config.getBilibiliSessdata());
        }
        if (config.getRoomInitialized() != null) {
            authController.restoreRoomPassword(config.getRoomInitialized(), config.getRoomPassword());
        }
        if (config.getStreamEnabled() != null) {
            liveStreamService.setEnabled(config.getStreamEnabled());
        }
        musicPlayerService.restoreRuntimeSettings(config.getPlayer());
    }

    private File getFile() {
        File queueFile = new File(appProperties.getQueue().getPersistenceFile());
        File parent = queueFile.getParentFile();
        return new File(parent == null ? new File("data") : parent, FILE_NAME);
    }

    private <T> void applyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    @Data
    public static class RuntimeConfig {
        private String neteaseCookie;
        private String bilibiliSessdata;
        private Boolean roomInitialized;
        private String roomPassword;
        private Boolean streamEnabled;
        private MusicPlayerService.RuntimeSettings player;
        private Integer queueMaxSize;
        private Integer queueHistorySize;
        private Integer queueMaxUserSongs;
        private Integer playlistImportSize;
        private Integer chatHistorySize;
        private Long chatMinIntervalMs;
        private Integer chatMaxMessageLength;
        private Boolean neteaseEnabled;
        private Boolean bilibiliEnabled;
    }
}
