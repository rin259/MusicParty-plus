import { defineStore } from 'pinia';
import { ref, shallowRef, computed, onScopeDispose } from 'vue';
import { getThemeRuntime } from '../logic/themeRuntime.js';

export const useAppearanceStore = defineStore('appearance', () => {
    const runtime = getThemeRuntime();
    const snapshot = shallowRef(runtime.getSnapshot());
    const isOpen = ref(false);
    const externalRevision = ref(0);
    const stop = runtime.subscribe((next, reason) => {
        snapshot.value = next;
        if (reason === 'storage') externalRevision.value++;
    });
    onScopeDispose(stop);
    const open = () => { runtime.cancel(); isOpen.value = true; };
    const cancel = () => { runtime.cancel(); isOpen.value = false; };
    const save = () => { const saved = runtime.save(); if (saved) isOpen.value = false; return saved; };
    const setMode = mode => runtime.save({ ...snapshot.value.committed, mode });
    return {
        snapshot, isOpen, externalRevision, open, cancel, save, setMode,
        preview: runtime.preview,
        resolvedMode: computed(() => snapshot.value.palette.mode)
    };
});
