// src/stores/ui.js
import { defineStore } from 'pinia';
import { ref, watch, onScopeDispose } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';
import client from '../api/client';
import { getThemeRuntime } from '../logic/themeRuntime.js';

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(localStorage.getItem(STORAGE_KEYS.LITE_MODE) === 'true');
    const volume = ref(parseFloat(localStorage.getItem(STORAGE_KEYS.VOLUME) || '0.5'));
    const keepAliveEnabled = ref(localStorage.getItem(STORAGE_KEYS.KEEP_ALIVE) === 'true');
    // 移动端抽屉/全屏面板打开时，隐藏浮动聊天按钮，避免它压在面板和播放器上
    const mobileOverlayOpen = ref(false);
    const themeRuntime = getThemeRuntime();
    const theme = ref(themeRuntime.getSnapshot().palette.mode);
    const appearanceSource = ref(themeRuntime.getSnapshot().appearance.source);
    const stopTheme = themeRuntime.subscribe(snapshot => {
        theme.value = snapshot.palette.mode;
        appearanceSource.value = snapshot.appearance.source;
    });
    onScopeDispose(stopTheme);

    const authorName = ref('ThorNex');
    const backWords = ref('THORNEX');

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
    };

    const setVolume = (val) => {
        volume.value = Math.max(0, Math.min(1, val));
    };

    const toggleKeepAlive = () => {
        keepAliveEnabled.value = !keepAliveEnabled.value;
        localStorage.setItem(STORAGE_KEYS.KEEP_ALIVE, keepAliveEnabled.value.toString());
    };

    const applyTheme = (nextTheme) => {
        themeRuntime.save({ ...themeRuntime.getSnapshot().committed, mode: nextTheme });
    };

    const toggleTheme = () => applyTheme(theme.value === 'dark' ? 'light' : 'dark');
    const setMonetAccent = color => themeRuntime.setMonetAccent(color);

    const fetchConfig = async () => {
        try {
            const config = await client.get('/api/config');
            authorName.value = config.authorName;
            backWords.value = config.backWords ?? backWords.value;
        } catch (e) {
            console.error('Failed to fetch config', e);
        }
    };

    // 监听音量变化并持久化
    watch(volume, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.VOLUME, newVal.toString());
    });

    watch(isLiteMode, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.LITE_MODE, newVal.toString());
    });

    return {
        isLiteMode,
        toggleLiteMode,
        volume,
        setVolume,
        keepAliveEnabled,
        toggleKeepAlive,
        mobileOverlayOpen,
        theme,
        appearanceSource,
        applyTheme,
        toggleTheme,
        setMonetAccent,
        authorName,
        backWords,
        fetchConfig
    };
});
