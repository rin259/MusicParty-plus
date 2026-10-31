import {
    deriveThreeWay, createRadiatorState, stepRadiator, coneAccel, boxPressure, staticExcursion
} from './passiveRadiator.js';

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
 * AnalyserNode 默认 dB 窗：-100..-30 dB 映射到 0..255（本项目未改 min/maxDecibels）。
 * 旧法把字节当线性幅度用，等于把动态压扁：低频常年落在 0.8 上下浮动，
 * 整段音乐的可视幅度只有 ~1.6 倍。还原线性幅度后可达 ~10 倍。
 */
const BYTE_TO_AMP = (() => {
    const table = new Float32Array(256);
    for (let i = 0; i < 256; i++) {
        table[i] = Math.pow(10, (-100 + (i / 255) * 70) / 20);
    }
    return table;
})();

/**
 * 頻帶統計（線性幅度）：平均、峰值、RMS 與削波。供右下角音頻遙測使用。
 * 注意：這裡的幅度是 AnalyserNode 的 dB 窗（-100..-30）反算而來，
 * 是「相對 dB」，不是校準 dBFS，更不是 LUFS。
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
 * 指定頻帶的線性能量（0..1）。供環場與全幅環境光共用，避免兩處各寫一套 dB→線性換算。
 */
export const spectrumBandEnergy = (spectrum, sampleRate, fftSize, fLow, fHigh, gain = 60) => {
    if (!spectrum?.length) return 0;
    const binHz = sampleRate / fftSize;
    const low = Math.max(0, Math.floor(fLow / binHz));
    const high = Math.min(spectrum.length - 1, Math.ceil(fHigh / binHz));
    if (high < low) return 0;
    let sum = 0;
    for (let i = low; i <= high; i++) sum += BYTE_TO_AMP[spectrum[i]];
    return Math.min(1, (sum / (high - low + 1)) * gain);
};

export class AudioVisualizer {
    // 統一緩動常數（C11）：所有指數平滑共用一組時間常數。
    static EASE_SLOW = 0.05;    // 常駐姿態（透明度、寬度、形態）
    static EASE_FAST = 0.09;    // 能量包絡上行
    static EASE_FALL = 0.16;    // 能量包絡下行
    // 物理之時間軸：真實 T/S 級頻率（箱內共振 ~84Hz、調諧 ~36Hz）須以慢動作呈現，
    // 否則在 60fps 下必混疊。定步長積分保 ωh ≤ 0.3（RK4 精度）。
    static TIME_SCALE = 0.22;
    static SIM_HZ = 2400;
    // 全環唯一振幅標尺：物理行程（mm）→ 環半徑位移（世界單位）。
    // 相對量級因此純由物理決定，而非逐環增益；此即「沖程」之唯一調節處。
    // 2026-10 再調：此前為收斂動態壓到 3.0，實看偏小；今放回 4.2（配 cap 提高）。
    static UNITS_PER_MM = 4.2;
    // 環流時鐘：僅為時間尺度（rad/秒之基），非觀測所得之頻率。
    // 各環實際遷徙速率另乘其自身振膜之活動度，故靜則止。
    static FLOW_RATE = 30;
    // 線性幅度 → 可視區間：約 -42 dBFS 落在 0.5 附近（音樂之常態），瞬態可近 1。
    static BAND_GAIN = 60;
    static ENERGY_GAIN = 150;

    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.center = 0;
        this.animationId = null;

        // 动画状态变量
        this.rippleTime = 0;

        // 平滑过渡变量 (Lerp)
        this.smoothAlpha = 0.05;
        this.smoothWidthScale = 0.3;

        // 状态标记
        this.isPlaying = false;
        this.spectrum = new Uint8Array(0);
        this.sampleRate = 48000;
        this.fftSize = 2048;
        this.energy = 0;
        // 三分頻各帶包絡（實測訊號；快起慢落之包絡跟隨器）
        this.band = { low: 0, mid: 0, high: 0 };
        // 三分頻箱體物理：低音雙自由度（被動振膜）＋中音＋高音準靜態響應。
        // 方程、真實器材參數（Mms/fs/Qms/Sd/Bl/Vb）與出處皆見 passiveRadiator.js。
        this.threeWay = deriveThreeWay();
        this.radState = createRadiatorState();
        this.simAcc = 0;        // 慢動作時間累積器（定步長積分）
        this.pressure = 0;      // 箱內氣壓（Pa）
        this.coneA = 0;         // 低音盆加速度（m/s²，遠場輻射聲壓之代理）
        // C：踢點撕裂（強低頻瞬態觸發，每秒最多一次、每次約 110ms）
        this.prevLowBand = 0;
        this.glitchUntil = 0;
        this.lastGlitchAt = 0;
        this.glitchSlices = [];

        // 爆发控制变量
        this.speedMultiplier = 1.0;
        this.widthMultiplier = 1.0;
        this.roughnessMultiplier = 1.0;

        // 圆环定义
        // Rings follow a superellipse ("squircle") that morphs toward the square
        // cover on bass hits, echoing Material-style media ripples.
        // 视觉分级（2026-10 依实看而修）：一英雄（低音：实心带＋锐边＋外晕）、三配角（细带＋锐边）。
        // 先前四环等量填色，叠出十二道柔边，糊作一团霞光；今以 alpha 与线宽分主次。
        // fillAlpha/edgeAlpha 为「播放满载」之绝对值，再乘 presence（淡出用）；行程升则略提。
        // edgeWidth 以 CSS px 计（见 worldPerCss），故细线在任何分辨率都一样细。
        this.rings = [
            { role: 'radiator', source: 'pr', radius: 416, baseWidth: 1.5, maxWidth: 10, speed: 0.017,
              offset: 0, segments: 6, drift: 0.028, fillAlpha: 0.10, edgeAlpha: 0.34, edgeWidth: 1.3,
              wobble: 5, cap: 22, priority: 1, points: 96 },
            { role: 'woofer', source: 'woofer', radius: 352, baseWidth: 3, maxWidth: 24, speed: -0.021,
              offset: 0, segments: 5, drift: 0, fillAlpha: 0.22, edgeAlpha: 0.68, edgeWidth: 1.7,
              wobble: 3, cap: 20, hot: 1, priority: 0, points: 120 },
            { role: 'mid', source: 'mid', radius: 286, baseWidth: 1.5, maxWidth: 10, speed: -0.026,
              offset: 1.7, segments: 4, drift: 0.045, fillAlpha: 0.09, edgeAlpha: 0.30, edgeWidth: 1.3,
              wobble: 4, cap: 12, priority: 2, points: 96 },
            { role: 'tweeter', source: 'tweeter', radius: 214, baseWidth: 1.2, maxWidth: 7, speed: 0.031,
              offset: 2.6, segments: 8, drift: 0.06, fillAlpha: 0.07, edgeAlpha: 0.24, edgeWidth: 1.2,
              wobble: 4, cap: 7, priority: 3, points: 80 }
        ];

        // 形态状态：0 = 圆，向上趋近方形封面
        this.squareness = 0;
        // 環場幾何（由 layoutRings 以實測之封面／畫布尺寸填入）
        this.coverSize = 0;
        this.maxOuter = this.center ? this.center * 0.94 : 564;

        // 渐变缓存（A3）：能量变化小于阈值时复用，免逐帧重建。
        this._washGrad = null;
        this._washEnergy = -1;
        this._coreGrad = null;
        this._corePulse = -1;
        // 逐帧暂存对象（A5）：消除 point() 的小对象分配。
        this._pt = { x: 0, y: 0, w: 0 };
        // 描形方向之暂存（性能）：shapeDirection 不再逐点分配对象。
        this._dir = { x: 0, y: 0 };
    }

    /**
     * 初始化 Canvas
     * @param {HTMLCanvasElement} canvasElement
     */
    mount(canvasElement) {
        this.canvas = canvasElement;
        this.ctx = this.canvas.getContext('2d', { alpha: true }); // optimize
        this.width = this.canvas.width;
        this.height = this.canvas.height;
        this.center = this.width / 2;
        this.worldScale = 1;
        // motion-systems 可达性：系统偏好「减少动态」时环场缓行、爆发静默。
        this.reducedMotion = typeof matchMedia === 'function'
            && matchMedia('(prefers-reduced-motion: reduce)').matches;
        this.buildBloomSprites();
        this.startLoop();
    }

        /**
     * 預渲靜態資源。2026-10：膠片顆粒層已移除——
     * 它以 overlay 3% 鋪滿整塊畫布，令畫布方形與背景之間出現可見色差（深色偏淺、淺色偏深），
     * 形成一塊「矩形」。質感由網格與字標承擔，不再需要該層。
     */
    buildBloomSprites() {
        // 預留：目前無靜態精靈
    }

    unmount() {
        if (this.animationId) {
            cancelAnimationFrame(this.animationId);
            this.animationId = null;
        }
        this.canvas = null;
        this.ctx = null;
    }

    /**
     * 更新播放状态
     * @param {boolean} isPlaying
     */
    setPlaying(isPlaying) {
        this.isPlaying = isPlaying;
    }

    /**
     * 自适应内部分辨率（A4）：内部边长 = CSS 显示尺寸 × 1.5（封顶 1200），
     * 消除移动端 600px 显示却渲染 1200² 的四倍浪费。
     * 绘制恒以 1200×1200 "世界坐标"进行，经 worldScale 变换落到实际像素，
     * 几何常量与断点无关。
     */
    fitResolution() {
        if (!this.canvas || !this.ctx) return;
        const cssSize = this.canvas.clientWidth || 0;
        if (!cssSize) return;
        const target = Math.min(1200, Math.max(600, Math.round(cssSize * 1.5 / 4) * 4));
        this.worldPerCss = 1200 / cssSize; // 世界單位／CSS px（線寬以 CSS px 定義，隨螢幕一致）
        if (Math.abs(target - this.canvas.width) < 40) return;
        this.canvas.width = target;
        this.canvas.height = target;
        this.worldScale = target / 1200;
        // 世界单位恒定：width/height/center 保持 1200/600，
        // 环带与谱条常量无需随分辨率改写。
        this.width = 1200;
        this.height = 1200;
        this.center = 600;
        this.buildBloomSprites();
        this._washGrad = null;
        this._coreGrad = null;
        this._washEnergy = -1;
        this._corePulse = -1;
    }

    /** 每次撕裂現抽：2–4 條切片，位置/厚度/位移全隨機，避免每次都撕在同一處。 */
    makeGlitchSlices() {
        const count = 2 + Math.floor(Math.random() * 3);
        return Array.from({ length: count }, () => ({
            y: 0.06 + Math.random() * 0.86,
            h: 0.03 + Math.random() * 0.07,
            dx: (Math.random() < 0.5 ? -1 : 1) * (3 + Math.random() * 5)
        }));
    }

    /**
     * 環場幾何：以「封面實測尺寸」與「畫布實測尺寸」定各環半徑（世界單位）。
     * 舊制半徑是定值，換螢幕即出事：窄欄把外環裁成直邊，手機則整圈藏到封面之後。
     * 今自封面半對角線起算、等距外推；徑向空間不足時由內而外退環（高音先退），
     * 而非硬塞成一團。幾何既由實測定，任何斷點都不再壓封面、亦不出畫布。
     */
    layoutRings(coverSize) {
        if (!this.canvas) return;
        const cssSize = this.canvas.clientWidth || 0;
        if (!cssSize) return;
        if (coverSize > 0) this.coverSize = coverSize;
        if (!this.coverSize) return;
        const worldPerCss = 1200 / cssSize;
        const halfDiag = this.coverSize * Math.SQRT1_2 * worldPerCss; // 封面半對角（世界單位）

        // 依給定留白求一次排布：可用徑向空間 → 環數 → 環距
        // 留白收緊（18/26 → 12/16）：小筆電上原本只剩 ~22px 環距，四環擠作一團。
        const place = (gapCss, marginCss) => {
            const inner = halfDiag + gapCss * worldPerCss;
            const room = 600 - marginCss * worldPerCss - inner;
            const preferred = 46 * worldPerCss; // 理想環距
            const minimum = 13 * worldPerCss;   // 再窄就退環，免糊作一團
            let keep = this.rings.length;
            while (keep > 1 && room / (keep - 1) < minimum) keep--;
            if (room < 0) keep = 0;
            const step = keep > 1 ? Math.min(preferred, room / (keep - 1)) : 0;
            return { inner, keep, step, marginCss };
        };

        let plan = place(12, 16);
        // 極窄欄（如 1024 視窗之三分欄）：以最小留白再試一次——寧留一線，不失其場。
        if (!plan.keep) plan = place(6, 8);
        const { inner, keep, step, marginCss } = plan;

        // 徑向空間不足時，優先留英雄（低音），其餘依序退場；半徑仍按 Sd 尺寸由外而內排。
        const byPriority = [...this.rings].sort((a, b) => (a.priority || 0) - (b.priority || 0));
        const shown = new Set(byPriority.slice(0, keep));
        let slot = keep - 1;
        this.rings.forEach((ring) => {
            ring.visible = shown.has(ring);
            if (!ring.visible) return;
            ring.radius = inner + step * slot--;
            // 帶幅亦受環距約束（僅容一環時退為細線），免相鄰兩帶相犯。
            ring.spaceCap = keep > 1 ? step * 0.55 : ring.baseWidth * 1.2;
        });
        this.maxOuter = 600 - marginCss * worldPerCss;
    }

    setSpectrum(payload) {
        this.spectrum = payload?.data || new Uint8Array(0);
        if (!this.spectrum.length) return;
        this.sampleRate = payload.sampleRate;
        this.fftSize = payload.fftSize;
        const total = this.spectrum.reduce((sum, value) => sum + BYTE_TO_AMP[value], 0) / this.spectrum.length;
        const energyRate = total < this.energy ? 0.48 : 0.32;
        this.energy += (Math.min(1, total * AudioVisualizer.ENERGY_GAIN) - this.energy) * energyRate;

        // --- 三分頻包絡：帶邊即真實分頻點（300 Hz / 2.5 kHz）---
        // 逐帶取線性幅度均值（非 dB 字節），再抬到可視區間。
        const bandLevel = (fLow, fHigh) => spectrumBandEnergy(
            this.spectrum, this.sampleRate, this.fftSize, fLow, fHigh, AudioVisualizer.BAND_GAIN
        );
        // 包絡跟隨器（快起慢落）：與 FFT 逐幀抖動解耦，仍為實測訊號之包絡。
        const follow = (prev, next) => prev + (next - prev) * (next > prev ? 0.5 : 0.12);
        this.band = {
            low: follow(this.band.low, bandLevel(20, 300)),
            mid: follow(this.band.mid, bandLevel(300, 2500)),
            high: follow(this.band.high, bandLevel(2500, 16000))
        };
    }

    /**
     * 固定角度的描形方向：介于单位圆与超椭圆（squircle, n=3.2）之间，
     * 由 squareness 控制，环场各环共用，保证形态一致。
     * 性能：常态即圆（少用变方后 mix≈0），快路径免逐点 pow；且复用暂存对象。
     */
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

    /**
     * 获取当前引擎状态指标
     */
    getStatus() {
        return {
            intensity: this.speedMultiplier.toFixed(2),
            rings: this.rings.length,
            active: this.isPlaying,
            alpha: this.smoothAlpha.toFixed(2)
        };
    }

    //触发爆发特效
    impulse() {
        if (this.reducedMotion) return; // 減少動態：爆發靜默
        // 瞬间拉高参数（已再收敛，避免过曝）
        this.speedMultiplier = 3.2;
        this.widthMultiplier = 1.45;
        this.roughnessMultiplier = 1.15;
    }

    startLoop() {
        let last = performance.now();
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;

            const t = typeof now === 'number' ? now : performance.now();
            const dt = Math.min(1 / 30, Math.max(1 / 240, (t - last) / 1000));
            last = t;

            this.draw(dt);
            this.animationId = requestAnimationFrame(loop);
        };
        loop();
    }

    draw(dt = 1 / 60) {
        const { ctx, width, height, center } = this;
        // 先以设备像素清屏，再切入世界坐标（A4）。
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        ctx.setTransform(this.worldScale, 0, 0, this.worldScale, 0, 0);

        // 如果不播放且没有爆发，可以降低渲染频率或跳过部分渲染（为了简单起见，这里保持 loop 但降低计算量）
        
        const decayFactor = 0.005;

        this.speedMultiplier += (1.0 - this.speedMultiplier) * decayFactor;
        this.widthMultiplier += (1.0 - this.widthMultiplier) * decayFactor;
        this.roughnessMultiplier += (1.0 - this.roughnessMultiplier) * decayFactor;

        // --- 1. 状态计算与平滑过渡 (Lerp) ---
        // presence（0..1）：环场整体淡入淡出之用。播放为 1，静置为 0.18。
        // 各环之绝对 alpha 另定（见 rings），presence 只管「在不在」，不再兼任亮度。
        const targetAlpha = this.isPlaying ? 1 : 0.18;
        const targetWidthScale = this.isPlaying ? 1.0 : 0.3;

        // 线性插值（统一缓动）
        this.smoothAlpha += (targetAlpha - this.smoothAlpha) * AudioVisualizer.EASE_SLOW;
        this.smoothWidthScale += (targetWidthScale - this.smoothWidthScale) * AudioVisualizer.EASE_SLOW;

        // --- 2. 三分頻物理：驅力即實測之頻帶包絡（無人造 kick 振子）---
        // F = Bl·I·amp（N）：amp 為該頻帶訊號包絡。低音由雙自由度微分方程積分；
        // 中／高音為準靜態懸掛響應。各振膜之相對行程純由 Bl 與 K 決定，非人為增益。
        const forceW = this.isPlaying
            ? this.threeWay.woofer.Bl * this.threeWay.current * this.band.low
            : 0;
        // 定步長慢動作積分：與幀率無關；步長恆保 ωh ≤ 0.3（RK4 精度）。
        const timeScale = AudioVisualizer.TIME_SCALE * (this.reducedMotion ? 0.5 : 1);
        this.simAcc += dt * timeScale;
        const h = 1 / AudioVisualizer.SIM_HZ;
        let guard = 0;
        while (this.simAcc >= h && guard++ < 240) {
            this.radState = stepRadiator(this.radState, forceW, h, this.threeWay);
            this.simAcc -= h;
        }
        this.pressure = boxPressure(this.radState, this.threeWay);
        this.coneA = coneAccel(this.radState, forceW, this.threeWay);

        // 四張振膜之行程（m）：低音／被動振膜取自積分態，中／高音為準靜態。
        const xw = this.radState.xw;
        const xr = this.radState.xr;
        const xm = this.isPlaying ? staticExcursion(this.threeWay.mid, this.threeWay, this.band.mid) : 0;
        const xt = this.isPlaying ? staticExcursion(this.threeWay.tweeter, this.threeWay, this.band.high) : 0;

        // C 觸發：真實 kick（低頻陡升）才撕裂；門檻 + 620ms 冷卻，故每秒至多一次
        if (this.isPlaying && !this.reducedMotion) {
            const now = performance.now();
            if (this.band.low - this.prevLowBand > 0.09 && now - this.lastGlitchAt > 620) {
                this.lastGlitchAt = now;
                this.glitchUntil = now + 110;
                // 每次撕裂重新抽切片：位置/厚度/位移皆随机，故不會每次撕在同一處
                this.glitchSlices = this.makeGlitchSlices();
            }
        }
        this.prevLowBand = this.band.low;

        // 時間推進：環流不再共用一個「頻率」（那無物理依據）——rippleTime 僅為時鐘，
        // 各環之遷徙速率另由其自身振膜之活動度決定（見環場迴圈），故靜則止、動則流。
        this.rippleTime += dt * AudioVisualizer.FLOW_RATE * this.speedMultiplier
            * (this.reducedMotion ? 0.3 : 1);

        // 优化：透明度极低时不渲染复杂图形
        if (this.smoothAlpha < 0.01) return;

        // 少用变方(2026-10)：常态恒圆，唯爆发（impulse）瞬间轻微趋方，cap 0.18。
        const squareTarget = this.isPlaying
            ? Math.min(0.18, (this.speedMultiplier - 1) * 0.12)
            : 0;
        this.squareness += (squareTarget - this.squareness) * AudioVisualizer.EASE_SLOW;

        // --- 3. Layered center light field behind the cover ---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        const maxField = this.center * 0.92;
        // 起音銳度：低音盆行程與其加速度（遠場輻射聲壓 ∝ 加速度）取大者，歸一至 0..1。
        const aRef = (this.threeWay.xRefMm.woofer / 1000)
            * (2 * Math.PI * this.threeWay.wooferInBox) ** 2;
        const bassPulse = Math.max(0, Math.min(1,
            Math.max(xw * 250, Math.abs(this.coneA) / aRef * 0.6)));

        // 2a. Ambient wash riding on overall energy. Gradient rebuilt only when
        // energy moved past a threshold (A3).
        if (this._washGrad === null || Math.abs(this.energy - this._washEnergy) > 0.02) {
            // 2026-10：只收紧约 25%（不删除）——保留暖场气质，但让字标/遥测透出来。
            const washRadius = Math.min(maxField, 285 + this.energy * 85);
            const washAlpha = this.isPlaying ? 0.019 + this.energy * 0.033 : 0.007;
            this._washGrad = ctx.createRadialGradient(center, center, this.center * 0.16, center, center, washRadius);
            this._washGrad.addColorStop(0, `rgba(249, 115, 22, ${washAlpha})`);
            this._washGrad.addColorStop(0.55, `rgba(249, 115, 22, ${washAlpha * 0.45})`);
            this._washGrad.addColorStop(1, 'rgba(249, 115, 22, 0)');
            this._washRadius = washRadius;
            this._washEnergy = this.energy;
        }
        ctx.fillStyle = this._washGrad;
        ctx.beginPath();
        ctx.arc(center, center, this._washRadius, 0, Math.PI * 2);
        ctx.fill();

        // 2b. Bass-driven hot core hugging the cover edge.
        if (this._coreGrad === null || Math.abs(bassPulse - this._corePulse) > 0.015) {
            const coreRadius = Math.min(maxField, 112 + bassPulse * 112 + this.energy * 42);
            const coreAlpha = this.isPlaying ? 0.022 + bassPulse * 0.090 : 0.009;
            this._coreGrad = ctx.createRadialGradient(center, center, 10, center, center, coreRadius);
            this._coreGrad.addColorStop(0, `rgba(255, 196, 120, ${coreAlpha})`);
            this._coreGrad.addColorStop(0.45, `rgba(249, 115, 22, ${coreAlpha * 0.5})`);
            this._coreGrad.addColorStop(1, 'rgba(249, 115, 22, 0)');
            this._coreRadius = coreRadius;
            this._corePulse = bassPulse;
        }
        ctx.fillStyle = this._coreGrad;
        ctx.beginPath();
        ctx.arc(center, center, this._coreRadius, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();

        // --- 4. 橙色流体环场（四環＝四張振膜：被動振膜 → 低音 → 中音 → 高音）---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';

        const refMm = this.threeWay.xRefMm.woofer;
        this.rings.forEach((ring) => {
            if (ring.visible === false) return;
            ctx.beginPath();
            const count = ring.points || 120;
            // 各環取其一張振膜之真實行程（m）；相位、反相與余韻皆由物理耦合自帶。
            const drive = ring.source === 'woofer' ? xw
                : ring.source === 'pr' ? xr
                    : ring.source === 'mid' ? xm : xt;
            // 該振膜行程（mm）：帶寬與亮度之響應皆繫於此物理量。
            const mm = drive * 1000;
            const phase = ring.offset + this.rippleTime * ring.drift;
            // 該環之瓣形遷徙速率 ∝ 其自身振膜活動度（平方根作感知抬升）：靜則止。
            const ringRate = ring.speed * Math.sqrt(Math.min(1, Math.abs(mm) / refMm));
            // 半徑位移（世界單位）= 行程(mm) × UNITS_PER_MM：全環同一標尺，
            // 故相對量級純由物理決定；此標尺即「沖程」之唯一調節處。
            const offsetUnits = Math.max(-ring.cap, Math.min(ring.cap,
                mm * AudioVisualizer.UNITS_PER_MM));
            const radiusOffset = offsetUnits;
            // 低音環帶寬隨箱內氣壓脹縮（pRefPa 為滿幅參照）；餘環隨自身行程微脹。
            const pressureScale = ring.source === 'woofer'
                ? 1 + Math.min(0.5, Math.abs(this.pressure) / this.threeWay.pRefPa * 0.5)
                : 1;
            // 帶寬響應繫於行程本身（mm），與半徑標尺解耦：調「沖程」不致連帶放大帶寬動態。
            // 2026-10：幅度由 ±0.30/0.20 放寬到 +0.45/−0.28——先前動態範圍被壓得看不出起伏。
            const beatScale = 1 + Math.max(-0.28, Math.min(0.45, mm * 0.095));
            // Hard clamp: the ornament outer edge must never reach the canvas rim,
            // otherwise impulse bursts get sliced into a visible straight edge.
            // The squircle's diagonal exceeds `radius` by up to ~13% (n = 3.2),
            // so the shape factor scales with squareness.
            const SQUIRCLE_MAX_FACTOR = 1.133;
            const shapeFactor = 1 + this.squareness * (SQUIRCLE_MAX_FACTOR - 1);
            const maxOuter = this.maxOuter || this.center * 0.94;
            const widthCap = Math.max(
                ring.baseWidth,
                (maxOuter - ring.radius * shapeFactor - Math.abs(radiusOffset) - (ring.wobble || 0)) * 2
            );
            const currentMaxWidth = Math.min(
                Math.min(ring.maxWidth, ring.spaceCap || ring.maxWidth)
                    * this.smoothWidthScale * this.roughnessMultiplier * beatScale * pressureScale,
                widthCap
            );

            const pt = this._pt;
            const point = (i) => {
                const angle = (i / count) * Math.PI * 2;
                const dir = this.shapeDirection(angle);
                pt.x = dir.x;
                pt.y = dir.y;

                // 三谐波叠加（权重 0.62/0.26/0.12，频率不可通约）+ 相位漂移：
                // 瓣形绕环迁徙而非原地循环，是「流」而非「摆」。
                const flowPhase = this.rippleTime * ringRate;
                const wave =
                    Math.sin(angle * ring.segments + flowPhase + phase) * 0.62 +
                    Math.sin(angle * (ring.segments * 2 + 1) - flowPhase + phase * 2) * 0.26 +
                    Math.sin(angle * (ring.segments * 3 + 2) + flowPhase * 1.7 + phase * 3) * 0.12;
                const normalizedWave = (wave + 1) / 2;
                // 徑向微擺：線身沿徑向起伏，輪廓「流」而不失其圓（幅度遠小於行程）。
                pt.wob = wave * (ring.wobble || 0);
                // 受张则薄（体积守恒）：外冲时带幅略收。
                pt.w = ring.baseWidth
                    + normalizedWave * currentMaxWidth
                    * (1 - Math.max(0, Math.min(0.12, mm * 0.024)));

                return pt;
            };

            // 外圈
            for (let i = 0; i <= count; i++) {
                const p = point(i);
                const r = ring.radius + radiusOffset + p.wob + p.w / 2;
                const px = center + p.x * r;
                const py = center + p.y * r;

                if (i === 0) ctx.moveTo(px, py);
                else ctx.lineTo(px, py);
            }

            // 内圈
            for (let i = count; i >= 0; i--) {
                const p = point(i);
                const r = ring.radius + radiusOffset + p.wob - p.w / 2;
                const px = center + p.x * r;
                const py = center + p.y * r;

                ctx.lineTo(px, py);
            }

            ctx.closePath();
            const presence = Math.min(1, this.smoothAlpha);
            const boost = Math.max(0, Math.min(0.2, mm * 0.05));
            const mix = Math.min(1, Math.max(0, mm * 0.144)) * (ring.hot || 0);
            // 填色：橙 500（249,115,22），受光時混向暖高光；邊色取橙 600（234,88,12），
            // 白底上才有真輪廓——先前柔邊加低 alpha，等於一團霞。
            const fillBase = `rgba(${Math.round(249 + 6 * mix)}, ${Math.round(115 + 81 * mix)}, ${Math.round(22 + 98 * mix)}, `;
            const edgeBase = `rgba(${Math.round(234 + 21 * mix)}, ${Math.round(88 + 108 * mix)}, ${Math.round(12 + 108 * mix)}, `;
            // 外暈已去（2026-10 實看）：寬描邊並非真輝光，只把帶緣糊成第二道淡邊。
            // 英雄之別改由實心帶、較粗銳邊與較寬帶幅承擔（見 rings 設定）。
            ctx.fillStyle = `${fillBase}${Math.min(0.55, (ring.fillAlpha + boost) * presence)})`;
            ctx.fill();
            // 銳邊：填後再描一道細線，輪廓分明。
            ctx.strokeStyle = `${edgeBase}${Math.min(0.9, (ring.edgeAlpha + boost * 2) * presence)})`;
            ctx.lineWidth = ring.edgeWidth * (this.worldPerCss || 1);
            ctx.stroke();
        });
        ctx.restore();

        // C：踢點撕裂——把已繪製的整層按數條水平切片錯位重貼（真 datamosh，而非再畫一遍）
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

    }
}
