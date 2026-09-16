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

export const advanceBassMass = (state, target) => {
    const velocity = state.velocity + (target - state.displacement) * 0.12;
    return {
        displacement: state.displacement + velocity * 0.78,
        velocity: velocity * 0.78
    };
};

export class AudioVisualizer {
    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.center = 0;
        this.animationId = null;

        // 动画状态变量
        this.rippleTime = 0;
        this.breatheOffset = 0;

        // 平滑过渡变量 (Lerp)
        this.smoothAlpha = 0.05;
        this.smoothWidthScale = 0.3;

        // 状态标记
        this.isPlaying = false;
        this.spectrum = new Uint8Array(0);
        this.sampleRate = 48000;
        this.fftSize = 2048;
        this.lowFrequencyBinCount = 1;
        this.energy = 0;
        this.bassEnergy = 0;
        this.bassDisplacement = 0;
        this.bassVelocity = 0;

        // 爆发控制变量
        this.speedMultiplier = 1.0;
        this.widthMultiplier = 1.0;
        this.roughnessMultiplier = 1.0;

        // 配置参数 (Performance Optimized)
        this.breatheBars = 60;
        this.breatheRadiusBase = 180;
        this.barLevels = new Float32Array(this.breatheBars);

        // 圆环定义
        this.rings = [
            { radius: 450, baseWidth: 5, maxWidth: 150, speed: -0.015, offset: 0, segments: 3 },
            { radius: 450, baseWidth: 10, maxWidth: 100, speed: 0.02, offset: 2, segments: 4 },
            { radius: 450, baseWidth: 8, maxWidth: 80, speed: 0.03, offset: 4, segments: 5 }
        ];
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
        this.startLoop();
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

    setSpectrum(payload) {
        this.spectrum = payload?.data || new Uint8Array(0);
        if (!this.spectrum.length) return;
        this.sampleRate = payload.sampleRate;
        this.fftSize = payload.fftSize;
        const binWidth = payload.sampleRate / payload.fftSize;
        this.lowFrequencyBinCount = Math.max(1, Math.min(
            this.spectrum.length,
            Math.floor(80 / binWidth) + 1
        ));
        const total = this.spectrum.reduce((sum, value) => sum + value, 0) / this.spectrum.length / 255;
        const bassBins = this.spectrum.slice(0, this.lowFrequencyBinCount);
        const bass = bassBins.reduce((sum, value) => sum + value, 0) / bassBins.length / 255;
        const energyRate = total < this.energy ? 0.48 : 0.24;
        const bassRate = bass < this.bassEnergy ? 0.55 : 0.3;
        this.energy += (total - this.energy) * energyRate;
        this.bassEnergy += (bass - this.bassEnergy) * bassRate;
    }

    sampleFrequency(frequency) {
        return sampleSpectrumFrequency(this.spectrum, this.sampleRate, this.fftSize, frequency);
    }

    /**
     * 获取当前引擎状态指标
     */
    getStatus() {
        return {
            intensity: this.speedMultiplier.toFixed(2),
            rings: this.rings.length,
            bars: this.breatheBars,
            active: this.isPlaying,
            alpha: this.smoothAlpha.toFixed(2)
        };
    }

    //触发爆发特效
    impulse() {
        // 瞬间拉高参数
        this.speedMultiplier = 6.0;   // 速度变快 8 倍
        this.widthMultiplier = 2.0;   // 宽度变粗 1.8 倍
        this.roughnessMultiplier = 1.5; // 波动幅度变大
    }

    startLoop() {
        const loop = () => {
            if (!this.canvas || !this.ctx) return;

            this.draw();
            this.animationId = requestAnimationFrame(loop);
        };
        loop();
    }

    draw() {
        const { ctx, width, height, center } = this;
        ctx.clearRect(0, 0, width, height);

        // 如果不播放且没有爆发，可以降低渲染频率或跳过部分渲染（为了简单起见，这里保持 loop 但降低计算量）
        
        const decayFactor = 0.005;

        this.speedMultiplier += (1.0 - this.speedMultiplier) * decayFactor;
        this.widthMultiplier += (1.0 - this.widthMultiplier) * decayFactor;
        this.roughnessMultiplier += (1.0 - this.roughnessMultiplier) * decayFactor;

        // --- 1. 状态计算与平滑过渡 (Lerp) ---
        // 时间流速
        const baseSpeed = this.isPlaying ? 0.5 : 0.1;
        this.rippleTime += baseSpeed * this.speedMultiplier;

        // 目标透明度与宽度
        const targetAlpha = this.isPlaying ? 0.25 : 0.05;
        const targetWidthScale = this.isPlaying ? 1.0 : 0.3;

        // 线性插值
        this.smoothAlpha += (targetAlpha - this.smoothAlpha) * 0.03;
        this.smoothWidthScale += (targetWidthScale - this.smoothWidthScale) * 0.05;

        // Low-frequency envelope is integrated as a damped moving mass,
        // approximating a passive radiator near its tuning resonance.
        const bassTarget = this.isPlaying ? this.bassEnergy : 0;
        const mass = advanceBassMass({
            displacement: this.bassDisplacement,
            velocity: this.bassVelocity
        }, bassTarget);
        this.bassDisplacement = mass.displacement;
        this.bassVelocity = mass.velocity;

        // 优化：透明度极低时不渲染复杂图形
        if (this.smoothAlpha < 0.01) return;

        // --- 2. Energy field behind the cover ---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        const glowRadius = 250 + this.energy * 35;
        const glow = ctx.createRadialGradient(center, center, 120, center, center, glowRadius);
        glow.addColorStop(0, `rgba(249, 115, 22, ${this.isPlaying ? 0.025 + this.energy * 0.07 : 0.01})`);
        glow.addColorStop(0.55, `rgba(249, 115, 22, ${this.isPlaying ? this.energy * 0.035 : 0})`);
        glow.addColorStop(1, 'rgba(249, 115, 22, 0)');
        ctx.fillStyle = glow;
        ctx.beginPath();
        ctx.arc(center, center, glowRadius, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();

        // --- 3. 绘制橙色流体圆环 ---
        ctx.save();
        ctx.globalCompositeOperation = 'screen';
        ctx.shadowBlur = 50;
        ctx.shadowColor = '#F97316';

        this.rings.forEach((ring) => {
            ctx.beginPath();
            const count = 120;
            const excursion = Math.max(-0.08, this.bassDisplacement);
            const beatScale = 1 + excursion * 0.72;
            const currentMaxWidth = ring.maxWidth * this.smoothWidthScale * this.roughnessMultiplier * beatScale;
            const radiusOffset = excursion * 18;

            // 外圈
            for (let i = 0; i <= count; i++) {
                const angle = (i / count) * Math.PI * 2;
                const wave = Math.sin(angle * ring.segments + this.rippleTime * ring.speed + ring.offset);
                const normalizedWave = (wave + 1) / 2;
                const currentWidth = ring.baseWidth + normalizedWave * currentMaxWidth;

                const r = ring.radius + radiusOffset + currentWidth / 2;
                const x = center + Math.cos(angle) * r;
                const y = center + Math.sin(angle) * r;

                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }

            // 内圈
            for (let i = count; i >= 0; i--) {
                const angle = (i / count) * Math.PI * 2;
                const wave = Math.sin(angle * ring.segments + this.rippleTime * ring.speed + ring.offset);
                const normalizedWave = (wave + 1) / 2;
                const currentWidth = ring.baseWidth + normalizedWave * currentMaxWidth;

                const r = ring.radius + radiusOffset - currentWidth / 2;
                const x = center + Math.cos(angle) * r;
                const y = center + Math.sin(angle) * r;

                ctx.lineTo(x, y);
            }

            ctx.closePath();
            ctx.fillStyle = `rgba(249, 115, 22, ${this.smoothAlpha})`;
            ctx.fill();
        });
        ctx.restore();

        // --- 4. Log-frequency spectrum with per-bar envelopes ---
        ctx.globalCompositeOperation = 'source-over';
        this.breatheOffset += 0.05;

        for (let i = 0; i < this.breatheBars; i++) {
            const angle = (Math.PI * 2 * i) / this.breatheBars;
            // Continuous vertical-coordinate mapping for exact left/right symmetry.
            const spectrumPosition = (Math.sin(angle) + 1) / 2;
            const frequency = logFrequencyPosition(spectrumPosition);
            const bin = this.sampleFrequency(frequency);
            const targetLevel = Math.pow(bin / 255, 0.72);
            const response = targetLevel > this.barLevels[i] ? 0.46 : 0.16;
            this.barLevels[i] += (targetLevel - this.barLevels[i]) * response;
            const level = this.barLevels[i];
            const h = this.isPlaying ? 5 + level * 58 : 4;

            const startX = center + Math.cos(angle) * (this.breatheRadiusBase + 10);
            const startY = center + Math.sin(angle) * (this.breatheRadiusBase + 10);
            const endX = center + Math.cos(angle) * (this.breatheRadiusBase + 10 + h);
            const endY = center + Math.sin(angle) * (this.breatheRadiusBase + 10 + h);

            ctx.beginPath();
            ctx.moveTo(startX, startY);
            ctx.lineTo(endX, endY);
            ctx.strokeStyle = this.isPlaying ? `rgba(249, 115, 22, ${0.25 + level * 0.48})` : '#D1D5DB';
            ctx.lineWidth = this.isPlaying ? 2.8 : 2;
            ctx.lineCap = 'round';
            ctx.shadowColor = this.isPlaying ? `rgba(249, 115, 22, ${0.28 + level * 0.42})` : 'transparent';
            ctx.shadowBlur = this.isPlaying ? 4 + level * 8 : 0;
            ctx.stroke();
        }
    }
}
