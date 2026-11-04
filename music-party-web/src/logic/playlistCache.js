/**
 * 歌单曲目缓存（进程内 + localStorage 持久化）。
 *
 * 背景（2026-11）：原先每次点开歌单都清空并从第 0 条重新拉取，
 * 于是 A→B→A 要重下整页，刷新页面也要重下；而滚动位置虽然存了，
 * 恢复时列表还是空的，scrollTop 会被浏览器夹回 0——等于没存。
 *
 * 策略：
 *  - 以 platform + playlistId 为键，缓存已加载的曲目、offset、hasMore 与滚动位置；
 *  - 命中即同步还原（无请求、无 loading 闪烁），滚动位置随后由 UI 恢复；
 *  - TTL 30 分钟，避免歌单在别处被改动后长期显示旧内容；
 *  - 最多保留 8 个歌单，按最近使用淘汰。
 */

// v2：v1 里存过「提前到底」的 hasMore=false，导致重新进入后直接 END。
const STORAGE_KEY = 'mp_playlist_cache_v2';
const TTL_MS = 30 * 60 * 1000;
const MAX_ENTRIES = 8;

const keyOf = (platform, playlistId) => `${platform}:${playlistId}`;

function readAll() {
    if (typeof localStorage === 'undefined') return {};
    try {
        const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
        return raw && typeof raw === 'object' ? raw : {};
    } catch {
        return {};
    }
}

function writeAll(map) {
    if (typeof localStorage === 'undefined') return;
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
    } catch {
        // 配额满或隐私模式：缓存只是加速手段，失败不影响功能
    }
}

export function readPlaylistCache(platform, playlistId) {
    if (!platform || !playlistId) return null;
    const map = readAll();
    const key = keyOf(platform, playlistId);
    const entry = map[key];
    if (!entry) return null;

    if (!entry.at || Date.now() - entry.at > TTL_MS) {
        delete map[key];
        writeAll(map);
        return null;
    }
    return entry;
}

export function writePlaylistCache(platform, playlistId, data) {
    if (!platform || !playlistId || !data) return;
    const map = readAll();
    // at 保持严格单调：同一毫秒内连续写入时，淘汰顺序才不会随机
    const lastAt = Object.values(map).reduce((max, entry) => Math.max(max, entry?.at || 0), 0);
    map[keyOf(platform, playlistId)] = {
        songs: data.songs || [],
        offset: data.offset || 0,
        hasMore: data.hasMore !== false,
        scrollTop: Math.max(0, Math.round(data.scrollTop || 0)),
        at: Math.max(Date.now(), lastAt + 1)
    };

    const keys = Object.keys(map).sort((a, b) => (map[b].at || 0) - (map[a].at || 0));
    for (const stale of keys.slice(MAX_ENTRIES)) delete map[stale];
    writeAll(map);
}

/** 供「刷新歌单」之类的显式操作使用 */
export function dropPlaylistCache(platform, playlistId) {
    const map = readAll();
    delete map[keyOf(platform, playlistId)];
    writeAll(map);
}
