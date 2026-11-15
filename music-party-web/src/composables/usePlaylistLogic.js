import { ref, computed, watch } from 'vue';
import { useUserStore } from '../stores/user.js';
import { usePlayerStore } from '../stores/player.js';
import { musicApi } from '../api/music.js';
import { useDebounceFn } from '@vueuse/core';
import { useToast } from './useToast';
import { readPlaylistCache, writePlaylistCache } from '../logic/playlistCache.js';

export function usePlaylistLogic(platformRef, songsRef, listModeRef, loadingRef) {
    const userStore = useUserStore();
    const playerStore = usePlayerStore();
    const { error } = useToast();

    // State
    const playlists = ref([]);
    const currentPlaylistId = ref(null);
    const searchUserKeyword = ref('');
    const userSearchResults = ref([]);
    const isSearchingUser = ref(false);
    const userSearchAttempted = ref(false);   // 用于区分「还没搜」和「搜了但没有」
    // 歌单刷新：重新核对已加载的曲目，但不先清空——加载一个长歌单要几分钟，
    // 刷掉再重来等于把用户已经等到的数据扔掉
    const refreshState = ref({ active: false, done: 0, target: 0 });
    const isPlaylistsLoading = ref(false);

    // 歌单缓存：切走时保存，切回时同步还原（含滚动位置）
    let viewportScrollTop = 0;
    let pendingRestoreScrollTop = 0;
    const setPlaylistScrollTop = (value) => { viewportScrollTop = Math.max(0, Number(value) || 0); };
    const takeRestoreScrollTop = () => {
        const value = pendingRestoreScrollTop;
        pendingRestoreScrollTop = 0;
        return value;
    };

    // Pagination
    const offset = ref(0);
    const hasMore = ref(true);
    const isLoadingMore = ref(false);
    const limit = computed(() => platformRef.value === 'bilibili' ? 20 : 50);

    const bindings = computed(() => userStore.bindings);

    // 获取用户歌单。
    // 服务端生成一份 SoundCloud 歌单列表要 1~4 秒，而列表内容几乎不变：
    //  - 会话内缓存：切回同一平台时先显示上次结果，后台再刷新（不白屏、不重复等）
    //  - 请求序号：切平台后迟到的响应直接丢弃，不能覆盖当前平台的列表
    const playlistsCache = new Map();
    const playlistsCacheAt = new Map();    // key -> 上次取到的时间
    const PLAYLISTS_FRESH_MS = 60_000;     // 与服务端缓存同寿命：一分钟内切回无需再问
    const playlistsInFlight = new Map();   // key -> 同一个请求，避免预热和正式加载各打一次
    let playlistsRequestSeq = 0;

    const requestPlaylists = (platform, uid) => {
        const key = `${platform}:${uid}`;
        const running = playlistsInFlight.get(key);
        if (running) return running;
        const request = musicApi.getUserPlaylists(platform, uid);
        playlistsInFlight.set(key, request);
        request.catch(() => {}).finally(() => playlistsInFlight.delete(key));
        return request;
    };

    const fetchPlaylists = async () => {
        const platform = platformRef.value;
        const uid = bindings.value[platform];
        // 先占号：即使这个平台没绑定（下面直接 return），也要让上一平台的在途请求作废
        const seq = ++playlistsRequestSeq;
        if (!uid) {
            playlists.value = [];
            isPlaylistsLoading.value = false;
            return;
        }

        const key = `${platform}:${uid}`;
        const cached = playlistsCache.get(key);
        if (cached) playlists.value = cached;
        if (cached && Date.now() - (playlistsCacheAt.get(key) || 0) < PLAYLISTS_FRESH_MS) {
            // 一分钟内刚取过：直接用缓存，不再发请求
            isPlaylistsLoading.value = false;
            return;
        }

        isPlaylistsLoading.value = !cached;   // 有缓存就静默刷新，不再用 spinner 盖住列表
        try {
            const data = await requestPlaylists(platform, uid);
            if (seq !== playlistsRequestSeq) return;   // 已切到别的平台，丢弃过期响应
            playlists.value = data || [];
            playlistsCache.set(key, playlists.value);
            playlistsCacheAt.set(key, Date.now());
        } catch (e) {
            if (seq !== playlistsRequestSeq) return;
            console.error(e);
            // 失败不清空：有旧列表就继续用，别让用户对着空面板
            if (e.response?.data?.message) {
                error(e.response.data.message);
            }
        } finally {
            if (seq === playlistsRequestSeq) isPlaylistsLoading.value = false;
        }
    };

    /**
     * 预热已绑定平台的歌单列表（打开「添加歌曲」时调用）。
     * SoundCloud 冷启动要 1.5 秒左右，提前在后台取好，用户真正切过去时列表已经在缓存里。
     * 只写缓存，不碰当前 UI 状态，避免和正常流程抢 playlists/isPlaylistsLoading。
     */
    const prefetchPlaylists = () => {
        Object.entries(bindings.value || {}).forEach(([platform, uid]) => {
            if (!platform || !uid) return;
            const key = `${platform}:${uid}`;
            if (playlistsCache.has(key)) return;
            requestPlaylists(platform, uid)
                .then((data) => {
                    if (!Array.isArray(data)) return;
                    playlistsCache.set(key, data);
                    playlistsCacheAt.set(key, Date.now());
                })
                .catch(() => { /* 预热失败无所谓，切过去时会正常重试 */ });
        });
    };

    // 搜索用户 (用于绑定)
    const searchUser = async () => {
        if (!searchUserKeyword.value) return;
        isSearchingUser.value = true;
        userSearchAttempted.value = false;
        userSearchResults.value = [];
        try {
            const data = await musicApi.searchUser(platformRef.value, searchUserKeyword.value);
            userSearchResults.value = data || [];
            userSearchAttempted.value = true;
        } catch (e) {
            console.error(e);
            if (e.response?.data?.message) {
                error(e.response.data.message);
            } else {
                error('User Search Failed');
            }
        } finally {
            isSearchingUser.value = false;
        }
    };

    // 绑定用户并刷新
    const bindUser = (user) => {
        playerStore.bindAccount(platformRef.value, user.id);
        userStore.saveBindingProfile(platformRef.value, { name: user.name, avatarUrl: user.avatarUrl });
        userSearchResults.value = [];
        userSearchAttempted.value = false;
        searchUserKeyword.value = '';
        // 不再手动 fetchPlaylists()：bindings 的 deep watch 会触发一次，
        // 手动调用只会让同一份列表请求两次（各 1~4 秒）
    };

    // 分页获取歌单歌曲 (核心复杂逻辑)
    const fetchSongsPage = async () => {
        if (!currentPlaylistId.value) return;
        try {
            const startOffset = offset.value;
            const response = await musicApi.getPlaylistSongsPage(
                platformRef.value,
                currentPlaylistId.value,
                startOffset,
                limit.value
            );
            const rawSongs = response.data || [];

            // 服务端会返回「下一页的偏移」与「是否还有」：
            // 因为部分曲目在区域不可用时会被丢弃，短页不等于到底。
            const nextOffsetHeader = Number(response.headers?.['x-playlist-next-offset']);
            const hasMoreHeader = response.headers?.['x-playlist-has-more'];

            if (Number.isFinite(nextOffsetHeader)) {
                offset.value = nextOffsetHeader;
            } else {
                offset.value = startOffset + rawSongs.length;
            }

            if (hasMoreHeader === 'true') hasMore.value = true;
            else if (hasMoreHeader === 'false') hasMore.value = false;
            else if (platformRef.value === 'bilibili') hasMore.value = rawSongs.length > 0;
            else hasMore.value = rawSongs.length >= limit.value;

            const validSongs = rawSongs.filter(s => s.id !== 'INVALID_SKIP');
            // 只追加没见过的曲目：服务端若回绕/重复分页，用户会看到同一批歌被反复接在列表后面
            const known = new Set(songsRef.value.map(s => `${s.platform}:${s.id}`));
            const fresh = validSongs.filter(s => !known.has(`${s.platform}:${s.id}`));
            songsRef.value.push(...fresh);

            // 整页都是已加载过的曲目 = 分页回绕/失效；必须收尾，否则会无限循环下去
            if (validSongs.length > 0 && fresh.length === 0) {
                console.warn('[playlist] 服务端返回了整页重复曲目，停止翻页', currentPlaylistId.value);
                hasMore.value = false;
            }

            // 贪婪加载：如果有效数据太少，自动加载下一页（offset 已由服务端给出）
            if (hasMore.value && rawSongs.length > 0 && fresh.length < 10) {
                setTimeout(fetchSongsPage, 50);
            }

            // 兜底
            if (rawSongs.length === 0) isLoadingMore.value = false;
            // 增量拉取后同步缓存；但空结果绝不覆盖——失败的加载不能把已有数据刷掉
            if (songsRef.value.length > 0) saveCurrentPlaylist();

        } catch (e) {
            console.error("Fetch songs failed", e);
            // 失败时不再静默：给出可见反馈，并且不要一路滚到底还显示「还有更多」
            const detail = e?.response?.data?.message || e?.message || '未知错误';
            error('加载歌单失败（' + detail + '）');
            hasMore.value = false;
        }
    };

    /** 把当前歌单的曲目、分页状态与滚动位置写进缓存（切走/关闭时调用） */
    const saveCurrentPlaylist = (platformOverride) => {
        if (!currentPlaylistId.value || listModeRef.value !== 'playlist') return;
        if (!songsRef.value.length) return;   // 空列表不落盘：防止失败加载污染缓存
        writePlaylistCache(platformOverride || platformRef.value, currentPlaylistId.value, {
            songs: songsRef.value,
            offset: offset.value,
            hasMore: hasMore.value,
            scrollTop: viewportScrollTop
        });
    };

    /**
     * 刷新当前歌单：按页重新核对，**期间不清空已加载的曲目**。
     * 以服务端为准做位置合并——歌单变长则只核对到原有数量（后续走滚动分页），
     * 变短则如实缩短；全部核对完才一次性替换，刷新过程旧数据始终可见。
     */
    const refreshPlaylist = async () => {
        const pid = currentPlaylistId.value;
        const plat = platformRef.value;
        if (!pid) {
            error('先选一个歌单再刷新');
            return;
        }
        if (refreshState.value.active) {
            error('正在刷新中，请稍候');
            return;
        }
        // 历史遗留的 loading 标志可能卡住（滚动加载被打断等），刷新时复位，避免「点了没反应」
        isLoadingMore.value = false;
        loadingRef.value = false;

        const target = songsRef.value.length;
        refreshState.value = { active: true, done: 0, target };
        isLoadingMore.value = true;   // 刷新期间挡住滚动分页，避免两路请求混流

        const fetched = [];
        let nextHint = 0;         // 下一次请求的偏移（服务端优先）
        let serverHasMore = true;
        try {
            // page === 0 保证空歌单/首次加载失败后也能真正发一次请求。
            // 旧条件 fetched.length < target 在 target=0 时直接跳过循环，按钮点了等于没点。
            for (let page = 0; page < 40 && serverHasMore && (page === 0 || fetched.length < target); page++) {
                const res = await musicApi.getPlaylistSongsPage(plat, pid, nextHint, limit.value);
                // 刷新期间切了平台/歌单：迟到的响应不能覆盖新界面的数据
                if (platformRef.value !== plat || currentPlaylistId.value !== pid) return;
                const raw = res.data || [];
                console.info('[refresh]', pid, 'offset', nextHint, '->', raw.length);

                const nextHeader = Number(res.headers?.['x-playlist-next-offset']);
                const moreHeader = res.headers?.['x-playlist-has-more'];
                nextHint = Number.isFinite(nextHeader) ? nextHeader : nextHint + raw.length;
                serverHasMore = moreHeader === 'true' ? true
                    : moreHeader === 'false' ? false
                        : raw.length >= limit.value;

                fetched.push(...raw.filter(s => s.id !== 'INVALID_SKIP'));
                refreshState.value.done = fetched.length;
                if (raw.length === 0) break;
            }

            // 服务端一页都没给时：原本就没有内容 -> 视为空歌单；原本有内容 -> 保留并提示
            if (fetched.length === 0) {
                if (target > 0) {
                    error('刷新歌单失败（源返回为空），已保留原数据');
                }
                return;
            }
            songsRef.value = fetched;
            offset.value = nextHint;
            hasMore.value = serverHasMore;
            saveCurrentPlaylist();
        } catch (e) {
            console.warn('[refresh] failed', pid, offset, e);
            if (platformRef.value === plat && currentPlaylistId.value === pid) {
                const detail = e?.response?.data?.message || e?.message || '未知错误';
                error('刷新歌单失败（' + detail + '），已保留原数据');
            }
        } finally {
            isLoadingMore.value = false;
            refreshState.value = { active: false, done: refreshState.value.done, target: refreshState.value.target };
        }
    };

    // 点击歌单：命中缓存则同步还原，不再重新拉取
    const loadPlaylist = async (pid) => {
        const platform = platformRef.value;
        saveCurrentPlaylist();

        listModeRef.value = 'playlist';
        currentPlaylistId.value = pid;

        const cached = readPlaylistCache(platform, pid);
        // 0 首的条目是失败加载的残留，绝不能当命中——否则点击歌单会毫无反应
        if (cached && (cached.songs || []).length > 0) {
            songsRef.value = cached.songs || [];
            // offset 是「下一页的 id 偏移」，可能大于已显示条数（有曲目不可用），
            // 所以优先用缓存里保存的值
            offset.value = Number.isFinite(cached.offset) ? cached.offset : songsRef.value.length;
            // 缓存里的 hasMore 可能是历史遗留的错误状态（后端修好前写入的 false），
            // 恢复时一律按「可能还有更多」处理：真到底了，翻页会拿到 0 条再收尾。
            hasMore.value = true;
            loadingRef.value = false;
            viewportScrollTop = cached.scrollTop || 0;
            pendingRestoreScrollTop = cached.scrollTop || 0;
            return;
        }

        songsRef.value = [];
        offset.value = 0;
        hasMore.value = true;
        // 全新加载：视口回到顶部（否则会把上一个歌单的滚动位置写进这个歌单的缓存）
        viewportScrollTop = 0;
        pendingRestoreScrollTop = 0;
        loadingRef.value = true;

        try {
            await fetchSongsPage();
        } finally {
            loadingRef.value = false;
        }
    };

    // 滚动加载
    const handleScroll = useDebounceFn(async (e) => {
        if (listModeRef.value !== 'playlist') return;
        const el = e.target;
        const bottom = el.scrollHeight - el.scrollTop - el.clientHeight;

        if (bottom < 100 && hasMore.value && !isLoadingMore.value && !loadingRef.value) {
            isLoadingMore.value = true;
            try {
                await fetchSongsPage();
            } finally {
                isLoadingMore.value = false;
            }
        }
    }, 200);

    const loadRemainingSongs = async () => {
        if (!currentPlaylistId.value || isLoadingMore.value || loadingRef.value) return;

        isLoadingMore.value = true;
        try {
            // 保险丝：极端情况下（服务端一直说还有）也不会无限循环
            for (let guard = 0; guard < 100 && hasMore.value; guard++) {
                await fetchSongsPage();
            }
        } finally {
            isLoadingMore.value = false;
        }
    };

    // 监听平台切换 -> 刷新歌单列表
    watch(platformRef, (next, prev) => {
        // 换平台 = 离开当前歌单：必须按「旧平台」保存，否则会把旧歌单写进新平台的键；
        // 也不能把 viewportScrollTop 清零——那是刚存下的滚动位置，清零后再回来就跳到顶部。
        if (prev) saveCurrentPlaylist(prev);
        // 关键：旧平台的歌单 ID 不能继续挂着。否则切到新平台后 REFRESH LIST
        // 会拿「网易云的 ID」去请求 SoundCloud，返回 0 首，看起来像按钮坏了。
        currentPlaylistId.value = null;
        offset.value = 0;
        hasMore.value = true;
        isLoadingMore.value = false;
        refreshState.value = { active: false, done: 0, target: 0 };
    });

    // 绑定是原地改对象属性（bindings.value[platform] = id），ref 本身不变，
    // 不加 deep 的话 watch 永远不触发——绑定成功后列表不会刷新。
    watch([platformRef, bindings], () => {
        fetchPlaylists();
    }, { immediate: true, deep: true });

    return {
        playlists,
        currentPlaylistId,
        searchUserKeyword,
        userSearchResults,
        isSearchingUser,
        userSearchAttempted,
        isPlaylistsLoading,
        hasMore,
        isLoadingMore,
        bindings,
        searchUser,
        bindUser,
        prefetchPlaylists,
        saveCurrentPlaylist,
        refreshPlaylist,
        refreshState,
        setPlaylistScrollTop,
        takeRestoreScrollTop,
        loadPlaylist,
        handleScroll,
        loadRemainingSongs
    };
}
