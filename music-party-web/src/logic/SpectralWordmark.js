import { spectrumBandEnergy } from './AudioVisualizer.js';

/**
 * 頻譜字標（spectral wordmark）—— 取時頻譜之「思想」，不取其形。
 *
 * 思想映射：
 *   位置 → 頻率：字標的第 i 個字母代表一段對數頻帶（左低右高），全寬即全頻譜。
 *   強度 → 能量：該帶能量決定字母的顏色（中性灰 → 琥珀）與不透明度。
 *   時間 → 余輝：起音快（0.55）、收得慢（0.16），故每個字母各自留下一道短暫餘輝，
 *              讀起來是「剛才那一下」而非瞬時閃爍。
 *
 * 另外：按讚 → 全體字母短促提亮（參與者的動作看得見）；點歌 → 基調緩升。
 * 恢復經典溫暖琥珀金光澤，保證在字標內容變更或重度重渲染時動態循環不中斷。
 */
const MIN_HZ = 20;
const MAX_HZ = 16000;
// 頻譜傾斜（tilt）：0 = 不傾斜；實測 18dB 時常態下 11 字母亮度極差由 0.33 收窄到 0.13
const TILT_DB = 18;

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

export class SpectralWordmark {
    constructor() {
        this.root = null;
        this.letters = [];
        this.level = new Float32Array(0);
        this.written = [];
        this.flash = 0;
        this.crowd = 0;
        this.prevLow = 0;
        this.animationId = null;
        this._lastSpectrumAt = 0;
    }

    attach(root) {
        if (!root) {
            this.unmount();
            return;
        }
        this.root = root;
        this.letters = Array.from(root.querySelectorAll('.spectral-letter'));
        this.level = new Float32Array(this.letters.length);
        this.written = new Array(this.letters.length).fill('');
        if (!this.animationId && typeof requestAnimationFrame === 'function') {
            this.start();
        }
    }

    /** 重新量測字母（字標內容被設定或房間名稱更改時調用，不中斷動態循環） */
    remeasure() {
        if (!this.root) return;
        const nextLetters = Array.from(this.root.querySelectorAll('.spectral-letter'));
        if (nextLetters.length !== this.letters.length) {
            this.level = new Float32Array(nextLetters.length);
            this.written = new Array(nextLetters.length).fill('');
        }
        this.letters = nextLetters;
        if (!this.animationId && typeof requestAnimationFrame === 'function') {
            this.start();
        }
    }

    setSpectrum(payload) {
        const n = this.letters.length;
        const { data, sampleRate, fftSize } = payload || {};
        if (!n || !data?.length) return;

        // 时间归一化：与 rAF 频率解耦，60/120Hz 的起音与回落一致。
        const now = performance.now();
        const dt = Math.min(0.1, Math.max(0.001, (now - (this._lastSpectrumAt || now)) / 1000));
        this._lastSpectrumAt = now;
        const attack = 1 - Math.exp(-dt / 0.021);
        const release = 1 - Math.exp(-dt / 0.096);

        for (let i = 0; i < n; i++) {
            const t0 = i / n;
            const t1 = (i + 1) / n;
            const fLo = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t0);
            const fHi = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t1);
            const tilt = Math.pow(10, (TILT_DB * (t1 - 0.5)) / 20);
            // 充分提升字标能量增益，使字母对音乐起伏保持灵敏呼吸
            const gain = (45 + 55 * t1) * tilt;
            const raw = spectrumBandEnergy(data, sampleRate, fftSize, fLo, fHi, gain);
            const target = clamp01(1 - Math.exp(-2.2 * raw));
            const rate = target > this.level[i] ? attack : release;
            this.level[i] += (target - this.level[i]) * rate;
        }

        // 低頻瞬態（鼓點）→ 全體短促提亮
        const lowNow = (this.level[0] + this.level[1]) / 2;
        if (lowNow - this.prevLow > 0.04) {
            this.flash = Math.min(1.1, this.flash + (lowNow - this.prevLow) * 3);
        }
        this.prevLow = lowNow;
    }

    pulse(strength = 1) {
        this.flash = Math.min(1.1, this.flash + 0.5 * strength);
    }

    bumpCrowd(amount = 0.35) {
        this.crowd = Math.min(1, this.crowd + amount);
    }

    start() {
        if (this.animationId || !this.root) return;
        const MIN_FRAME_MS = 1000 / 30 - 0.2;
        let lastDraw = performance.now();
        const loop = (now) => {
            if (!this.root) {
                this.animationId = null;
                return;
            }
            if (now - lastDraw < MIN_FRAME_MS) {
                if (typeof requestAnimationFrame === 'function') {
                    this.animationId = requestAnimationFrame(loop);
                }
                return;
            }
            const dt = Math.min(1 / 20, Math.max(1 / 240, (now - lastDraw) / 1000));
            lastDraw = now;
            this.draw(dt);
            if (typeof requestAnimationFrame === 'function') {
                this.animationId = requestAnimationFrame(loop);
            }
        };
        if (typeof requestAnimationFrame === 'function') {
            this.animationId = requestAnimationFrame(loop);
        }
    }

    unmount() {
        if (this.animationId && typeof cancelAnimationFrame === 'function') {
            cancelAnimationFrame(this.animationId);
            this.animationId = null;
        }
        this.root = null;
        this.letters = [];
    }

    draw(dt) {
        this.flash *= Math.pow(0.87, dt * 60);
        this.crowd *= Math.pow(0.998, dt * 60);
        const boost = 1 + 0.45 * this.flash + 0.2 * this.crowd;

        // 映射研判：同一强度 m 同时驱动「色深」与「不透明度」
        // 恢复经典温暖琥珀金光泽：深色下纯净暖琥珀光，浅色下深沉夕阳琥珀
        const dark = typeof document !== 'undefined' && (
            document.documentElement?.dataset?.theme === 'dark' ||
            document.documentElement?.classList?.contains('dark')
        );
        const from = dark ? [64, 70, 84] : [228, 230, 235];
        const to = dark ? [255, 188, 122] : [236, 124, 40];

        for (let i = 0; i < this.letters.length; i++) {
            const lv = clamp01(this.level[i] * boost);
            const m = Math.pow(lv, 0.85);
            const opacity = Math.min(0.88, 0.20 + 0.68 * Math.pow(lv, 0.82));
            const r = Math.round(from[0] + (to[0] - from[0]) * m);
            const g = Math.round(from[1] + (to[1] - from[1]) * m);
            const b = Math.round(from[2] + (to[2] - from[2]) * m);
            const key = `${opacity.toFixed(3)}/${r}/${g}/${b}`;
            if (key === this.written[i]) continue;
            this.written[i] = key;
            const el = this.letters[i];
            el.style.opacity = opacity.toFixed(3);
            el.style.color = `rgb(${r} ${g} ${b})`;
        }
    }
}
