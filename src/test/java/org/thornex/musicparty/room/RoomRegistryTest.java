package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.MusicQueueManager;
import org.thornex.musicparty.service.UserService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 房间元数据/所有权/密码的不变量。运行时不联网、不落盘（persistence 全部 mock）。
 */
class RoomRegistryTest {

    private RoomRegistry registry() {
        RoomFactory factory = mock(RoomFactory.class);
        when(factory.create(anyString())).thenAnswer(invocation -> new RoomRuntime(
                invocation.getArgument(0),
                mock(MusicPlayerService.class),
                mock(MusicQueueManager.class),
                mock(UserService.class),
                mock(ChatService.class)));

        RoomPersistenceService persistence = mock(RoomPersistenceService.class);
        when(persistence.loadRooms()).thenReturn(List.of());

        AuthController authController = mock(AuthController.class);
        when(authController.getRawPassword()).thenReturn(null);

        AppProperties properties = new AppProperties();
        RoomRegistry registry = new RoomRegistry(factory, persistence, properties, authController);
        registry.init();
        return registry;
    }

    @Test
    void createsMainRoomAndCreatesRoomsWithHashedPassword() {
        RoomRegistry registry = registry();

        assertThat(registry.get(Room.MAIN_ROOM_ID)).isNotNull();

        Room room = registry.create("测试房", "device-a", "secret1");

        assertThat(room.id()).hasSize(8);
        assertThat(room.ownerDeviceId()).isEqualTo("device-a");
        assertThat(room.passwordHash()).doesNotContain("secret1");
        assertThat(registry.hasPassword(room.id())).isTrue();
        assertThat(registry.verifyPassword(room.id(), "secret1")).isTrue();
        assertThat(registry.verifyPassword(room.id(), "wrong")).isFalse();
        assertThat(registry.listOwned("device-a")).extracting(Room::id).contains(room.id());
        assertThat(registry.listOwned("device-b")).isEmpty();
    }

    @Test
    void onlyOwnerCanModifyOrDelete() {
        RoomRegistry registry = registry();
        Room room = registry.create("测试房", "device-a", null);

        assertThatThrownBy(() -> registry.rename(room.id(), "device-b", "偷改"))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("房主");

        Room renamed = registry.rename(room.id(), "device-a", "新名字");
        assertThat(renamed.name()).isEqualTo("新名字");

        assertThatThrownBy(() -> registry.delete(Room.MAIN_ROOM_ID, "device-a"))
                .isInstanceOf(ApiRequestException.class)
                .hasMessage("默认房间不能删除");

        registry.delete(room.id(), "device-a");
        assertThat(registry.get(room.id())).isNull();
    }

    @Test
    void roomCodeIsUniqueAndNoPasswordRoomsAcceptAnyone() {
        RoomRegistry registry = registry();
        Room first = registry.create("A", "device-a", null);
        Room second = registry.create("B", "device-a", null);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(registry.verifyPassword(first.id(), null)).isTrue();
        assertThat(registry.hasPassword(first.id())).isFalse();
    }

    @Test
    void runtimeIsLazyAndLoadedPerRoom() {
        RoomRegistry registry = registry();
        Room room = registry.create("A", "device-a", null);

        assertThat(registry.runtimeQuietly(room.id())).isNull();
        assertThat(registry.getOrCreateRuntime(room.id())).isNotNull();
        assertThat(registry.runtimeQuietly(room.id())).isNotNull();
        assertThat(registry.mainRuntime()).isNotNull();
    }
}
