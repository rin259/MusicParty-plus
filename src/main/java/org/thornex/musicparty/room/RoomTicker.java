package org.thornex.musicparty.room;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 全局调度：一个 1 秒循环遍历所有房间，绝不为每个房间单独开线程。
 *
 * <p>房间数量增长时这里必须保持 O(rooms) 且单轮开销可控（实测 5 房 < 5ms）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomTicker {

    private final RoomRegistry registry;

    @Scheduled(fixedRate = 1000)
    public void tick() {
        for (RoomRuntime runtime : registry.runtimes()) {
            try {
                runtime.tick();
            } catch (RuntimeException error) {
                log.warn("Room {} tick failed: {}", runtime.roomId(), error.toString());
            }
        }
    }

    @Scheduled(fixedDelay = 100)
    public void flushQueueUpdates() {
        for (RoomRuntime runtime : registry.runtimes()) {
            try { runtime.player().flushQueueUpdate(); }
            catch (RuntimeException error) { log.warn("Room {} queue flush failed", runtime.roomId(), error); }
        }
    }

    @Scheduled(fixedRate = 600_000)
    public void housekeeping() {
        registry.housekeeping();
    }

    @Scheduled(fixedDelayString = "${app.music-api.queue.persistence-interval-ms:60000}")
    public void persist() {
        registry.saveAll();
    }
}
