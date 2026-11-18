package org.thornex.musicparty.room;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.exception.ApiRequestException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间注册表：元数据 + 运行时对象图。
 *
 * <p>房间是懒加载的：只有真正有人连接/写入时才创建 runtime；空闲超时后卸载，
 * 快照仍在磁盘上，下次进入自动恢复。默认房 main 永远保留。
 */
@Service
@Slf4j
public class RoomRegistry {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final RoomFactory roomFactory;
    private final RoomPersistenceService persistence;
    private final AppProperties appProperties;
    private final AuthController authController;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, RoomRuntime> runtimes = new ConcurrentHashMap<>();

    public RoomRegistry(RoomFactory roomFactory, RoomPersistenceService persistence,
                        AppProperties appProperties, AuthController authController) {
        this.roomFactory = roomFactory;
        this.persistence = persistence;
        this.appProperties = appProperties;
        this.authController = authController;
    }

    @PostConstruct
    public void init() {
        for (Room room : persistence.loadRooms()) {
            if (room != null && room.id() != null) rooms.put(room.id(), room);
        }
        if (!rooms.containsKey(Room.MAIN_ROOM_ID)) {
            long now = System.currentTimeMillis();
            rooms.put(Room.MAIN_ROOM_ID, new Room(Room.MAIN_ROOM_ID, "主房间", null, null,
                    Room.VISIBILITY_PUBLIC, now, now));
        }
        persistence.saveRooms(metadataSnapshot());
        log.info("Room registry ready: {} room(s)", rooms.size());
    }

    /**
     * 延迟到应用就绪后再创建默认房运行时：SimpMessagingTemplate 与 WebSocket 配置
     * 之间存在 bean 创建顺序上的环，这里错开即可（@Lazy 只解决注入，不解决 @PostConstruct 时机）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpMainRoom() {
        getOrCreateRuntime(Room.MAIN_ROOM_ID);
        log.info("Main room runtime loaded");
    }

    /** 房间元数据；不存在返回 null。 */
    public Room get(String roomId) {
        if (roomId == null || roomId.isBlank()) return null;
        return rooms.get(roomId);
    }

    public Room require(String roomId) {
        Room room = get(roomId);
        if (room == null) throw new ApiRequestException("房间不存在或已关闭");
        return room;
    }

    /** 所有房间（含密码房；main 由调用方决定是否排除）。 */
    public List<Room> listAll() {
        return new ArrayList<>(rooms.values());
    }

    public List<Room> listOwned(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return List.of();
        return rooms.values().stream()
                .filter(room -> room.ownedBy(deviceId))
                .sorted(Comparator.comparingLong(Room::lastActiveAt).reversed())
                .toList();
    }

    public synchronized Room create(String name, String ownerDeviceId, String rawPassword, String visibility) {
        if (ownerDeviceId == null || ownerDeviceId.isBlank()) {
            throw new ApiRequestException("设备凭证缺失，请刷新页面后重试");
        }
        long owned = rooms.values().stream().filter(room -> room.ownedBy(ownerDeviceId)).count();
        if (owned >= appProperties.getRooms().getMaxRoomsPerDevice()) {
            throw new ApiRequestException("你创建的房间数量已达上限");
        }
        if (rooms.size() >= appProperties.getRooms().getMaxRooms()) {
            throw new ApiRequestException("服务器房间数量已达上限，请稍后再试");
        }
        String id = generateRoomId();
        long now = System.currentTimeMillis();
        Room room = new Room(id, name, ownerDeviceId, PasswordHasher.hash(rawPassword),
                normalizeVisibility(visibility), now, now);
        rooms.put(id, room);
        persistence.saveRooms(metadataSnapshot());
        log.info("Room created: {} ({}) by device {}", id, name, ownerDeviceId);
        return room;
    }

    public synchronized Room rename(String roomId, String deviceId, String newName) {
        Room room = requireOwned(roomId, deviceId);
        Room updated = room.withName(newName).touched(System.currentTimeMillis());
        rooms.put(roomId, updated);
        persistence.saveRooms(metadataSnapshot());
        return updated;
    }

    public synchronized Room setVisibility(String roomId, String deviceId, String visibility) {
        Room room = requireOwned(roomId, deviceId);
        Room updated = room.withVisibility(normalizeVisibility(visibility)).touched(System.currentTimeMillis());
        rooms.put(roomId, updated);
        persistence.saveRooms(metadataSnapshot());
        return updated;
    }

    public synchronized void setSummonCooldownHours(String roomId, String deviceId, Integer hours) {
        Room room = requireOwned(roomId, deviceId);
        if (Objects.equals(room.summonCooldownHours(), hours)) return;
        rooms.put(roomId, room.withSummonCooldownHours(hours).touched(System.currentTimeMillis()));
        persistence.saveRooms(metadataSnapshot());
    }

    private static String normalizeVisibility(String visibility) {
        return Room.VISIBILITY_UNLISTED.equalsIgnoreCase(visibility)
                ? Room.VISIBILITY_UNLISTED
                : Room.VISIBILITY_PUBLIC;
    }

    public synchronized Room setPassword(String roomId, String deviceId, String rawPassword) {
        Room room = requireOwned(roomId, deviceId);
        Room updated = room.withPasswordHash(PasswordHasher.hash(rawPassword));
        rooms.put(roomId, updated);
        persistence.saveRooms(metadataSnapshot());
        if (updated.isMain()) {
            // 旧版管理端仍通过 AuthController 维护主房密码，保持两边一致
            authController.forceSetPassword(rawPassword == null ? "" : rawPassword);
        }
        return updated;
    }

    /** 管理端设置主房密码时同步房间元数据。 */
    public synchronized void syncMainPasswordFromAdmin(String rawPassword) {
        Room room = rooms.get(Room.MAIN_ROOM_ID);
        if (room == null) return;
        rooms.put(Room.MAIN_ROOM_ID, room.withPasswordHash(PasswordHasher.hash(rawPassword)));
        persistence.saveRooms(metadataSnapshot());
    }

    public synchronized void delete(String roomId, String deviceId) {
        if (Room.MAIN_ROOM_ID.equals(roomId)) {
            throw new ApiRequestException("默认房间不能删除");
        }
        requireOwned(roomId, deviceId);
        RoomRuntime runtime = runtimes.remove(roomId);
        if (runtime != null) {
            persistence.saveSnapshot(roomId, runtime);
            runtime.shutdown();
        }
        persistence.deleteSnapshot(roomId);
        rooms.remove(roomId);
        persistence.deleteSubscriptions(roomId);
        persistence.saveRooms(metadataSnapshot());
        log.info("Room deleted: {}", roomId);
    }

    private Room requireOwned(String roomId, String deviceId) {
        Room room = require(roomId);
        if (!room.ownedBy(deviceId)) {
            throw new ApiRequestException("只有房主可以修改这个房间");
        }
        return room;
    }

    public boolean hasPassword(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) return false;
        if (room.hasPassword()) return true;
        if (!room.isMain()) return false;
        String legacy = authController.getRawPassword();
        return legacy != null && !legacy.isEmpty();
    }

    public boolean verifyPassword(String roomId, String rawPassword) {
        Room room = rooms.get(roomId);
        if (room == null) return false;
        if (room.hasPassword()) {
            return PasswordHasher.matches(rawPassword, room.passwordHash());
        }
        if (room.isMain()) {
            String legacy = authController.getRawPassword();
            if (legacy == null || legacy.isEmpty()) return true;
            return Objects.equals(legacy, rawPassword);
        }
        return true;
    }

    public int onlineCount(String roomId) {
        RoomRuntime runtime = runtimes.get(roomId);
        return runtime == null ? 0 : runtime.users().getOnlineUserSummaries().size();
    }

    public List<RoomRuntime> runtimes() {
        return new ArrayList<>(runtimes.values());
    }

    public RoomRuntime runtimeQuietly(String roomId) {
        return roomId == null ? null : runtimes.get(roomId);
    }

    public RoomRuntime getOrCreateRuntime(String roomId) {
        RoomRuntime existing = runtimes.get(roomId);
        if (existing != null) return existing;
        synchronized (this) {
            existing = runtimes.get(roomId);
            if (existing != null) return existing;
            Room room = rooms.get(roomId);
            if (room == null) throw new ApiRequestException("房间不存在或已关闭");
            RoomRuntime runtime = roomFactory.create(roomId);
            RoomPersistenceService.RuntimeSnapshot snapshot = persistence.loadSnapshot(roomId);
            if (snapshot != null) {
                if (snapshot.getQueue() != null) {
                    runtime.queue().restore(snapshot.getQueue(),
                            snapshot.getHistory() == null ? List.of() : snapshot.getHistory());
                }
                if (snapshot.getChatHistory() != null) {
                    runtime.chat().restore(snapshot.getChatHistory());
                }
            }
            runtimes.put(roomId, runtime);
            return runtime;
        }
    }

    public RoomRuntime mainRuntime() {
        return getOrCreateRuntime(Room.MAIN_ROOM_ID);
    }

    public RoomRuntime mainRuntimeQuietly() {
        return runtimes.get(Room.MAIN_ROOM_ID);
    }

    /** 每 10 分钟：清理空闲播放器/过期用户，卸载长期无人房间。 */
    public void housekeeping() {
        long now = System.currentTimeMillis();
        long idleMs = appProperties.getRooms().getIdleUnloadMinutes() * 60_000L;
        for (Map.Entry<String, RoomRuntime> entry : runtimes.entrySet()) {
            String roomId = entry.getKey();
            RoomRuntime runtime = entry.getValue();
            try {
                runtime.player().cleanupIdlePlayer();
                runtime.users().cleanupExpiredUsers();
            } catch (RuntimeException error) {
                log.warn("Room {} housekeeping failed: {}", roomId, error.toString());
            }
            boolean empty = runtime.users().getOnlineUserSummaries().isEmpty();
            if (!Room.MAIN_ROOM_ID.equals(roomId) && empty && now - runtime.lastActiveAt() > idleMs) {
                persistence.saveSnapshot(roomId, runtime);
                runtime.shutdown();
                runtimes.remove(roomId, runtime);
                touchRoom(roomId, now);
                log.info("Room {} unloaded after {} minutes idle", roomId, idleMs / 60_000);
            }
        }
        saveAll();
    }

    public void saveAll() {
        synchronized (this) {
            persistence.saveRooms(metadataSnapshot());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RoomRuntime> entry : runtimes.entrySet()) {
            try {
                persistence.saveSnapshot(entry.getKey(), entry.getValue());
                if (!entry.getValue().users().getOnlineUserSummaries().isEmpty()) {
                    touchRoom(entry.getKey(), now);
                }
            } catch (RuntimeException error) {
                log.warn("Failed to persist room {}: {}", entry.getKey(), error.toString());
            }
        }
    }

    private synchronized void touchRoom(String roomId, long timestamp) {
        rooms.computeIfPresent(roomId, (id, room) -> room.touched(timestamp));
    }

    private List<Room> metadataSnapshot() {
        return rooms.values().stream()
                .sorted(Comparator.comparing(Room::id))
                .toList();
    }

    private String generateRoomId() {
        int length = Math.max(6, appProperties.getRooms().getCodeLength());
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder builder = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = builder.toString();
            if (!rooms.containsKey(code)) return code;
        }
        throw new ApiRequestException("房间码生成失败，请重试");
    }

    @PreDestroy
    public void shutdown() {
        saveAll();
        runtimes.values().forEach(RoomRuntime::shutdown);
        runtimes.clear();
    }
}
