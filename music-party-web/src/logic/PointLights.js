import { spectrumBandEnergy } from './AudioVisualizer.js';

/**
 * 隨機點光源（spectrum-linked point lights）。
 *
 * 研判（2026-10）：
 *  - 業界做法（Spotify 社群「living artwork / ambient frequency glow」、常見 bokeh 背景）
 *    皆是「離散光點 + 音訊動態」，而非一整片漸變糊。離散光點既保留背景的空氣感，
 *    又能被眼睛解析為「一顆一顆」——這正是「豐富但不搶眼」的分界。
 *  - 位置：不再固定。但**不做瞬移式隨機**（光點跳位會被讀成故障/閃爍）；
 *    改為連續游走：每顆燈有各自航向的隨機游走（sin 調變的轉向速率），
 *    速度隨其頻帶能量增減——響時游得快、靜時幾乎凝住，故「時刻在變」卻不跳變。
 *    出界前先在邊緣淡出，再從對側回卷，避免可見的瞬移。
 *  - 與頻譜的關係：16 顆燈各自绑定一段對數頻帶（低→高），低頻燈在鼓點上先亮，
 *    高頻燈隨鑔片/齒音閃動；於是背景的「哪一顆亮」本身就是頻譜信息的空間化。
 *  - 低解析度畫布再放大：柔化天然、成本極低（每幀 16 個徑向漸變）。
 */
const COUNT = 24;
const MIN_HZ = 20;
const MAX_HZ = 16000;
// 与 SpectralWordmark 的 TILT_DB 保持一致：只补高频的自然衰减，不改其它任何视觉参数。
const TILT_DB = 18;

const rand = (a, b) => a + Math.random() * (b - a);

// 白 ↔ 標誌性橙（#F97316）在**線性光**空間插值：直接 sRGB 混會讓中段發灰發髒。
// 「白」端隨主題自適應：深色下純白（發光），淺色下取略暗的中性——否則白燈落在近白底上等於隱形。
const ORANGE = [249, 115, 22];
const WHITE_DARK = [255, 255, 255];
const WHITE_LIGHT = [212, 218, 228];
const srgbToLinear = (v) => (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
const linearToSrgb = (v) => (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
/** t = 0 → 低頻（橙），t = 1 → 高頻（白端） */
const bandColor = (t, white) => ORANGE.map((c) => {
    const a = srgbToLinear(c / 255);
    const b = srgbToLinear(white[0] / 255);
    return Math.round(linearToSrgb(a + (b - a) * t) * 255);
});

export class PointLights {
    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.levels = new Float32Array(COUNT);
        this.time = 0;
        this.flash = 0;
        this.crowd = 0;
        this.animationId = null;
        this._last = 0;
        this.lights = this.seed();
        // 兩套調色板：淺色/深色各一套，繪製時依當前主題取用（避免白燈在淺底上隱形）
        this.palettes = {
            light: Array.from({ length: COUNT }, (_, i) => bandColor(i / (COUNT - 1), WHITE_LIGHT)),
            dark: Array.from({ length: COUNT }, (_, i) => bandColor(i / (COUNT - 1), WHITE_DARK))
        };
    }

    seed() {
        return Array.from({ length: COUNT }, (_, i) => {
            const t = i / (COUNT - 1);           // 0 = 低頻（橙）→ 1 = 高頻（白）
            return {
                // 避開正中（封面）區域，聚在四野
                x: rand(0.04, 0.96),
                y: rand(0.06, 0.94),
                r: rand(0.055, 0.12),            // 數量增多 → 半徑收小，免糊成一片
                alpha: rand(0.05, 0.10),
                band: t,
                angle: rand(0, Math.PI * 2),     // 航向
                speed: rand(0.022, 0.048),       // 基礎速度（容器比例 / 秒）
                turn: rand(0.25, 0.85),          // 轉向速率
                phase: rand(0, Math.PI * 2)
            };
        });
    }

    mount(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: true });
        this.buildSprites();
        this.resize();
        this._observer = typeof ResizeObserver === 'function'
            ? new ResizeObserver(() => this.resize())
            : null;
        this._observer?.observe(canvas.parentElement || canvas);
        this.start();
    }

    unmount() {
        if (this.animationId) cancelAnimationFrame(this.animationId);
        this.animationId = null;
        this._observer?.disconnect();
        this._observer = null;
        this.canvas = null;
        this.ctx = null;
    }

    /** 预渲染 24×2 张径向光斑 sprite：替代每帧 24 次 createRadialGradient。 */
    buildSprites() {
        if (typeof document === 'undefined') return;
        const make = (color) => {
            const sprite = document.createElement('canvas');
            sprite.width = 128;
            sprite.height = 128;
            const g = sprite.getContext('2d');
            const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
            const [r, gr, b] = color;
            grad.addColorStop(0, `rgba(${r}, ${gr}, ${b}, 1)`);
            grad.addColorStop(0.45, `rgba(${r}, ${gr}, ${b}, 0.45)`);
            grad.addColorStop(1, `rgba(${r}, ${gr}, ${b}, 0)`);
            g.fillStyle = grad;
            g.fillRect(0, 0, 128, 128);
            return sprite;
        };
        this.sprites = {
            light: this.palettes.light.map(make),
            dark: this.palettes.dark.map(make)
        };
    }

    resize() {
        if (!this.canvas) return;
        const host = this.canvas.parentElement || this.canvas;
        const w = host.clientWidth || 0;
        const h = host.clientHeight || 0;
        if (!w || !h) return;
        const scale = Math.min(1, 420 / Math.max(w, h));
        const nextW = Math.max(160, Math.round(w * scale));
        const nextH = Math.max(100, Math.round(h * scale));
        if (nextW === this.canvas.width && nextH === this.canvas.height) return;
        this.canvas.width = nextW;
        this.canvas.height = nextH;
        this.width = nextW;
        this.height = nextH;
    }

    setSpectrum(payload) {
        const { data, sampleRate, fftSize } = payload || {};
        if (!data?.length) return;
        // 时间归一化：响应速度与 rAF 频率无关（120Hz 不再比 60Hz 更快）。
        const now = performance.now();
        const dt = Math.min(0.1, Math.max(0.001, (now - (this._lastSpectrumAt || now)) / 1000));
        this._lastSpectrumAt = now;
        const attack = 1 - Math.exp(-dt / 0.014);
        const release = 1 - Math.exp(-dt / 0.072);
        for (let i = 0; i < COUNT; i++) {
            const t = this.lights[i].band;
            const fLo = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t);
            const bandEnd = Math.min(1, t + 1 / COUNT);
            const fHi = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, bandEnd);
            // 18dB tilt：低端 −9dB、高端 +9dB；位置/半径/alpha 一律不动。
            const tilt = Math.pow(10, (TILT_DB * (bandEnd - 0.5)) / 20);
            const gain = (34 + 46 * bandEnd) * tilt;
            const raw = spectrumBandEnergy(data, sampleRate, fftSize, fLo, fHi, gain);
            const target = Math.min(1, 1 - Math.exp(-1.8 * raw));
            const rate = target > this.levels[i] ? attack : release;
            this.levels[i] += (target - this.levels[i]) * rate;
        }
    }

    pulse(strength = 1) {
        this.flash = Math.min(1, this.flash + 0.45 * strength);
    }

    bumpCrowd(amount = 0.35) {
        this.crowd = Math.min(1, this.crowd + amount);
    }

    start() {
        // 精灵绘制足够便宜：回到 60fps，响应跟手。字标已不再缩放，预算让给它。
        const MIN_FRAME_MS = 1000 / 60 - 0.2;
        let lastDraw = performance.now();
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;
            if (now - lastDraw < MIN_FRAME_MS) {
                this.animationId = requestAnimationFrame(loop);
                return;
            }
            const dt = Math.min(1 / 20, Math.max(1 / 240, (now - lastDraw) / 1000));
            lastDraw = now;
            this.draw(dt);
            this.animationId = requestAnimationFrame(loop);
        };
        this.animationId = requestAnimationFrame(loop);
    }

    draw(dt) {
        const { ctx, width, height } = this;
        if (!ctx || !width || !height) return;

        this.flash *= Math.pow(0.87, dt * 60);
        this.crowd *= Math.pow(0.998, dt * 60);
        const boost = 1 + 0.45 * this.flash + 0.15 * this.crowd;
        this.time += dt;

        ctx.clearRect(0, 0, width, height);
        const base = Math.max(width, height);
        const dark = document.documentElement.dataset.theme === 'dark'
            || document.documentElement.classList.contains('dark');

        for (let i = 0; i < COUNT; i++) {
            const light = this.lights[i];
            const lv = Math.min(1, this.levels[i] * boost);

            // 連續游走：轉向由各自相位緩慢調變；速度隨該帶能量（響則游得快，靜時仍緩行）
            light.angle += Math.sin(this.time * light.turn + light.phase) * 0.9 * dt;
            const speed = light.speed * (0.45 + 1.8 * lv) * dt;
            light.x += Math.cos(light.angle) * speed;
            light.y += Math.sin(light.angle) * speed;
            // 回卷（留邊界餘量；配合邊緣淡出，故不可見跳變）
            if (light.x < -0.12) light.x += 1.24;
            if (light.x > 1.12) light.x -= 1.24;
            if (light.y < -0.12) light.y += 1.24;
            if (light.y > 1.12) light.y -= 1.24;
            // 邊緣淡出：接近邊界時漸隱，避免光斑被切邊或回卷時突兀
            const edge = Math.min(1, Math.min(light.x, 1 - light.x) / 0.12,
                Math.min(light.y, 1 - light.y) / 0.12);
            // 半徑與亮度同步隨所屬頻帶（並對正相關，與字標同一原則）
            // 亮度极差再拉大：静时仍留可见的淡光（0.45 系数），响时逼近上限（4.2 倍、封顶 0.7）
            // 2026-11：原先 0.10 的系数让安静段的 alpha 只有 1–3/255——光点实际上不存在，
            // 于是「在移动」和「看不见」同时成立。抬底噪让每颗光斑始终可辨，游走才读得出来。
            const r = Math.max(8, light.r * base * (1 + 1.1 * lv));
            const alpha = Math.min(0.7, light.alpha * (0.45 + 4.2 * lv)) * Math.max(0, edge);
            if (alpha < 0.004) continue;

            const cx = light.x * width;
            const cy = light.y * height;
            const sprite = (dark ? this.sprites.dark : this.sprites.light)[i];
            if (!sprite) continue;
            ctx.globalAlpha = alpha;
            ctx.drawImage(sprite, cx - r, cy - r, r * 2, r * 2);
            ctx.globalAlpha = 1;
        }
    }
}
