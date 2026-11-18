import { onBeforeUnmount, reactive, watch } from 'vue';
import { isInWallpaperBand, WALLPAPER_EDGES } from '../logic/wallpaper.js';

const HIDE_DELAY_MS = 550;

export function useWallpaperEdges(wallpaper, layout, panels) {
    const shown = reactive({});
    const timers = new Map();
    const hovered = new Set();
    let touchMode = false;

    function cancel(edge) {
        clearTimeout(timers.get(edge));
        timers.delete(edge);
    }

    function show(edge) {
        cancel(edge);
        shown[edge] = true;
    }

    function hideLater(edge) {
        if (!shown[edge] || timers.has(edge) || panels[edge].value?.querySelector(':focus-visible')) return;
        timers.set(edge, setTimeout(() => {
            timers.delete(edge);
            if (!panels[edge].value?.querySelector(':focus-visible')) shown[edge] = false;
        }, HIDE_DELAY_MS));
    }

    function handlePointer(event) {
        if (!wallpaper.active || !layout.value) return;
        const { top, right, bottom, left, width, height } = layout.value.getBoundingClientRect();
        const distances = { top: event.clientY - top, right: right - event.clientX,
            bottom: bottom - event.clientY, left: event.clientX - left };
        touchMode = event.pointerType === 'touch';
        hovered.clear();
        for (const edge of WALLPAPER_EDGES) {
            const span = edge === 'top' || edge === 'bottom' ? height : width;
            const reaction = wallpaper.settings.reaction.edges[edge];
            const inside = panels[edge].value?.contains(event.target)
                || isInWallpaperBand(distances[edge], span, wallpaper.settings.deadZone.edges[edge],
                    touchMode ? Math.max(24, reaction) : reaction);
            if (inside) {
                hovered.add(edge);
                show(edge);
            } else {
                hideLater(edge);
            }
        }
    }

    function leave() {
        if (touchMode) return;
        hovered.clear();
        WALLPAPER_EDGES.forEach(hideLater);
    }

    function focusOut() {
        queueMicrotask(() => {
            if (!wallpaper.active) return;
            for (const edge of WALLPAPER_EDGES) {
                if (!hovered.has(edge)) hideLater(edge);
            }
        });
    }

    function reset() {
        hovered.clear();
        touchMode = false;
        for (const edge of WALLPAPER_EDGES) {
            cancel(edge);
            shown[edge] = false;
        }
    }

    watch(() => wallpaper.active, reset);
    onBeforeUnmount(reset);

    return { shown, handlePointer, leave, focusOut };
}
