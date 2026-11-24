package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;
import org.thornex.musicparty.event.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.LocalCacheService.DownloadPriority;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class MusicPlayerService {

    private final String roomId;

    private final Map<String, IMusicApiService> apiServiceMap;
    private final UserService userService;
    private final LocalCacheService localCacheService;
    // ChatService dependency removed to break circular reference
    private final LiveStreamService liveStreamService;

    // --- Refactored Dependencies ---
    private final MusicQueueManager queueManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;

    // --- Player State ---
    // 换源重试去重：同一队列项只尝试一次，避免搜索失败时反复打各个源
    private final Set<String> failoverAttempted = ConcurrentHashMap.newKeySet();

    /** 单轮最多跳过多少首不可播放项（防递归爆栈/死循环） */
    private static final int MAX_SKIP_PER_ROUND = 64;

    private final AtomicReference<PlayableMusic> currentMusic = new AtomicReference<>(null);
    private final AtomicReference<String> currentEnqueuerId = new AtomicReference<>(null);
    private final AtomicReference<String> currentEnqueuerName = new AtomicReference<>(null);

    // 核心计时逻辑
    private final AtomicLong positionAnchor = new AtomicLong(0); // 上一次更新状态时的进度(ms)
    private final AtomicLong timestampAnchor = new AtomicLong(0); // 上一次更新状态时的系统时间(ms)


    private final AtomicBoolean isShuffle = new AtomicBoolean(false);
    private final AtomicReference<PlayMode> playMode = new AtomicReference<>(PlayMode.SEQUENTIAL);
    private final AtomicBoolean isFairShuffle;
    private final AtomicBoolean allowOfflineShuffle;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isPauseLocked = new AtomicBoolean(false);
    private final AtomicBoolean isSkipLocked = new AtomicBoolean(false);
    private final AtomicBoolean isPlayModeLocked = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isStreamActive = new AtomicBoolean(false);

    // 投票切歌状态
    private final AtomicBoolean isVoteSkipEnabled = new AtomicBoolean(false);
    private final AtomicReference<Double> voteSkipThreshold = new AtomicReference<>(0.5);
    private final AtomicReference<Integer> voteSkipWaitTime = new AtomicReference<>(15);
    private final Set<String> skipVotes = ConcurrentHashMap.newKeySet();

    private final Map<String, Object> likeLock = new HashMap<>();
    // Keep the unique-user list for presence UI, while preserving every reaction as a marker.
    private Set<String> currentLikedUserIds;
    private List<Long> currentLikeMarkers;

    /**
     * Per-user control throttle. A single shared timestamp meant two different people
     * pressing vote-skip within the same second had the second press silently dropped,
     * so the tally always looked one short of what the room actually did.
     */
    private final Map<String, Long> lastControlAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeekTimestamps = new ConcurrentHashMap<>();
    private static final long CONTROL_COOLDOWN_MS = 1000;
    private static final long IDLE_RESET_TIMEOUT_MS = Duration.ofHours(2).toMillis();

    private final AtomicLong playHeadVersion = new AtomicLong(0);
    private final AtomicBoolean queueDirty = new AtomicBoolean();
    private final AtomicBoolean stateDirty = new AtomicBoolean();
    private final AtomicBoolean windowDirty = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<String> changedDownloads = ConcurrentHashMap.newKeySet();
    private volatile long lastQueueBroadcast;
    private final Object preparationWindowLock = new Object();

    public MusicPlayerService(List<IMusicApiService> apiServices, UserService userService,
                              LocalCacheService localCacheService,
                              LiveStreamService liveStreamService,
                              MusicQueueManager queueManager,
                              ApplicationEventPublisher eventPublisher,
                              AppProperties appProperties,
                              String roomId) {
        this.roomId = roomId == null || roomId.isBlank() ? "main" : roomId;
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.localCacheService = localCacheService;
        this.liveStreamService = liveStreamService;
        this.queueManager = queueManager;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
        this.isFairShuffle = new AtomicBoolean(true);
        this.allowOfflineShuffle = new AtomicBoolean(false);
        this.currentLikedUserIds = ConcurrentHashMap.newKeySet();
        this.currentLikeMarkers = new CopyOnWriteArrayList<>();
    }

    public void init() {
        log.info("Room {} player initialized with {} API services: {}",
                roomId, apiServiceMap.size(), apiServiceMap.keySet());
        
        // 从配置初始化投票参数
        AppProperties.PlayerConfig playerConfig = appProperties.getPlayer();
        isVoteSkipEnabled.set(playerConfig.isVoteSkipEnabled());
        voteSkipThreshold.set(playerConfig.getVoteSkipThreshold());
        voteSkipWaitTime.set(playerConfig.getVoteSkipWaitTime());
    }

    /** 由 RoomTicker 每秒遍历所有房间调用（不要在此方法上加 @Scheduled）。 */
    public void tick() {
        if (closed.get()) return;
        // Reconcile even without local download events: another room may free a
        // pending slot after this room's admission was rejected at the global cap.
        refreshPreparationWindow();
        if (isPaused.get()) {
            return;
        }

        PlayableMusic music = currentMusic.get();

        if (music != null) {
            long currentPos = calculateCurrentPosition();

            // 检查是否播放结束
            if (currentPos >= music.duration() && music.duration() > 0) {
                log.info("Song finished: {}", music.name());

                if (playMode.get() == PlayMode.REPEAT_ONE) {
                    // 单曲循环模式：重置进度，重新播放当前歌曲
                    // 前端 AudioEngine 通过 @seeked 事件检测进度跳变并自动重启播放
                    positionAnchor.set(0);
                    timestampAnchor.set(System.currentTimeMillis());
                    log.info("Repeat-one mode: restarting {}", music.name());
                    broadcastFullPlayerState();
                    return;
                }

                if (playMode.get() == PlayMode.LIST_LOOP) {
                        Music finishedMusic = new Music(
                                music.id(),
                                music.name(),
                                music.artists(),
                                music.duration(),
                                music.platform(),
                                music.coverUrl()
                        );
                        queueManager.addToHistory(finishedMusic);
                        currentMusic.set(null);
                        playNextInQueue();
                        return;
                    }

                Music finishedMusic = new Music(
                        music.id(),
                        music.name(),
                        music.artists(),
                        music.duration(),
                        music.platform(),
                        music.coverUrl()
                );
                queueManager.addToHistory(finishedMusic);

                // 清空当前，触发下一首
                currentMusic.set(null);
                playNextInQueue();
            }
        } else {
            if (userService.getOnlineUserSummaries().isEmpty() && !isStreamActive.get()) {
                return;
            }
            if (!queueManager.getQueueSnapshot().isEmpty()) {
                playNextInQueue();
            }
        }
    }

    /**
     * 取下一首并开始播放。
     *
     * <p>旧实现遇到不可播放项就递归调用自己，LIST_LOOP 下还会在队列空时重填再递归——
     * 当队列里积累了大量 FAILED 项时直接 StackOverflowError（2026-11 事故：队列被灌到 2.4 万首）。
     * 现在改为有界循环：一轮最多跳过 MAX_SKIP_PER_ROUND 项，重填只做一次。
     */
    private synchronized void playNextInQueue() {
        if (currentMusic.get() != null || isLoading.get()) {
            return;
        }

        boolean repopulated = false;
        for (int attempt = 0; attempt < MAX_SKIP_PER_ROUND; attempt++) {
            Map<String, QueueItemStatus> statusMap = buildStatusMap();
            Set<String> onlineUserTokens = userService.getRecentlyActiveUserTokens();
            MusicQueueItem nextItem = queueManager.pollNext(playMode.get(), isFairShuffle.get(),
                    allowOfflineShuffle.get(), statusMap, onlineUserTokens);

            if (nextItem == null) {
                if (playMode.get() == PlayMode.LIST_LOOP && !repopulated) {
                    repopulated = true;
                    queueManager.repopulateFromLoopList();
                    if (!queueManager.getQueueSnapshot().isEmpty()) continue;
                }
                if (isLoading.get()) isLoading.set(false);
                broadcastFullPlayerState();
                return;
            }

            if (nextItem.status() == QueueItemStatus.FAILED ||
                    (statusMap.get(MusicQueueManager.musicKey(nextItem.music())) == QueueItemStatus.FAILED)) {
                log.warn("Skipping failed song: {}", nextItem.music().name());
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR,
                        PlayerAction.ERROR_LOAD, "SYSTEM", nextItem.music().name()));
                continue;
            }

            long currentVersion = playHeadVersion.incrementAndGet();
            isLoading.set(true);
            broadcastFullPlayerState();
            isPaused.set(false);
            log.info("Playing next: {}", nextItem.music().name());

            try {
                IMusicApiService service = getApiService(nextItem.music().platform());
                String cachedUrl = localCacheService.getLocalUrl(cacheIdFor(nextItem.music()));
                Music metadata = nextItem.music();
                reactor.core.publisher.Mono<PlayableMusic> playable = cachedUrl == null
                        ? service.getPlayableMusic(metadata.id())
                        : reactor.core.publisher.Mono.just(new PlayableMusic(metadata.id(), metadata.name(), metadata.artists(),
                                metadata.duration(), metadata.platform(), cachedUrl, metadata.coverUrl(), false));
                playable
                        .timeout(Duration.ofSeconds(10))
                        .subscribe(
                                playableMusic -> {
                                    if (playHeadVersion.get() == currentVersion) {
                                        applyNewSong(playableMusic, nextItem);
                                    } else {
                                        log.info("Discarded stale play result for {}", nextItem.music().name());
                                    }
                                },
                                error -> {
                                    log.error("Play failed for {}: {}", nextItem.music().name(), error.getMessage());
                                    eventPublisher.publishEvent(new SystemMessageEvent(this,
                                            SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, "SYSTEM",
                                            nextItem.music().name()));
                                    isLoading.set(false);
                                    broadcastFullPlayerState();
                                    playNextInQueue();
                                });
            } catch (Exception error) {
                log.error("Unexpected error in playNextInQueue", error);
                isLoading.set(false);
                broadcastFullPlayerState();
            }
            return;
        }

        log.warn("Skipped {} consecutive unplayable items; stopping this round to avoid a runaway loop.",
                MAX_SKIP_PER_ROUND);
        if (isLoading.get()) isLoading.set(false);
        broadcastFullPlayerState();
    }

    private long calculateCurrentPosition() {
        if (currentMusic.get() == null) return 0;
        if (isPaused.get()) {
            return positionAnchor.get();
        } else {
            long now = System.currentTimeMillis();
            long elapsed = now - timestampAnchor.get();
            return positionAnchor.get() + elapsed;
        }
    }

    private void applyNewSong(PlayableMusic music, MusicQueueItem queueItem) {
        currentLikedUserIds.clear();
        currentLikeMarkers.clear();
        skipVotes.clear(); // 切歌时清空投票

        // 重置计时器
        currentMusic.set(music);
        currentEnqueuerId.set(queueItem.enqueuedBy().token());
        currentEnqueuerName.set(queueItem.enqueuedBy().name());

        positionAnchor.set(0);
        timestampAnchor.set(System.currentTimeMillis());
        isPaused.set(false);

        log.info("Now playing: {}", music.name());
        isLoading.set(false);
        broadcastFullPlayerState();
        broadcastQueueUpdate();

        // 发布开始播放事件 (用于聊天栏展示)
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.PLAY_START, queueItem.enqueuedBy().token(), music.name()));
    }

    public PlayerState getCurrentPlayerState() {
        PlayableMusic music = currentMusic.get();
        NowPlayingInfo infoToSend = null;

        if (music != null) {
            infoToSend = new NowPlayingInfo(
                    music,
                    calculateCurrentPosition(), // 直接返回计算好的进度
                    currentEnqueuerId.get(),
                    currentEnqueuerName.get(),
                    currentLikedUserIds,
                    currentLikeMarkers
            );
        }

        // One snapshot for the room list AND the vote tally. Reading the roster twice
        // let a disconnect in between produce "6 online, 5 eligible" on the same frame.
        List<UserSummary> onlineUsers = userService.getOnlineUserSummaries();
        int currentVoteCount = countVotes(onlineUsers);
        int eligibleCount = countEligibleUsers(onlineUsers);

        PlayMode currentPlayMode = playMode.get();
        return new PlayerState(
                infoToSend,
                getQueueWithUpdatedStatus(),
                currentPlayMode.name(),
                currentPlayMode == PlayMode.SHUFFLE,
                isFairShuffle.get(),
                allowOfflineShuffle.get(),
                onlineUsers,
                isPaused.get(),
                isPauseLocked.get(),
                isSkipLocked.get(),
                isPlayModeLocked.get(),
                isLoading.get(),
                isMainRoom() ? liveStreamService.getStreamListenerCount() : 0,
                isMainRoom() && liveStreamService.isEnabled(),
                isVoteSkipEnabled.get(),
                voteSkipThreshold.get(),
                voteSkipWaitTime.get(),
                currentVoteCount,
                eligibleCount,
                new PlayerState.AppConfigSummary(
                        appProperties.getQueue().getMaxSize(),
                        appProperties.getQueue().getHistorySize(),
                        appProperties.getQueue().getMaxUserSongs(),
                        appProperties.getPlayer().getMaxPlaylistImportSize(),
                        appProperties.getChat().getMaxHistorySize(),
                        appProperties.getChat().getMinIntervalMs(),
                        appProperties.getChat().getMaxMessageLength(),
                        appProperties.getNetease().isEnabled(),
                        appProperties.getBilibili().isEnabled(),
                        appProperties.getSoundcloud().isEnabled(),
                        appProperties.getYtmusic().isEnabled(),
                        isVoteSkipEnabled.get(),
                        voteSkipThreshold.get(),
                        voteSkipWaitTime.get()
                )
        );
    }

    private int countEligibleUsers(List<UserSummary> onlineUsers) {
        return VoteTally.eligible(onlineUsers, currentEnqueuerId.get());
    }

    private int countVotes(List<UserSummary> onlineUsers) {
        return VoteTally.votes(onlineUsers, skipVotes, currentEnqueuerId.get());
    }

    public void toggleFairShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;
        boolean current;
        boolean newState;
        do {
            current = isFairShuffle.get();
            newState = !current;
        } while (!isFairShuffle.compareAndSet(current, newState));

        log.info("Fair shuffle mode set to {} by {}", newState, getUserName(sessionId));
        broadcastFullPlayerState();
        broadcastQueueUpdate(); // 可能会影响前端展示
        publishRuntimeConfigChanged();
    }

    public void toggleAllowOfflineShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;
        boolean current;
        boolean newState;
        do {
            current = allowOfflineShuffle.get();
            newState = !current;
        } while (!allowOfflineShuffle.compareAndSet(current, newState));

        log.info("Allow offline shuffle set to {} by {}", newState, getUserName(sessionId));
        broadcastFullPlayerState();
        publishRuntimeConfigChanged();
    }

    public int clearOfflineSongs() {
        Set<String> onlineTokens = userService.getRecentlyActiveUserTokens();
        List<MusicQueueItem> snapshot = queueManager.getQueueSnapshot();
        int removedCount = 0;
        for (MusicQueueItem item : snapshot) {
            if (!onlineTokens.contains(item.enqueuedBy().token())) {
                queueManager.remove(item.queueId());
                removedCount++;
            }
        }
        log.info("Cleared {} songs from offline users.", removedCount);
        broadcastQueueUpdate();
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "管理员已清理 " + removedCount + " 首离线成员的点播歌曲"));
        return removedCount;
    }

    public void setLock(String type, boolean locked) {
        AtomicBoolean targetLock;
        String desc;
        switch (type.toUpperCase()) {
            case "PAUSE" -> { targetLock = isPauseLocked; desc = "暂停"; }
            case "SKIP" -> { targetLock = isSkipLocked; desc = "切歌"; }
            case "SHUFFLE" -> { targetLock = isPlayModeLocked; desc = "播放模式"; }
            default -> throw new IllegalArgumentException("Unknown lock type");
        }

        boolean old = targetLock.getAndSet(locked);
        if (old != locked) {
            log.info("{} lock set to: {}", desc, locked);
            broadcastFullPlayerState();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM",
                    locked ? "管理员锁定了" + desc : "管理员解锁了" + desc));
            publishRuntimeConfigChanged();
        }
    }

    public void setAllLocks(boolean locked) {
        isPauseLocked.set(locked);
        isSkipLocked.set(locked);
        isPlayModeLocked.set(locked);
        broadcastFullPlayerState();
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM",
                locked ? "管理员锁定了所有控制" : "管理员解锁了所有控制"));
        publishRuntimeConfigChanged();
    }

    public RuntimeSettings getRuntimeSettings() {
        return new RuntimeSettings(
                playMode.get().name(),
                isFairShuffle.get(),
                allowOfflineShuffle.get(),
                isPauseLocked.get(),
                isSkipLocked.get(),
                isPlayModeLocked.get(),
                isVoteSkipEnabled.get(),
                voteSkipThreshold.get(),
                voteSkipWaitTime.get()
        );
    }

    public void restoreRuntimeSettings(RuntimeSettings settings) {
        if (settings == null) {
            return;
        }

        if (settings.playMode() != null) {
            try {
                PlayMode restoredMode = PlayMode.valueOf(settings.playMode());
                playMode.set(restoredMode);
                isShuffle.set(restoredMode == PlayMode.SHUFFLE);
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown persisted play mode: {}", settings.playMode());
            }
        }
        if (settings.fairShuffle() != null) isFairShuffle.set(settings.fairShuffle());
        if (settings.allowOfflineShuffle() != null) allowOfflineShuffle.set(settings.allowOfflineShuffle());
        if (settings.pauseLocked() != null) isPauseLocked.set(settings.pauseLocked());
        if (settings.skipLocked() != null) isSkipLocked.set(settings.skipLocked());
        if (settings.playModeLocked() != null) isPlayModeLocked.set(settings.playModeLocked());
        if (settings.voteSkipEnabled() != null) {
            isVoteSkipEnabled.set(settings.voteSkipEnabled());
            appProperties.getPlayer().setVoteSkipEnabled(settings.voteSkipEnabled());
        }
        if (settings.voteSkipThreshold() != null) {
            voteSkipThreshold.set(settings.voteSkipThreshold());
            appProperties.getPlayer().setVoteSkipThreshold(settings.voteSkipThreshold());
        }
        if (settings.voteSkipWaitTime() != null) {
            voteSkipWaitTime.set(settings.voteSkipWaitTime());
            appProperties.getPlayer().setVoteSkipWaitTime(settings.voteSkipWaitTime());
        }
    }

    public record RuntimeSettings(
            String playMode,
            Boolean fairShuffle,
            Boolean allowOfflineShuffle,
            Boolean pauseLocked,
            Boolean skipLocked,
            Boolean playModeLocked,
            Boolean voteSkipEnabled,
            Double voteSkipThreshold,
            Integer voteSkipWaitTime
    ) {}

    // B 站视频上限：与前端列表的 >10MIN 标记保持一致，且必须服务端兜底
    // （直接粘贴 BV / URL 点歌不会经过前端列表的过滤）。
    private static final long MAX_BILIBILI_TRACK_MS = 10 * 60 * 1000L;

    /** 点歌回执：优先走个人频道；旧客户端（无 requestId）仍走原来的全房间系统消息。 */
    private void sendEnqueueResult(String sessionId, String requestId, String platform, String musicId,
                                   String status, String message) {
        if (requestId != null && sessionId != null) {
            eventPublisher.publishEvent(new EnqueueResultEvent(this, sessionId,
                    new EnqueueResult(requestId, platform, musicId, status, message)));
            return;
        }
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR,
                PlayerAction.SYSTEM_MESSAGE, getUserToken(sessionId), message));
    }

    private void sendImportProgress(String sessionId, ImportProgress progress) {
        if (progress.requestId() == null || sessionId == null) return;
        eventPublisher.publishEvent(new ImportProgressEvent(this, sessionId, progress));
    }

    public void enqueue(EnqueueRequest request, String sessionId) {
        if (closed.get()) return;
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();
        String requestId = request.requestId();

        if (!isPlatformEnabled(request.platform())) {
            sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                    EnqueueResult.DISABLED, "添加失败: 此音乐源已被禁用");
            return;
        }

        long userSongCount = queueManager.getQueueSnapshot().stream()
                .filter(item -> item.enqueuedBy().token().equals(enqueuer.getToken()))
                .count();
        int maxUserSongs = appProperties.getQueue().getMaxUserSongs();

        if (userSongCount >= maxUserSongs) {
            sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                    EnqueueResult.LIMIT, "添加失败: 您的点歌数量已达上限 (" + maxUserSongs + "首)");
            return;
        }

        String reservation = request.platform() + ":" + request.musicId();
        String ticket = UUID.randomUUID().toString();
        MusicQueueManager.AddFailure rejected = queueManager.reserve(reservation, enqueuer.getToken(), ticket);
        if (rejected != null) {
            sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                    rejected == MusicQueueManager.AddFailure.DUPLICATE ? EnqueueResult.DUPLICATE
                            : rejected == MusicQueueManager.AddFailure.QUEUE_FULL ? EnqueueResult.QUEUE_FULL : EnqueueResult.LIMIT,
                    "添加失败: 重复歌曲、队列已满或个人配额已用尽");
            return;
        }
        IMusicApiService service = getApiService(request.platform());
        localCacheService.lookupMetadata(service, request.musicId())
                .switchIfEmpty(reactor.core.publisher.Mono.error(new ApiRequestException("未返回歌曲信息")))
                .doFinally(signal -> queueManager.releaseReservation(reservation, ticket))
                .subscribe(playableMusic -> {
                            if (closed.get()) return;
                            if ("bilibili".equalsIgnoreCase(request.platform())
                                    && playableMusic.duration() > MAX_BILIBILI_TRACK_MS) {
                                sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                                        EnqueueResult.TOO_LONG, "添加失败: B 站视频超过 10 分钟，不支持点歌");
                                return;
                            }

                            Music music = new Music(playableMusic.id(), playableMusic.name(), playableMusic.artists(),
                                    playableMusic.duration(), playableMusic.platform(), playableMusic.coverUrl());

                            QueueItemStatus initialStatus = usesLocalCache(request.platform()) ? QueueItemStatus.PENDING : QueueItemStatus.READY;
                            MusicQueueManager.AddResult added = queueManager.commitReserved(reservation, ticket, music,
                                    new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName(), enqueuer.isGuest()),
                                    initialStatus);

                            if (!added.ok()) {
                                String status = added.failure() == MusicQueueManager.AddFailure.QUEUE_FULL
                                        ? EnqueueResult.QUEUE_FULL : added.failure() == MusicQueueManager.AddFailure.USER_LIMIT
                                        ? EnqueueResult.LIMIT : EnqueueResult.DUPLICATE;
                                String message = added.failure() == MusicQueueManager.AddFailure.QUEUE_FULL
                                        ? "添加失败: 队列已满" : added.failure() == MusicQueueManager.AddFailure.USER_LIMIT
                                        ? "添加已取消或个人配额已用尽" : "这首歌已在队列中";
                                sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(), status, message);
                                return;
                            }

                            queueManager.addToLoopList(music);
                            log.info("{} enqueued: {}", enqueuer.getName(), music.name());
                            broadcastQueueUpdate();
                            sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                                    EnqueueResult.QUEUED, "已加入队列");
                            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS,
                                    PlayerAction.ADD, enqueuer.getToken(), music.name()));
                        },
                        error -> {
                            log.error("Enqueue failed for musicId: {}", request.musicId(), error);
                            String raw = error.getMessage() == null ? "未知错误" : error.getMessage();
                            String msg = raw.contains("Could not get Bilibili video info") ? "无效资源或API受限" : raw;
                            sendEnqueueResult(sessionId, requestId, request.platform(), request.musicId(),
                                    EnqueueResult.FAILED, "添加失败: " + msg);
                        });
    }

    // 点赞逻辑
    public void likeSong(String sessionId) {
        PlayableMusic music = currentMusic.get();
        if (music == null) return;

        String token = getUserToken(sessionId);

        // A listener may react to a track more than once. The marker list records each reaction.
        currentLikedUserIds.add(token);

        // 使用计算出的当前进度作为 Marker
        long progress = calculateCurrentPosition();
        currentLikeMarkers.add(progress);

        log.info("Like received from {}", getUserName(sessionId));

        // 3. 广播
        // 广播事件用于触发特效
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.LIKE, token, music.name()));
        // 广播状态更新进度条打点和用户列表
        broadcastFullPlayerState();
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String sessionId) {
        if (closed.get()) return;
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();
        String requestId = request.requestId();

        if (!isPlatformEnabled(request.platform())) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: 此音乐源已被禁用"));
            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                    "playlist", 0, 0, 0, 0, true, "导入失败: 此音乐源已被禁用"));
            return;
        }

        long currentCount = queueManager.getQueueSnapshot().stream()
                .filter(item -> item.enqueuedBy().token().equals(enqueuer.getToken()))
                .count();
        int maxUserSongs = appProperties.getQueue().getMaxUserSongs();

        if (currentCount >= maxUserSongs) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: 您的点歌数量已达上限"));
            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                    "playlist", 0, 0, 0, 0, true, "导入失败: 点歌数量已达上限"));
            return;
        }

        int remainingQuota = (int) (maxUserSongs - currentCount);
        int importLimit = Math.min(appProperties.getPlayer().getMaxPlaylistImportSize(), remainingQuota);
        long admissionEpoch = queueManager.admissionEpoch();

        IMusicApiService service = getApiService(request.platform());
        service.getPlaylistMusics(request.playlistId(), 0, importLimit)
                .subscribe(musics -> {
                    if (closed.get()) return;
                    int total = musics.size();
                    int done = 0;
                    int added = 0;
                    int skipped = 0;
                    QueueItemStatus initialStatus = usesLocalCache(request.platform()) ? QueueItemStatus.PENDING : QueueItemStatus.READY;

                    for (Music music : musics) {
                        MusicQueueManager.AddResult result = queueManager.addImported(admissionEpoch, music,
                                new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName(), enqueuer.isGuest()),
                                initialStatus);
                        if (result.ok()) {
                            queueManager.addToLoopList(music);
                            added++;
                        } else {
                            skipped++;
                        }
                        done++;
                        if (done % 5 == 0 && done != total) {
                            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(),
                                    request.playlistId(), "playlist", done, total, added, skipped, false, null));
                        }
                    }

                    log.info("{} enqueued {} songs from playlist ({} skipped)", enqueuer.getName(), added, skipped);
                    broadcastQueueUpdate();
                    sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                            "playlist", done, total, added, skipped, true, importSummary(added, skipped)));
                    eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.IMPORT_PLAYLIST, enqueuer.getToken(), String.valueOf(added)));
                },
                error -> {
                    log.error("Playlist import failed: {}", request.playlistId(), error);
                    sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                            "playlist", 0, 0, 0, 0, true, "导入失败: " + safeMessage(error)));
                });
    }

    public void enqueueAlbum(EnqueuePlaylistRequest request, String sessionId) {
        if (closed.get()) return;
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();
        String requestId = request.requestId();

        if (!isPlatformEnabled(request.platform())) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: 此音乐源已被禁用"));
            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                    "album", 0, 0, 0, 0, true, "导入失败: 此音乐源已被禁用"));
            return;
        }

        long currentCount = queueManager.getQueueSnapshot().stream()
                .filter(item -> item.enqueuedBy().token().equals(enqueuer.getToken()))
                .count();
        int maxUserSongs = appProperties.getQueue().getMaxUserSongs();

        if (currentCount >= maxUserSongs) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: 您的点歌数量已达上限"));
            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                    "album", 0, 0, 0, 0, true, "导入失败: 点歌数量已达上限"));
            return;
        }

        int remainingQuota = (int) (maxUserSongs - currentCount);
        int importLimit = Math.min(appProperties.getPlayer().getMaxPlaylistImportSize(), remainingQuota);
        long admissionEpoch = queueManager.admissionEpoch();

        IMusicApiService service = getApiService(request.platform());
        service.getAlbumMusics(request.playlistId())
                .subscribe(musics -> {
                    if (closed.get()) return;
                    int total = Math.min(musics.size(), importLimit);
                    int done = 0;
                    int added = 0;
                    int skipped = 0;
                    QueueItemStatus initialStatus = usesLocalCache(request.platform()) ? QueueItemStatus.PENDING : QueueItemStatus.READY;

                    for (Music music : musics) {
                        if (done >= importLimit) break;
                        MusicQueueManager.AddResult result = queueManager.addImported(admissionEpoch, music,
                                new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName(), enqueuer.isGuest()),
                                initialStatus);
                        if (result.ok()) {
                            queueManager.addToLoopList(music);
                            added++;
                        } else {
                            skipped++;
                        }
                        done++;
                        if (done % 5 == 0 && done != total) {
                            sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(),
                                    request.playlistId(), "album", done, total, added, skipped, false, null));
                        }
                    }

                    log.info("{} enqueued {} songs from album ({} skipped)", enqueuer.getName(), added, skipped);
                    broadcastQueueUpdate();
                    sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                            "album", done, total, added, skipped, true, importSummary(added, skipped)));
                    eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.IMPORT_PLAYLIST, enqueuer.getToken(), String.valueOf(added)));
                },
                error -> {
                    log.error("Album import failed: {}", request.playlistId(), error);
                    sendImportProgress(sessionId, new ImportProgress(requestId, request.platform(), request.playlistId(),
                            "album", 0, 0, 0, 0, true, "导入失败: " + safeMessage(error)));
                });
    }

    private static String importSummary(int added, int skipped) {
        if (skipped == 0) return "已入队 " + added + " 首";
        return "已入队 " + added + " 首，跳过 " + skipped + " 首（重复 / 队列上限 / 超出配额）";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return (message == null || message.isBlank()) ? "上游无响应" : message;
    }

    public synchronized void topSong(String queueId, String sessionId) {
        // 先调用 top 执行置顶操作
        TopResult result = queueManager.top(queueId, playMode.get());
        
        if (result != TopResult.NONE) {
            log.info("Song topped ({}) request by {}", result, getUserName(sessionId));
            broadcastQueueUpdate();

            // 只有全局置顶才发送系统消息广播
            if (result == TopResult.GLOBAL) {
                String songName = queueManager.getItem(queueId)
                        .map(item -> item.music().name())
                        .orElse("未知歌曲");
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.TOP, getUserToken(sessionId), songName));
            }
            
            if (currentMusic.get() == null) {
                playNextInQueue();
            }
        }
    }

    public void removeSongFromQueue(String queueId, String sessionId) {
        Optional<MusicQueueItem> removedItem = queueManager.remove(queueId);
        if (removedItem.isPresent()) {
            queueManager.removeFromLoopList(removedItem.get().music());
            log.info("Removed song from queue by {}", getUserName(sessionId));
            broadcastQueueUpdate();
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.REMOVE, getUserToken(sessionId), removedItem.get().music().name()));
        }
    }

    public void skipToNext(String sessionId) {
        if (isRateLimited(sessionId)) return;
        
        // 管理员通过控制面板切歌 (SYSTEM) 或未开启投票模式，直接切歌
        if ("SYSTEM".equals(sessionId) || !isVoteSkipEnabled.get()) {
            executeSkip(sessionId);
            return;
        }

        // 投票模式逻辑
        handleVoteSkip(sessionId);
    }

    private void handleVoteSkip(String sessionId) {
        if (currentMusic.get() == null) return;
        
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty() || userOpt.get().isGuest()) return;
        
        String token = userOpt.get().getToken();
        String enqueuerToken = currentEnqueuerId.get();

        // 特殊逻辑：点歌者切自己的歌，视为强制切歌，不走投票逻辑，且不设 15 秒限制
        if (token.equals(enqueuerToken)) {
            log.info("Enqueuer {} skipped their own song.", userOpt.get().getName());
            executeSkip(sessionId);
            return;
        }

        // 1. 检查播放时长 (15秒限制，仅针对投票者)
        long currentPos = calculateCurrentPosition();
        if (currentPos < voteSkipWaitTime.get() * 1000L) {
            return;
        }

        // 2. 切换投票状态 (Toggle)
        if (skipVotes.contains(token)) {
            skipVotes.remove(token);
        } else {
            skipVotes.add(token);
        }

        // 3. 判定是否达成条件
        checkVoteSkipThreshold();
        
        // 4. 广播状态更新 (用于前端角标)
        broadcastFullPlayerState();
    }

    private void checkVoteSkipThreshold() {
        List<UserSummary> onlineUsers = userService.getOnlineUserSummaries();
        int currentVoteCount = countVotes(onlineUsers);
        int eligibleCount = countEligibleUsers(onlineUsers);

        if (VoteTally.passes(currentVoteCount, eligibleCount, voteSkipThreshold.get())) {
            String msg = String.format("投票切歌通过！(%d/%d 票)，正在进入下一首。", currentVoteCount, eligibleCount);
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", msg));
            executeSkip("SYSTEM");
        }
    }

    private void executeSkip(String sessionId) {
        if (isSkipLocked.get() && !"SYSTEM".equals(sessionId)) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, getUserToken(sessionId), "切歌功能已被锁定"));
            return;
        }

        // 切歌时版本号自增，废弃之前的任何 pending 请求
        playHeadVersion.incrementAndGet();
        isLoading.set(false);

        currentMusic.set(null);
        positionAnchor.set(0);

        if (!"SYSTEM".equals(sessionId)) {
            eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.SKIP, getUserToken(sessionId), null));
        }
        playNextInQueue();
    }

    public void updateConfig(AdminConfigUpdateRequest request) {
        StringBuilder logMsg = new StringBuilder("System configuration updated: ");
        
        // The dashboard has always sent maxQueueSize/maxHistorySize; accept both
        // spellings so a save is never silently ignored.
        Integer maxQueueSize = firstPositive(request.maxSize(), request.maxQueueSize());
        if (maxQueueSize != null) {
            appProperties.getQueue().setMaxSize(maxQueueSize);
            logMsg.append("MaxQueueSize=").append(maxQueueSize).append(" ");
        }
        Integer historySize = firstPositive(request.historySize(), request.maxHistorySize());
        if (historySize != null) {
            appProperties.getQueue().setHistorySize(historySize);
            logMsg.append("HistorySize=").append(historySize).append(" ");
        }
        if (request.maxUserSongs() != null) {
            appProperties.getQueue().setMaxUserSongs(request.maxUserSongs());
            logMsg.append("MaxUserSongs=").append(request.maxUserSongs()).append(" ");
        }
        if (request.maxPlaylistImportSize() != null) {
            appProperties.getPlayer().setMaxPlaylistImportSize(request.maxPlaylistImportSize());
            logMsg.append("MaxPlaylistImportSize=").append(request.maxPlaylistImportSize()).append(" ");
        }
        if (request.maxChatHistorySize() != null) {
            appProperties.getChat().setMaxHistorySize(request.maxChatHistorySize());
            logMsg.append("MaxChatHistorySize=").append(request.maxChatHistorySize()).append(" ");
        }
        if (request.minChatIntervalMs() != null) {
            appProperties.getChat().setMinIntervalMs(request.minChatIntervalMs());
            logMsg.append("MinChatInterval=").append(request.minChatIntervalMs()).append("ms ");
        }
        if (request.maxChatMessageLength() != null) {
            appProperties.getChat().setMaxMessageLength(request.maxChatMessageLength());
            logMsg.append("MaxChatMessageLength=").append(request.maxChatMessageLength()).append(" ");
        }
        if (request.neteaseEnabled() != null) {
            appProperties.getNetease().setEnabled(request.neteaseEnabled());
            logMsg.append("NeteaseEnabled=").append(request.neteaseEnabled()).append(" ");
        }
        if (request.bilibiliEnabled() != null) {
            appProperties.getBilibili().setEnabled(request.bilibiliEnabled());
            logMsg.append("BilibiliEnabled=").append(request.bilibiliEnabled()).append(" ");
        }
        if (request.soundcloudEnabled() != null) {
            appProperties.getSoundcloud().setEnabled(request.soundcloudEnabled());
            logMsg.append("SoundcloudEnabled=").append(request.soundcloudEnabled()).append(" ");
        }
        if (request.ytmusicEnabled() != null) {
            appProperties.getYtmusic().setEnabled(request.ytmusicEnabled());
            logMsg.append("YtmusicEnabled=").append(request.ytmusicEnabled()).append(" ");
        }
        
        if (request.voteSkipEnabled() != null) {
            isVoteSkipEnabled.set(request.voteSkipEnabled());
            logMsg.append("VoteSkipEnabled=").append(request.voteSkipEnabled()).append(" ");
        }
        if (request.voteSkipThreshold() != null) {
            voteSkipThreshold.set(request.voteSkipThreshold());
            logMsg.append("VoteSkipThreshold=").append(request.voteSkipThreshold()).append(" ");
        }
        if (request.voteSkipWaitTime() != null) {
            voteSkipWaitTime.set(request.voteSkipWaitTime());
            logMsg.append("VoteSkipWaitTime=").append(request.voteSkipWaitTime()).append("s ");
        }

        log.info(logMsg.toString().trim());
        
        // 关键增强：如果更新了投票相关配置，立即触发一次阈值检查
        if (isVoteSkipEnabled.get() && currentMusic.get() != null) {
            checkVoteSkipThreshold();
        }

        broadcastFullPlayerState();
        publishRuntimeConfigChanged();
    }

    private static Integer firstPositive(Integer preferred, Integer fallback) {
        if (preferred != null && preferred > 0) return preferred;
        if (fallback != null && fallback > 0) return fallback;
        return null;
    }

    public void togglePause(String sessionId) {
        if (currentMusic.get() == null) {
            if (!queueManager.getQueueSnapshot().isEmpty()) {
                playNextInQueue();
            }
            return;
        }
        if (isRateLimited(sessionId)) return;

        // 锁定检查：如果是系统操作，放行。如果是用户操作，检查锁。
        // 规则：如果不控制播放权限（允许从暂停->播放），则只有当当前是播放状态(即试图暂停)且锁定时才拦截。
        if (!"SYSTEM".equals(sessionId)) {
            if (isPauseLocked.get() && !isPaused.get()) {
                // eventPublisher.publishEvent(...);
                return;
            }
        }

        // 核心：在切换状态的一瞬间，更新 Anchor
        // 1. 先计算出当前的进度
        long currentPos = calculateCurrentPosition();

        // 2. 更新状态
        boolean newState = !isPaused.get();
        isPaused.set(newState);

        // 3. 重置锚点：无论是暂停还是播放，当前进度都变成新的基准进度
        positionAnchor.set(currentPos);
        timestampAnchor.set(System.currentTimeMillis());

        log.info("Player {} by {}", newState ? "PAUSED" : "RESUMED", getUserName(sessionId));
        broadcastFullPlayerState();
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, newState ? PlayerAction.PAUSE : PlayerAction.PLAY, getUserToken(sessionId), null));
    }

    public synchronized void seekTo(long requestedPositionMs, String sessionId) {
        PlayableMusic music = currentMusic.get();
        if (music == null || isSeekRateLimited(sessionId)) return;

        long position = clampSeekPosition(requestedPositionMs, music.duration());
        positionAnchor.set(position);
        timestampAnchor.set(System.currentTimeMillis());

        log.info("Player seeked to {}ms by {}", position, getUserName(sessionId));
        eventPublisher.publishEvent(new SeekEvent(this, position));
        broadcastFullPlayerState();
    }

    static long clampSeekPosition(long requestedPositionMs, long durationMs) {
        return Math.max(0, Math.min(requestedPositionMs, Math.max(0, durationMs)));
    }

    private boolean isSeekRateLimited(String sessionId) {
        long now = System.currentTimeMillis();
        long previous = lastSeekTimestamps.getOrDefault(sessionId, 0L);
        if (now - previous < 250) return true;
        lastSeekTimestamps.put(sessionId, now);
        return false;
    }

    public void cyclePlayMode(String sessionId) {
        if (isRateLimited(sessionId)) return;
        if (isPlayModeLocked.get() && !"SYSTEM".equals(sessionId)) return;

        PlayMode current;
        PlayMode next;
        do {
            current = playMode.get();
            next = switch (current) {
                case SEQUENTIAL -> PlayMode.SHUFFLE;
                case SHUFFLE -> PlayMode.REPEAT_ONE;
                case REPEAT_ONE -> PlayMode.LIST_LOOP;
                case LIST_LOOP -> PlayMode.SEQUENTIAL;
            };
        } while (!playMode.compareAndSet(current, next));

        // 同步 isShuffle 以保持向后兼容
        isShuffle.set(next == PlayMode.SHUFFLE);

        log.info("Play mode cycled to {} by {}", next, getUserName(sessionId));
        broadcastFullPlayerState();

        // 统一格式的通知：MessageFormatter 格式化为 "XXX 切换到了[模式]"
        String modeName = switch (next) {
            case SEQUENTIAL -> "顺序播放";
            case SHUFFLE -> "随机播放";
            case REPEAT_ONE -> "单曲循环";
            case LIST_LOOP -> "列表循环";
        };
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                PlayerAction.MODE_CHANGE, getUserToken(sessionId), modeName));
        publishRuntimeConfigChanged();
    }

    public void resetSystem() {
        log.warn("!!!SYSTEM RESET INITIATED!!!");
        currentMusic.set(null);
        positionAnchor.set(0);
        timestampAnchor.set(0);

        queueManager.clearAll();
        isPaused.set(false);
        isShuffle.set(false);
        playMode.set(PlayMode.SEQUENTIAL);
        isLoading.set(false);

        broadcastFullPlayerState();
        broadcastQueueUpdate();
        log.warn("System reset complete.");
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.RESET, "SYSTEM", null));
        publishRuntimeConfigChanged();
    }

    private void publishRuntimeConfigChanged() {
        eventPublisher.publishEvent(new RuntimeConfigChangedEvent(this));
    }

    public void clearQueue() {
        queueManager.clearPendingQueue();
        log.info("Queue cleared by Admin.");
        // 广播队列更新
        broadcastQueueUpdate();
        // 发送全员通知
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "管理员已清空播放列表"));
    }

    public void prefetchRestoredQueue() {
        refreshPreparationWindow();
        broadcastQueueUpdate();
    }

    /** 由 RoomEventBridge 分发（下载事件是全局的，每个房间各自判断是否相关）。 */
    public void handleDownloadEvent(DownloadStatusEvent event) {
        // Application events are synchronous. Never acquire the player lock or
        // call upstream work from a scheduler admission/completion callback.
        changedDownloads.add(event.getMusicId());
        queueDirty.set(true);
    }

    private void processDownloadChange(String musicId) {
        Optional<MusicQueueItem> queueItem = queueManager.getQueueSnapshot().stream()
                .filter(item -> cacheIdFor(item.music()).equals(musicId))
                .findFirst();

        if (queueItem.isPresent()) {
            log.debug("Download status changed for {}, updating queue UI.", musicId);
            broadcastQueueUpdate();
            boolean failed = localCacheService.getStatus(musicId) == CacheStatus.FAILED;
            if (failed) {
                String reason = localCacheService.failureReason(musicId);
                String detail = reason == null || reason.isBlank()
                        ? queueItem.get().music().name() : queueItem.get().music().name() + "（" + reason + "）";
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR,
                        PlayerAction.ERROR_LOAD, "SYSTEM", detail));
            }
            PlayableMusic current = currentMusic.get();
            if (failed && current != null && cacheIdFor(current).equals(musicId)) {
                // 当前曲目取源失败：立即换下一首。旧逻辑仅在「无曲目」时前进，
                // 于是当前曲失败会把播放器永久留在「获取源」状态。
                log.warn("Current track {} failed to load; advancing to next.", musicId);
                playNextInQueue();
                return;
            }
            if (failed) {
                // 队列里的其它歌：先尝试换到别的源，换不到才保持 FAILED。
                // 异步执行，搜索要打外部 API，不能阻塞事件线程。
                MusicQueueItem failedItem = queueItem.get();
                CompletableFuture.runAsync(() -> failoverToAnotherSource(failedItem));
            }
            if (current == null) {
                playNextInQueue();
            }
        }
    }

    /**
     * 换源重试：某源解析失败后，按「标题归一 + 时长接近 + 艺人重合」在其它已启用的源里找同一首歌，
     * 命中则原地替换（队列位置不变），并重新进入解析。
     *
     * 研判（2026-11）：派对场景里「点了放不出来」是最差的体验，而多源搜索本来就是我们的能力。
     * 匹配刻意保守——宁可保持 FAILED，也不要把翻唱/不同版本换进来。
     */
    private void failoverToAnotherSource(MusicQueueItem failedItem) {
        if (failedItem == null || failedItem.music() == null) return;
        String dedupeKey = failedItem.queueId() + "|" + MusicQueueManager.musicKey(failedItem.music());
        if (!failoverAttempted.add(dedupeKey)) return;

        Music failed = failedItem.music();
        String keyword = searchKeywordFor(failed);
        List<String> platforms = List.of("netease", "bilibili", "soundcloud", "ytmusic").stream()
                .filter(p -> !p.equalsIgnoreCase(failed.platform()))
                .filter(this::isPlatformEnabled)
                .toList();

        for (String platform : platforms) {
            try {
                List<Music> hits = getApiService(platform)
                        .searchMusic(keyword, 0, 10)
                        .block(Duration.ofSeconds(6));
                Optional<Music> match = pickFailoverMatch(failed, hits);
                if (match.isEmpty()) continue;

                Music replacement = match.get();
                if (!queueManager.replaceMusic(failedItem.queueId(), replacement)) return;
                log.info("Failover {} -> {}:{} ({})", failed.name(), replacement.platform(),
                        replacement.id(), replacement.name());
                broadcastQueueUpdate();
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                        PlayerAction.ERROR_LOAD, "SYSTEM",
                        "已换源重试：" + failed.name() + " → " + replacement.platform()));
                return;
            } catch (RuntimeException error) {
                log.debug("Failover search on {} failed: {}", platform, error.toString());
            }
        }
    }

    private static String searchKeywordFor(Music music) {
        String artist = (music.artists() == null || music.artists().isEmpty()) ? "" : music.artists().get(0);
        return (music.name() + " " + artist).trim();
    }

    /** 归一化：去空白与标点，用于标题/艺人比较 */
    static String normalizeForMatch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}（）【】《》「」·、，。！？~]", "");
    }

    /** 保守匹配：标题互为包含、时长差 ≤7s、艺人至少一个重合（缺失时放宽） */
    static Optional<Music> pickFailoverMatch(Music failed, List<Music> hits) {
        if (hits == null || hits.isEmpty()) return Optional.empty();
        String target = normalizeForMatch(failed.name());
        if (target.isEmpty()) return Optional.empty();

        for (Music candidate : hits) {
            if (candidate == null || candidate.id() == null) continue;
            if (candidate.duration() > 0 && failed.duration() > 0
                    && Math.abs(candidate.duration() - failed.duration()) > 7_000) continue;

            String name = normalizeForMatch(candidate.name());
            if (name.isEmpty()) continue;
            if (!name.equals(target) && !name.contains(target) && !target.contains(name)) continue;

            if (!artistsOverlap(failed.artists(), candidate.artists())) continue;
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean artistsOverlap(List<String> a, List<String> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return true;
        for (String left : a) {
            String ln = normalizeForMatch(left);
            if (ln.isEmpty()) continue;
            for (String right : b) {
                String rn = normalizeForMatch(right);
                if (rn.isEmpty()) continue;
                if (ln.contains(rn) || rn.contains(ln)) return true;
            }
        }
        return false;
    }

    /**
     * 监听用户数量变化事件
     */
    /** 由 RoomEventBridge 分发到本房间。 */
    public void onUserCountChanged(UserCountChangeEvent event) {
        if (event.getOnlineUserCount() == 0 && !isStreamActive.get()) {
            enterIdleMode();
        }
        
        // 用户变动时重新检查投票阈值
        if (isVoteSkipEnabled.get() && currentMusic.get() != null) {
            checkVoteSkipThreshold();
            broadcastFullPlayerState();
        }
    }

    /**
     * 监听直播流状态变化
     */
    /** 由 RoomEventBridge 只分发给默认房。 */
    public void onStreamStatusChanged(StreamStatusEvent event) {
        boolean hasListeners = event.isHasListeners();
        this.isStreamActive.set(hasListeners);
        log.info("System: Stream active status changed to: {}, Count: {}", hasListeners, event.getListenerCount());

        if (hasListeners) {
            // 场景 A: 列表为空，有人连入流 -> 尝试开始播放下一首
            if (currentMusic.get() == null) {
                playNextInQueue();
            } 
            // 场景 B: 正在暂停中，且网页端没人，有人连入流 -> 自动恢复播放
            else if (isPaused.get() && userService.getOnlineUserSummaries().isEmpty()) {
                log.info("System: Auto-resuming player for new stream listener.");
                togglePause("SYSTEM");
            }
        } else {
            // 场景 C: 流用户离开，且网页端也没人 -> 进入休眠
            if (userService.getOnlineUserSummaries().isEmpty()) {
                enterIdleMode();
            }
        }
        broadcastFullPlayerState();
    }

    /**
     * 进入空闲模式，停止播放
     */
    private void enterIdleMode() {
        log.info("Last user disconnected. Entering idle mode.");
        isLoading.set(false);

        // 如果正在播放，自动暂停，记录当前进度
        if (currentMusic.get() != null && !isPaused.get()) {
            long currentPos = calculateCurrentPosition();

            if (isPaused.compareAndSet(false, true)) {
                // 暂停时，更新锚点为刚才计算出的准确进度
                positionAnchor.set(currentPos);
                timestampAnchor.set(System.currentTimeMillis()); // 这个时间在暂停期间主要用于超时判断

                log.info("Player paused as all users have disconnected. Position saved at: {}", currentPos);
                broadcastFullPlayerState();
            }
        }
    }

    /**
     * 定时清理长时间暂停的播放器状态
     */
    /** 由 RoomTicker 每 10 分钟调用。 */
    public void cleanupIdlePlayer() {
        if (isPaused.get() && currentMusic.get() != null) {
            // 在暂停状态下，timestampAnchor 记录的是暂停开始的时间
            long pausedDuration = System.currentTimeMillis() - timestampAnchor.get();
            if (pausedDuration > IDLE_RESET_TIMEOUT_MS) {
                log.info("Idle player timeout reached. Resetting now playing.");
                currentMusic.set(null);
                positionAnchor.set(0);
                timestampAnchor.set(0);
                isPaused.set(false);
                broadcastFullPlayerState();
            }
        }
    }

    // --- Broadcasting and Helper Methods ---

    public void broadcastQueueUpdate() {
        queueDirty.set(true);
        windowDirty.set(true);
    }

    /** Flushed by one shared short-period ticker, never one timer/thread per room. */
    public void flushQueueUpdate() {
        if (closed.get()) return;
        for (String id : changedDownloads.toArray(String[]::new)) {
            if (changedDownloads.remove(id)) processDownloadChange(id);
        }
        if (windowDirty.getAndSet(false)) refreshPreparationWindow();
        long now = System.currentTimeMillis();
        if (now - lastQueueBroadcast < Math.max(50, appProperties.getPreparation().getBroadcastIntervalMs())) return;
        if (stateDirty.getAndSet(false)) {
            queueDirty.set(false);
            lastQueueBroadcast = now;
            eventPublisher.publishEvent(new PlayerStateEvent(this, getCurrentPlayerState()));
        } else if (queueDirty.compareAndSet(true, false)) {
            lastQueueBroadcast = now;
            eventPublisher.publishEvent(new QueueUpdateEvent(this, getQueueWithUpdatedStatus()));
        }
    }

    public void releasePreparationWindow() {
        synchronized (preparationWindowLock) { localCacheService.updatePreparationWindow(roomId, List.of()); }
    }

    public void shutdown() {
        closed.set(true);
        changedDownloads.clear();
        releasePreparationWindow();
        queueManager.clearReservations();
    }

    private void refreshPreparationWindow() {
        synchronized (preparationWindowLock) {
            if (closed.get() || (userService.getOnlineUserSummaries().isEmpty() && !isStreamActive.get())) {
                localCacheService.updatePreparationWindow(roomId, List.of());
                return;
            }
            List<LocalCacheService.PreparationRequest> requests = new ArrayList<>();
            Map<String, Integer> userCounts = new HashMap<>();
            int limit = Math.max(2, appProperties.getPreparation().getWindowSize());
            int perUser = Math.max(1, appProperties.getPreparation().getPerUserWindow());
            boolean onlyActiveUsers = playMode.get() == PlayMode.SHUFFLE && !allowOfflineShuffle.get();
            Set<String> activeUsers = onlyActiveUsers ? userService.getRecentlyActiveUserTokens() : Set.of();
            // Queue is already round-robin by user. Only work that still needs
            // preparation may consume the window: READY entries would otherwise
            // pin the whole window and a later import could wait forever.
            for (MusicQueueItem item : queueManager.getQueueSnapshot()) {
                if (requests.size() >= limit) break;
                if (!usesLocalCache(item.music().platform())) continue;
                CacheStatus status = localCacheService.getStatus(cacheIdFor(item.music()));
                if (status == CacheStatus.COMPLETED || status == CacheStatus.FAILED) continue;
                if (onlyActiveUsers && !activeUsers.contains(item.enqueuedBy().token())) continue;
                String user = item.enqueuedBy().token();
                int n = userCounts.getOrDefault(user, 0);
                if (n >= perUser && item.priority() != org.thornex.musicparty.enums.Priority.GLOBAL_TOP) continue;
                userCounts.put(user, n + 1);
                DownloadPriority priority = n == 0 || item.priority() == org.thornex.musicparty.enums.Priority.GLOBAL_TOP
                        ? DownloadPriority.URGENT : DownloadPriority.NORMAL;
                Music music = item.music();
                requests.add(new LocalCacheService.PreparationRequest(cacheIdFor(music), user, priority,
                        () -> getApiService(music.platform()).prefetchMusic(music.id(), priority)));
            }
            PlayableMusic current = currentMusic.get();
            if (current != null && usesLocalCache(current.platform())) {
                requests.add(new LocalCacheService.PreparationRequest(cacheIdFor(current),
                        Optional.ofNullable(currentEnqueuerId.get()).orElse("SYSTEM"), DownloadPriority.URGENT,
                        () -> getApiService(current.platform()).prefetchMusic(current.id(), DownloadPriority.URGENT)));
            }
            localCacheService.updatePreparationWindow(roomId, requests);
        }
    }

    public void broadcastFullPlayerState() {
        stateDirty.set(true);
    }

    public void broadcastOnlineUsers() {
        // This is triggered by UserService, so we can keep it simple or create another event type
        broadcastFullPlayerState();
    }

    public void broadcastPasswordChanged() {
        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "房间密码已更改，请重新验证"));
    }

    private List<MusicQueueItem> getQueueWithUpdatedStatus() {
        return queueManager.getQueueSnapshot().stream().map(item -> {
            if (usesLocalCache(item.music().platform())) {
                CacheStatus cacheStatus = localCacheService.getStatus(cacheIdFor(item.music()));
                QueueItemStatus newStatus = mapCacheStatusToEnum(cacheStatus);
                if (item.status() != newStatus) {
                    return item.withStatus(newStatus);
                }
            }
            return item;
        }).collect(Collectors.toList());
    }

    private Map<String, QueueItemStatus> buildStatusMap() {
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        for (MusicQueueItem item : queueManager.getQueueSnapshot()) {
            if (usesLocalCache(item.music().platform())) {
                statusMap.put(MusicQueueManager.musicKey(item.music()), mapCacheStatusToEnum(localCacheService.getStatus(cacheIdFor(item.music()))));
            } else {
                statusMap.put(MusicQueueManager.musicKey(item.music()), QueueItemStatus.READY);
            }
        }
        return statusMap;
    }

    private QueueItemStatus mapCacheStatusToEnum(CacheStatus status) {
        if (status == null) return QueueItemStatus.PENDING;
        return switch (status) {
            case COMPLETED -> QueueItemStatus.READY;
            case DOWNLOADING -> QueueItemStatus.DOWNLOADING;
            case FAILED -> QueueItemStatus.FAILED;
            default -> QueueItemStatus.PENDING;
        };
    }

    private boolean isPlatformEnabled(String platform) {
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "netease" -> appProperties.getNetease().isEnabled();
            case "bilibili" -> appProperties.getBilibili().isEnabled();
            case "soundcloud" -> appProperties.getSoundcloud().isEnabled();
            case "ytmusic" -> appProperties.getYtmusic().isEnabled();
            default -> false;
        };
    }

    private boolean usesLocalCache(String platform) {
        return "netease".equals(platform) || "bilibili".equals(platform) || "soundcloud".equals(platform) || "ytmusic".equals(platform);
    }

    private String cacheIdFor(Music music) {
        return "bilibili".equals(music.platform()) ? music.id() : music.platform() + "_" + music.id();
    }

    private String cacheIdFor(PlayableMusic music) {
        return "bilibili".equals(music.platform()) ? music.id() : music.platform() + "_" + music.id();
    }

    /*private void resetPauseState() {
        isPaused.set(false);
        pauseStateChangeTime.set(0);
        totalPausedTimeMillis.set(0);
    }*/

    private boolean isRateLimited(String userId) {
        long now = System.currentTimeMillis();
        // Admin/system commands must not be swallowed by the per-user throttle:
        // the endpoint still reports success, so a dropped command looks like a
        // dead switch in the dashboard.
        if ("SYSTEM".equals(userId)) return false;
        Long previous = lastControlAt.put(userId, now);
        if (previous != null && now - previous < CONTROL_COOLDOWN_MS) {
            log.debug("Action rate limited for user: {}", userId);
            return true;
        }
        // Keep the map from growing with one entry per token that ever pressed a button.
        if (lastControlAt.size() > 512) {
            lastControlAt.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
        }
        return false;
    }

    public AppProperties getAppProperties() {
        return appProperties;
    }

    public String getRoomId() {
        return roomId;
    }

    private boolean isMainRoom() {
        return "main".equals(roomId);
    }

    private IMusicApiService getApiService(String platform) {
        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) throw new ApiRequestException("Unsupported platform: " + platform);
        return service;
    }

    private String getUserToken(String sessionId) {
        if ("SYSTEM".equals(sessionId)) return "SYSTEM";
        return userService.getUser(sessionId).map(User::getToken).orElse("UNKNOWN_TOKEN");
    }

    private String getUserName(String sessionId) {
        return userService.getUser(sessionId).map(User::getName).orElse("Unknown User");
    }
}
