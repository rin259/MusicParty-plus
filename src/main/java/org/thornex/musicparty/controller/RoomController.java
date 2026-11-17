package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.RoomCreateRequest;
import org.thornex.musicparty.dto.RoomSummary;
import org.thornex.musicparty.dto.RoomUpdateRequest;
import org.thornex.musicparty.dto.RoomVerifyRequest;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.room.Room;
import org.thornex.musicparty.room.RoomMailSettings;
import org.thornex.musicparty.room.RoomRegistry;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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
    private final SimpMessagingTemplate messagingTemplate;
    private final RoomMailSettings roomMailSettings;

    public RoomController(RoomRegistry roomRegistry, SimpMessagingTemplate messagingTemplate,
                          RoomMailSettings roomMailSettings) {
        this.roomRegistry = roomRegistry;
        this.messagingTemplate = messagingTemplate;
        this.roomMailSettings = roomMailSettings;
    }

    private RoomSummary summary(Room room, String deviceId) {
        return new RoomSummary(
                room.id(),
                room.name(),
                roomRegistry.hasPassword(room.id()),
                room.listed(),
                roomRegistry.onlineCount(room.id()),
                room.ownedBy(deviceId),
                room.isMain(),
                room.summonCooldownHours(),
                roomMailSettings.view().summonCooldownHours()
        );
    }

    /** 公共房间目录（不含默认房）：其他设备创建的房间也能被发现。 */
    @GetMapping
    public List<RoomSummary> list(@RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return roomRegistry.listAll().stream()
                .filter(room -> !room.isMain() && room.listed())
                .map(room -> summary(room, deviceId))
                .sorted(Comparator.comparingInt(RoomSummary::onlineCount).reversed()
                        .thenComparing(RoomSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
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
                                    @RequestHeader(value = "X-User-Name", required = false) String encodedUserName,
                                    @RequestBody RoomCreateRequest request) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ApiRequestException("设备凭证缺失，请刷新页面后重试");
        }
        // 前端对 header 做了 percent-encode（XHR 只接受 ISO-8859-1，中文直接塞会抛异常）
        String userName = decodeHeader(encodedUserName);
        if (userName == null || userName.isBlank() || isGuestName(userName)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", "NAME_REQUIRED", "message", "创建房间前请先给自己取一个名字"));
        }
        String roomName = request.name() == null ? "" : request.name().trim();
        if (roomName.isEmpty()) roomName = userName.trim() + " 的房间";
        if (roomName.length() > 30) roomName = roomName.substring(0, 30);
        String password = normalizePassword(request.password());

        Room room = roomRegistry.create(roomName, deviceId, password, request.visibility());
        return ResponseEntity.ok(summary(room, deviceId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                    @RequestBody RoomUpdateRequest request) {
        Room room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        if (!room.ownedBy(deviceId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "只有房主可以修改房间设置"));
        }

        JsonNode cooldown = request.summonCooldownHours();
        Integer cooldownHours = null;
        if (cooldown != null && !cooldown.isNull()) {
            if (!cooldown.isIntegralNumber() || !cooldown.canConvertToInt()
                    || !Room.isValidSummonCooldownHours(cooldown.intValue())) {
                return ResponseEntity.badRequest().body(Map.of("message", "召唤冷却须为 0 到 24 小时的整数"));
            }
            cooldownHours = cooldown.intValue();
        }

        String name = request.name() == null ? null : request.name().trim();
        if (name != null) {
            if (name.isEmpty()) throw new ApiRequestException("房间名不能为空");
            if (name.length() > 30) name = name.substring(0, 30);
            roomRegistry.rename(id, deviceId, name);
        }
        if (request.visibility() != null) {
            roomRegistry.setVisibility(id, deviceId, request.visibility());
        }
        if (Boolean.TRUE.equals(request.clearPassword())) {
            roomRegistry.setPassword(id, deviceId, null);
        } else if (request.password() != null) {
            roomRegistry.setPassword(id, deviceId, normalizePassword(request.password()));
        }
        if (cooldown != null) {
            roomRegistry.setSummonCooldownHours(id, deviceId, cooldownHours);
        }
        room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary(room, deviceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        Room room = roomRegistry.get(id);
        if (room == null) return ResponseEntity.notFound().build();
        if (room.isMain()) {
            return ResponseEntity.badRequest().body(Map.of("message", "默认房间不能删除"));
        }
        if (!room.ownedBy(deviceId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "只有房主可以关闭这个房间"));
        }

        // 先通知在线成员，再卸载运行时/删除元数据。前端收到后会回到房间选择页。
        messagingTemplate.convertAndSend("/topic/r/" + id + "/player/events",
                new PlayerEvent("WARN", "ROOM_CLOSED", "SYSTEM", "房间已被房主关闭", null));
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

    /**
     * 还原前端 percent-encode 过的 header 值。纯 ASCII 的名字解码后不变；
     * 老客户端直接发原始值（含孤立的 % 等）时解码会失败，此时按原样使用。
     */
    private static String decodeHeader(String value) {
        if (value == null || value.isEmpty()) return value;
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
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
