package org.thornex.musicparty.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.room.RoomMailSettings;
import org.thornex.musicparty.room.RoomRegistry;
import org.thornex.musicparty.service.RuntimeConfigService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RoomRegistry roomRegistry;
    private final String adminPassword;
    private final AuthController authController;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;
    private final LiveStreamService liveStreamService;
    private final RuntimeConfigService runtimeConfigService;
    private final org.thornex.musicparty.service.LocalCacheService localCacheService;
    private final RoomMailSettings roomMailSettings;

    public AdminController(RoomRegistry roomRegistry, AppProperties appProperties, AuthController authController, NeteaseMusicApiService neteaseMusicApiService, BilibiliMusicApiService bilibiliMusicApiService, LiveStreamService liveStreamService, RuntimeConfigService runtimeConfigService, org.thornex.musicparty.service.LocalCacheService localCacheService, RoomMailSettings roomMailSettings) {
        this.roomRegistry = roomRegistry;
        this.adminPassword = appProperties.getAdminPassword();
        this.authController = authController;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.bilibiliMusicApiService = bilibiliMusicApiService;
        this.liveStreamService = liveStreamService;
        this.runtimeConfigService = runtimeConfigService;
        this.localCacheService = localCacheService;
        this.roomMailSettings = roomMailSettings;
    }

    @GetMapping("/preparation")
    public ResponseEntity<?> preparation(@RequestHeader(value = "X-Admin-Password", required = false) String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(localCacheService.preparationStats());
    }

    @GetMapping("/config/mail")
    public ResponseEntity<?> getMailSettings(@RequestHeader(value = "X-Admin-Password", required = false) String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roomMailSettings.view());
    }

    @PatchMapping("/config/mail")
    public ResponseEntity<?> updateMailSettings(
            @RequestHeader(value = "X-Admin-Password", required = false) String password,
            @RequestBody RoomMailSettings.Update request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roomMailSettings.update(request));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IOException error) {
            return ResponseEntity.internalServerError().body(Map.of("message", "无法保存邮件设置，请检查服务端数据目录权限"));
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedAdminRequest() {
        return ResponseEntity.badRequest().body(Map.of("message", "配置格式无效"));
    }

    private boolean isValid(String password) {
        return password != null && adminPassword != null && adminPassword.equals(password);
    }

    /** 管理端操作默认作用于主房间；分房管理属于后续阶段。 */
    private org.thornex.musicparty.service.MusicPlayerService player() {
        return roomRegistry.mainRuntime().player();
    }

    private org.thornex.musicparty.service.ChatService chat() {
        return roomRegistry.mainRuntime().chat();
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody AdminVerifyRequest request) {
        if (isValid(request.password())) {
            return ResponseEntity.ok(Map.of("message", "VERIFIED"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
    }

    @PostMapping("/lock")
    public ResponseEntity<?> setLock(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminLockRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        
        String type = request.type().toUpperCase();
        if ("ALL".equalsIgnoreCase(type)) {
            player().setAllLocks(request.locked());
            return ResponseEntity.ok(Map.of("message", (request.locked() ? "已开启全频道操作锁定" : "已解除全频道操作锁定")));
        } else {
            player().setLock(type, request.locked());
            String desc = switch (type) {
                case "PAUSE" -> "暂停控制";
                case "SKIP" -> "切歌控制";
                case "SHUFFLE" -> "播放模式";
                default -> type;
            };
            return ResponseEntity.ok(Map.of("message", desc + (request.locked() ? "已锁定" : "已解锁")));
        }
    }

    @PostMapping("/player/action")
    public ResponseEntity<?> playerAction(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminPlayerActionRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String action = request.action().toUpperCase();
        String msg = switch (action) {
            case "PAUSE" -> {
                player().togglePause("SYSTEM");
                yield "播放状态已切换";
            }
            case "SKIP" -> {
                player().skipToNext("SYSTEM");
                yield "已强制跳过当前歌曲";
            }
            case "SHUFFLE" -> {
                player().cyclePlayMode("SYSTEM");
                yield "播放模式已切换";
            }
            case "TOGGLE_FAIR_SHUFFLE" -> {
                player().toggleFairShuffle("SYSTEM");
                yield "随机算法已切换";
            }
            case "TOGGLE_ALLOW_OFFLINE" -> {
                player().toggleAllowOfflineShuffle("SYSTEM");
                yield "离线成员过滤规则已更新";
            }
            default -> null;
        };

        if (msg == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/room/password")
    public ResponseEntity<?> setRoomPassword(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminRoomPasswordRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String newPwd = request.password() == null ? "" : request.password();
        authController.forceSetPassword(newPwd);
        roomRegistry.syncMainPasswordFromAdmin(newPwd);
        player().broadcastPasswordChanged();
        return ResponseEntity.ok(Map.of("message", newPwd.isEmpty() ? "房间已设为公开访问" : "房间访问密码已更新"));
    }

    @PostMapping("/room/clear")
    public ResponseEntity<?> clearData(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminClearRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String target = request.target().toUpperCase();
        if ("CHAT".equalsIgnoreCase(target)) {
            chat().clearHistoryAndNotify();
            return ResponseEntity.ok(Map.of("message", "聊天历史记录已清空"));
        } else if ("OFFLINE".equalsIgnoreCase(target)) {
            int count = player().clearOfflineSongs();
            return ResponseEntity.ok(Map.of("message", "已清理 " + count + " 首离线成员的点播歌曲"));
        } else {
            player().clearQueue();
            return ResponseEntity.ok(Map.of("message", "播放队列已全部重置"));
        }
    }

    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem(@RequestHeader("X-Admin-Password") String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        player().resetSystem();
        return ResponseEntity.ok(Map.of("message", "系统核心已完成全量重置并重启"));
    }

    @PostMapping("/config/cookie")
    public ResponseEntity<?> setCookie(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminCookieRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if ("netease".equalsIgnoreCase(request.platform())) {
            neteaseMusicApiService.updateCookie(request.value());
            runtimeConfigService.save();
            return ResponseEntity.ok(Map.of("message", "网易云音乐凭据已更新"));
        } else if ("bilibili".equalsIgnoreCase(request.platform())) {
            bilibiliMusicApiService.updateSessdata(request.value());
            runtimeConfigService.save();
            return ResponseEntity.ok(Map.of("message", "Bilibili SessData 已更新"));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/room/stream")
    public ResponseEntity<?> setStream(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminStreamRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        liveStreamService.setEnabled(request.enabled());
        runtimeConfigService.save();
        player().broadcastFullPlayerState();
        return ResponseEntity.ok(Map.of("message", request.enabled() ? "直播流同步服务已启动" : "直播流同步服务已停止"));
    }

    @PostMapping("/config/update")
    public ResponseEntity<?> updateConfig(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminConfigUpdateRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        roomRegistry.runtimes().forEach(runtime -> runtime.player().updateConfig(request));
        return ResponseEntity.ok(Map.of("message", "系统配置已刷新"));
    }

    // Keep compatibility for now or remove if sure
    @Deprecated
    @PostMapping("/command")
    public ResponseEntity<?> handleAdminCommand(@RequestBody AdminCommandRequest request) {
        if (!isValid(request.password())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
        }
        // ... (original implementation if still needed, but I'll remove it since we are refactoring)
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "This endpoint is deprecated. Use structured endpoints."));
    }
}
