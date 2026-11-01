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
const VALUE_MS = 220;   // 數字/日誌/狀態列：~4.5Hz
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
        this.lastValueAt = 0;
        this.lastLog = 0;
        this.lastSpectrumAt = 0;
        this.rendered = { low: '---', mid: '---', high: '---' };
        this.log = [];            // [{ at, text }]，最新在前
        this.clipUntil = 0;
        this.sr = null;
        this.lastSrAt = 0;
    }

    attach(root, sr = null) {
        this.root = root || null;
        this.sr = sr || null;
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

    /** 行与日志共用的唯一格式化：夹到量程内，故同一时刻两处必然同值。 */
    fmtDb(amp) {
        const d = toDb(amp || 0);
        return Math.max(FLOOR_DB, Math.min(0, d)).toFixed(1);
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

        // 條與峰值刻度維持 12.5Hz：這是「跟手」的部分
        for (const row of this.rows) {
            const amp = this.levels[row.key] || 0;
            if (row.bar) row.bar.style.transform = `scaleX(${this.toFill(amp).toFixed(3)})`;
            if (row.peakEl) row.peakEl.style.left = `${(this.peaks[row.key] * 100).toFixed(1)}%`;
        }

        // 數值降到 ~4.5Hz：12.5Hz 跳動的數字讀不出來，且會和日誌行互相矛盾
        if (now - this.lastValueAt >= VALUE_MS) {
            this.lastValueAt = now;

            for (const row of this.rows) {
                const amp = this.levels[row.key] || 0;
                const text = amp > 0 ? this.fmtDb(amp) : '---';
                this.rendered[row.key] = text;
                if (row.value) row.value.textContent = text;
            }

            // 日誌每 500ms 取一次「當下已顯示的數值」，並帶上相對時間；
            // 這樣它讀起來是歷史，而不是同一面板上的第二組讀數。
            if (this.lineEls.length && this.lastSpectrumAt > 0 && now - this.lastLog >= LOG_MS) {
                this.lastLog = now;
                this.log.unshift({
                    at: now,
                    text: `L ${this.rendered.low}  M ${this.rendered.mid}  H ${this.rendered.high}`
                });
                this.log.length = Math.min(this.log.length, this.lineEls.length);
            }
            this.lineEls.forEach((el, i) => {
                const entry = this.log[i];
                if (!entry) { el.textContent = ''; return; }
                el.textContent = `-${((now - entry.at) / 1000).toFixed(1)}s  ${entry.text}`;
                el.style.color = i === 0 ? 'rgb(var(--medical-600))' : 'rgb(var(--medical-500))';
            });

            if (this.meta.crest) this.meta.crest.textContent = (this.global.crest > 0 ? this.global.crest : 0).toFixed(1);
            if (this.meta.peak) this.meta.peak.textContent = this.global.peak.toFixed(1);
            if (this.meta.rms) this.meta.rms.textContent = this.global.rms.toFixed(1);
            if (this.meta.link) {
                this.meta.link.textContent = `LINK ${this.state.connected ? 'LIVE' : 'SEARCHING'} · QUEUE ${this.state.queue}`;
            }

            // 屏讀摘要：2 秒一次，避免高頻 aria-live 噪音
            if (this.sr && now - this.lastSrAt >= 2000) {
                this.lastSrAt = now;
                const live = this.lastSpectrumAt > 0 && now - this.lastSpectrumAt < SILENCE_MS;
                this.sr.textContent = live
                    ? `Audio live. Low ${this.rendered.low}, mid ${this.rendered.mid}, high ${this.rendered.high} dB. Queue ${this.state.queue}.`
                    : `Audio idle. Queue ${this.state.queue}.`;
            }
        }

        // CLIP：只在真的削波時出現（並保持 1.5s），常態不佔用唯一的語義色
        if (this.global.clip) this.clipUntil = now + 1500;
        if (this.meta.clip) {
            this.meta.clip.style.visibility = now < this.clipUntil ? 'visible' : 'hidden';
        }
    }
}
