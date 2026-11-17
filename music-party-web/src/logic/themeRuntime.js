import { APPEARANCE_KEY, APPEARANCE_BOOT_KEY, DEFAULT_APPEARANCE, normalizeAppearance, buildTheme } from './theme.js';

let active = null;
export function createThemeRuntime({ win, doc, storage }) {
    const media = win.matchMedia?.('(prefers-color-scheme: dark)');
    const listeners = new Set();
    let persistenceError = '';
    const read = () => {
        try {
            const raw = storage?.getItem(APPEARANCE_KEY);
            if (raw) return normalizeAppearance(JSON.parse(raw));
            const mode = storage?.getItem('mp_theme');
            return normalizeAppearance({ ...DEFAULT_APPEARANCE, mode: ['light', 'dark'].includes(mode) ? mode : 'system' });
        } catch { return normalizeAppearance(); }
    };
    let committed = read(), current = committed, palette;
    let monetAccent = null;
    const apply = (reason) => {
        const mode = ['light', 'dark'].includes(current.mode)
            ? current.mode : (media?.matches ? 'dark' : 'light');
        palette = buildTheme(current, mode, current.source === 'monet' ? monetAccent : null);
        const root = doc.documentElement;
        root.classList.toggle('dark', mode === 'dark');
        root.dataset.theme = mode;
        root.style.colorScheme = mode;
        for (const [key, rgb] of Object.entries(palette.tokens)) root.style.setProperty(`--${key}`, rgb.join(' '));
        for (const meta of doc.querySelectorAll('meta[name="theme-color"]')) meta.setAttribute('content', palette.background);
        root.dataset.accent = palette.accent.join(' ');
        const snapshot = getSnapshot();
        for (const fn of listeners) fn(snapshot, reason);
        win.dispatchEvent(new win.CustomEvent('music-party:theme-change', { detail: palette }));
    };
    const getSnapshot = () => ({ appearance: normalizeAppearance(current), committed: normalizeAppearance(committed), palette, monetAccent, persistenceError });
    const preview = prefs => { current = normalizeAppearance(prefs); apply('preview'); };
    const save = (prefs = current) => {
        current = normalizeAppearance(prefs);
        try {
            if (!storage) throw new Error('storage unavailable');
            storage.setItem(APPEARANCE_KEY, JSON.stringify(current));
            // The new preference supersedes the legacy two-state toggle.
            storage.removeItem('mp_theme');
            persistenceError = '';
            committed = normalizeAppearance(current);
        } catch { persistenceError = '浏览器禁止保存设置；本次页面内仍然有效，刷新后可能丢失。'; }
        if (!persistenceError) {
            // Pre-paint cache is derived data, not a second source of truth.
            // Cache both modes; never persist a remote cover URL or extracted art.
            try {
                storage.setItem(APPEARANCE_BOOT_KEY, JSON.stringify({
                    preference: JSON.stringify(current), mode: current.mode,
                    light: buildTheme(current, 'light').tokens,
                    dark: buildTheme(current, 'dark').tokens
                }));
            } catch { /* Optional pre-paint cache must not invalidate a saved preference. */ }
        }
        apply('save');
        return !persistenceError;
    };
    const cancel = () => { current = normalizeAppearance(committed); apply('cancel'); };
    const setMonetAccent = value => {
        const next = value ? normalizeAppearance({ accent: value }).accent : null;
        if (next === monetAccent) return;
        monetAccent = next;
        if (current.source === 'monet') apply('monet');
    };
    const systemChanged = () => { if (current.mode === 'system') apply('system'); };
    const storageChanged = event => {
        if (event.key !== APPEARANCE_KEY && event.key !== null) return;
        committed = read(); current = committed; apply('storage');
    };
    if (media?.addEventListener) media.addEventListener('change', systemChanged);
    else media?.addListener?.(systemChanged);
    win.addEventListener('storage', storageChanged);
    apply('initial');
    return {
        getSnapshot, getPalette: () => palette, preview, save, cancel, setMonetAccent,
        subscribe: fn => { listeners.add(fn); return () => listeners.delete(fn); },
        dispose() {
            if (media?.removeEventListener) media.removeEventListener('change', systemChanged);
            else media?.removeListener?.(systemChanged);
            win.removeEventListener('storage', storageChanged); listeners.clear();
        }
    };
}
export function getThemeRuntime() {
    if (!active) {
        let storage;
        try { storage = window.localStorage; } catch { /* Private/restricted storage: in-memory appearance still works. */ }
        active = createThemeRuntime({ win: window, doc: document, storage });
    }
    return active;
}
const defaultPalette = buildTheme(DEFAULT_APPEARANCE);
export function getVisualTheme() { return active?.getPalette() || defaultPalette; }
