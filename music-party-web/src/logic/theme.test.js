import test from 'node:test';
import assert from 'node:assert/strict';
import { APPEARANCE_KEY, APPEARANCE_BOOT_KEY, DEFAULT_APPEARANCE, normalizeHex, normalizeAppearance, parseAppearance, buildTheme, contrast } from './theme.js';
import { createThemeRuntime } from './themeRuntime.js';
import { seedFromPixels, extractCoverColor } from './monet.js';

test('strict theme imports whitelist fields and reject unsafe/partial/versioned input', () => {
    assert.equal(normalizeHex('#AbC'), '#aabbcc');
    assert.equal(normalizeHex('red;url(x)'), null);
    const valid = parseAppearance(JSON.stringify({ ...DEFAULT_APPEARANCE, account: 'not exported', accent: '#123' }));
    assert.equal(valid.accent, '#112233'); assert.equal(valid.account, undefined);
    for (const raw of ['[]', 'null', '{', JSON.stringify({ version: 2 }), JSON.stringify({ ...DEFAULT_APPEARANCE, light: {} }), ' '.repeat(17000)]) {
        assert.throws(() => parseAppearance(raw));
    }
    assert.deepEqual(normalizeAppearance({ accent: 'bad', mode: 'bad' }), DEFAULT_APPEARANCE);
});

test('all manual extremes and Monet palettes keep text and button contrast >= 4.5', () => {
    for (const mode of ['light', 'dark']) {
        for (const source of ['manual', 'monet']) {
            for (const accent of ['#000000', '#ffffff', '#ffff00', '#00ffff', '#ff00ff', '#2563eb', '#f97316']) {
                for (const background of ['#000000', '#ffffff', '#ff0000', '#005500']) {
                    const p = buildTheme({ ...DEFAULT_APPEARANCE, source, accent, [mode]: { background, surface: accent } }, mode);
                    const t = p.tokens;
                    for (const color of ['medical-500', 'medical-600', 'medical-800', 'medical-900', 'accent-text', 'danger', 'warn', 'success']) {
                        for (const bg of ['medical-50', 'surface-1']) assert.ok(contrast(t[color], t[bg]) >= 4.5,
                            `${mode}/${source}/${accent}/${background} ${color} on ${bg}: ${contrast(t[color], t[bg])}`);
                    }
                    assert.ok(contrast(t['accent-fg'], t.accent) >= 4.5);
                    assert.ok(contrast(t['accent-fg'], t['accent-hover']) >= 4.5);
                    assert.ok(contrast(t['danger-fg'], t.danger) >= 4.5);
                    assert.ok(contrast(t['accent-on-strong'], t['surface-strong']) >= 4.5);
                }
            }
        }
    }
});

function fakeRuntime(initial = {}, restricted = false) {
    const storageMap = new Map(Object.entries(initial));
    const storage = { getItem: k => storageMap.get(k) ?? null, setItem: (k, v) => { if (restricted) throw Error('blocked'); storageMap.set(k, v); }, removeItem: k => storageMap.delete(k) };
    const media = new EventTarget(); media.matches = false;
    const win = new EventTarget(); win.matchMedia = () => media;
    win.CustomEvent = class extends Event { constructor(type, args) { super(type); this.detail = args.detail; } };
    const css = new Map(), classes = new Set();
    const root = { dataset: {}, classList: { toggle(k, on) { if (on) classes.add(k); else classes.delete(k); } }, style: { setProperty: (k, v) => css.set(k, v) } };
    const runtime = createThemeRuntime({ win, storage, doc: { documentElement: root, querySelectorAll: () => [] } });
    return { runtime, media, win, root, css, storageMap };
}
test('runtime migrates legacy mode, previews without persisting and cancels atomically', () => {
    const f = fakeRuntime({ mp_theme: 'dark' });
    assert.equal(f.runtime.getSnapshot().palette.mode, 'dark');
    f.runtime.preview({ ...DEFAULT_APPEARANCE, accent: '#2563eb', mode: 'light' });
    assert.equal(f.css.get('--accent'), '37 99 235'); assert.equal(f.storageMap.has(APPEARANCE_KEY), false);
    f.runtime.cancel(); assert.equal(f.root.dataset.theme, 'dark'); assert.equal(f.css.get('--accent'), '249 115 22');
    f.runtime.save({ ...DEFAULT_APPEARANCE, accent: '#123456' });
    assert.equal(JSON.parse(f.storageMap.get(APPEARANCE_KEY)).accent, '#123456'); assert.equal(f.storageMap.has('mp_theme'), false);
    const boot = JSON.parse(f.storageMap.get(APPEARANCE_BOOT_KEY));
    assert.equal(boot.preference, f.storageMap.get(APPEARANCE_KEY));
    assert.deepEqual(boot.light.accent, [18, 52, 86]); assert.deepEqual(boot.dark.accent, [18, 52, 86]);
    f.runtime.dispose();
});
test('system mode follows live OS changes, manual mode does not', () => {
    const f = fakeRuntime();
    f.media.matches = true; f.media.dispatchEvent(new Event('change'));
    assert.equal(f.root.dataset.theme, 'dark');
    f.runtime.save({ ...DEFAULT_APPEARANCE, mode: 'light' });
    f.media.dispatchEvent(new Event('change')); assert.equal(f.root.dataset.theme, 'light');
    f.runtime.dispose(); f.media.matches = false; f.media.dispatchEvent(new Event('change'));
    assert.equal(f.root.dataset.theme, 'light');
});
test('corrupt storage and unavailable writes retain a usable appearance with a warning', () => {
    const f = fakeRuntime({ [APPEARANCE_KEY]: '{broken' }, true);
    assert.equal(f.runtime.getSnapshot().appearance.accent, DEFAULT_APPEARANCE.accent);
    assert.equal(f.runtime.save({ ...DEFAULT_APPEARANCE, accent: '#00ff00' }), false);
    assert.equal(f.runtime.getSnapshot().appearance.accent, '#00ff00'); assert.ok(f.runtime.getSnapshot().persistenceError);
    f.runtime.cancel(); assert.equal(f.runtime.getSnapshot().appearance.accent, DEFAULT_APPEARANCE.accent);
    f.runtime.dispose();
});
test('cross-tab theme updates replace stale drafts; clear-storage restores defaults', () => {
    const f = fakeRuntime();
    const dispatch = key => { const e = new Event('storage'); Object.defineProperty(e, 'key', { value: key }); f.win.dispatchEvent(e); };
    f.storageMap.set(APPEARANCE_KEY, JSON.stringify({ ...DEFAULT_APPEARANCE, accent: '#ff00ff' }));
    dispatch(APPEARANCE_KEY); assert.equal(f.runtime.getSnapshot().committed.accent, '#ff00ff');
    f.storageMap.clear(); dispatch(null); assert.equal(f.runtime.getSnapshot().committed.accent, DEFAULT_APPEARANCE.accent);
    f.runtime.dispose();
});
test('Monet HCT derives tonal colors and falls back to the chosen manual seed', () => {
    const f = fakeRuntime();
    f.runtime.preview({ ...DEFAULT_APPEARANCE, source: 'monet', accent: '#ff0000' });
    const fallback = f.css.get('--accent');
    f.runtime.setMonetAccent('#0000ff'); assert.notEqual(f.css.get('--accent'), fallback);
    f.runtime.setMonetAccent(null); assert.equal(f.css.get('--accent'), fallback);
    f.runtime.cancel(); assert.equal(f.css.get('--accent'), '249 115 22'); f.runtime.dispose();
});
test('Monet pixel sampling ignores transparency and selects a real dominant color', () => {
    const pixels = new Uint8ClampedArray(400);
    for (let i = 0; i < 400; i += 4) pixels.set([30, 140, 210, 255], i);
    assert.ok(seedFromPixels(pixels)); assert.equal(seedFromPixels(new Uint8ClampedArray(400)), null);
});
test('cover CORS, abort, missing image and timeout failures never reject', async () => {
    class BrokenImage { set src(_) { queueMicrotask(() => this.onerror?.()); } }
    assert.equal(await extractCoverColor('x', { ImageCtor: BrokenImage, documentRef: { createElement() {} } }), null);
    assert.equal(await extractCoverColor(null), null);
    const abort = new AbortController();
    class HangingImage { set src(_) {} }
    const options = { ImageCtor: HangingImage, documentRef: { createElement() {} }, timeoutMs: 5 };
    const pending = extractCoverColor('x', { ...options, signal: abort.signal }); abort.abort();
    assert.equal(await pending, null); assert.equal(await extractCoverColor('x', options), null);
    class LoadedImage { set src(_) { queueMicrotask(() => this.onload?.()); } }
    assert.equal(await extractCoverColor('cross-origin', { ImageCtor: LoadedImage, documentRef: {
        createElement: () => ({ getContext: () => ({ drawImage() {}, getImageData() { throw new Error('tainted canvas'); } }) })
    } }), null);
});
