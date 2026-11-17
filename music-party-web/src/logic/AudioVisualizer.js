import {
    deriveThreeWay, createRadiatorState, stepRadiator, coneAccel, boxPressure, staticExcursion
} from './passiveRadiator.js';
import { fidelity } from './Fidelity.js';
import { getVisualTheme } from './themeRuntime.js';
import { mixRgb } from './theme.js';

export const sampleSpectrumFrequency = (spectrum, sampleRate, fftSize, frequency) => {
    if (!spectrum.length) return 0;
    const bin = frequency * fftSize / sampleRate;
    const lower = Math.max(0, Math.min(spectrum.length - 1, Math.floor(bin)));
    const upper = Math.min(spectrum.length - 1, lower + 1);
    const mix = bin - Math.floor(bin);
    return spectrum[lower] + (spectrum[upper] - spectrum[lower]) * mix;
};

export const logFrequencyPosition = (position, minFrequency = 45, maxFrequency = 16000) => (
    minFrequency * Math.pow(maxFrequency / minFrequency, position)
);

/**
 * AnalyserNode 默认 dB 窗：-100..-30 dB 映射到 0..255。
 * 还原线性幅度以真实反映物理声压动态（动态范围达 ~10 倍）。
 */
const BYTE_TO_AMP = (() => {
    const table = new Float32Array(256);
    for (let i = 0; i < 256; i++) {
        table[i] = Math.pow(10, (-100 + (i / 255) * 70) / 20);
    }
    return table;
})();

/**
 * 频带统计（线性幅度）：供右下角音频遥测使用。
 */
export const spectrumStats = (spectrum, sampleRate, fftSize, fLow, fHigh) => {
    const binHz = sampleRate / fftSize;
    const low = Math.max(0, Math.floor(fLow / binHz));
    const high = Math.min(spectrum.length - 1, Math.ceil(fHigh / binHz));
    if (high < low) return { mean: 0, peak: 0, rms: 0, clip: false };
    let sum = 0;
    let peak = 0;
    let sq = 0;
    let clip = false;
    for (let i = low; i <= high; i++) {
        const amp = BYTE_TO_AMP[spectrum[i]];
        sum += amp;
        sq += amp * amp;
        if (amp > peak) peak = amp;
        if (spectrum[i] === 255) clip = true;
    }
    const n = high - low + 1;
    return { mean: sum / n, peak, rms: Math.sqrt(sq / n), clip };
};

/**
 * 指定频带的线性能量（0..1）。
 */
export const spectrumBandEnergy = (spectrum, sampleRate, fftSize, fLow, fHigh, gain = 60) => {
    if (!spectrum?.length) return 0;
    const binHz = sampleRate / fftSize;
    const low = Math.max(0, Math.floor(fLow / binHz));
    const high = Math.min(spectrum.length - 1, Math.ceil(fHigh / binHz));
    if (high < low) return 0;
    let sum = 0;
    let peak = 0;
    for (let i = low; i <= high; i++) {
        const amp = BYTE_TO_AMP[spectrum[i]];
        sum += amp;
        if (amp > peak) peak = amp;
    }
    return Math.min(1, ((sum / (high - low + 1)) * 0.5 + peak * 0.5) * gain);
};

// 12 分度方位角微刻度查找表（每 30° 一点，预计算，零每帧对象分配）
const AZIMUTH_DIVS = 12;
const AZIMUTH_COS = new Float32Array(AZIMUTH_DIVS);
const AZIMUTH_SIN = new Float32Array(AZIMUTH_DIVS);
for (let i = 0; i < AZIMUTH_DIVS; i++) {
    const a = (i / AZIMUTH_DIVS) * Math.PI * 2;
    AZIMUTH_COS[i] = Math.cos(a);
    AZIMUTH_SIN[i] = Math.sin(a);
}

/**
 * 贝塞尔一类函数 J_m(x) 的快速级数展开（针对圆形膜本征模态 m=0,1,2,3）。
 * 真实圆膜振动方程的本征解即 J_m(k_mn * r) * cos(m * theta)。
 */
export function besselJ(m, x) {
    const half = x * 0.5;
    let term = 1;
    for (let i = 1; i <= m; i++) term *= half / i;
    let sum = term;
    const halfSq = -half * half;
    for (let k = 1; k < 14; k++) {
        term *= halfSq / (k * (m + k));
        sum += term;
        if (Math.abs(term) < 1e-7) break;
    }
    return sum;
}

/**
 * 精密物理声学辐射场可视化器
 *
 * 核心升级：
 * 1. 紧密锚定封面：将封面视作扬声器中心振膜，四周以物理悬边（Surround Roll）弹性连接；
 * 2. 真实膜本征模态：以 Bessel J0（活塞呼吸）、J1/J2（偶极/四极叶瓣）驱动声场扰动，而非人造正弦橡皮筋；
 * 3. 示波器光栅刻度（Calibrated Acoustic Graticule）：背景叠加精密极坐标刻度环与方位准星，波前经过时共鸣点亮；
 * 4. 真实行波声场衰减：波前自内向外辐射，遵循物理柱面波 1/√r 衰减，发丝级线条（0.8px~1.3px），克制优雅。
 */
export class AudioVisualizer {
    static EASE_SLOW = 0.05;
    static EASE_FAST = 0.09;
    static EASE_FALL = 0.16;
    static TIME_SCALE = 0.22;
    static SIM_HZ = 2400;
    static UNITS_PER_MM = 4.2;
    static FLOW_RATE = 28;
    static BAND_GAIN = 60;
    static ENERGY_GAIN = 150;

    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 1200;
        this.height = 1200;
        this.center = 600;
        this.worldScale = 1;
        this.worldPerCss = 1;
        this.animationId = null;

        this.rippleTime = 0;
        this.smoothAlpha = 0.05;
        this.smoothWidthScale = 0.3;

        this.isPlaying = false;
        this.spectrum = new Uint8Array(0);
        this.sampleRate = 48000;
        this.fftSize = 2048;
        this.energy = 0;
        this.band = { low: 0, mid: 0, high: 0 };

        // 扬声器箱体双自由度物理系统（Dayton DCS165-4 + E150HE-PR）
        this.threeWay = deriveThreeWay();
        this.radState = createRadiatorState();
        this.simAcc = 0;
        this.pressure = 0;
        this.coneA = 0;

        // 瞬态撕裂故障模拟
        this.prevLowBand = 0;
        this.lowRiseMean = 0;
        this.glitchUntil = 0;
        this.lastGlitchAt = 0;
        this.glitchSlices = [];
        this.frameSlipPx = 0;
        this.holdUntil = 0;

        this.speedMultiplier = 1.0;
        this.widthMultiplier = 1.0;
        this.roughnessMultiplier = 1.0;

        // 4 道物理声学波前层，各表其真实的电声发声单体：
        // 0: 被动辐射器（次低频气箱共振波，36 Hz）
        // 1: 低音主活塞（声压加速度主波前，Hero 环）
        // 2: 中音（Bessel J1/J2 偶极与四极声学指向性叶瓣）
        // 3: 高音发丝层（极高频微细声学微扰）
        this.rings = [
            { role: 'radiator', source: 'pr', radius: 420, baseWidth: 0.9, maxWidth: 5, speed: 0.014,
              offset: 0, drift: 0.022, fillAlpha: 0.03, edgeAlpha: 0.28, edgeWidth: 0.95,
              cap: 22, priority: 1, points: 96 },
            { role: 'woofer', source: 'woofer', radius: 350, baseWidth: 1.2, maxWidth: 12, speed: -0.018,
              offset: 0, drift: 0, fillAlpha: 0.08, edgeAlpha: 0.72, edgeWidth: 1.35,
              cap: 20, hot: 1, priority: 0, points: 120 },
            { role: 'mid', source: 'mid', radius: 280, baseWidth: 0.9, maxWidth: 6, speed: -0.022,
              offset: 1.7, drift: 0.035, fillAlpha: 0.03, edgeAlpha: 0.32, edgeWidth: 0.95,
              cap: 12, priority: 2, points: 96 },
            { role: 'tweeter', source: 'tweeter', radius: 210, baseWidth: 0.75, maxWidth: 3.5, speed: 0.028,
              offset: 2.6, drift: 0.048, fillAlpha: 0.02, edgeAlpha: 0.26, edgeWidth: 0.8,
              cap: 7, priority: 3, points: 80 }
        ];

        this.squareness = 0;
        this.coverSize = 0;
        this.innerRadius = 180;
        this.maxOuter = 564;
        this.radialSpan = 384;

        this._theme = null;
        this._themeHandler = null;
        this._washGrad = null;
        this._washEnergy = -1;
        this._coreGrad = null;
        this._corePulse = -1;
        this._pt = { x: 0, y: 0, w: 0 };
        this._dir = { x: 0, y: 0 };
    }

    mount(canvasElement) {
        this.unmount();
        this.canvas = canvasElement;
        this.ctx = this.canvas.getContext('2d', { alpha: true });
        this.fitResolution();
        if (typeof window !== 'undefined') {
            this._themeHandler = (e) => {
                const theme = e?.detail || getVisualTheme();
                this._theme = theme;
                this._washGrad = this._coreGrad = null;
            };
            window.addEventListener('music-party:theme-change', this._themeHandler);
            this._themeHandler();
        }
        this.startLoop();
    }

    unmount() {
        if (this._themeHandler && typeof window !== 'undefined') {
            window.removeEventListener('music-party:theme-change', this._themeHandler);
            this._themeHandler = null;
        }
        if (this.animationId && typeof cancelAnimationFrame === 'function') {
            cancelAnimationFrame(this.animationId);
            this.animationId = null;
        }
        this.canvas = null;
        this.ctx = null;
    }

    setPlaying(isPlaying) {
        this.isPlaying = isPlaying;
    }

    fitResolution() {
        if (!this.canvas || !this.ctx) return;
        const cssSize = this.canvas.clientWidth || 0;
        if (!cssSize) return;
        const target = Math.min(1000, Math.max(600, Math.round(cssSize * 1.5 / 4) * 4));
        this.worldPerCss = 1200 / cssSize;
        if (Math.abs(target - this.canvas.width) < 40) return;
        this.canvas.width = target;
        this.canvas.height = target;
        this.worldScale = target / 1200;
        this.width = 1200;
        this.height = 1200;
        this.center = 600;
        this._washGrad = null;
        this._coreGrad = null;
        this._washEnergy = -1;
        this._corePulse = -1;
    }

    makeGlitchSlices(amp = 1) {
        const count = 2 + Math.floor(Math.random() * 3);
        return Array.from({ length: count }, () => ({
            y: 0.06 + Math.random() * 0.86,
            h: 0.03 + Math.random() * 0.07,
            dx: (Math.random() < 0.5 ? -1 : 1) * (3 + Math.random() * 5) * amp
        }));
    }

    layoutRings(coverSize) {
        if (!this.canvas) return;
        const cssSize = this.canvas.clientWidth || 0;
        if (!cssSize) return;
        if (coverSize > 0) this.coverSize = coverSize;
        if (!this.coverSize) return;
        const worldPerCss = 1200 / cssSize;
        const halfDiag = this.coverSize * Math.SQRT1_2 * worldPerCss;

        const place = (gapCss, marginCss) => {
            const inner = halfDiag + gapCss * worldPerCss;
            const room = 600 - marginCss * worldPerCss - inner;
            const preferred = 48 * worldPerCss;
            const minimum = 14 * worldPerCss;
            let keep = this.rings.length;
            while (keep > 1 && room / (keep - 1) < minimum) keep--;
            if (room < 0) keep = 0;
            const step = keep > 1 ? Math.min(preferred, room / (keep - 1)) : 0;
            return { inner, keep, step, marginCss };
        };

        let plan = place(12, 16);
        if (!plan.keep) plan = place(6, 8);
        const { inner, keep, step, marginCss } = plan;

        this.innerRadius = inner;
        this.maxOuter = 600 - marginCss * worldPerCss;
        this.radialSpan = Math.max(20, this.maxOuter - this.innerRadius);

        const byPriority = [...this.rings].sort((a, b) => (a.priority || 0) - (b.priority || 0));
        const shown = new Set(byPriority.slice(0, keep));
        let slot = keep - 1;
        this.rings.forEach((ring) => {
            ring.visible = shown.has(ring);
            if (!ring.visible) return;
            ring.radius = inner + step * slot--;
            ring.spaceCap = keep > 1 ? step * 0.55 : ring.baseWidth * 1.2;
        });
    }

    setSpectrum(payload) {
        this.spectrum = payload?.data || new Uint8Array(0);
        if (!this.spectrum.length) return;
        this.sampleRate = payload.sampleRate;
        this.fftSize = payload.fftSize;

        const now = performance.now();
        const dt = Math.min(0.1, Math.max(0.001, (now - (this._lastSpectrumAt || now)) / 1000));
        this._lastSpectrumAt = now;
        const approach = (prev, next, tau) => prev + (next - prev) * (1 - Math.exp(-dt / tau));

        const total = this.spectrum.reduce((sum, value) => sum + BYTE_TO_AMP[value], 0) / this.spectrum.length;
        const energyTarget = Math.min(1, total * AudioVisualizer.ENERGY_GAIN);
        this.energy = approach(this.energy, energyTarget, energyTarget > this.energy ? 0.023 : 0.044);

        const bandLevel = (fLow, fHigh) => spectrumBandEnergy(
            this.spectrum, this.sampleRate, this.fftSize, fLow, fHigh, AudioVisualizer.BAND_GAIN
        );
        const follow = (prev, next) => approach(prev, next, next > prev ? 0.024 : 0.131);
        this.band = {
            low: follow(this.band.low, bandLevel(20, 300)),
            mid: follow(this.band.mid, bandLevel(300, 2500)),
            high: follow(this.band.high, bandLevel(2500, 16000))
        };
    }

    shapeDirection(angle) {
        const dir = this._dir;
        const cos = Math.cos(angle);
        const sin = Math.sin(angle);
        const mix = this.squareness;
        if (mix < 0.02) {
            dir.x = cos;
            dir.y = sin;
            return dir;
        }
        const SQUIRCLE_N = 3.2;
        const shapeX = Math.sign(cos) * Math.pow(Math.abs(cos), 2 / SQUIRCLE_N);
        const shapeY = Math.sign(sin) * Math.pow(Math.abs(sin), 2 / SQUIRCLE_N);
        dir.x = cos + (shapeX - cos) * mix;
        dir.y = sin + (shapeY - sin) * mix;
        return dir;
    }

    getStatus() {
        return {
            intensity: this.speedMultiplier.toFixed(2),
            rings: this.rings.length,
            active: this.isPlaying,
            alpha: this.smoothAlpha.toFixed(2)
        };
    }

    hold(ms = 110) {
        this.holdUntil = Math.max(this.holdUntil, performance.now() + ms);
    }

    impulse() {
        this.speedMultiplier = 2.8;
        this.widthMultiplier = 1.35;
        this.roughnessMultiplier = 1.15;
    }

    startLoop() {
        const MIN_FRAME_MS = 1000 / 60 - 0.2;
        let lastDraw = performance.now();
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;
            const t = typeof now === 'number' ? now : performance.now();
            if (t < this.holdUntil) {
                if (typeof requestAnimationFrame === 'function') {
                    this.animationId = requestAnimationFrame(loop);
                }
                return;
            }
            const gapMs = t - lastDraw;
            if (gapMs > 50 && gapMs < 2000 && typeof document !== 'undefined'
                && document.visibilityState === 'visible') {
                this.frameSlipPx = (Math.random() < 0.5 ? -1 : 1) * (1 + 2 * (1 - fidelity.update()));
                fidelity.noteFrameSlip(t);
            }
            if (t - lastDraw < MIN_FRAME_MS) {
                if (typeof requestAnimationFrame === 'function') {
                    this.animationId = requestAnimationFrame(loop);
                }
                return;
            }
            const dt = Math.min(1 / 30, Math.max(1 / 240, (t - lastDraw) / 1000));
            lastDraw = t;
            this.draw(dt);
            if (typeof requestAnimationFrame === 'function') {
                this.animationId = requestAnimationFrame(loop);
            }
        };
        if (typeof requestAnimationFrame === 'function') {
            loop();
        }
    }

    /**
     * 绘制实验室级声学光栅与极坐标微刻度（Dieter Rams / Tektronix 示波器美学）。
     * 在暗室中作为仪器的静置基准，当声压波前经过时，沿途的方位角刻度被共鸣点亮。
     */
    drawGraticule(ctx, center, inner, maxOuter, presence, theme) {
        const span = maxOuter - inner;
        if (span < 40) return;
        const r1 = inner + span * 0.28;
        const r2 = inner + span * 0.62;
        const r3 = inner + span * 0.95;
        const rings = [r1, r2, r3];

        const [ar, ag, ab] = theme.accent;
        const energyBoost = this.energy * 0.35 + (this.coneA ? Math.min(0.3, Math.abs(this.coneA) * 0.005) : 0);

        ctx.save();
        ctx.lineWidth = Math.max(0.6, 0.75 * (this.worldPerCss || 1));

        for (let idx = 0; idx < rings.length; idx++) {
            const r = rings[idx];
            // 极细微虚线参考圆
            const circleAlpha = (0.05 + idx * 0.02 + energyBoost * 0.12) * presence;
            ctx.strokeStyle = `rgba(${ar}, ${ag}, ${ab}, ${circleAlpha.toFixed(4)})`;
            ctx.setLineDash([2 * (this.worldPerCss || 1), 6 * (this.worldPerCss || 1)]);
            ctx.beginPath();
            ctx.arc(center, center, r, 0, Math.PI * 2);
            ctx.stroke();

            // 12 分度方位角刻度短线（30° 一点）
            ctx.setLineDash([]);
            const tickLen = (idx === 1 ? 5 : 3.5) * (this.worldPerCss || 1);
            for (let i = 0; i < AZIMUTH_DIVS; i++) {
                const isCardinal = (i % 3 === 0);
                const tickAlpha = (isCardinal ? 0.16 + energyBoost * 0.45 : 0.08 + energyBoost * 0.25) * presence;
                ctx.strokeStyle = `rgba(${ar}, ${ag}, ${ab}, ${tickAlpha.toFixed(4)})`;

                const cos = AZIMUTH_COS[i];
                const sin = AZIMUTH_SIN[i];
                const rIn = r - tickLen * 0.5;
                const rOut = r + tickLen * 0.5;

                ctx.beginPath();
                ctx.moveTo(center + cos * rIn, center + sin * rIn);
                ctx.lineTo(center + cos * rOut, center + sin * rOut);
                ctx.stroke();
            }
        }
        ctx.restore();
    }

    /**
     * 扬声器物理悬边（Surround Roll）：
     * 紧密锚定在封面外围，将矩形封面与外部声学场通过物理弹性折环机械耦合。
     * 行程 xw（米）推动折环径向膨胀，气箱气压（pBox）调节悬边张力。
     */
    drawSurroundRoll(ctx, center, inner, presence, xw, pBox, theme) {
        if (!inner) return;
        ctx.save();
        const excursionOffset = xw * 1000 * AudioVisualizer.UNITS_PER_MM * 0.45;
        const pressureTighten = Math.min(1.5, Math.abs(pBox) / (this.threeWay.pRefPa || 1));
        const rollRadius = inner + excursionOffset;

        const [ar, ag, ab] = theme.accent;
        const [gr, gg, gb] = theme.glow;
        const rollAlpha = (0.35 + Math.min(0.45, Math.abs(xw) * 120)) * presence;

        // 悬边外脊线（高张力发丝线）
        ctx.lineWidth = Math.max(0.85, (1.15 - pressureTighten * 0.2) * (this.worldPerCss || 1));
        ctx.strokeStyle = `rgba(${ar}, ${ag}, ${ab}, ${rollAlpha.toFixed(3)})`;
        ctx.beginPath();
        const count = 72;
        for (let i = 0; i <= count; i++) {
            const angle = (i / count) * Math.PI * 2;
            const dir = this.shapeDirection(angle);
            const cornerStiff = 1 + 0.04 * Math.cos(angle * 4);
            const px = center + dir.x * rollRadius * cornerStiff;
            const py = center + dir.y * rollRadius * cornerStiff;
            if (i === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
        }
        ctx.stroke();

        // 悬边内凹折阴影轮廓
        const innerValley = Math.max(inner * 0.85, rollRadius - 3.5 * (this.worldPerCss || 1));
        ctx.lineWidth = Math.max(0.5, 0.7 * (this.worldPerCss || 1));
        ctx.strokeStyle = `rgba(${gr}, ${gg}, ${gb}, ${(rollAlpha * 0.45).toFixed(3)})`;
        ctx.beginPath();
        for (let i = 0; i <= count; i++) {
            const angle = (i / count) * Math.PI * 2;
            const dir = this.shapeDirection(angle);
            const px = center + dir.x * innerValley;
            const py = center + dir.y * innerValley;
            if (i === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
        }
        ctx.stroke();
        ctx.restore();
    }

    draw(dt = 1 / 60) {
        const { ctx, center } = this;
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        ctx.setTransform(this.worldScale, 0, 0, this.worldScale, 0, 0);

        const decayFactor = 0.005;
        this.speedMultiplier += (1.0 - this.speedMultiplier) * decayFactor;
        this.widthMultiplier += (1.0 - this.widthMultiplier) * decayFactor;
        this.roughnessMultiplier += (1.0 - this.roughnessMultiplier) * decayFactor;

        // 1. 状态计算与平滑过渡
        const targetAlpha = this.isPlaying ? 1 : 0.18;
        const targetWidthScale = this.isPlaying ? 1.0 : 0.3;
        this.smoothAlpha += (targetAlpha - this.smoothAlpha) * AudioVisualizer.EASE_SLOW;
        this.smoothWidthScale += (targetWidthScale - this.smoothWidthScale) * AudioVisualizer.EASE_SLOW;

        // 2. 三分频箱体物理推进
        const forceW = this.isPlaying
            ? this.threeWay.woofer.Bl * this.threeWay.current * this.band.low
            : 0;
        const timeScale = AudioVisualizer.TIME_SCALE;
        this.simAcc += dt * timeScale;
        const h = 1 / AudioVisualizer.SIM_HZ;
        let guard = 0;
        while (this.simAcc >= h && guard++ < 240) {
            this.radState = stepRadiator(this.radState, forceW, h, this.threeWay);
            this.simAcc -= h;
        }
        this.pressure = boxPressure(this.radState, this.threeWay);
        this.coneA = coneAccel(this.radState, forceW, this.threeWay);

        const xw = this.radState.xw;
        const xr = this.radState.xr;
        const xm = this.isPlaying ? staticExcursion(this.threeWay.mid, this.threeWay, this.band.mid) : 0;
        const xt = this.isPlaying ? staticExcursion(this.threeWay.tweeter, this.threeWay, this.band.high) : 0;

        // 瞬态撕裂
        if (this.isPlaying) {
            const now = performance.now();
            const rise = this.band.low - this.prevLowBand;
            this.lowRiseMean += (Math.max(0, rise) - this.lowRiseMean) * (1 - Math.exp(-dt / 0.5));
            const onsetThreshold = Math.max(0.02, this.lowRiseMean * 1.6);
            if (rise > onsetThreshold && now - this.lastGlitchAt > 620) {
                this.lastGlitchAt = now;
                this.glitchUntil = now + 110;
                this.glitchSlices = this.makeGlitchSlices(fidelity.amplify());
            }
        }
        this.prevLowBand = this.band.low;

        // 时间步进
        this.rippleTime += dt * AudioVisualizer.FLOW_RATE * this.speedMultiplier;

        if (this.smoothAlpha < 0.01) return;

        // 掉帧水平错位微偏
        ctx.save();
        if (this.frameSlipPx) {
            ctx.translate(this.frameSlipPx, 0);
            this.frameSlipPx = 0;
        }

        // 超椭圆形态趋近
        const squareTarget = this.isPlaying
            ? Math.min(0.18, (this.speedMultiplier - 1) * 0.12)
            : 0;
        this.squareness += (squareTarget - this.squareness) * AudioVisualizer.EASE_SLOW;

        const theme = this._theme || getVisualTheme();
        const [ar, ag, ab] = theme.accent;
        const accentRgb = theme.accent.join(',');
        const presence = Math.min(1, this.smoothAlpha);

        // --- 3. 中心声压辐射场渐变 ---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        const maxField = this.center * 0.92;
        const aRef = (this.threeWay.xRefMm.woofer / 1000)
            * (2 * Math.PI * this.threeWay.wooferInBox) ** 2;
        const bassPulse = Math.max(0, Math.min(1,
            Math.max(xw * 250, Math.abs(this.coneA) / aRef * 0.6)));

        if (this._washGrad === null || Math.abs(this.energy - this._washEnergy) > 0.02) {
            const washRadius = Math.min(maxField, 275 + this.energy * 85);
            const washAlpha = this.isPlaying ? 0.016 + this.energy * 0.030 : 0.006;
            this._washGrad = ctx.createRadialGradient(center, center, this.center * 0.16, center, center, washRadius);
            this._washGrad.addColorStop(0, `rgba(${accentRgb}, ${washAlpha.toFixed(4)})`);
            this._washGrad.addColorStop(0.55, `rgba(${accentRgb}, ${(washAlpha * 0.4).toFixed(4)})`);
            this._washGrad.addColorStop(1, `rgba(${accentRgb}, 0)`);
            this._washRadius = washRadius;
            this._washEnergy = this.energy;
        }
        ctx.fillStyle = this._washGrad;
        ctx.beginPath();
        ctx.arc(center, center, this._washRadius, 0, Math.PI * 2);
        ctx.fill();

        if (this._coreGrad === null || Math.abs(bassPulse - this._corePulse) > 0.015) {
            const coreRadius = Math.min(maxField, 110 + bassPulse * 110 + this.energy * 40);
            const coreAlpha = this.isPlaying ? 0.020 + bassPulse * 0.085 : 0.008;
            this._coreGrad = ctx.createRadialGradient(center, center, 10, center, center, coreRadius);
            this._coreGrad.addColorStop(0, `rgba(${theme.glow.join(',')}, ${coreAlpha.toFixed(4)})`);
            this._coreGrad.addColorStop(0.45, `rgba(${accentRgb}, ${(coreAlpha * 0.45).toFixed(4)})`);
            this._coreGrad.addColorStop(1, `rgba(${accentRgb}, 0)`);
            this._coreRadius = coreRadius;
            this._corePulse = bassPulse;
        }
        ctx.fillStyle = this._coreGrad;
        ctx.beginPath();
        ctx.arc(center, center, this._coreRadius, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();

        // --- 4. 实验室极坐标微标尺（Graticule）---
        this.drawGraticule(ctx, center, this.innerRadius, this.maxOuter, presence, theme);

        // --- 5. 扬声器折环悬边（Surround Roll）---
        this.drawSurroundRoll(ctx, center, this.innerRadius, presence, xw, this.pressure, theme);

        // --- 6. 物理波前场（贝塞尔膜振动模态 + 真实柱面波衰减）---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';

        const refMm = this.threeWay.xRefMm.woofer;
        const span = this.radialSpan;
        const inner = this.innerRadius;

        this.rings.forEach((ring) => {
            if (ring.visible === false) return;
            ctx.beginPath();
            const count = ring.points || 96;

            const drive = ring.source === 'woofer' ? xw
                : ring.source === 'pr' ? xr
                    : ring.source === 'mid' ? xm : xt;
            const mm = drive * 1000;
            const flowPhase = this.rippleTime * ring.drift;

            // 径向位置与衰减因子
            const normR = Math.max(0.05, Math.min(0.95, (ring.radius - inner) / span));
            const attenuation = Math.max(0.2, 1 / Math.sqrt(1 + normR * 3));

            // 基于物理单体的贝塞尔模态位移函数
            const modalDisplacement = (angle) => {
                if (ring.source === 'woofer') {
                    // 低音主活塞：J0(2.4048 * rho) 径向呼吸模态 + 弱四极微张
                    const j0 = besselJ(0, 2.4048 * normR);
                    const quadrupole = 0.28 * Math.cos(angle * 2 - flowPhase);
                    return (j0 + quadrupole) * mm * AudioVisualizer.UNITS_PER_MM * 0.7;
                } else if (ring.source === 'pr') {
                    // 被动辐射器：大质量超低频 J0 纯振动
                    const j0 = besselJ(0, 2.4048 * normR);
                    return j0 * mm * AudioVisualizer.UNITS_PER_MM * 0.55;
                } else if (ring.source === 'mid') {
                    // 中音：J1(3.8317 * rho) 偶极叶瓣 + J2(5.1356 * rho) 四极旋转模态
                    const j1 = besselJ(1, 3.8317 * normR) * Math.cos(angle + flowPhase);
                    const j2 = 0.5 * besselJ(2, 5.1356 * normR) * Math.cos(angle * 2 - flowPhase * 1.5);
                    return (j1 + j2) * mm * AudioVisualizer.UNITS_PER_MM * 0.9;
                } else {
                    // 高音发丝层：高角向谐波微细微颤
                    const h6 = 0.6 * Math.cos(angle * 6 - flowPhase * 2.2);
                    const h8 = 0.4 * Math.cos(angle * 8 + flowPhase * 3.1);
                    return (h6 + h8) * (0.8 + mm * 1.2) * (this.worldPerCss || 1);
                }
            };

            const pt = this._pt;
            const point = (i, isOuter) => {
                const angle = (i / count) * Math.PI * 2;
                const dir = this.shapeDirection(angle);
                pt.x = dir.x;
                pt.y = dir.y;

                const disp = modalDisplacement(angle);
                const halfW = (ring.baseWidth + Math.min(ring.spaceCap, Math.abs(mm) * 0.8)) * (this.worldPerCss || 1) * 0.5;
                const r = ring.radius + disp + (isOuter ? halfW : -halfW);
                return { x: center + pt.x * r, y: center + pt.y * r };
            };

            // 外轮廓
            for (let i = 0; i <= count; i++) {
                const p = point(i, true);
                if (i === 0) ctx.moveTo(p.x, p.y);
                else ctx.lineTo(p.x, p.y);
            }

            // 内轮廓
            for (let i = count; i >= 0; i--) {
                const p = point(i, false);
                ctx.lineTo(p.x, p.y);
            }
            ctx.closePath();

            // 柔和伴随声压场填充
            const boost = Math.max(0, Math.min(0.25, Math.abs(mm) * 0.06));
            const mix = Math.min(1, Math.max(0, Math.abs(mm) * 0.14)) * (ring.hot || 0);
            const fillBase = `rgba(${mixRgb(theme.accent, theme.glow, mix).join(',')}, `;
            const edgeBase = `rgba(${mixRgb(theme.hover, theme.glow, mix).join(',')}, `;

            const fillA = Math.min(0.35, (ring.fillAlpha + boost * 0.5) * presence * attenuation);
            ctx.fillStyle = `${fillBase}${fillA.toFixed(4)})`;
            ctx.fill();

            // 0.8px ~ 1.35px 发丝级矢量主波前线（Teenage Engineering / Braun 精度）
            const edgeA = Math.min(0.92, (ring.edgeAlpha + boost * 1.8) * presence * attenuation);
            ctx.strokeStyle = `${edgeBase}${edgeA.toFixed(4)})`;
            ctx.lineWidth = Math.max(0.65, ring.edgeWidth * (this.worldPerCss || 1));
            ctx.stroke();
        });
        ctx.restore();

        // 7. 瞬态踢点撕裂（datamosh 切片错位）
        if (performance.now() < this.glitchUntil && this.glitchSlices.length) {
            const w = this.canvas.width;
            const h = this.canvas.height;
            ctx.setTransform(1, 0, 0, 1, 0, 0);
            for (const slice of this.glitchSlices) {
                const sy = Math.max(0, Math.min(h - 2, Math.floor(slice.y * h)));
                const sh = Math.max(2, Math.floor(slice.h * h));
                const dx = Math.round(slice.dx);
                ctx.drawImage(this.canvas, 0, sy, w, sh, dx, sy, w, sh);
            }
            ctx.setTransform(this.worldScale, 0, 0, this.worldScale, 0, 0);
        }
        ctx.restore();
    }
}
