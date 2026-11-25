// 房间级广播频道：所有房间内的状态都从这里走，跨房间串消息在结构上不可能发生
export const roomTopics = (roomId) => {
    const id = roomId || 'main';
    return {
        TOPIC_EVENTS: `/topic/r/${id}/player/events`,
        TOPIC_STATE: `/topic/r/${id}/player/state`,
        TOPIC_QUEUE: `/topic/r/${id}/player/queue`,
        TOPIC_USERS: `/topic/r/${id}/users/online`,
        TOPIC_CHAT: `/topic/r/${id}/chat`,
        TOPIC_SEEK: `/topic/r/${id}/player/seek`
    };
};

// WebSocket 目的地
export const WS_DEST = {
    // 发送指令 (Publish)
    CHAT_SEND: '/app/chat',
    CHAT_HISTORY_FETCH: '/app/chat/history/fetch',
    PLAYER_NEXT: '/app/control/next',
    PLAYER_PAUSE: '/app/control/toggle-pause',
    PLAYER_SHUFFLE: '/app/control/toggle-shuffle',
    PLAYER_LIKE: '/app/control/like',
    PLAYER_SEEK: '/app/control/seek',
    ENQUEUE: '/app/enqueue',
    ENQUEUE_PLAYLIST: '/app/enqueue/playlist',
    ENQUEUE_ALBUM: '/app/enqueue/album',
    QUEUE_TOP: '/app/queue/top',
    QUEUE_REMOVE: '/app/queue/remove',
    USER_BIND: '/app/user/bind',
    USER_RENAME: '/app/user/rename',
    RESYNC: '/app/player/resync',

    // 订阅频道请使用 roomTopics(roomId)，不要在这里写死全局频道
    // 个人频道
    USER_ME: '/app/user/me',
    USER_ME_UPDATE: '/user/queue/me',
    APP_CHAT_HISTORY: '/app/chat/history',
    USER_STATE: '/user/queue/player/state',
    USER_VOTE: '/user/queue/vote',
    USER_CHAT_HISTORY: '/user/queue/chat/history',
    USER_EVENTS: '/user/queue/events',
    USER_PRIVATE_CHAT: '/user/queue/chat/private',
    USER_ENQUEUE_RESULT: '/user/queue/enqueue',
    USER_IMPORT_PROGRESS: '/user/queue/import'
};
