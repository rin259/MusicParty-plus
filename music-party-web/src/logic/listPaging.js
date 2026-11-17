/** Stable platform/id identity: never dedupe distinct versions by their title. */
export function appendUniqueSongs(existing, incoming, platform) {
    const seen = new Set();
    const normalize = (song) => {
        if (!song || song.id == null || String(song.id).trim() === '' || song.id === 'INVALID_SKIP') return null;
        return { ...song, platform: song.platform || platform };
    };
    const items = [];
    for (const song of existing || []) {
        const item = normalize(song);
        if (!item) continue;
        const key = `${item.platform}:${item.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        items.push(item);
    }
    const fresh = [];
    let valid = 0;
    for (const song of Array.isArray(incoming) ? incoming : []) {
        const item = normalize(song);
        if (!item) continue;
        valid++;
        const key = `${item.platform}:${item.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        fresh.push(item);
    }
    return { items: [...items, ...fresh], fresh, valid };
}

export function playlistPageInfo(response, startOffset, limit) {
    const raw = Array.isArray(response?.data) ? response.data : [];
    const header = response?.headers?.['x-playlist-next-offset'];
    const hint = header == null || header === '' ? NaN : Number(header);
    const nextOffset = Number.isFinite(hint) && hint >= 0 ? hint : startOffset + raw.length;
    const more = response?.headers?.['x-playlist-has-more'];
    const hasMore = more === 'true' ? true : more === 'false' ? false : raw.length >= limit;
    return { raw, nextOffset, hasMore: hasMore && nextOffset > startOffset };
}
