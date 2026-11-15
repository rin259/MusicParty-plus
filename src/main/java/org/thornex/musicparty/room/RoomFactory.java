package org.thornex.musicparty.room;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.LocalCacheService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.MusicQueueManager;
import org.thornex.musicparty.service.UserService;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.command.AdminCommand;
import org.thornex.musicparty.service.command.ChatCommand;
import org.thornex.musicparty.service.command.ClearCommand;
import org.thornex.musicparty.service.command.StreamCommand;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.service.stream.StreamTokenService;

import java.util.ArrayList;
import java.util.List;

/**
 * 用共享的单例依赖构造一份「房间级」服务对象图。
 *
 * <p>刻意不使用 Spring prototype scope：prototype bean 不受容器管理，
 * @PreDestroy / @Scheduled / @EventListener 的生命周期语义都不可靠。
 * 这里由 RoomRegistry 显式创建、显式关闭。
 */
@Component
public class RoomFactory {

    private final List<IMusicApiService> apiServices;
    private final LocalCacheService localCacheService;
    private final LiveStreamService liveStreamService;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final StreamTokenService streamTokenService;

    public RoomFactory(List<IMusicApiService> apiServices,
                       LocalCacheService localCacheService,
                       LiveStreamService liveStreamService,
                       AppProperties appProperties,
                       ApplicationEventPublisher eventPublisher,
                       @Lazy SimpMessagingTemplate messagingTemplate,
                       StreamTokenService streamTokenService) {
        this.apiServices = apiServices;
        this.localCacheService = localCacheService;
        this.liveStreamService = liveStreamService;
        this.appProperties = appProperties;
        this.eventPublisher = eventPublisher;
        this.messagingTemplate = messagingTemplate;
        this.streamTokenService = streamTokenService;
    }

    public RoomRuntime create(String roomId) {
        RoomScopedPublisher publisher = new RoomScopedPublisher(eventPublisher, roomId);

        UserService users = new UserService(publisher, 45, roomId);
        MusicQueueManager queue = new MusicQueueManager(appProperties);
        MusicPlayerService player = new MusicPlayerService(apiServices, users, localCacheService,
                liveStreamService, queue, publisher, appProperties, roomId);
        player.init();

        List<ChatCommand> commands = new ArrayList<>();
        commands.add(new AdminCommand(messagingTemplate));
        commands.add(new ClearCommand(queue, player, publisher));
        if (Room.MAIN_ROOM_ID.equals(roomId)) {
            // 直播功能已搁置：只在默认房保留 /stream 命令，其余房间不暴露
            commands.add(new StreamCommand(streamTokenService, liveStreamService, messagingTemplate, appProperties));
        }

        ChatService chat = new ChatService(messagingTemplate, users, appProperties, commands, roomId);
        return new RoomRuntime(roomId, player, queue, users, chat);
    }
}
