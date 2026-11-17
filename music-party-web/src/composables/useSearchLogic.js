import { computed, ref, watch, onScopeDispose } from 'vue';
import { useToast } from './useToast.js';
import { musicApi } from '../api/music.js';
import { usePlayerStore } from '../stores/player.js';
import { mergeResults, PLATFORMS } from '../logic/searchMerge.js';
import { appendUniqueSongs } from '../logic/listPaging.js';

const PAGE_SIZE = 30;
const CACHE_TTL_MS = 60_000;
const SOFT_SLOW_MS = 2_500;
const HARD_TIMEOUT_MS = 9_000;

const emptySource = () => ({
    state: 'idle',      // idle | loading | ok | error | timeout | disabled
    latencyMs: 0,
    count: 0,
    error: null,
    slow: false,
    fromCache: false
});

/**
 * 搜索：单源 + 「全部」跨源并发。
 *
 * - 「全部」并发扇出，先到先渲染（不阻塞最慢的源）；
 * - 2.5s 标记 SLOW，9s 硬超时；部分结果照常展示；
 * - 结果按「标题+主艺人」归并，来源保留在 _sources；
 * - 60s 结果缓存，来回切源不重复打上游。
 */
export function useSearchLogic({ api = musicApi, player = null, notify = null } = {}) {
    const { error } = notify || useToast();
    const playerStore = player || usePlayerStore();

    const platform = ref('all');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const hasMore = ref(false);
    const listMode = ref('search');
    const offset = ref(0);
    const lastQuery = ref('');
    const batches = ref({});
    const sources = ref(Object.fromEntries(PLATFORMS.map((p) => [p, emptySource()])));
    const health = ref({});
    const healthLoading = ref(false);
    const summary = ref({ hits: 0, merged: 0, done: 0, sources: 0, elapsedMs: 0 });

    let seq = 0;
    let slowTimer = null;
    const cache = new Map();
    const inFlight = new Map();
    const isLoadingMore = ref(false);

    const invalidateSearch = () => {
        seq++;
        clearTimeout(slowTimer);
        slowTimer = null;
        loading.value = false;
        isLoadingMore.value = false;
        hasMore.value = false;
    };
    watch([platform, listMode], invalidateSearch, { flush: 'sync' });
    onScopeDispose(invalidateSearch);

    const enabledPlatforms = computed(() => PLATFORMS.filter((p) => isEnabled(p)));

    function isEnabled(p) {
        const cfg = playerStore.config || {};
        if (p === 'netease') return cfg.neteaseEnabled !== false;
        if (p === 'bilibili') return cfg.bilibiliEnabled !== false;
        if (p === 'soundcloud') return cfg.soundcloudEnabled !== false;
        if (p === 'ytmusic') return cfg.ytmusicEnabled !== false;
        return true;
    }

    function resetSources() {
        sources.value = Object.fromEntries(PLATFORMS.map((p) => [p, emptySource()]));
    }

    function recompute(elapsedMs = 0) {
        const list = mergeResults(
            PLATFORMS.map((p) => ({ platform: p, songs: batches.value[p] || [] })),
            lastQuery.value
        );
        songs.value = list;
        const done = PLATFORMS.filter((p) => {
            const state = sources.value[p].state;
            return state === 'ok' || state === 'error' || state === 'timeout' || state === 'disabled';
        }).length;
        summary.value = {
            hits: PLATFORMS.reduce((sum, p) => sum + (batches.value[p]?.length || 0), 0),
            merged: list.length,
            done,
            sources: enabledPlatforms.value.length,
            elapsedMs
        };
    }

    async function fetchPage(sourcePlatform, query, pageOffset, limit) {
        const key = `${sourcePlatform}|${query}|${pageOffset}|${limit}`;
        const cached = cache.get(key);
        if (cached && Date.now() - cached.at < CACHE_TTL_MS) {
            return { data: cached.data, fromCache: true };
        }
        if (inFlight.has(key)) return inFlight.get(key);
        const request = api.search(sourcePlatform, query, pageOffset, limit, {
            timeout: HARD_TIMEOUT_MS
        }).then(data => {
            cache.set(key, { at: Date.now(), data });
            if (cache.size > 120) cache.delete(cache.keys().next().value);
            return { data, fromCache: false };
        });
        inFlight.set(key, request);
        try { return await request; }
        finally { if (inFlight.get(key) === request) inFlight.delete(key); }
    }

    async function searchOne(sourcePlatform, query, runSeq, startedAt) {
        const begun = performance.now();
        if (!isEnabled(sourcePlatform)) {
            sources.value[sourcePlatform] = { ...emptySource(), state: 'disabled' };
            return;
        }
        sources.value[sourcePlatform] = { ...emptySource(), state: 'loading' };
        try {
            const { data, fromCache } = await fetchPage(sourcePlatform, query, 0, PAGE_SIZE);
            if (runSeq !== seq) return;
            const list = appendUniqueSongs([], data, sourcePlatform).items;
            batches.value[sourcePlatform] = list;
            sources.value[sourcePlatform] = {
                state: 'ok',
                latencyMs: Math.round(performance.now() - begun),
                count: list.length,
                error: null,
                slow: false,
                fromCache
            };
            recompute(Math.round(performance.now() - startedAt));
        } catch (e) {
            if (runSeq !== seq) return;
            const timedOut = e?.code === 'ECONNABORTED' || /timeout/i.test(e?.message || '');
            sources.value[sourcePlatform] = {
                ...emptySource(),
                state: timedOut ? 'timeout' : 'error',
                latencyMs: Math.round(performance.now() - begun),
                error: timedOut ? 'timeout' : (e?.response?.data?.message || e?.message || 'failed')
            };
            recompute(Math.round(performance.now() - startedAt));
        }
    }

    const doSearch = async () => {
        const query = keyword.value.trim();
        if (!query) return;
        const sourcePlatform = platform.value;
        invalidateSearch();

        lastQuery.value = query;
        listMode.value = 'search';
        offset.value = 0;
        songs.value = [];
        sequencesReset();
        const runSeq = ++seq;
        const startedAt = performance.now();

        if (platform.value === 'all') {
            batches.value = {};
            resetSources();
            loading.value = true;
            const targets = enabledPlatforms.value;
            summary.value = { hits: 0, merged: 0, done: 0, sources: targets.length, elapsedMs: 0 };

            clearTimeout(slowTimer);
            slowTimer = setTimeout(() => {
                if (runSeq !== seq) return;
                for (const p of targets) {
                    if (sources.value[p].state === 'loading') {
                        sources.value[p] = { ...sources.value[p], slow: true };
                    }
                }
            }, SOFT_SLOW_MS);

            await Promise.allSettled(targets.map((p) => searchOne(p, query, runSeq, startedAt)));
            if (runSeq === seq) {
                clearTimeout(slowTimer);
                loading.value = false;
                hasMore.value = false;
                recompute(Math.round(performance.now() - startedAt));
            }
            return;
        }

        loading.value = true;
        resetSources();
        batches.value = { [platform.value]: [] };
        try {
            const { data, fromCache } = await fetchPage(sourcePlatform, query, 0, PAGE_SIZE);
            if (runSeq !== seq) return;
            const raw = Array.isArray(data) ? data : [];
            const list = appendUniqueSongs([], raw, sourcePlatform).items;
            songs.value = list;
            batches.value[sourcePlatform] = list;
            hasMore.value = raw.length >= PAGE_SIZE && list.length > 0;
            sources.value[sourcePlatform] = {
                state: 'ok',
                latencyMs: Math.round(performance.now() - startedAt),
                count: list.length,
                error: null,
                slow: false,
                fromCache
            };
            summary.value = {
                hits: list.length,
                merged: list.length,
                done: 1,
                sources: 1,
                elapsedMs: Math.round(performance.now() - startedAt)
            };
        } catch (e) {
            if (runSeq !== seq) return;
            const timedOut = e?.code === 'ECONNABORTED' || /timeout/i.test(e?.message || '');
            sources.value[sourcePlatform] = {
                ...emptySource(),
                state: timedOut ? 'timeout' : 'error',
                latencyMs: Math.round(performance.now() - startedAt),
                error: timedOut ? 'timeout' : (e?.response?.data?.message || e?.message)
            };
            error(timedOut ? '搜索超时，试试更短的关键词' : (e?.response?.data?.message || '搜索失败'));
        } finally {
            if (runSeq === seq) loading.value = false;
        }
    };

    function sequencesReset() {
        hasMore.value = false;
        summary.value = { hits: 0, merged: 0, done: 0, sources: 0, elapsedMs: 0 };
    }

    const loadMore = async () => {
        if (platform.value === 'all' || listMode.value !== 'search' || loading.value || isLoadingMore.value || !hasMore.value) return;
        const query = lastQuery.value;
        if (!query) return;
        const runSeq = seq;
        const sourcePlatform = platform.value;
        const nextOffset = offset.value + PAGE_SIZE;
        isLoadingMore.value = true;
        try {
            const { data } = await fetchPage(sourcePlatform, query, nextOffset, PAGE_SIZE);
            if (runSeq !== seq) return;
            const list = Array.isArray(data) ? data : [];
            if (list.length === 0) {
                hasMore.value = false;
                return;
            }
            const { items, fresh } = appendUniqueSongs(songs.value, list, sourcePlatform);
            songs.value = items;
            batches.value[sourcePlatform] = items;
            offset.value = nextOffset;
            hasMore.value = list.length >= PAGE_SIZE && fresh.length > 0;
            sources.value[sourcePlatform] = {
                ...sources.value[sourcePlatform],
                count: songs.value.length
            };
            summary.value = { ...summary.value, hits: items.length, merged: items.length };
        } catch (e) {
            if (runSeq !== seq) return;
            console.error('[loadMore]', e);
            error('加载搜索结果失败，请稍后重试');
        } finally {
            if (runSeq === seq) isLoadingMore.value = false;
        }
    };

    /** 打开搜索时探测四源健康（服务端 60s 缓存，失败不弹错）。 */
    const probeHealth = async () => {
        healthLoading.value = true;
        try {
            const list = await api.sourcesHealth({ timeout: 12_000 });
            const next = {};
            for (const item of list || []) next[item.platform] = item;
            health.value = next;
        } catch (e) {
            console.warn('source health probe failed', e?.message);
        } finally {
            healthLoading.value = false;
        }
    };

    return {
        platform,
        keyword,
        songs,
        loading,
        listMode,
        hasMore,
        sources,
        health,
        healthLoading,
        summary,
        lastQuery,
        doSearch,
        invalidateSearch,
        isLoadingMore,
        loadMore,
        probeHealth
    };
}
