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
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
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

    private void writeAtomically(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        Path temporary = new File(target.getAbsolutePath() + ".tmp").toPath();
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(target.toPath(),
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统（Windows 本地开发）忽略
        }
    }

    @Data
    public static class RuntimeSnapshot {
        private List<MusicQueueItem> queue;
        private List<Music> history;
        private List<ChatMessage> chatHistory;
    }
}
