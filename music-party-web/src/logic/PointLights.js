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
import { getVisualTheme } from './themeRuntime.js';

const COUNT = 16;
const MIN_HZ = 20;
const MAX_HZ = 16000;
// 与 SpectralWordmark 的 TILT_DB 保持一致：只补高频的自然衰减，不改其它任何视觉参数。
const TILT_DB = 18;
// 光點中心的安全框：留出餘量讓光斑主體留在畫面內（邊緣淡出僅作保險）
const EDGE_X = 0.12;
const EDGE_Y = 0.14;
// 互斥：两盏灯中心的最小间距（以画布宽度为 1）。16 盏灯 + 更小的半径后，
// 这个间距仍留有余量，既拉得开又不会互相顶到无法收敛。
const MIN_SEP = 0.16;
// 归一化坐标里的 y 比 x 短（画布约 420×350），算距离时按此比例校正
const ASPECT = 0.85;

const rand = (a, b) => a + Math.random() * (b - a);

// 白 ↔ 標誌性橙（#F97316）在**線性光**空間插值：直接 sRGB 混會讓中段發灰發髒。
// 「白」端隨主題自適應：深色下純白（發光），淺色下取略暗的中性——否則白燈落在近白底上等於隱形。
const ORANGE = [249, 115, 22];
const WHITE_DARK = [255, 255, 255];
const WHITE_LIGHT = [212, 218, 228];
const srgbToLinear = (v) => (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
const linearToSrgb = (v) => (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
/** t = 0 → 低頻（橙），t = 1 → 高頻（白端） */
export const pointLightColor = (t, white, accent = ORANGE) => accent.map((c, channel) => {
    const a = srgbToLinear(c / 255);
    const b = srgbToLinear(white[channel] / 255);
    return Math.round(linearToSrgb(a + (b - a) * t) * 255);
});

export class PointLights {
    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.levels = new Float32Array(COUNT);
        this.prevRaw = new Float32Array(COUNT);   // 上一帧该带的原始能量（算上升量）
        this.riseMean = new Float32Array(COUNT);  // 上升量的慢速均值（onset 自适应阈值）
        this.pop = new Float32Array(COUNT);       // 瞬态脉冲，0..1
        // 时间常数按频带分层：低频慢（呼吸）、高频快（闪光）。
        // 全部共用 14/72ms 时，16 颗灯的起落完全同步，读起来像整体在抽搐。
        this.attackTau = new Float32Array(COUNT);
        this.releaseTau = new Float32Array(COUNT);
        for (let i = 0; i < COUNT; i++) {
            const t = i / (COUNT - 1);                  // 0 = 低频 → 1 = 高频
            this.attackTau[i] = 0.018 - 0.010 * t;      // 18ms → 8ms
            this.releaseTau[i] = 0.090 - 0.045 * t;     // 90ms → 45ms
        }
        this.time = 0;
        this.flash = 0;
        this.crowd = 0;
        this.animationId = null;
        this._last = 0;
        this.lights = this.seed();
        // 兩套調色板：淺色/深色各一套，繪製時依當前主題取用（避免白燈在淺底上隱形）
        this.palettes = {
            light: Array.from({ length: COUNT }, (_, i) => pointLightColor(i / (COUNT - 1), WHITE_LIGHT)),
            dark: Array.from({ length: COUNT }, (_, i) => pointLightColor(i / (COUNT - 1), WHITE_DARK))
        };
    }

    seed() {
        // best-candidate 撒点：每个位置抽 12 个候选，取离已有点最远的那个。
        // 比纯随机均匀得多（纯随机会成团），也不需要 Poisson 迭代求解。
        const dist = (p, q) => Math.hypot(p.x - q.x, (p.y - q.y) * ASPECT);
        const placed = [];
        for (let i = 0; i < COUNT; i++) {
            let best = null;
            let bestGap = -1;
            for (let k = 0; k < 12; k++) {
                const c = { x: rand(EDGE_X, 1 - EDGE_X), y: rand(EDGE_Y, 1 - EDGE_Y) };
                let gap = Infinity;
                for (const p of placed) gap = Math.min(gap, dist(c, p));
                if (gap > bestGap) { bestGap = gap; best = c; }
            }
            placed.push(best);
        }
        return placed.map((p, i) => {
            const t = i / (COUNT - 1);           // 0 = 低頻（橙）→ 1 = 高頻（白）
            return {
                x: p.x,
                y: p.y,
                r: rand(0.055, 0.12),            // 半徑回到原水平；擁擠度由「點數 16 + 互斥 0.16」控制
                alpha: rand(0.05, 0.10),
                band: t,
                angle: rand(0, Math.PI * 2),     // 航向
                speed: rand(0.022, 0.048),       // 基礎速度（容器比例 / 秒）
                turn: rand(0.25, 0.85),          // 轉向速率
                phase: rand(0, Math.PI * 2)
            };
        });
    }

    /**
     * 互斥：任两盏灯中心距离小于 MIN_SEP 时互相推开（各让一半）。
     * 位置松弛按 dt 归一，故 60/120Hz 的收敛速度一致；276 对/帧，成本可忽略。
     */
    separate(dt) {
        const { width, height } = this;
        if (!width || !height) return;
        const minPx = MIN_SEP * width;
        const k = 1 - Math.exp(-dt / 0.18);
        for (let i = 0; i < COUNT; i++) {
            const a = this.lights[i];
            for (let j = i + 1; j < COUNT; j++) {
                const b = this.lights[j];
                const dx = (b.x - a.x) * width;
                const dy = (b.y - a.y) * height;
                const d = Math.hypot(dx, dy);
                if (d >= minPx) continue;
                // 完全重合时用固定相位给一个方向，避免除零后原地不动
                const ux = d > 1e-3 ? dx / d : Math.cos(a.phase);
                const uy = d > 1e-3 ? dy / d : Math.sin(a.phase);
                const push = (minPx - d) * 0.5 * k;
                a.x -= (ux * push) / width;
                a.y -= (uy * push) / height;
                b.x += (ux * push) / width;
                b.y += (uy * push) / height;
            }
        }
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
        const accent = getVisualTheme().accent;
        this._accentKey = accent.join(',');
        this.palettes = {
            light: Array.from({ length: COUNT }, (_, i) => pointLightColor(i / (COUNT - 1), WHITE_LIGHT, accent)),
            dark: Array.from({ length: COUNT }, (_, i) => pointLightColor(i / (COUNT - 1), WHITE_DARK, accent))
        };
        // 光晕剖面 a(t) = (1-t)^2 * (1 + 1.084t)：
        //   a(0)=1、a(0.45)=0.45（与旧版中段亮度一致）、a(1)=0，且 t=1 处导数为 0——
        //   旧版在 t=0.45 处斜率突变、末端线性收口，放大后能看到一圈「边界」；
        //   这个剖面全程连续可导，尾部收得极慢，边缘几乎不可分辨。
        const SIZE = 256;
        const STOPS = 32;
        const make = (color) => {
            const sprite = document.createElement('canvas');
            sprite.width = SIZE;
            sprite.height = SIZE;
            const g = sprite.getContext('2d');
            const c = SIZE / 2;
            const grad = g.createRadialGradient(c, c, 0, c, c, c);
            const [r, gr, b] = color;
            for (let s = 0; s <= STOPS; s++) {
                const t = s / STOPS;
                const a = (1 - t) * (1 - t) * (1 + 1.084 * t);
                grad.addColorStop(t, `rgba(${r}, ${gr}, ${b}, ${a.toFixed(5)})`);
            }
            g.fillStyle = grad;
            g.fillRect(0, 0, SIZE, SIZE);
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
        for (let i = 0; i < COUNT; i++) {
            const attack = 1 - Math.exp(-dt / this.attackTau[i]);
            const release = 1 - Math.exp(-dt / this.releaseTau[i]);
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

            // 每盞燈自己的 onset：該帶上升量超過自身慢速均值 1.6 倍即判為瞬態。
            // 用自適應閾值而非固定值，故與素材電平、幀率都無關。
            const rise = raw - this.prevRaw[i];
            this.prevRaw[i] = raw;
            this.riseMean[i] += (Math.max(0, rise) - this.riseMean[i]) * (1 - Math.exp(-dt / 0.5));
            if (rise > Math.max(0.02, this.riseMean[i] * 1.6)) {
                this.pop[i] = Math.min(1, this.pop[i] + 0.85);
            }
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
        if (getVisualTheme().accent.join(',') !== this._accentKey) this.buildSprites();

        this.flash *= Math.pow(0.87, dt * 60);
        this.crowd *= Math.pow(0.998, dt * 60);
        // 頻譜事件停止（暫停/斷流）時自行歸零，保證「靜音即不可見」不依賴上游是否還在發事件
        if (this._lastSpectrumAt && performance.now() - this._lastSpectrumAt > 450) {
            const k = Math.pow(0.86, dt * 60);
            for (let i = 0; i < COUNT; i++) this.levels[i] *= k;
        }
        const boost = 1 + 0.45 * this.flash + 0.15 * this.crowd;
        this.time += dt;

        ctx.clearRect(0, 0, width, height);
        // 先互斥再积分：用上一帧的位置推一次，位移量小于一帧的游走距离，肉眼无差
        this.separate(dt);
        const base = Math.max(width, height);
        const dark = document.documentElement.dataset.theme === 'dark'
            || document.documentElement.classList.contains('dark');

        for (let i = 0; i < COUNT; i++) {
            const light = this.lights[i];
            const lv = Math.min(1, this.levels[i] * boost);
            this.pop[i] *= Math.pow(0.86, dt * 60);
            // 瞬態只在亮度上「彈」一下，不改半徑/形狀
            const glow = Math.min(1, lv + 0.5 * this.pop[i]);

            // 連續游走：轉向由各自相位緩慢調變；速度隨該帶能量（響則游得快，靜時仍緩行）
            light.angle += Math.sin(this.time * light.turn + light.phase) * 0.9 * dt;
            const speed = light.speed * (0.45 + 1.8 * lv) * dt;
            light.x += Math.cos(light.angle) * speed;
            light.y += Math.sin(light.angle) * speed;
            // 邊界反射：光點始終留在可視範圍內（不再跨邊界回卷，也就不會「消失後從對側出現」）
            if (light.x < EDGE_X) {
                light.x = EDGE_X + (EDGE_X - light.x);
                light.angle = Math.PI - light.angle;
            } else if (light.x > 1 - EDGE_X) {
                light.x = (1 - EDGE_X) - (light.x - (1 - EDGE_X));
                light.angle = Math.PI - light.angle;
            }
            if (light.y < EDGE_Y) {
                light.y = EDGE_Y + (EDGE_Y - light.y);
                light.angle = -light.angle;
            } else if (light.y > 1 - EDGE_Y) {
                light.y = (1 - EDGE_Y) - (light.y - (1 - EDGE_Y));
                light.angle = -light.angle;
            }
            // 邊緣淡出：接近邊界時漸隱，避免光斑被切邊或回卷時突兀
            const edge = Math.min(1, Math.min(light.x, 1 - light.x) / 0.12,
                Math.min(light.y, 1 - light.y) / 0.12);
            // 半徑與亮度同步隨所屬頻帶（並對正相關，與字標同一原則）
            // 亮度：底噪为 0（静音时完全不可见），并拉开极差——
            // lv 做 1.3 次幂，故弱段压得更暗、强段更快逼近上限（0.72）。
            const r = Math.max(8, light.r * base * (1 + 1.1 * lv));
            const alpha = Math.min(0.72, light.alpha * 12 * Math.pow(glow, 1.3)) * Math.max(0, edge);
            if (alpha < 0.0005) continue;

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
