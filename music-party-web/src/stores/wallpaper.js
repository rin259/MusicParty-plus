import { ref } from 'vue';
import { defineStore } from 'pinia';
import { normalizeWallpaperRoom, normalizeWallpaperSettings } from '../logic/wallpaper.js';

const SETTINGS_KEY = 'mp_wallpaper_settings';
const ROOM_KEY = 'mp_wallpaper_room';

function read(key, normalize) {
    try {
        return normalize(JSON.parse(localStorage.getItem(key)));
    } catch {
        return normalize();
    }
}

export const useWallpaperStore = defineStore('wallpaper', () => {
    const fromLauncher = new URLSearchParams(window.location.search).get('wallpaper') === '1';
    const active = ref(fromLauncher);
    const settings = ref(read(SETTINGS_KEY, normalizeWallpaperSettings));
    const lastRoom = ref(read(ROOM_KEY, normalizeWallpaperRoom));

    function rememberRoom(roomId, entered) {
        lastRoom.value = normalizeWallpaperRoom({ roomId, entered });
        try {
            localStorage.setItem(ROOM_KEY, JSON.stringify(lastRoom.value));
        } catch {
            // Continue to use the current room if storage is unavailable.
        }
    }

    function save(input) {
        const next = normalizeWallpaperSettings(input);
        try {
            localStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
            settings.value = next;
            return true;
        } catch {
            return false;
        }
    }

    return { fromLauncher, active, settings, save, lastRoom, rememberRoom };
});
