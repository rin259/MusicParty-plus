import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { effectScope, reactive, ref, nextTick } from 'vue';

const storage = new Map();
globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key)
};
const server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true }, appType: 'custom' });
after(() => server.close());
const { useSearchLogic } = await server.ssrLoadModule('/src/composables/useSearchLogic.js');
const { usePlaylistLogic } = await server.ssrLoadModule('/src/composables/usePlaylistLogic.js');
const { useAlbumLogic } = await server.ssrLoadModule('/src/composables/useAlbumLogic.js');
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const song = (id, platform = 'netease') => ({ id: String(id), name: `Song ${id}`, platform, artists: ['Artist'] });
const page = (ids, next, more) => ({ data: ids.map(id => song(id)), headers: {
    'x-playlist-next-offset': String(next), 'x-playlist-has-more': String(more)
} });
const notify = { error() {} };
const makeSearch = api => {
    const scope = effectScope();
    const state = scope.run(() => useSearchLogic({ api, player: { config: {} }, notify }));
    state.platform.value = 'netease'; state.keyword.value = 'test';
    return { ...state, stop: () => scope.stop() };
};
const makePlaylist = api => {
    storage.clear();
    const scope = effectScope();
    const platform = ref('netease'), songs = ref([]), mode = ref('search'), loading = ref(false);
    const state = scope.run(() => usePlaylistLogic(platform, songs, mode, loading,
        { api, user: reactive({ bindings: {} }), player: {}, notify }));
    return { ...state, platform, songs, mode, loading, stop: () => scope.stop() };
};

test('single-source pagination locks before await and appends a page once', async () => {
    const pending = deferred(); let calls = 0;
    const s = makeSearch({ search: (_, __, offset) => { calls++; return offset ? pending.promise : Promise.resolve(Array.from({ length: 30 }, (_, i) => song(i))); } });
    try {
        await s.doSearch();
        const requests = [s.loadMore(), s.loadMore(), s.loadMore()];
        assert.equal(calls, 2);
        pending.resolve(Array.from({ length: 30 }, (_, i) => song(i + 30)));
        await Promise.all(requests);
        assert.equal(s.songs.value.length, 60);
        assert.equal(new Set(s.songs.value.map(x => x.id)).size, 60);
        assert.equal(s.isLoadingMore.value, false);
    } finally { s.stop(); }
});

test('search stops when upstream repeats a full earlier page', async () => {
    let calls = 0; const batch = Array.from({ length: 30 }, (_, i) => song(i));
    const s = makeSearch({ search: () => { calls++; return Promise.resolve(batch); } });
    try {
        await s.doSearch(); await s.loadMore(); await s.loadMore();
        assert.equal(s.songs.value.length, 30);
        assert.equal(s.hasMore.value, false);
        assert.equal(calls, 2);
    } finally { s.stop(); }
});

test('overlapping pages and within-page duplicates keep unique platform/id rows', async () => {
    const s = makeSearch({ search: (_, __, offset) => Promise.resolve(offset
        ? [song(29), song(30), song(30), song(31)] : Array.from({ length: 30 }, (_, i) => song(i))) });
    try { await s.doSearch(); await s.loadMore(); assert.equal(s.songs.value.length, 32); }
    finally { s.stop(); }
});

test('late song search cannot overwrite a playlist or album view', async () => {
    for (const mode of ['playlist', 'album-search', 'album']) {
        const pending = deferred(); const s = makeSearch({ search: () => pending.promise });
        try {
            const p = s.doSearch();
            s.listMode.value = mode; s.songs.value = [song('selected')]; s.loading.value = true;
            pending.resolve([song('stale')]); await p;
            assert.equal(s.songs.value[0].id, 'selected');
            assert.equal(s.loading.value, true, 'old finally must not clear a newer loading flag');
        } finally { s.stop(); }
    }
});

test('platform change invalidates search even without a replacement request', async () => {
    const pending = deferred(); const s = makeSearch({ search: () => pending.promise });
    try {
        const p = s.doSearch(); s.platform.value = 'soundcloud'; pending.resolve([song('stale')]); await p;
        assert.deepEqual(s.songs.value, []);
    } finally { s.stop(); }
});

test('slow playlist A cannot append to B or poison B cache', async () => {
    const pending = deferred();
    const s = makePlaylist({ getPlaylistSongsPage: (_, pid) => pid === 'A' ? pending.promise : Promise.resolve(page(['B'], 1, false)) });
    try {
        const a = s.loadPlaylist('A'); await s.loadPlaylist('B');
        pending.resolve(page(['A'], 1, false)); await a;
        assert.deepEqual(s.songs.value.map(x => x.id), ['B']);
        const cache = JSON.parse(storage.get('mp_playlist_cache_v2'));
        assert.deepEqual(cache['netease:B'].songs.map(x => x.id), ['B']);
    } finally { s.stop(); }
});

test('playlist pagination removes within-page duplicates and stops non-advancing cursors', async () => {
    const s = makePlaylist({ getPlaylistSongsPage: () => Promise.resolve(page(['A', 'A', 'B'], 0, true)) });
    try { await s.loadPlaylist('P'); assert.equal(s.songs.value.length, 2); assert.equal(s.hasMore.value, false); }
    finally { s.stop(); }
});

test('completed cached playlists stay complete when reopened', async () => {
    let calls = 0;
    const s = makePlaylist({ getPlaylistSongsPage: () => { calls++; return Promise.resolve(page(['A'], 1, false)); } });
    try {
        await s.loadPlaylist('P'); await s.loadPlaylist('P');
        assert.equal(calls, 1); assert.equal(s.hasMore.value, false);
    } finally { s.stop(); }
});

test('playlist load-more locks repeated scroll triggers and stops repeated pages', async () => {
    const pending = deferred(); let calls = 0;
    const first = Array.from({ length: 50 }, (_, i) => String(i));
    const s = makePlaylist({ getPlaylistSongsPage: (_, __, offset) => {
        calls++; return offset ? pending.promise : Promise.resolve(page(first, 50, true));
    } });
    try {
        await s.loadPlaylist('P');
        const requests = [s.loadMoreSongs(), s.loadMoreSongs(), s.loadMoreSongs()];
        assert.equal(calls, 2);
        pending.resolve(page(first, 100, true)); await Promise.all(requests);
        assert.equal(s.songs.value.length, 50);
        assert.equal(s.hasMore.value, false);
        await s.loadMoreSongs(); assert.equal(calls, 2);
    } finally { s.stop(); }
});

test('playlist transport failure preserves cursor for retry instead of caching false END', async () => {
    let fail = true; const offsets = [];
    const s = makePlaylist({ getPlaylistSongsPage: (_, __, offset) => {
        offsets.push(offset);
        if (offset && fail) return Promise.reject(new Error('temporary failure'));
        return Promise.resolve(page([offset ? 'B' : 'A'], offset + 1, offset === 0));
    } });
    try {
        await s.loadPlaylist('P'); await s.loadMoreSongs();
        assert.equal(s.hasMore.value, true);
        fail = false; await s.loadMoreSongs();
        assert.deepEqual(offsets, [0, 1, 1]);
        assert.deepEqual(s.songs.value.map(x => x.id), ['A', 'B']);
    } finally { s.stop(); }
});

test('scroll persistence timer cannot write playlist A position into playlist B', async () => {
    const s = makePlaylist({ getPlaylistSongsPage: (_, pid) => Promise.resolve(page([pid], 1, false)) });
    try {
        await s.loadPlaylist('A'); s.setPlaylistScrollTop(800);
        await s.loadPlaylist('B');
        await new Promise(r => setTimeout(r, 300));
        const cache = JSON.parse(storage.get('mp_playlist_cache_v2'));
        assert.equal(cache['netease:A'].scrollTop, 800);
        assert.equal(cache['netease:B'].scrollTop, 0);
    } finally { s.stop(); }
});

test('refresh loop preserves original list when upstream repeats its first page', async () => {
    let refreshing = false;
    const s = makePlaylist({ getPlaylistSongsPage: (_, __, offset) => Promise.resolve(refreshing
        ? page(['X'], offset + 1, true) : page(['A', 'B', 'C'], 3, false)) });
    try {
        await s.loadPlaylist('P'); refreshing = true; await s.refreshPlaylist();
        assert.deepEqual(s.songs.value.map(x => x.id), ['A', 'B', 'C']);
    } finally { s.stop(); }
});

test('playlist switch makes old refresh finally leave the new request loading', async () => {
    const pending = deferred(), next = deferred(); let refreshing = false;
    const s = makePlaylist({ getPlaylistSongsPage: (_, pid) => pid === 'B' ? next.promise : refreshing ? pending.promise : Promise.resolve(page(['A'], 1, false)) });
    try {
        await s.loadPlaylist('A'); refreshing = true; const r = s.refreshPlaylist();
        const b = s.loadPlaylist('B'); pending.resolve(page(['old'], 1, false)); await r;
        assert.equal(s.loading.value, true);
        next.resolve(page(['B'], 1, false)); await b;
        assert.deepEqual(s.songs.value.map(x => x.id), ['B']);
    } finally { s.stop(); }
});

test('album searches clear songs and ignore slow results from an older album', async () => {
    const scope = effectScope(); const pending = deferred();
    const platform = ref('netease'), songs = ref([song('old')]), mode = ref('playlist'), loading = ref(false);
    const a = scope.run(() => useAlbumLogic(platform, songs, mode, loading, { api: {
        searchAlbums: () => Promise.resolve([{ id: 'a', name: 'Album A' }]),
        getAlbumSongs: (_, id) => id === 'a' ? pending.promise : Promise.resolve([song('b')])
    } }));
    try {
        a.albumSearchMode.value = true; await a.searchAlbums('test');
        assert.equal(mode.value, 'album-search'); assert.deepEqual(songs.value, []);
        const first = a.selectAlbum({ id: 'a' }); await a.selectAlbum({ id: 'b' });
        pending.resolve([song('stale')]); await first;
        assert.deepEqual(songs.value.map(x => x.id), ['b']);
        a.backToAlbums(); assert.equal(mode.value, 'album-search'); assert.deepEqual(songs.value, []);
    } finally { scope.stop(); }
});

test('album response cannot overwrite a playlist selected while it was loading', async () => {
    const scope = effectScope(); const pending = deferred();
    const platform = ref('netease'), songs = ref([]), mode = ref('album-search'), loading = ref(false);
    const a = scope.run(() => useAlbumLogic(platform, songs, mode, loading,
        { api: { searchAlbums: () => pending.promise } }));
    try {
        a.albumSearchMode.value = true; const p = a.searchAlbums('slow');
        mode.value = 'playlist'; songs.value = [song('chosen-playlist')]; loading.value = true;
        pending.resolve([{ id: 'stale-album' }]); await p;
        assert.deepEqual(a.albums.value, []);
        assert.equal(songs.value[0].id, 'chosen-playlist');
        assert.equal(loading.value, true);
    } finally { scope.stop(); }
});
