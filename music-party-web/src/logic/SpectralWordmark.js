import { spectrumBandEnergy } from './AudioVisualizer.js';

/**
 * 字標（brand wordmark）—— 已降級為「整體能量水印」。
 *
 * 2026-11：頻譜的職責移交給點陣譜紙（DotMatrix）。字標不再逐字母映射頻帶，
 * 只保留全體能量的色深/透明度/縱向呼吸；否則同一份頻譜會有兩套編碼。
 * 按讚 → 短促提亮；點歌 → 基調緩升；prefers-reduced-motion → 去掉縱向縮放。
 */
const MIN_HZ = 40;
const MAX_HZ = 16000;

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

export class SpectralWordmark {
    constructor() {
        this.root = null;
        this.letters = [];
        this.global = 0;
        this._globalKey = '';
        this.flash = 0;
        this.crowd = 0;
        this.prevLevel = 0;
        this.animationId = null;
        this.reducedMotion = typeof matchMedia === 'function'
            && matchMedia('(prefers-reduced-motion: reduce)').matches;
        this._last = 0;
    }

    attach(root) {
        this.root = root || null;
        this.letters = root ? Array.from(root.querySelectorAll('.spectral-letter')) : [];
        this._globalKey = '';
    }

    /** 重新量測字母（字標內容可被設定更改） */
    remeasure() {
        if (!this.root) return;
        this.attach(this.root);
    }

    setSpectrum(payload) {
        const { data, sampleRate, fftSize } = payload || {};
        if (!this.letters.length || !data?.length) return;

        const raw = spectrumBandEnergy(data, sampleRate, fftSize, MIN_HZ, MAX_HZ, 80);
        const target = clamp01(1 - Math.exp(-1.8 * raw));
        const rate = target > this.global ? 0.55 : 0.16;
        this.global += (target - this.global) * rate;

        if (target - this.prevLevel > 0.04) {
            this.flash = Math.min(1.1, this.flash + (target - this.prevLevel) * 3);
        }
        this.prevLevel = target;
    }

    pulse(strength = 1) {
        this.flash = Math.min(1.1, this.flash + 0.5 * strength);
    }

    bumpCrowd(amount = 0.35) {
        this.crowd = Math.min(1, this.crowd + amount);
    }

    start() {
        this._last = performance.now();
        const loop = (now) => {
            if (!this.root) return;
            const dt = Math.min(1 / 20, Math.max(1 / 240, (now - this._last) / 1000));
            this._last = now;
            this.draw(dt);
            this.animationId = requestAnimationFrame(loop);
        };
        this.animationId = requestAnimationFrame(loop);
    }

    unmount() {
        if (this.animationId) cancelAnimationFrame(this.animationId);
        this.animationId = null;
        this.root = null;
        this.letters = [];
    }

    draw(dt) {
        this.flash *= Math.pow(0.87, dt * 60);
        this.crowd *= Math.pow(0.998, dt * 60);
        const boost = 1 + 0.45 * this.flash + 0.2 * this.crowd;

        const dark = document.documentElement.dataset.theme === 'dark'
            || document.documentElement.classList.contains('dark');
        const from = dark ? [64, 70, 84] : [228, 230, 235];
        const to = dark ? [255, 188, 122] : [236, 124, 40];

        const lv = clamp01(this.global * boost);
        const m = Math.pow(lv, 0.9);
        const opacity = Math.min(0.88, 0.24 + 0.64 * Math.pow(lv, 0.85));
        const scaleY = this.reducedMotion ? 1 : 1 + 0.22 * m;
        const r = Math.round(from[0] + (to[0] - from[0]) * m);
        const g = Math.round(from[1] + (to[1] - from[1]) * m);
        const b = Math.round(from[2] + (to[2] - from[2]) * m);
        const key = `${opacity.toFixed(3)}/${scaleY.toFixed(3)}/${r}/${g}/${b}`;
        if (key === this._globalKey) return;
        this._globalKey = key;

        for (let i = 0; i < this.letters.length; i++) {
            const el = this.letters[i];
            el.style.opacity = opacity.toFixed(3);
            el.style.transform = `scaleY(${scaleY.toFixed(3)})`;
            el.style.color = `rgb(${r} ${g} ${b})`;
        }
    }
}
