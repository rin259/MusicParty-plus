/**
 * 聊天列表的行构建（纯函数，便于测试）。
 *
 * 关键行为：
 *  - 按 Tab 过滤消息类型；
 *  - 超过 3 分钟显示时间戳；
 *  - 连续的 LIKE 合并成一行并计数（和 toast 的 ×N 一致），
 *    时间窗内连续点赞不再刷屏；跨过时间窗或中间夹了别的消息就另起一行。
 */

export const LIKE_MERGE_WINDOW_MS = 15_000;
export const TIME_GAP_MS = 3 * 60 * 1000;

const keepForTab = (type, tab) => {
    if (tab === 'CHAT') return type === 'CHAT' || type === 'LIKE' || type === 'PLAY_START';
    if (tab === 'SYSTEM INFO') return type === 'SYSTEM' || type === 'LIKE' || type === 'PLAY_START';
    return false;
};

export function buildChatRows(messages, tab) {
    const rows = [];
    let lastTime = 0;

    for (const msg of messages || []) {
        if (!msg || !keepForTab(msg.type, tab)) continue;

        const showTime = (msg.timestamp || 0) - lastTime > TIME_GAP_MS;
        if (showTime) lastTime = msg.timestamp || 0;

        const prev = rows.length ? rows[rows.length - 1] : null;
        const mergeLike = msg.type === 'LIKE'
            && prev
            && prev.msg.type === 'LIKE'
            && (msg.timestamp || 0) - (prev.msg.timestamp || 0) <= LIKE_MERGE_WINDOW_MS;

        if (mergeLike) {
            prev.count += 1;
            prev.msg = msg;            // 展示最近一条的文案
            prev.showTime = prev.showTime || showTime;
            continue;
        }

        // id 保持为“这一行的第一条消息”，点赞合并时 DOM 节点和滚动锚点都不会换
        rows.push({ id: msg.id, msg, showTime, count: 1 });
    }

    return rows;
}
