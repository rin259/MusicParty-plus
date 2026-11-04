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

    // 获取用户歌单
    const fetchPlaylists = async () => {
        const uid = bindings.value[platformRef.value];
        if (!uid) {
            playlists.value = [];
            return;
        }
        isPlaylistsLoading.value = true;
        try {
            const data = await musicApi.getUserPlaylists(platformRef.value, uid);
            playlists.value = data;
        } catch (e) {
            console.error(e);
            playlists.value = [];
            if (e.response?.data?.message) {
                error(e.response.data.message);
            }
        } finally {
            isPlaylistsLoading.value = false;
        }
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
        fetchPlaylists(); // 立即刷新
    };

    // 分页获取歌单歌曲 (核心复杂逻辑)
    const fetchSongsPage = async () => {
        if (!currentPlaylistId.value) return;
        try {
            const rawSongs = await musicApi.getPlaylistSongs(
                platformRef.value,
                currentPlaylistId.value,
                offset.value,
                limit.value
            );

            // B站特殊的分页判定
            if (platformRef.value === 'bilibili') {
                if (rawSongs.length === 0) hasMore.value = false;
            } else {
                if (rawSongs.length < limit.value) hasMore.value = false;
            }

            const validSongs = rawSongs.filter(s => s.id !== 'INVALID_SKIP');
            songsRef.value.push(...validSongs);

            // 贪婪加载：如果有效数据太少，自动加载下一页
            if (hasMore.value && rawSongs.length > 0 && validSongs.length < 10) {
                offset.value += limit.value;
                setTimeout(fetchSongsPage, 50);
            }

            // 兜底
            if (rawSongs.length === 0) isLoadingMore.value = false;
            // 增量拉取后同步缓存；但空结果绝不覆盖——失败的加载不能把已有数据刷掉
            if (songsRef.value.length > 0) saveCurrentPlaylist();

        } catch (e) {
            console.error("Fetch songs failed", e);
            hasMore.value = false;
        }
    };

    /** 把当前歌单的曲目、分页状态与滚动位置写进缓存（切走/关闭时调用） */
    const saveCurrentPlaylist = () => {
        if (!currentPlaylistId.value || listModeRef.value !== 'playlist') return;
        if (!songsRef.value.length) return;   // 空列表不落盘：防止失败加载污染缓存
        writePlaylistCache(platformRef.value, currentPlaylistId.value, {
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
        let rawCount = 0;
        let pageOffset = 0;   // 注意：不能用 offset——那会遮蔽外层的 offset ref
        let lastPageFull = false;
        try {
            for (let page = 0; page < 40; page++) {   // 保险丝：最多 2000 首
                const raw = await musicApi.getPlaylistSongs(plat, pid, pageOffset, limit.value);
                console.info('[refresh]', pid, 'offset', pageOffset, '->', Array.isArray(raw) ? raw.length : raw);
                lastPageFull = raw.length >= limit.value;
                rawCount += raw.length;
                fetched.push(...raw.filter(s => s.id !== 'INVALID_SKIP'));
                refreshState.value.done = fetched.length;

                pageOffset += limit.value;
                if (raw.length === 0 || !lastPageFull) break;       // 服务端到底
                if (fetched.length >= target) break;                // 已核对完用户手里的量
            }

            // 服务端一页都没给时：原本就没有内容 -> 视为空歌单；原本有内容 -> 保留并提示
            if (fetched.length === 0) {
                if (target > 0) {
                    error('刷新歌单失败（源返回为空），已保留原数据');
                }
                return;
            }
            songsRef.value = fetched;
            offset.value = rawCount;
            hasMore.value = lastPageFull;
            saveCurrentPlaylist();
        } catch (e) {
            console.warn('[refresh] failed', pid, offset, e);
            const detail = e?.response?.data?.message || e?.message || '未知错误';
            error('刷新歌单失败（' + detail + '），已保留原数据');
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
            offset.value = cached.offset || songsRef.value.length;
            hasMore.value = cached.hasMore !== false;
            loadingRef.value = false;
            viewportScrollTop = cached.scrollTop || 0;
            pendingRestoreScrollTop = cached.scrollTop || 0;
            return;
        }

        songsRef.value = [];
        offset.value = 0;
        hasMore.value = true;
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
            offset.value += limit.value;
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
            while (hasMore.value) {
                offset.value += limit.value;
                await fetchSongsPage();
            }
        } finally {
            isLoadingMore.value = false;
        }
    };

    // 监听平台切换 -> 刷新歌单列表
    watch(platformRef, () => {
        // 换平台相当于离开当前歌单，先落盘
        saveCurrentPlaylist();
        viewportScrollTop = 0;
        pendingRestoreScrollTop = 0;
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
