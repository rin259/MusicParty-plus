import { spectrumStats } from './AudioVisualizer.js';

/**
 * 右下角音頻遙測（A1 + B1 + B4 中的 B1/B4）。
 *
 * 立場：只顯示**真的測得到**的量。AnalyserNode 的位元組值是 -100..-30 dB 窗的映射，
 * 故這裡的 dB 是「相對 dB」（面板標 REL_DB），不是校準 dBFS，更不是 LUFS。
 *  - B1：LOW / MID / HIGH 三條電平 + 峰值保持刻度（慢回落）+ 每帶 dB 數值；
 *  - B4：每 500ms 一行真實快照（滾動三行），保留「HUD 日誌」的閱讀感；
 *  - 另保留一行真實連線狀態（原 SYS 塊裡只有這類是真的，其餘為氛圍文案，已刪）。
 *
 * 效能：DOM 寫入節流到 12.5Hz（80ms），條用 transform 而非寬度，避免逐幀重排。
 */
const BANDS = [
    { key: 'low', label: 'LOW', from: 20, to: 300 },
    { key: 'mid', label: 'MID', from: 300, to: 2500 },
    { key: 'high', label: 'HIGH', from: 2500, to: 16000 }
];
// 分析器窗為 -100..-30 dB：電平條取「峰值」，量程 -80..-30（50dB），
// 故低頻可打滿、高頻仍有 ~0.4 的可讀位置，不會整條空掉。
const FLOOR_DB = -80;
const SPAN_DB = 50;
const RENDER_MS = 80;
const LOG_MS = 500;
const SILENCE_MS = 450;

const toDb = (amp) => 20 * Math.log10(Math.max(amp, 1e-7));

export class AudioTelemetry {
    constructor() {
        this.root = null;
        this.rows = [];
        this.meta = {};
        this.lineEls = [];
        this.levels = { low: 0, mid: 0, high: 0 };   // 條＝當下電平（VU 式，取均值）
        this.peaks = { low: 0, mid: 0, high: 0 };    // 刻度＝峰值保持（PPM 式，慢回落）
        this.global = { peak: 0, rms: 0, crest: 0, clip: false };
        this.state = { connected: false, queue: 0 };
        this.lastRender = 0;
        this.lastLog = 0;
        this.lastSpectrumAt = 0;
    }

    attach(root) {
        this.root = root || null;
        if (!this.root) return;
        this.rows = BANDS.map((band) => {
            const row = this.root.querySelector(`[data-band="${band.key}"]`);
            return {
                ...band,
                bar: row?.querySelector('.telem-bar') || null,
                peakEl: row?.querySelector('.telem-peak') || null,
                value: row?.querySelector('.telem-value') || null
            };
        });
        const q = (name) => this.root.querySelector(`[data-meta="${name}"]`);
        this.meta = {
            crest: q('crest'), peak: q('peak'), rms: q('rms'), clip: q('clip'), link: q('link')
        };
        this.lineEls = Array.from(this.root.querySelectorAll('[data-line]'));
    }

    setSpectrum(payload) {
        if (!payload?.data?.length) return;
        const { data, sampleRate, fftSize } = payload;
        this.lastSpectrumAt = performance.now();
        for (const band of BANDS) {
            const stats = spectrumStats(data, sampleRate, fftSize, band.from, band.to);
            // 條與數值＝當下電平（均值）；右側刻度＝峰值保持，故回落時刻度會留在條前方
            this.levels[band.key] = stats.mean;
            this.peaks[band.key] = Math.max(this.toFill(stats.peak), this.peaks[band.key] - 0.012);
        }
        const all = spectrumStats(data, sampleRate, fftSize, 20, 16000);
        this.global = {
            peak: toDb(all.peak),
            rms: toDb(all.rms),
            crest: toDb(all.peak) - toDb(all.rms),
            clip: all.clip
        };
    }

    setState(state) {
        Object.assign(this.state, state || {});
    }

    toFill(amp) {
        const d = toDb(amp);
        return Math.max(0, Math.min(1, (d - FLOOR_DB) / SPAN_DB));
    }

    /** 由外部逐幀呼叫；內部節流，避免每次 rAF 都改 DOM。 */
    tick(now = performance.now()) {
        if (!this.root || now - this.lastRender < RENDER_MS) return;
        this.lastRender = now;
        this.render(now);
    }

    render(now) {
        // 靜音（無新頻譜）時電平自然回落，不留殘值
        if (now - this.lastSpectrumAt > SILENCE_MS) {
            for (const band of BANDS) {
                this.levels[band.key] *= 0.82;
                this.peaks[band.key] = Math.max(0, this.peaks[band.key] - 0.02);
            }
            this.global.crest *= 0.9;
        }

        for (const row of this.rows) {
            const amp = this.levels[row.key] || 0;
            const fill = this.toFill(amp);
            if (row.bar) row.bar.style.transform = `scaleX(${fill.toFixed(3)})`;
            if (row.peakEl) row.peakEl.style.left = `${(this.peaks[row.key] * 100).toFixed(1)}%`;
            if (row.value) row.value.textContent = amp > 0 ? toDb(amp).toFixed(1) : '---';
        }

        if (this.meta.crest) this.meta.crest.textContent = (this.global.crest > 0 ? this.global.crest : 0).toFixed(1);
        if (this.meta.peak) this.meta.peak.textContent = this.global.peak.toFixed(1);
        if (this.meta.rms) this.meta.rms.textContent = this.global.rms.toFixed(1);
        if (this.meta.clip) this.meta.clip.style.opacity = this.global.clip ? '1' : '0.25';
        if (this.meta.link) {
            this.meta.link.textContent = `LINK ${this.state.connected ? 'ESTABLISHED' : 'SEARCHING'} · Q${this.state.queue}`;
        }

        // B4：每 500ms 一行真實快照（新在上）；未收到頻譜前不寫，靜音時夾到量程下限
        if (this.lineEls.length && this.lastSpectrumAt > 0 && now - this.lastLog >= LOG_MS) {
            this.lastLog = now;
            const clampDb = (amp) => Math.max(FLOOR_DB, Math.min(0, toDb(amp || 0))).toFixed(1);
            const line = `L ${clampDb(this.levels.low)}  M ${clampDb(this.levels.mid)}  H ${clampDb(this.levels.high)}`;
            for (let i = this.lineEls.length - 1; i > 0; i--) {
                this.lineEls[i].textContent = this.lineEls[i - 1].textContent;
            }
            this.lineEls[0].textContent = line;
            this.lineEls.forEach((el, i) => { el.style.opacity = i === 0 ? '0.95' : (i === 1 ? '0.5' : '0.28'); });
        }
    }
}
