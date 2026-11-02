// src/stores/player.js

import { defineStore } from 'pinia';
import { ref, computed, watch } from 'vue';
import { useUserStore } from './user';
import { socketService } from '../services/socket';
import { createSocketSubscriptions, createSocketCallbacks } from '../services/socketHandler'; // 引入新文件
import { musicApi } from '../api/music';
import { WS_DEST } from '../constants/api';
import { STORAGE_KEYS } from '../constants/keys';

export const usePlayerStore = defineStore('player', () => {
    // === 1. State ===
    const nowPlaying = ref(null);
    const queue = ref([]);
    const isPaused = ref(false);
    const playMode = ref('SEQUENTIAL'); // 'SEQUENTIAL' | 'SHUFFLE' | 'REPEAT_ONE' | 'LIST_LOOP'
    const isShuffle = computed(() => playMode.value === 'SHUFFLE');
    const isRepeatOne = computed(() => playMode.value === 'REPEAT_ONE');
    const isListLoop = computed(() => playMode.value === 'LIST_LOOP');
    const isFairShuffle = ref(true);
    const allowOfflineShuffle = ref(false);
    const isPauseLocked = ref(false);
    const isSkipLocked = ref(false);
    const isPlayModeLocked = ref(false);
    const lyricText = ref('');
    const connected = ref(false);
    const isLoading = ref(false);
    const streamListenerCount = ref(0);
    const streamActive = ref(false);
    const lastControlTime = ref(0);
    const remotePosition = ref(0);
    const lastSyncTime = ref(0);
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const isErrorState = ref(false);

    // === 点歌可观测性 ===
    // key = platform:id。提交中 → 服务端回执（QUEUED/FAILED…）→ 队列快照确认。
    const enqueueStates = ref({});
    const pendingEnqueues = ref({});   // requestId -> { key, platform, musicId, at, timer }
    const importJobs = ref({});        // requestId -> ImportProgress
    const ackTimers = new Map();

    const songKey = (platform, musicId) => `${platform}:${musicId}`;

    // 投票切歌相关
    const isVoteSkipEnabled = ref(false);
    const voteSkipThreshold = ref(0.5);
    const voteSkipWaitTime = ref(15);
    const currentVotes = ref(0);
    const eligibleUsers = ref(0);

    const config = ref({
        maxQueueSize: 1000,
        maxHistorySize: 50,
        maxUserSongs: 100,
        maxPlaylistImportSize: 100,
        maxChatHistorySize: 1000,
        minChatIntervalMs: 1000,
        maxChatMessageLength: 200,
        neteaseEnabled: true,
        bilibiliEnabled: true,
        soundcloudEnabled: true,
        ytmusicEnabled: true,
        voteSkipEnabled: false,
        voteSkipThreshold: 0.5,
        voteSkipWaitTime: 15
    });

    const userStore = useUserStore();
    const LOCAL_COOLDOWN = 500; // 稍微调低一点冷却时间提升手感

    // === 2. Logic ===
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;
        if (isPaused.value) {
            return remotePosition.value;
        } else {
            // 本地简单的推算：服务器给的进度 + (当前时间 -收到包的时间)
            return remotePosition.value + (Date.now() - lastSyncTime.value);
        }
    };

    const requireAuth = () => {
        if (userStore.isGuest) {
            userStore.showNameModal = true;
            return false;
        }
        return true;
    };

    const checkCooldown = () => {
        const now = Date.now();
        if (now - lastControlTime.value < LOCAL_COOLDOWN) {
            // 这里可以不再弹 Toast 报错，而是静默失败，避免刷屏
            return false;
        }
        lastControlTime.value = now;
        return true;
    };

    // === 3. Actions ===

    const syncState = (state) => {
        nowPlaying.value = state.nowPlaying;
        queue.value = state.queue;
        isPaused.value = state.isPaused;
        playMode.value = state.playMode || (state.isShuffle ? 'SHUFFLE' : 'SEQUENTIAL');
        isFairShuffle.value = state.isFairShuffle !== undefined ? state.isFairShuffle : true;
        allowOfflineShuffle.value = state.allowOfflineShuffle || false;
        isPauseLocked.value = state.isPauseLocked || false;
        isSkipLocked.value = state.isSkipLocked || false;
        isPlayModeLocked.value = state.isPlayModeLocked || false;
        isLoading.value = state.isLoading || false;
        streamListenerCount.value = state.streamListenerCount || 0;
        streamActive.value = state.isStreamEnabled || false;

        // 投票状态同步
        isVoteSkipEnabled.value = state.isVoteSkipEnabled || false;
        voteSkipThreshold.value = state.voteSkipThreshold || 0.5;
        voteSkipWaitTime.value = state.voteSkipWaitTime || 15;
        currentVotes.value = state.currentVotes || 0;
        eligibleUsers.value = state.eligibleUsers || 0;

        // 记录服务器发来的进度和收到包的时间
        if (state.nowPlaying) {
            remotePosition.value = state.nowPlaying.currentPosition;
            lastSyncTime.value = Date.now();
        } else {
            remotePosition.value = 0;
        }

        if (state.onlineUsers) {
            userStore.setOnlineUsers(state.onlineUsers);
        }

        if (state.config) {
            config.value = { ...state.config };
        }
    };

    const connect = () => {
        const authHeaders = {
            'user-name': localStorage.getItem(STORAGE_KEYS.USERNAME) || '游客',
            'user-token': userStore.userToken,
            'room-password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || ''
        };

        // 使用抽离出的订阅配置
        const subscriptions = createSocketSubscriptions();

        // 补充 UserMe 的特殊处理 (因为它需要用到 renameUser，如果放在 socketHandler 会导致循环依赖)
        subscriptions[WS_DEST.USER_ME] = (me) => {
            // me: { token, sessionId, name, isGuest }
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        subscriptions[WS_DEST.USER_ME_UPDATE] = (me) => {
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        const callbacks = createSocketCallbacks();

        socketService.connect(authHeaders, callbacks, subscriptions);
    };

    const tryReconnect = (force = false) => {
        return socketService.forceReconnect(force);
    };

    // --- 指令发送 ---
    const playNext = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_NEXT);
    const togglePause = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_PAUSE);
    const cyclePlayMode = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_SHUFFLE);

    function newRequestId() {
        if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
        return `r-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    const enqueue = (platform, musicId) => {
        if (!requireAuth()) return null;
        const requestId = newRequestId();
        const key = songKey(platform, musicId);
        enqueueStates.value[key] = { state: 'submitting', message: null, at: Date.now() };
        pendingEnqueues.value[requestId] = { key, platform, musicId, at: Date.now() };
        const timer = setTimeout(() => {
            if (!pendingEnqueues.value[requestId]) return;
            delete pendingEnqueues.value[requestId];
            const current = enqueueStates.value[key];
            if (current && current.state === 'submitting') {
                enqueueStates.value[key] = { state: 'failed', message: '服务端无响应（15s），请重试', at: Date.now() };
            }
        }, 15_000);
        ackTimers.set(requestId, timer);
        socketService.send(WS_DEST.ENQUEUE, { platform, musicId, requestId });
        return requestId;
    };

    /** 服务端回执：结束 submitting，落到 queued / failed，并记录真实原因。 */
    const applyEnqueueResult = (result) => {
        if (!result?.requestId) return null;
        const pending = pendingEnqueues.value[result.requestId];
        clearTimeout(ackTimers.get(result.requestId));
        ackTimers.delete(result.requestId);
        if (pending) delete pendingEnqueues.value[result.requestId];

        const key = songKey(result.platform, result.musicId || pending?.musicId);
        const queued = result.status === 'QUEUED';
        enqueueStates.value[key] = {
            state: queued ? 'queued' : 'failed',
            message: result.message || null,
            code: result.status,
            at: Date.now()
        };
        return result;
    };

    const enqueuePlaylist = (platform, playlistId) => {
        if (!requireAuth()) return null;
        const requestId = newRequestId();
        importJobs.value[requestId] = {
            requestId, platform, listId: playlistId, kind: 'playlist',
            done: 0, total: 0, added: 0, skipped: 0, finished: false,
            startedAt: Date.now(), message: null
        };
        socketService.send(WS_DEST.ENQUEUE_PLAYLIST, { platform, playlistId, requestId });
        return requestId;
    };

    const enqueueAlbum = (platform, albumId) => {
        if (!requireAuth()) return null;
        const requestId = newRequestId();
        importJobs.value[requestId] = {
            requestId, platform, listId: albumId, kind: 'album',
            done: 0, total: 0, added: 0, skipped: 0, finished: false,
            startedAt: Date.now(), message: null
        };
        socketService.send(WS_DEST.ENQUEUE_ALBUM, { platform, playlistId: albumId, requestId });
        return requestId;
    };

    const applyImportProgress = (progress) => {
        if (!progress?.requestId) return null;
        const previous = importJobs.value[progress.requestId] || {};
        // 服务端进度不含 startedAt，合并而非覆盖，否则列表排序会丢失起点。
        importJobs.value[progress.requestId] = { ...previous, ...progress };
        return progress;
    };

    /** 当前用户的已点数量与配额（服务端 config 为准）。 */
    const mySongCount = computed(() => queue.value.filter(
        (item) => item.enqueuedBy?.token === userStore.userToken
    ).length);
    const mySongLimit = computed(() => config.value.maxUserSongs || 0);

    const isInQueue = (platform, musicId) => queue.value.some(
        (item) => item.music?.id === musicId
            && (item.music?.platform || platform) === platform
    );
    const topSong = (queueId) => requireAuth() && socketService.send(WS_DEST.QUEUE_TOP, { queueId });
    const removeSong = (queueId) => requireAuth() && socketService.send(WS_DEST.QUEUE_REMOVE, { queueId });

    const bindAccount = (platform, accountId) => {
        socketService.send(WS_DEST.USER_BIND, { platform, accountId });
        userStore.updateBinding(platform, accountId);
    };

    const renameUser = (newName) => {
        socketService.send(WS_DEST.USER_RENAME, { newName });
        // userStore.saveName(newName) removed; relying on backend sync
    };

    const sendLike = () => {
        if (requireAuth()) socketService.send(WS_DEST.PLAYER_LIKE);
    };

    const seekTo = (positionMs) => {
        if (requireAuth()) socketService.send(WS_DEST.PLAYER_SEEK, { positionMs: Math.round(positionMs) });
    };

    const sendChatMessage = (content) => {
        if (requireAuth()) socketService.send(WS_DEST.CHAT_SEND, { content });
    };

    // 歌词监听
    watch(() => nowPlaying.value?.music?.id, async (newId) => {
        lyricText.value = '';
        if (!newId) return;
        try {
            const platform = nowPlaying.value.music.platform;
            const data = await musicApi.getLyric(platform, newId);
            lyricText.value = data || '';
        } catch (e) {
            console.error("Lyrics Error", e);
        }
    });

    // 队列快照确认：即使回执丢失，只要歌真的进了队列，行状态也应落到 queued。
    watch(() => queue.value, (list) => {
        if (!Array.isArray(list) || list.length === 0) return;
        const present = new Set(list.map((item) => songKey(item.music?.platform, item.music?.id)));
        for (const [key, state] of Object.entries(enqueueStates.value)) {
            if (state.state === 'submitting' && present.has(key)) {
                enqueueStates.value[key] = { state: 'queued', message: '已加入队列', at: Date.now() };
            }
        }
    });

    return {
nowPlaying, queue, isPaused, playMode, isShuffle, isRepeatOne, isListLoop, isFairShuffle, allowOfflineShuffle, config,
        isPauseLocked, isSkipLocked, isPlayModeLocked, connected, isLoading, lyricText,
        localProgress, isBuffering, isErrorState, streamListenerCount, streamActive,
        isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, currentVotes, eligibleUsers,
        connect, tryReconnect, getCurrentProgress, syncState,
        playNext, togglePause, cyclePlayMode,
        enqueue, enqueuePlaylist, enqueueAlbum, topSong, removeSong,
        enqueueStates, pendingEnqueues, importJobs, applyEnqueueResult, applyImportProgress,
        mySongCount, mySongLimit, isInQueue, songKey,
        bindAccount, renameUser, sendChatMessage, sendLike, seekTo
    };
});
