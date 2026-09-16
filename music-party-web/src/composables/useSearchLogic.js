import { ref } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';

export function useSearchLogic(emit) {
    const { error } = useToast();

    const platform = ref('netease');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'
    const hasMore = ref(false);
    const offset = ref(0);
    const PAGE_SIZE = 30;

    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val) return;

        listMode.value = 'search';
        loading.value = true;
        offset.value = 0;
        songs.value = [];
        try {
            const data = await musicApi.search(platform.value, val, 0, PAGE_SIZE);
            songs.value = data || [];
            hasMore.value = (data && data.length >= PAGE_SIZE);
            console.log('[doSearch] got', data?.length, 'results, hasMore:', hasMore.value);
        } catch (e) {
            console.error(e);
            const msg = e.response?.data?.message || 'Search Failed';
            error(msg);
        } finally {
            loading.value = false;
        }
    };

    const loadMore = async () => {
        console.log('[loadMore] called, loading:', loading.value, 'hasMore:', hasMore.value);
        if (loading.value || !hasMore.value) return;
        const val = keyword.value.trim();
        if (!val) return;

        loading.value = true;
        const nextOffset = offset.value + PAGE_SIZE;
        console.log('[loadMore] fetching offset:', nextOffset);
        try {
            const data = await musicApi.search(platform.value, val, nextOffset, PAGE_SIZE);
            console.log('[loadMore] got', data?.length, 'results');
            if (data && data.length > 0) {
                songs.value = [...songs.value, ...data];
                offset.value = nextOffset;
                hasMore.value = data.length >= PAGE_SIZE;
            } else {
                hasMore.value = false;
            }
        } catch (e) {
            console.error('[loadMore] error:', e);
        } finally {
            loading.value = false;
        }
    };

    return {
        platform,
        keyword,
        songs,
        loading,
        listMode,
        hasMore,
        doSearch,
        loadMore
    };
}
