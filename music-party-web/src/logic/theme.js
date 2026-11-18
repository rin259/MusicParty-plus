import { CorePalette, argbFromHex, hexFromArgb } from '@material/material-color-utilities';

export const APPEARANCE_KEY = 'mp_appearance_v1';
export const APPEARANCE_BOOT_KEY = 'mp_appearance_boot_v1';
export const DEFAULT_APPEARANCE = {
    version: 1, mode: 'system', source: 'manual', accent: '#f97316',
    light: { background: '#f9fafb', surface: '#ffffff' },
    dark: { background: '#0e1116', surface: '#161a22' }
};
export const THEME_PRESETS = [
    { name: '标志橙', color: '#f97316' }, { name: '海洋蓝', color: '#2563eb' },
    { name: '森林绿', color: '#059669' }, { name: '鸢尾紫', color: '#8b5cf6' },
    { name: '玫瑰红', color: '#e11d48' }, { name: '湖水青', color: '#0891b2' },
    { name: '琥珀金', color: '#eab308' }, { name: '石墨灰', color: '#64748b' }
];
export function normalizeHex(value) {
    if (typeof value !== 'string') return null;
    const hex = value.trim().toLowerCase();
    if (/^#[0-9a-f]{6}$/.test(hex)) return hex;
    if (/^#[0-9a-f]{3}$/.test(hex)) return `#${[...hex.slice(1)].map(c => c + c).join('')}`;
    return null;
}
export const hexRgb = hex => normalizeHex(hex)?.slice(1).match(/../g).map(c => parseInt(c, 16));
export const rgbHex = rgb => `#${rgb.map(c => Math.max(0, Math.min(255, Math.round(c))).toString(16).padStart(2, '0')).join('')}`;
export const mixRgb = (a, b, t) => a.map((c, i) => Math.round(c + (b[i] - c) * t));
export const luminance = rgb => rgb.map(c => c / 255).map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
    .reduce((sum, c, i) => sum + c * [0.2126, 0.7152, 0.0722][i], 0);
export const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + 0.05) / (Math.min(luminance(a), luminance(b)) + 0.05);
export function readableColor(seed, backgrounds, minimum = 4.5) {
    const black = [0, 0, 0], white = [255, 255, 255];
    const score = c => Math.min(...backgrounds.map(bg => contrast(c, bg)));
    if (score(seed) >= minimum) return seed;
    const target = score(black) >= score(white) ? black : white;
    for (let i = 1; i <= 100; i++) {
        const next = mixRgb(seed, target, i / 100);
        if (score(next) >= minimum) return next;
    }
    return target;
}
// Keep both modes readable even when an imported surface has the opposite brightness.
function safeSurface(hex, dark) {
    const rgb = hexRgb(hex), target = dark ? [0, 0, 0] : [255, 255, 255];
    for (let i = 0; i <= 100; i++) {
        const c = mixRgb(rgb, target, i / 100), l = luminance(c);
        if (dark ? l <= 0.045 : l >= 0.6) return c;
    }
    return target;
}
export function normalizeAppearance(raw = {}, { strict = false } = {}) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('主题文件必须是 JSON 对象');
    if (strict && raw.version !== 1) throw new Error('不支持的主题文件版本');
    const mode = ['system', 'light', 'dark'].includes(raw.mode) ? raw.mode : DEFAULT_APPEARANCE.mode;
    if (strict && mode !== raw.mode) throw new Error('主题模式无效');
    const source = ['manual', 'monet'].includes(raw.source) ? raw.source : 'manual';
    if (strict && source !== raw.source) throw new Error('主题取色方式无效');
    const color = (value, fallback) => {
        const parsed = normalizeHex(value);
        if (strict && !parsed) throw new Error('颜色须为 #RGB 或 #RRGGBB 格式');
        return parsed || fallback;
    };
    const result = { version: 1, mode, source, accent: color(raw.accent, DEFAULT_APPEARANCE.accent) };
    for (const name of ['light', 'dark']) {
        result[name] = {};
        for (const key of ['background', 'surface']) result[name][key] = color(raw[name]?.[key], DEFAULT_APPEARANCE[name][key]);
    }
    return result;
}
export function parseAppearance(text) {
    if (typeof text !== 'string' || text.length > 16_384) throw new Error('主题文件过大（最多 16 KB）');
    let raw;
    try { raw = JSON.parse(text); } catch { throw new Error('无法读取 JSON 主题文件'); }
    return normalizeAppearance(raw, { strict: true });
}
export function buildTheme(appearance, resolvedMode = 'light', accentOverride = null) {
    const prefs = normalizeAppearance(appearance), dark = resolvedMode === 'dark';
    const mode = dark ? 'dark' : 'light';
    let background = safeSurface(prefs[mode].background, dark), surface = safeSurface(prefs[mode].surface, dark);
    const fg = dark ? [248, 250, 252] : [17, 24, 39];
    let accent = hexRgb(prefs.accent);
    // Monet uses Google's HCT tonal palettes, not an RGB average masquerading as Material You.
    // Preserve the user's surfaces, adding restrained neutral hue rather than replacing brightness.
    if (prefs.source === 'monet') {
        const palette = CorePalette.of(argbFromHex(accentOverride || prefs.accent));
        const tone = (p, t) => hexRgb(hexFromArgb(p.tone(t)));
        accent = tone(palette.a1, dark ? 80 : 40);
        background = mixRgb(background, tone(palette.n1, dark ? 6 : 98), 0.25);
        surface = mixRgb(surface, tone(palette.n2, dark ? 10 : 99), 0.25);
    }
    const accentFg = readableColor([24, 18, 12], [accent]);
    let hover = mixRgb(accent, luminance(accentFg) > 0.5 ? [0, 0, 0] : [255, 255, 255], 0.1);
    if (contrast(accentFg, hover) < 4.5) hover = accent;
    const backgrounds = [background, surface, mixRgb(background, accent, 0.1)];
    const tokens = {
        'medical-50': background, 'medical-100': mixRgb(background, fg, dark ? 0.035 : 0.025),
        'medical-200': mixRgb(background, fg, dark ? 0.13 : 0.1),
        'medical-300': mixRgb(background, fg, dark ? 0.23 : 0.2),
        'medical-400': mixRgb(background, fg, 0.48),
        'medical-500': readableColor(mixRgb(background, fg, 0.57), backgrounds),
        'medical-600': readableColor(mixRgb(background, fg, 0.7), backgrounds),
        'medical-700': mixRgb(background, fg, 0.83), 'medical-800': mixRgb(background, fg, 0.93), 'medical-900': fg,
        'surface-1': surface, 'surface-2': mixRgb(surface, fg, dark ? 0.025 : 0.04),
        'surface-strong': dark ? [51, 65, 85] : [17, 24, 39],
        'accent-on-strong': readableColor(accent, [dark ? [51, 65, 85] : [17, 24, 39]]),
        'accent': accent, 'accent-hover': hover, 'accent-fg': accentFg,
        'accent-text': readableColor(accent, backgrounds), 'accent-glow': mixRgb(accent, [255, 255, 255], 0.4),
        'ink': fg, 'ink-fg': background, 'scrim': [10, 14, 20]
    };
    for (const [name, seed] of Object.entries({ danger: [185, 28, 28], success: [21, 128, 61], warn: [133, 77, 14] })) {
        const tint = mixRgb(surface, seed, dark ? 0.2 : 0.07);
        tokens[name] = readableColor(seed, [...backgrounds, tint]);
        tokens[`${name}-bg`] = tint;
        tokens[`${name}-border`] = mixRgb(surface, tokens[name], 0.4);
        tokens[`${name}-fg`] = readableColor([255, 255, 255], [tokens[name]]);
    }
    return { mode, tokens, background: rgbHex(background), accent, hover, glow: tokens['accent-glow'] };
}
