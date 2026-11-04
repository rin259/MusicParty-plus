package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.dto.RoomCreateRequest;
import org.thornex.musicparty.dto.RoomSummary;
import org.thornex.musicparty.dto.RoomUpdateRequest;
import org.thornex.musicparty.dto.RoomVerifyRequest;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.room.Room;
import org.thornex.musicparty.room.RoomRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 房间入口 API。
 *
 * <p>身份 = 设备凭证（X-Device-Id，浏览器 localStorage 里的 mp_user_token）。
 * 创建房间必须带 X-User-Name（游客没有名字，必须先起名）。
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomRegistry roomRegistry;

    public RoomController(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    private RoomSummary summary(Room room, String deviceId) {
        return new RoomSummary(
                room.id(),
                room.name(),
                roomRegistry.hasPassword(room.id()),
                roomRegistry.onlineCount(room.id()),
                room.ownedBy(deviceId),
                room.isMain()
        );
    }

    /** 我的房间（按设备）+ 默认房。 */
    @GetMapping("/mine")
    public List<RoomSummary> mine(@RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        List<RoomSummary> out = new ArrayList<>();
        Room main = roomRegistry.get(Room.MAIN_ROOM_ID);
        if (main != null) out.add(summary(main, deviceId));
        roomRegistry.listOwned(deviceId).stream()
                .filter(room -> !room.isMain())
                .map(room -> summary(room, deviceId))
                .forEach(out::add);
        out.sort(Comparator.comparing(RoomSummary::mainRoom).reversed()
                .thenComparing(RoomSummary::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomSummary> info(@PathVariable String id,
                                            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        Room room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary(room, deviceId));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                    @RequestHeader(value = "X-User-Name", required = false) String userName,
                                    @RequestBody RoomCreateRequest request) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ApiRequestException("设备凭证缺失，请刷新页面后重试");
        }
        if (userName == null || userName.isBlank() || isGuestName(userName)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", "NAME_REQUIRED", "message", "创建房间前请先给自己取一个名字"));
        }
        String roomName = request.name() == null ? "" : request.name().trim();
        if (roomName.isEmpty()) roomName = userName.trim() + " 的房间";
        if (roomName.length() > 30) roomName = roomName.substring(0, 30);
        String password = normalizePassword(request.password());

        Room room = roomRegistry.create(roomName, deviceId, password);
        return ResponseEntity.ok(summary(room, deviceId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                    @RequestBody RoomUpdateRequest request) {
        String name = request.name() == null ? null : request.name().trim();
        if (name != null) {
            if (name.isEmpty()) throw new ApiRequestException("房间名不能为空");
            if (name.length() > 30) name = name.substring(0, 30);
            roomRegistry.rename(id, deviceId, name);
        }
        if (Boolean.TRUE.equals(request.clearPassword())) {
            roomRegistry.setPassword(id, deviceId, null);
        } else if (request.password() != null) {
            roomRegistry.setPassword(id, deviceId, normalizePassword(request.password()));
        }
        Room room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary(room, deviceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        roomRegistry.delete(id, deviceId);
        return ResponseEntity.ok(Map.of("message", "房间已关闭"));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable String id, @RequestBody RoomVerifyRequest request) {
        Room room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        if (roomRegistry.verifyPassword(id, request.password())) {
            return ResponseEntity.ok(Map.of("valid", true));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("valid", false));
    }

    private String normalizePassword(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.length() < 4) throw new ApiRequestException("房间密码至少 4 位");
        if (raw.length() > 64) throw new ApiRequestException("房间密码过长");
        return raw;
    }

    private boolean isGuestName(String name) {
        String value = name.trim().toLowerCase();
        return value.startsWith("guest") || name.trim().startsWith("游客");
    }
}
