import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useChatStore } from '../stores/chat';
import { useToast } from '../composables/useToast';
import { useAdminStore } from '../stores/admin';
import { useRoomStore } from '../stores/room';
import { adminApi } from '../api/admin';
import { WS_DEST, roomTopics } from '../constants/api';
import { socketService } from './socket';

/**
 * 处理游戏/播放器事件通知 (Toast)
 * 这里集中管理所有的业务通知文案
 */
function handleGameEvent(event) {
    const userStore = useUserStore();
    const playerStore = usePlayerStore();
    const roomStore = useRoomStore();
    const chatStore = useChatStore();
    const adminStore = useAdminStore();
    const { show, error, like } = useToast(); // This now uses the Pinia store wrapper
    const userName = event.userId === 'SYSTEM' ? '系统' : userStore.resolveName(event.userId);

    // 1. 处理特殊业务逻辑 (非 UI 展示)
    if (event.action === 'LIKE') {
        window.dispatchEvent(new CustomEvent('player:like', { detail: { userId: event.userId } }));
        // 点赞 toast 堆叠：连续点赞合并为一条，右上角 ×N 计数，淡出节奏不变
        like(event.message || `${userName} 点赞了`);
        return;
    }

    if (event.action === 'ROOM_CLOSED') {
        // 房主关闭房间：断开连接并回到落地页，避免停留在一个已不存在的房间
        error(event.message || '房间已被房主关闭');
        socketService.disconnect();
        playerStore.connected = false;
        playerStore.resetRoomState();
        roomStore.reject('房间已被房主关闭');
        return;
    }

    if (event.action === 'ADMIN_TRIGGER') {
        if (adminStore.adminPassword) {
            // Attempt auto-login
            adminApi.verify(adminStore.adminPassword)
                .then(() => {
                    adminStore.isVerified = true;
                    adminStore.showDashboard = true;
                })
                .catch(() => {
                    adminStore.logout();
                    adminStore.showAuthModal = true;
                });
        } else {
            adminStore.showAuthModal = true;
        }
        return;
    }

    if (event.action === 'RESET') {
        chatStore.messages = []; // 清空聊天
    }

    if (event.action === 'PASSWORD_CHANGED') {
        error('房间密码已更改，请重新验证');
        setTimeout(() => {
            userStore.resetAuthentication();
            window.location.reload();
        }, 1500);
        return;
    }

    if (event.action === 'RENAME_FAILED' || (event.type === 'ERROR' && event.message && (event.message.includes('taken') || event.message.includes('占用')))) {
        error(event.message || '该名称已被占用，请更换。');
        userStore.showNameModal = true;
        return;
    }

    // 过滤掉用户进入/离开、以及歌曲开始播放的系统内部通知，避免弹窗干扰
    // 这些事件已经在 Chat Log 中展示，Toast 只展示关键交互
    if (event.action === 'USER_JOIN' || event.action === 'USER_LEAVE' || event.action === 'PLAY_START') {
        return;
    }

    // 2. 使用后端传来的格式化消息
    let msgText = event.message || event.payload || `${userName} ${event.action}`;

    // 将后端 Level 枚举映射为 toast 类型
    const typeMap = { 'error': 'error', 'warn': 'warning', 'success': 'success' };
    let type = typeMap[event.type?.toLowerCase()] || 'info';
    if (event.action === 'ERROR_LOAD') type = 'error';

    show({
        title: event.action === 'ERROR_LOAD' ? 'PLAYBACK ERROR'
             : event.action === 'SYSTEM_MESSAGE' ? 'SYSTEM'
             : event.action === 'MODE_CHANGE' ? '播放模式'
             : event.action,
        message: msgText,
        type: type,
        duration: 3000
    });
}

/**
 * 创建并返回 Socket 订阅配置
 * @returns {Object} 订阅路径 -> 回调函数 的映射
 */
export const createSocketSubscriptions = (roomId = 'main') => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();
    const chatStore = useChatStore();
    const topics = roomTopics(roomId);

    return {
        // 1. 状态同步（房间频道）
        [topics.TOPIC_STATE]: (state) => playerStore.syncState(state),
        [WS_DEST.USER_STATE]: (state) => playerStore.syncState(state),

        // 2. 用户列表
        [topics.TOPIC_USERS]: (users) => userStore.setOnlineUsers(users),

        // 3. 队列更新
        [topics.TOPIC_QUEUE]: (data) => { playerStore.queue = data; },
        [topics.TOPIC_SEEK]: (positionMs) => {
            window.dispatchEvent(new CustomEvent('music-party:seek', { detail: Number(positionMs) }));
        },

        // 4. 事件通知 (Toast)
        [topics.TOPIC_EVENTS]: handleGameEvent,
        [WS_DEST.USER_EVENTS]: handleGameEvent,

        // 4b. 点歌回执 / 导入进度（只发给自己）
        [WS_DEST.USER_ENQUEUE_RESULT]: (result) => {
            const handled = playerStore.applyEnqueueResult(result);
            if (handled && handled.status !== 'QUEUED') {
                const { error } = useToast();
                error(handled.message || '添加失败');
            }
        },
        [WS_DEST.USER_IMPORT_PROGRESS]: (progress) => {
            playerStore.applyImportProgress(progress);
        },

        // 5. 聊天相关
        [topics.TOPIC_CHAT]: (msg) => chatStore.addMessage(msg),
        [WS_DEST.USER_PRIVATE_CHAT]: (msg) => chatStore.addMessage(msg),

        // 初始历史记录
        [WS_DEST.APP_CHAT_HISTORY]: (history) => chatStore.setHistory(history),

        // 分页历史记录回调
        [WS_DEST.USER_CHAT_HISTORY]: (moreMessages) => chatStore.prependHistory(moreMessages)
    };
};

/**
 * [新增] 创建 Socket 生命周期回调
 * 包含：连接成功处理、断连处理、错误处理
 */
export const createSocketCallbacks = () => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();
    const roomStore = useRoomStore();

    return {
        // 连接成功
        onConnect: () => {
            playerStore.connected = true;
            // 发起同步
            setTimeout(() => {
                socketService.send(WS_DEST.RESYNC);
            }, 300);
            // 恢复绑定
            Object.entries(userStore.bindings).forEach(([platform, id]) => {
                if (id) playerStore.bindAccount(platform, id);
            });
        },

        // 连接断开 (含异常断开)
        onDisconnect: () => {
            playerStore.connected = false;
        },

        // STOMP 协议层错误 (如密码错误、Token失效、服务器内部错误等)
        onStompError: (frame) => {
            console.error('STOMP Error:', frame);

            // 房间相关错误：不要 reload（会把用户丢回落地页并丢掉刚输入的密码），
            // 直接断开并交回落地页显示原因。
            const body = frame.body || '';
            if (body.includes('INVALID_ROOM_PASSWORD')) {
                socketService.disconnect();
                playerStore.connected = false;
                playerStore.resetRoomState();
                roomStore.reject('房间密码错误，或密码已变更');
                return;
            }
            if (body.includes('ROOM_NOT_FOUND')) {
                socketService.disconnect();
                playerStore.connected = false;
                playerStore.resetRoomState();
                roomStore.reject('房间不存在或已关闭');
                return;
            }

            // 其他协议级错误：强制刷新以重置状态
            window.location.reload();
        }
    };
};

// 为了兼容旧代码命名，导出这个别名
const handleEventMessage = handleGameEvent;
