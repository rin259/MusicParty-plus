export const WALLPAPER_EDGES = ['top', 'right', 'bottom', 'left'];
export const WALLPAPER_EDGE_LABELS = { top: '上', right: '右', bottom: '下', left: '左' };
export const WALLPAPER_MAX_DISTANCE = 240;

export function wallpaperDistances(metric, input) {
    const fallback = metric === 'reaction' ? 24 : 0;
    return Object.fromEntries(WALLPAPER_EDGES.map(edge => {
        const value = input?.[edge];
        const distance = Number.isFinite(value) ? Math.round(value) : fallback;
        return [edge, Math.max(metric === 'reaction' ? 1 : 0, Math.min(WALLPAPER_MAX_DISTANCE, distance))];
    }));
}

export function normalizeWallpaperSettings(input) {
    const distances = {};
    for (const metric of ['deadZone', 'reaction']) {
        const linked = input?.[metric]?.linked !== false;
        const edges = wallpaperDistances(metric, input?.[metric]?.edges);
        if (linked) WALLPAPER_EDGES.forEach(edge => { edges[edge] = edges.top; });
        distances[metric] = { linked, edges };
    }
    return distances;
}

export function isInWallpaperBand(distance, span, deadZone, reaction) {
    const start = Math.min(deadZone, span / 4);
    return distance >= start && distance < Math.min(start + reaction, span / 2);
}

export function normalizeWallpaperRoom(input) {
    const roomId = typeof input?.roomId === 'string' ? input.roomId.trim().toLowerCase() : '';
    return { roomId, entered: Boolean(roomId) && input?.entered === true };
}
