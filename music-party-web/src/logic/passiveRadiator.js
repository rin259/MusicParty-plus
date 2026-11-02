/**
 * 三分频箱体物理：低音（被动振膜双自由度）＋ 中音 ＋ 高音。
 *
 * 参数皆为真实器材级 SI 值（kg, m, N/m, Hz, A），故每一数字可复算、可验证；
 * 频率响应之相对量级不由人调，而由物理决定（见 xRefMm）。
 *
 * 物理出处（2026-10 查考）：
 *  - jahonen《Electroacoustic modelling of the subwoofer enclosures》§4.7（WinISD 作者）：
 *    箱体＋被動振膜之機械阻抗等效電路；Kb = ρc²·Sd·Sr/Vb 之氣彈簧耦合法。
 *  - DrStef/Electroacoustics（notebook，對標 WinISD）：
 *    Kd = Mms·(2πfs)²、Rd = 2πfs·Mms/Qms、氣彈簧與懸掛並聯之箱內共振。
 *  - Jiang et al., Arch. Acoust. 42(4) 2017（doi:10.1515/aoa-2017-0078）：雙自由度振動理論。
 *  - 00aud 仿真器文件之範例（Dayton DCS165-4 ＋ E150HE-PR 於 10 L，Fb = 36 Hz）
 *    用作參數校驗：本模型由下方參數反得 Fb ≈ 36 Hz（見測試）。
 *
 * 低音（双自由度；两振膜皆外向为正）：
 *   Mw·Ẍw + Rw·Ẋw + (Kw + Kad)·Xw + Kab·Xr = Fw(t)
 *   Mr·Ẍr + Rr·Ẋr + (Kr + Kar)·Xr + Kab·Xw = 0
 *   Kad = ρc²Sd²/Vb, Kar = ρc²Sr²/Vb, Kab = ρc²Sd·Sr/Vb, 箱內氣壓 p = -ρc²(Sd·Xw + Sr·Xr)/Vb
 * 中音／高音（各自密閉箱，懸掛含箱體剛度）：準靜態懸掛響應 X = Bl·I·amp / K。
 *   說明：中高音自身共振（180Hz/900Hz）遠在視覺取樣之上，相位不可辨；
 *   對肉眼而言所見即其包絡。此式即「遠低於共振」之物理極限，非杜撰。
 */

const RHO_C2 = 1.2 * 343 ** 2; // ρc² ≈ 141,179 (N·m/… 声学)
const TAU = Math.PI * 2;

// ── 真實器材參數（代表性三分頻）────────────────────────────────────────
export const BOX = { Vb: 0.010 };                                              // 10 L
export const WOOFER = { Mms: 0.013, fs: 43, Qms: 4.0, Sd: 137e-4, Bl: 5.5 };   // 6.5" 低音
export const MID = { Mms: 0.004, fs: 180, Qms: 2.5, Sd: 50e-4, Bl: 3.5 };      // 3" 中音
export const TWEETER = { Mms: 0.0004, fs: 900, Qms: 1.2, Sd: 8e-4, Bl: 1.5 };  // 1" 高音
export const RADIATOR = { Mms: 0.090, fs: 25, Qms: 10, Sd: 130e-4 };           // 5.25" 被動振膜

// 滿幅驅動電流（A）：Bl·I 即最大力——低音 5.5×2.5 ≈ 13.75 N（數十瓦級，現實）
export const DRIVE_CURRENT = 2.5;

const driverCoeffs = (d) => ({
    M: d.Mms,
    K: d.Mms * (TAU * d.fs) ** 2,
    R: TAU * d.fs * d.Mms / d.Qms,
    Bl: d.Bl,
    Sd: d.Sd,
    fs: d.fs
});

export const deriveThreeWay = () => {
    const woofer = driverCoeffs(WOOFER);
    const mid = driverCoeffs(MID);
    const tweeter = driverCoeffs(TWEETER);
    const radiator = driverCoeffs(RADIATOR);

    // 氣彈簧（機械域，N/m）
    const Kad = RHO_C2 * woofer.Sd ** 2 / BOX.Vb;
    const Kar = RHO_C2 * radiator.Sd ** 2 / BOX.Vb;
    const Kab = RHO_C2 * woofer.Sd * radiator.Sd / BOX.Vb;

    const I = DRIVE_CURRENT;
    return {
        woofer, mid, tweeter, radiator,
        Kad, Kar, Kab, current: I,
        // 箱體調諧：振膜在箱內之共振（由 Kb 反得；校驗 ≈ 36 Hz）
        Fb: Math.sqrt((radiator.K + Kar) / radiator.M) / TAU,
        // 低音盆之箱內共振（氣彈簧抬高 fs）
        wooferInBox: Math.sqrt((woofer.K + Kad) / woofer.M) / TAU,
        // 滿幅靜態位移（mm）——三分頻之相對量級由此物理決定，非人為增益
        xRefMm: {
            woofer: woofer.Bl * I / (woofer.K + Kad) * 1000,
            mid: mid.Bl * I / mid.K * 1000,
            tweeter: tweeter.Bl * I / tweeter.K * 1000
        },
        // 滿幅時之箱內氣壓（Pa）——供「帶寬隨氣壓」之響應參照
        pRefPa: RHO_C2 * woofer.Sd * (woofer.Bl * I / (woofer.K + Kad)) / BOX.Vb
    };
};

export const createRadiatorState = () => ({ xw: 0, vw: 0, xr: 0, vr: 0 });

const deriv = (s, p, Fw) => {
    const aw = (Fw - p.woofer.R * s.vw - (p.woofer.K + p.Kad) * s.xw - p.Kab * s.xr) / p.woofer.M;
    const ar = (-p.radiator.R * s.vr - (p.radiator.K + p.Kar) * s.xr - p.Kab * s.xw) / p.radiator.M;
    return { dxw: s.vw, dvw: aw, dxr: s.vr, dvr: ar };
};

/** 低音雙自由度單步 RK4（h 秒；Fw 牛頓）。純函數，便於測試。 */
export const stepRadiator = (s, Fw, h, p) => {
    const k1 = deriv(s, p, Fw);
    const s2 = { xw: s.xw + k1.dxw * h / 2, vw: s.vw + k1.dvw * h / 2, xr: s.xr + k1.dxr * h / 2, vr: s.vr + k1.dvr * h / 2 };
    const k2 = deriv(s2, p, Fw);
    const s3 = { xw: s.xw + k2.dxw * h / 2, vw: s.vw + k2.dvw * h / 2, xr: s.xr + k2.dxr * h / 2, vr: s.vr + k2.dvr * h / 2 };
    const k3 = deriv(s3, p, Fw);
    const s4 = { xw: s.xw + k3.dxw * h, vw: s.vw + k3.dvw * h, xr: s.xr + k3.dxr * h, vr: s.vr + k3.dvr * h };
    const k4 = deriv(s4, p, Fw);
    return {
        xw: s.xw + h / 6 * (k1.dxw + 2 * k2.dxw + 2 * k3.dxw + k4.dxw),
        vw: s.vw + h / 6 * (k1.dvw + 2 * k2.dvw + 2 * k3.dvw + k4.dvw),
        xr: s.xr + h / 6 * (k1.dxr + 2 * k2.dxr + 2 * k3.dxr + k4.dxr),
        vr: s.vr + h / 6 * (k1.dvr + 2 * k2.dvr + 2 * k3.dvr + k4.dvr)
    };
};

/** 箱內氣壓（Pa，正為壓縮）∝ -(Sd·Xw + Sr·Xr)。調諧處兩者相消故最小。 */
export const boxPressure = (s, p) => -RHO_C2 * (p.woofer.Sd * s.xw + p.radiator.Sd * s.xr) / BOX.Vb;

/** 低音盆加速度（m/s²）——遠場輻射聲壓 ∝ 加速度。 */
export const coneAccel = (s, Fw, p) =>
    (Fw - p.woofer.R * s.vw - (p.woofer.K + p.Kad) * s.xw - p.Kab * s.xr) / p.woofer.M;

/** 中／高音之準靜態行程（m）：X = Bl·I·amp / K（amp 為該頻段包絡 0..1）。 */
export const staticExcursion = (driver, p, amp) => driver.Bl * p.current * amp / driver.K;
