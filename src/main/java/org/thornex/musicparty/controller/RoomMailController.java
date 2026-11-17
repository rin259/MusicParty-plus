package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.room.RoomMailService;
import org.thornex.musicparty.room.RoomRegistry;

import java.util.Map;

@RestController
@RequestMapping("/api/rooms/{id}/mail")
public class RoomMailController {
    private final RoomRegistry rooms;
    private final RoomMailService mail;

    public RoomMailController(RoomRegistry rooms, RoomMailService mail) {
        this.rooms = rooms;
        this.mail = mail;
    }

    @GetMapping
    public ResponseEntity<Map<String, Boolean>> status(@PathVariable String id) {
        if (rooms.get(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在或已关闭");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("enabled", mail.enabled()));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@PathVariable String id, @RequestBody SubscribeRequest request) {
        return ResponseEntity.ok(Map.of("status", mail.subscribe(id, request.email(), request.password())));
    }

    @DeleteMapping("/subscription")
    public ResponseEntity<?> unsubscribeByEmail(@PathVariable String id, @RequestBody SubscribeRequest request) {
        mail.unsubscribeByEmail(id, request.email(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@PathVariable String id, @RequestBody TokenRequest request) {
        mail.updateSubscription(id, request.token(), true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<?> unsubscribe(@PathVariable String id, @RequestBody TokenRequest request) {
        mail.updateSubscription(id, request.token(), false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/summon")
    public ResponseEntity<?> summon(
            @PathVariable String id,
            @RequestBody(required = false) SummonRequest request) {
        String password = request == null ? null : request.password();
        return ResponseEntity.accepted().body(Map.of("queued", mail.summon(id, password)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    record SubscribeRequest(String email, String password) {}
    record TokenRequest(String token) {}
    record SummonRequest(String password) {}
}
