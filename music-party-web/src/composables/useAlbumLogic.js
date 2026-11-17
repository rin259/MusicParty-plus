import { ref, watch, onScopeDispose } from 'vue';
import { musicApi } from '../api/music.js';
import { appendUniqueSongs } from '../logic/listPaging.js';

export function useAlbumLogic(platform, songs, listMode, loading, { api = musicApi, error = () => {} } = {}) {
    const albumSearchMode = ref(false);
    const albums = ref([]);
    const currentAlbumId = ref(null);
    const currentAlbumName = ref('');
    let seq = 0;
    const invalidate = () => { seq++; };
    watch([platform, listMode, albumSearchMode], invalidate, { flush: 'sync' });
    onScopeDispose(invalidate);
    const reset = () => {
        invalidate();
        albums.value = [];
        currentAlbumId.value = null;
        currentAlbumName.value = '';
    };
    const searchAlbums = async (query) => {
        query = query.trim();
        if (!query || platform.value !== 'netease' || !albumSearchMode.value) return;
        listMode.value = 'album-search';
        const run = ++seq;
        const source = platform.value;
        loading.value = true;
        songs.value = [];
        albums.value = [];
        currentAlbumId.value = null;
        try {
            const data = await api.searchAlbums(source, query);
            if (run !== seq) return;
            const seen = new Set();
            albums.value = (Array.isArray(data) ? data : []).filter(album => {
                if (!album?.id || seen.has(String(album.id))) return false;
                seen.add(String(album.id));
                return true;
            });
        } catch (e) {
            if (run === seq) error(e?.response?.data?.message || '搜索专辑失败');
        } finally { if (run === seq) loading.value = false; }
    };
    const selectAlbum = async (album) => {
        if (!albumSearchMode.value || platform.value !== 'netease') return;
        listMode.value = 'album';
        const run = ++seq;
        const source = platform.value;
        currentAlbumId.value = album.id;
        currentAlbumName.value = album.name;
        songs.value = [];
        loading.value = true;
        try {
            const data = await api.getAlbumSongs(source, album.id);
            if (run === seq) songs.value = appendUniqueSongs([], data, source).items;
        } catch (e) {
            if (run === seq) error(e?.response?.data?.message || '加载专辑失败');
        } finally { if (run === seq) loading.value = false; }
    };
    const backToAlbums = () => {
        invalidate();
        currentAlbumId.value = null;
        currentAlbumName.value = '';
        songs.value = [];
        loading.value = false;
        listMode.value = 'album-search';
    };
    return { albumSearchMode, albums, currentAlbumId, currentAlbumName, searchAlbums, selectAlbum, backToAlbums, reset };
}
