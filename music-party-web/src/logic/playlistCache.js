/**
 * 歌单曲目缓存（进程内 + localStorage 持久化）。
 *
 * 核心策略（2026-11 完善）：
 *  - 尽可能减少加载次数（尤其是慢速的 SoundCloud / 网易云长歌单），让 REFRESH LIST 发挥正当作用；
 *  - 以 platform + playlistId 为键，缓存已加载的曲目、offset、hasMore 与滚动位置；
 *  - 命中即同步还原（无网络请求、无 loading 闪烁），滚动位置随后由 UI 多帧稳妥恢复；
 *  - 7 天长效缓存（7 * 24h），用户听歌期间与关掉标签页数天内不会无谓失效；用户需要更新时点击 REFRESH LIST；
 *  - 最多保留 64 个歌单，按最近使用淘汰（LRU）；
 *  - 滚动时支持轻量级更新 scrollTop，防止刷新标签页时滚动位置丢失；
 *  - 歌单列表（User Playlists）同样持久化，刷新标签页打开弹窗瞬间就能看到列表。
 */

const STORAGE_KEY = 'mp_playlist_cache_v2';
const PLAYLISTS_STORAGE_KEY = 'mp_user_playlists_cache_v1';
const TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 天长效缓存
const PLAYLISTS_TTL_MS = 24 * 60 * 60 * 1000; // 歌单列表 24 小时
const MAX_ENTRIES = 64;

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

/** 轻量级更新滚动位置：滚动时防抖调用，避免深拷贝整个 songs 数组 */
export function updatePlaylistScrollTop(platform, playlistId, scrollTop) {
    if (!platform || !playlistId) return;
    const map = readAll();
    const key = keyOf(platform, playlistId);
    const entry = map[key];
    if (!entry) return;

    entry.scrollTop = Math.max(0, Math.round(scrollTop || 0));
    entry.at = Date.now();
    writeAll(map);
}

/** 供「刷新歌单」之类的显式操作使用 */
export function dropPlaylistCache(platform, playlistId) {
    const map = readAll();
    delete map[keyOf(platform, playlistId)];
    writeAll(map);
}

// ----------------- 用户歌单列表（User Playlists）缓存 -----------------

function readUserPlaylistsAll() {
    if (typeof localStorage === 'undefined') return {};
    try {
        const raw = JSON.parse(localStorage.getItem(PLAYLISTS_STORAGE_KEY) || '{}');
        return raw && typeof raw === 'object' ? raw : {};
    } catch {
        return {};
    }
}

function writeUserPlaylistsAll(map) {
    if (typeof localStorage === 'undefined') return;
    try {
        localStorage.setItem(PLAYLISTS_STORAGE_KEY, JSON.stringify(map));
    } catch {
        /* ignore */
    }
}

export function getUserPlaylistsCache(platform, uid) {
    if (!platform || !uid) return null;
    const map = readUserPlaylistsAll();
    const key = keyOf(platform, uid);
    const entry = map[key];
    if (!entry) return null;
    if (!entry.at || Date.now() - entry.at > PLAYLISTS_TTL_MS) {
        delete map[key];
        writeUserPlaylistsAll(map);
        return null;
    }
    return Array.isArray(entry.playlists) ? entry.playlists : null;
}

export function setUserPlaylistsCache(platform, uid, playlists) {
    if (!platform || !uid || !Array.isArray(playlists)) return;
    const map = readUserPlaylistsAll();
    map[keyOf(platform, uid)] = {
        playlists,
        at: Date.now()
    };
    writeUserPlaylistsAll(map);
}
