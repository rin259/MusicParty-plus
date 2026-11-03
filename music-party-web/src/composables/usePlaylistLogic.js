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
            // 增量拉取后同步缓存，切走再回来不会丢掉已加载的页
            saveCurrentPlaylist();

        } catch (e) {
            console.error("Fetch songs failed", e);
            hasMore.value = false;
        }
    };

    /** 把当前歌单的曲目、分页状态与滚动位置写进缓存（切走/关闭时调用） */
    const saveCurrentPlaylist = () => {
        if (!currentPlaylistId.value || listModeRef.value !== 'playlist') return;
        writePlaylistCache(platformRef.value, currentPlaylistId.value, {
            songs: songsRef.value,
            offset: offset.value,
            hasMore: hasMore.value,
            scrollTop: viewportScrollTop
        });
    };

    // 点击歌单：命中缓存则同步还原，不再重新拉取
    const loadPlaylist = async (pid) => {
        const platform = platformRef.value;
        saveCurrentPlaylist();

        listModeRef.value = 'playlist';
        currentPlaylistId.value = pid;

        const cached = readPlaylistCache(platform, pid);
        if (cached) {
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

    watch([platformRef, bindings], () => {
        fetchPlaylists();
    }, { immediate: true });

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
        setPlaylistScrollTop,
        takeRestoreScrollTop,
        loadPlaylist,
        handleScroll,
        loadRemainingSongs
    };
}
