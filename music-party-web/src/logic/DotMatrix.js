import { spectrumBandEnergy } from './AudioVisualizer.js';

/**
 * 点阵谱纸（Dot-Matrix Score）。
 *
 * 取 watsonCrick 的骨架：列＝对数频率、竖向＝时间、未点亮的点也在；
 * 取 cellBlock4 的呼吸：点有 ±1px 的慢抖动，避免变成 Excel 表格。
 * 拒掉两者的皮：DNA 隐喻、品红/青、强 bloom。
 *
 * 纪律：
 *  - 它是画面里唯一的离散数据层；浅色是墨点，深色是微光点。
 *  - 当前行橙、历史墨；未点亮 4–5%，点亮 8–45%（深色 10–55%）。
 *  - 160ms 一行、离散下移；不插值、不辉光。
 *  - 40px 网格（窄屏 24px）；点阵替换网格线，不新增图层。
 */

const MIN_HZ = 40;
const MAX_HZ = 16000;
const ROW_MS = 160;
const TILT_DB = 18;
const MAX_CATCHUP = 4;

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

export class DotMatrix {
    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.dpr = 1;
        this.pitch = 40;
        this.origin = 40;
        this.cols = 0;
        this.rows = 0;
        this.levels = new Float32Array(0);
        this.history = [];
        this.time = 0;
        this._raf = 0;
        this._last = 0;
        this._drawAt = 0;
        this._tickAt = 0;
        this.reducedMotion = typeof matchMedia === 'function'
            && matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    mount(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: true });
        this.resize();
        this._observer = typeof ResizeObserver === 'function'
            ? new ResizeObserver(() => this.resize())
            : null;
        this._observer?.observe(canvas.parentElement || canvas);
        this.start();
    }

    unmount() {
        if (this._raf) cancelAnimationFrame(this._raf);
        this._raf = 0;
        this._observer?.disconnect();
        this._observer = null;
        this.canvas = null;
        this.ctx = null;
    }

    resize() {
        if (!this.canvas) return;
        const host = this.canvas.parentElement || this.canvas;
        const cssW = host.clientWidth || 0;
        const cssH = host.clientHeight || 0;
        if (!cssW || !cssH) return;

        const nextDpr = Math.min(2, window.devicePixelRatio || 1);
        const nextPitch = cssW < 620 ? 24 : 40;
        // 尺寸/像素比/pitch 未变时不重建：否则 ResizeObserver 每次回调都会清空历史。
        if (this.cols && this.width === cssW && this.height === cssH
            && this.dpr === nextDpr && this.pitch === nextPitch) {
            return;
        }

        this.dpr = nextDpr;
        this.canvas.width = Math.round(cssW * this.dpr);
        this.canvas.height = Math.round(cssH * this.dpr);
        this.width = cssW;
        this.height = cssH;

        // 窄屏用 24px，桌面用 40px；origin 取一个 pitch，和 40px 内缩/角标同源。
        this.pitch = nextPitch;
        this.origin = this.pitch;
        this.cols = Math.max(8, Math.floor((cssW - this.origin) / this.pitch) + 1);
        this.rows = Math.max(8, Math.floor((cssH - this.origin) / this.pitch) + 1);

        this.levels = new Float32Array(this.cols);
        this.history = Array.from({ length: this.rows }, () => new Float32Array(this.cols));
        this._tickAt = performance.now();
    }

    setSpectrum(payload) {
        const { data, sampleRate, fftSize } = payload || {};
        if (!data?.length || !this.cols) return;

        for (let c = 0; c < this.cols; c++) {
            const t0 = c / this.cols;
            const t1 = (c + 1) / this.cols;
            const fLo = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t0);
            const fHi = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t1);
            const tilt = Math.pow(10, (TILT_DB * (t1 - 0.5)) / 20);
            const gain = (40 + 40 * t1) * tilt;
            const raw = spectrumBandEnergy(data, sampleRate, fftSize, fLo, fHi, gain);
            const target = clamp01(1 - Math.exp(-2.6 * raw));
            const rate = target > this.levels[c] ? 0.5 : 0.18;
            this.levels[c] += (target - this.levels[c]) * rate;
        }
    }

    pushRow() {
        this.history.pop();
        this.history.unshift(Float32Array.from(this.levels));
    }

    tick(now) {
        if (!this.history.length) return;
        let guard = 0;
        while (now - this._tickAt >= ROW_MS && guard++ < MAX_CATCHUP) {
            this._tickAt += ROW_MS;
            this.pushRow();
        }
        if (now - this._tickAt > ROW_MS * 8) this._tickAt = now; // 后台回来不补上百行
    }

    start() {
        this._last = performance.now();
        this._drawAt = 0;
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;
            const dt = Math.min(1 / 20, Math.max(1 / 240, (now - this._last) / 1000));
            this._last = now;
            this.tick(now);
            if (now - this._drawAt >= 33) {
                this._drawAt = now;
                this.draw(dt, now);
            }
            this._raf = requestAnimationFrame(loop);
        };
        this._raf = requestAnimationFrame(loop);
    }

    draw(dt, now) {
        const { ctx, width, height, pitch, origin, cols, rows } = this;
        if (!ctx || !width || !height || !cols || !rows) return;

        const dark = document.documentElement.dataset.theme === 'dark'
            || document.documentElement.classList.contains('dark');
        const ink = dark ? '255, 255, 255' : '20, 24, 30';
        const unlitAlpha = dark ? 0.05 : 0.04;
        this.time += this.reducedMotion ? 0 : dt;

        ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        ctx.clearRect(0, 0, width, height);

        const jitter = (c, r, axis) => {
            if (this.reducedMotion) return 0;
            const p = axis === 0 ? 1.3 : 0.9;
            const q = axis === 0 ? 0.7 : 1.1;
            return Math.sin(this.time * (axis === 0 ? 0.25 : 0.21) + c * p + r * q) * 0.8;
        };

        // 1) 未点亮的点：它就是网格
        ctx.fillStyle = `rgba(${ink}, ${unlitAlpha})`;
        for (let r = 0; r < rows; r++) {
            const y = origin + r * pitch;
            for (let c = 0; c < cols; c++) {
                const x = origin + c * pitch;
                ctx.beginPath();
                ctx.arc(x + jitter(c, r, 0), y + jitter(c, r, 1), 0.9, 0, Math.PI * 2);
                ctx.fill();
            }
        }

        // 2) 点亮的点：当前行橙，历史墨
        for (let r = 0; r < rows; r++) {
            const row = this.history[r];
            if (!row) continue;
            const age = rows > 1 ? r / (rows - 1) : 0;
            const y = origin + r * pitch;
            for (let c = 0; c < cols; c++) {
                const v = row[c];
                if (v < 0.02) continue;
                const x = origin + c * pitch;
                const isNow = r === 0;
                const alpha = isNow
                    ? Math.min(0.64, 0.26 + 0.45 * v)
                    : Math.min(0.54, (0.16 + 0.44 * v) * (1 - 0.30 * age));
                const radius = isNow ? 2.0 + 2.8 * v : 1.8 + 2.6 * v;
                ctx.fillStyle = isNow ? `rgba(249, 115, 22, ${alpha})` : `rgba(${ink}, ${alpha})`;
                ctx.beginPath();
                ctx.arc(x + jitter(c, r, 0), y + jitter(c, r, 1), radius, 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }
}
