import test from 'node:test';
import assert from 'node:assert/strict';
import {
    WOOFER, DRIVE_CURRENT,
    deriveThreeWay, createRadiatorState, stepRadiator, boxPressure, staticExcursion
} from './passiveRadiator.js';

const H = 1 / 2400;
const P = deriveThreeWay();
const F_FULL = WOOFER.Bl * DRIVE_CURRENT; // 满幅驱动力（N）

/** 稳态正弦力驱动之行程峰值（后段取峰，已滤暂态） */
const steady = (freq) => {
    let s = createRadiatorState();
    const w = 2 * Math.PI * freq;
    const steps = Math.round(4 / H);
    let cone = 0;
    let pr = 0;
    let pressure = 0;
    for (let i = 0; i < steps; i++) {
        s = stepRadiator(s, F_FULL * Math.sin(w * i * H), H, P);
        if (i * H > 3.3) {
            cone = Math.max(cone, Math.abs(s.xw));
            pr = Math.max(pr, Math.abs(s.xr));
            pressure = Math.max(pressure, Math.abs(boxPressure(s, P)));
        }
    }
    return { cone, pr, pressure };
};

test('enclosure tuning lands on the documented 36 Hz reference', () => {
    // 参数取自 00aud 仿真器范例（Dayton DCS165-4 + E150HE-PR 于 10 L，Fb = 36 Hz），
    // 本模型反算得 Fb 应与之吻合 —— 参数可溯源之校验。
    assert.ok(Math.abs(P.Fb - 36) < 1.5, `Fb = ${P.Fb.toFixed(2)} Hz`);
    // 振膜在箱内之共振即调谐频率
    const radiatorInBox = Math.sqrt((P.radiator.K + P.Kar) / P.radiator.M) / (2 * Math.PI);
    assert.ok(Math.abs(radiatorInBox - P.Fb) < 0.01);
});

test('the box air spring raises the woofer resonance above free-air fs', () => {
    assert.ok(P.wooferInBox > WOOFER.fs, `${P.wooferInBox.toFixed(1)} > ${WOOFER.fs}`);
});

test('at tuning the passive radiator out-travels the cone (it does the work)', () => {
    const r = steady(P.Fb);
    assert.ok(r.pr / r.cone > 2, `PR/cone = ${(r.pr / r.cone).toFixed(2)}`);
});

test('cone excursion is a local minimum at tuning (the radiator controls it)', () => {
    const atFb = steady(P.Fb).cone;
    const below = steady(P.Fb * 0.8).cone;
    const above = steady(P.Fb * 1.25).cone;
    assert.ok(atFb < below, `cone@fb ${atFb.toExponential(2)} < cone@0.8fb ${below.toExponential(2)}`);
    assert.ok(atFb < above, `cone@fb ${atFb.toExponential(2)} < cone@1.25fb ${above.toExponential(2)}`);
});

test('the radiator lags the cone on a transient', () => {
    let s = stepRadiator(createRadiatorState(), F_FULL, H, P);
    let conePeak = 0;
    let coneAt = 0;
    let prPeak = 0;
    let prAt = 0;
    for (let i = 1; i < 3000; i++) {
        const t = i * H;
        s = stepRadiator(s, 0, H, P);
        if (Math.abs(s.xw) > conePeak) { conePeak = Math.abs(s.xw); coneAt = t; }
        if (Math.abs(s.xr) > prPeak) { prPeak = Math.abs(s.xr); prAt = t; }
    }
    assert.ok(prAt > coneAt,
        `PR peak ${(prAt * 1000).toFixed(1)}ms must follow cone peak ${(coneAt * 1000).toFixed(1)}ms`);
});

test('cone and radiator settle back to rest', () => {
    let s = stepRadiator(createRadiatorState(), F_FULL, H, P);
    for (let i = 0; i < 30000; i++) s = stepRadiator(s, 0, H, P);
    assert.ok(Math.abs(s.xw) < 1e-9, `xw = ${s.xw}`);
    assert.ok(Math.abs(s.xr) < 1e-9, `xr = ${s.xr}`);
});

test('stays finite and bounded under sustained loud drive', () => {
    let s = createRadiatorState();
    for (let i = 0; i < 200000; i++) {
        s = stepRadiator(s, F_FULL * Math.sin(i * H * 240) * 0.5, H, P);
        assert.ok(Number.isFinite(s.xw) && Math.abs(s.xw) < 0.1);
    }
});

test('three-way relative excursions follow Bl and stiffness (tweeter ≪ mid < woofer)', () => {
    const wooferMm = P.xRefMm.woofer;
    const midMm = staticExcursion(P.mid, P, 1) * 1000;
    const tweeterMm = P.xRefMm.tweeter;
    assert.ok(wooferMm > midMm, `${wooferMm.toFixed(2)} > ${midMm.toFixed(2)}`);
    assert.ok(midMm > tweeterMm, `${midMm.toFixed(2)} > ${tweeterMm.toFixed(2)}`);
    // 满幅静态行程落在真实器材量级
    assert.ok(wooferMm > 2 && wooferMm < 6, `woofer ${wooferMm.toFixed(2)} mm`);
    assert.ok(tweeterMm < 0.5, `tweeter ${tweeterMm.toFixed(3)} mm`);
});
