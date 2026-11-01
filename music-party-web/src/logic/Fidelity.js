/**
 * 保真度标量（0.2 .. 1）——把「这套系统现在有多干净」变成一个数。
 *
 * 依据（2026-11 故障美学研判）：
 *  - Menkman / Moradi 区分「真 glitch」与「glitch-alike」：真实故障产生的失真
 *    与设计仿造的失真，观感上不是一回事；前者自带因果，后者是戏服。
 *  - 所以这里不随机造故障：只把**真实状态**（连接、缓冲、近期失败、真实掉帧）
 *    折算成一个数，让已有的 glitch 通道按它调幅。
 *  - 结果：健康时 glitch 几乎看不见；出问题时同一个 glitch 才明显。
 *    幅度因此携带信息，而不是装饰。
 */

const FAILURE_WINDOW_MS = 15000;
const SLIP_WINDOW_MS = 10000;

export class Fidelity {
    constructor() {
        this.connected = true;
        this.buffering = false;
        this.failures = [];
        this.slips = [];
        this.value = 1;
    }

    setState({ connected, buffering } = {}) {
        if (typeof connected === 'boolean') this.connected = connected;
        if (typeof buffering === 'boolean') this.buffering = buffering;
        return this;
    }

    noteFailure(now = performance.now()) {
        this.failures.push(now);
        return this;
    }

    /** 真实掉帧（rAF 间隔超阈值）——唯一「诚实」的故障源，因为它真的发生了 */
    noteFrameSlip(now = performance.now()) {
        this.slips.push(now);
        return this;
    }

    update(now = performance.now()) {
        this.failures = this.failures.filter((t) => now - t < FAILURE_WINDOW_MS);
        this.slips = this.slips.filter((t) => now - t < SLIP_WINDOW_MS);

        let v = 1;
        if (!this.connected) v -= 0.45;
        if (this.buffering) v -= 0.2;
        v -= Math.min(0.3, this.failures.length * 0.12);
        v -= Math.min(0.25, this.slips.length * 0.06);

        this.value = Math.max(0.2, Math.min(1, v));
        return this.value;
    }

    /** glitch 幅度倍率：健康时 1，最差时 1.8——同一段动画只靠幅度表达严重程度 */
    amplify() {
        return 1 + (1 - this.update()) * 1.0;
    }
}

/** 全局单例：可视化层与 UI 层读同一个标量，避免各自计一套健康度 */
export const fidelity = new Fidelity();
