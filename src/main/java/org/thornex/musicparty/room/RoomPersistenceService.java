package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 房间元数据与运行时快照的落盘。
 *
 * <p>写文件一律 tmp + rename（原子替换），避免进程被杀时留下半个 JSON。
 * 默认房 main 会优先读旧版 queue-data.json，保证升级不丢队列。
 */
@Service
@Slf4j
public class RoomPersistenceService {

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public RoomPersistenceService(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public File dataDir() {
        File queueFile = new File(appProperties.getQueue().getPersistenceFile());
        File parent = queueFile.getParentFile();
        return parent == null ? new File("data") : parent;
    }

    private File indexFile() {
        return new File(new File(dataDir(), "rooms"), "index.json");
    }

    private File snapshotFile(String roomId) {
        return new File(new File(dataDir(), "rooms"), roomId + ".json");
    }

    public synchronized List<Room> loadRooms() {
        File file = indexFile();
        if (!file.exists()) return new ArrayList<>();
        try {
            Room[] rooms = objectMapper.readValue(file, Room[].class);
            List<Room> out = new ArrayList<>();
            Collections.addAll(out, rooms);
            return out;
        } catch (IOException error) {
            log.error("Failed to read room index from {}", file.getAbsolutePath(), error);
            return new ArrayList<>();
        }
    }

    public synchronized void saveRooms(List<Room> rooms) {
        try {
            writeAtomically(indexFile(), objectMapper.writeValueAsBytes(rooms));
        } catch (IOException error) {
            log.error("Failed to write room index", error);
        }
    }

    public synchronized RuntimeSnapshot loadSnapshot(String roomId) {
        File file = snapshotFile(roomId);
        if (!file.exists() && Room.MAIN_ROOM_ID.equals(roomId)) {
            file = new File(appProperties.getQueue().getPersistenceFile());   // 旧版单房间数据
        }
        if (!file.exists()) return null;
        try {
            RuntimeSnapshot snapshot = objectMapper.readValue(file, RuntimeSnapshot.class);
            log.info("Restored room {} state from {}", roomId, file.getAbsolutePath());
            return snapshot;
        } catch (IOException error) {
            log.error("Failed to read room snapshot {}", file.getAbsolutePath(), error);
            return null;
        }
    }

    public synchronized void saveSnapshot(String roomId, RoomRuntime runtime) {
        RuntimeSnapshot snapshot = new RuntimeSnapshot();
        snapshot.setQueue(new ArrayList<>(runtime.queue().getQueueSnapshot()));
        snapshot.setHistory(new ArrayList<>(runtime.queue().getHistorySnapshot()));
        snapshot.setChatHistory(new ArrayList<>(runtime.chat().getHistoryFull()));
        try {
            writeAtomically(snapshotFile(roomId), objectMapper.writeValueAsBytes(snapshot));
        } catch (IOException error) {
            log.error("Failed to save room snapshot {}", roomId, error);
        }
    }

    public synchronized void deleteSnapshot(String roomId) {
        try {
            Files.deleteIfExists(snapshotFile(roomId).toPath());
        } catch (IOException error) {
            log.warn("Failed to delete room snapshot {}", roomId, error);
        }
    }

    private File subscriptionFile(String roomId) {
        return new File(new File(dataDir(), "room-subscriptions"), roomId + ".json");
    }

    private File summonFile(String roomId) {
        return new File(new File(dataDir(), "room-subscriptions"), roomId + ".summon.json");
    }

    synchronized long loadLastSummon(String roomId) throws IOException {
        File file = summonFile(roomId);
        return file.exists() ? objectMapper.readValue(file, Long.TYPE) : 0;
    }

    synchronized void saveLastSummon(String roomId, long timestamp) throws IOException {
        writeAtomically(summonFile(roomId), objectMapper.writeValueAsBytes(timestamp));
    }

    synchronized RoomMailSettings.Update loadMailSettings() throws IOException {
        File file = new File(dataDir(), "room-mail-settings.json");
        return file.exists() ? objectMapper.readValue(file, RoomMailSettings.Update.class) : null;
    }

    synchronized void saveMailSettings(RoomMailSettings.Update overrides) throws IOException {
        writeAtomically(new File(dataDir(), "room-mail-settings.json"), objectMapper.writeValueAsBytes(overrides));
    }

    synchronized List<EmailSubscription> loadSubscriptions(String roomId) throws IOException {
        File file = subscriptionFile(roomId);
        if (!file.exists()) return new ArrayList<>();
        return new ArrayList<>(List.of(objectMapper.readValue(file, EmailSubscription[].class)));
    }

    synchronized void saveSubscriptions(String roomId, List<EmailSubscription> subscriptions) throws IOException {
        if (subscriptions.isEmpty()) {
            Files.deleteIfExists(subscriptionFile(roomId).toPath());
        } else {
            writeAtomically(subscriptionFile(roomId), objectMapper.writeValueAsBytes(subscriptions));
        }
    }

    synchronized void deleteSubscriptions(String roomId) {
        try {
            Files.deleteIfExists(subscriptionFile(roomId).toPath());
            Files.deleteIfExists(summonFile(roomId).toPath());
        } catch (IOException error) {
            log.warn("Failed to delete email subscriptions for room {}", roomId, error);
        }
    }

    record EmailSubscription(String email, String token, long requestedAt, boolean confirmed) {
        EmailSubscription confirm() {
            return new EmailSubscription(email, token, requestedAt, true);
        }
    }

    private void writeAtomically(File target, byte[] bytes) throws IOException {
        Path destination = target.toPath().toAbsolutePath();
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getName() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Data
    public static class RuntimeSnapshot {
        private List<MusicQueueItem> queue;
        private List<Music> history;
        private List<ChatMessage> chatHistory;
    }
}
