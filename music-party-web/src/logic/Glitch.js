/**
 * Glitch 語彙（A + C + D）——共用閘門與文字解碼。
 *
 * 設計取捨（2026-10 研判）：
 *  - glitch 只在「有語義」時出現：換曲（解碼）、取源失敗/斷線（遙測抖動）、強鼓點（環場撕裂）。
 *    不做常駐全屏色差／逐幀隨機切片——那既廉價也違反 WCAG 2.3.1（≤3 次/秒）。
 *  - 所有觸發共用一個閘門，全域同時只允許一次；每類預設 ≥900ms 間隔。
 *  - prefers-reduced-motion 下一律靜默（錯誤態以靜態紅字呈現，不閃）。
 *  - 零依賴：純 DOM + CSS 類，成本近乎為零。
 */
export const prefersReducedMotion = () => typeof matchMedia === 'function'
    && matchMedia('(prefers-reduced-motion: reduce)').matches;

export class GlitchGate {
    constructor(minInterval = 900) {
        this.minInterval = minInterval;
        this.last = 0;
    }

    /** 通過則占用一次配額；reduced-motion 或過於頻繁時回 false。 */
    take(now = performance.now()) {
        if (prefersReducedMotion()) return false;
        if (now - this.last < this.minInterval) return false;
        this.last = now;
        return true;
    }
}

const GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#/\\_<>';

/**
 * 終端解碼：由亂碼逐字落定。用於換曲時的標題——新數據「正在解碼」，
 * 而不是單純換字。等寬同位替換以減少回流（外層已 truncate）。
 */
export class TextDecode {
    constructor() {
        this.el = null;
        this.raf = 0;
        this.startedAt = 0;
        this.target = '';
    }

    attach(el) {
        this.el = el || null;
    }

    cancel() {
        if (this.raf) cancelAnimationFrame(this.raf);
        this.raf = 0;
        if (this.el && this.target) this.el.textContent = this.target;
    }

    run(text, duration = 240) {
        if (!this.el) return;
        if (prefersReducedMotion()) {
            this.el.textContent = text;
            return;
        }
        this.cancel();
        this.target = text;
        this.startedAt = performance.now();

        const tick = (now) => {
            const p = Math.min(1, (now - this.startedAt) / duration);
            const reveal = Math.floor(p * text.length);
            let out = '';
            for (let i = 0; i < text.length; i++) {
                const ch = text[i];
                if (i < reveal || ch === ' ') out += ch;
                else out += GLYPHS[(Math.random() * GLYPHS.length) | 0];
            }
            this.el.textContent = out;
            if (p < 1) {
                this.raf = requestAnimationFrame(tick);
            } else {
                this.el.textContent = text;
                this.raf = 0;
            }
        };
        this.raf = requestAnimationFrame(tick);
    }
}
