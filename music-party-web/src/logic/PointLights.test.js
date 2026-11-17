import test from 'node:test';
import assert from 'node:assert/strict';
import {
    PointLights, POINT_LIGHT_BANDS, pointLightColor, pointLightBandEnergy,
    pointLightLevel, pointLightFalloff, pointLightOpacity
} from './PointLights.js';

test('16 positive-width bands tile 20–16000 Hz without gaps or a zero-width final band', () => {
    assert.equal(POINT_LIGHT_BANDS.length, 16);
    assert.equal(POINT_LIGHT_BANDS[0].from, 20);
    assert.equal(POINT_LIGHT_BANDS.at(-1).to, 16000);
    POINT_LIGHT_BANDS.forEach((band, i) => {
        assert.ok(band.to > band.from);
        if (i) assert.equal(band.from, POINT_LIGHT_BANDS[i - 1].to);
    });
});

test('weighted bin coverage leaves no high-frequency blind spots at 44.1/48 kHz', () => {
    for (const sampleRate of [44100, 48000]) {
        const data = new Uint8Array(1024);
        const binHz = sampleRate / 2048;
        for (let bin = Math.ceil(20 / binHz); bin * binHz < 16000; bin++) {
            data.fill(0);
            data[bin] = 220;
            const responding = POINT_LIGHT_BANDS.filter(b =>
                pointLightBandEnergy(data, sampleRate, 2048, b.from, b.to, 1) > 0);
            assert.ok(responding.length > 0, `${sampleRate}: bin ${bin} was lost`);
            assert.ok(responding.length <= 3, `${sampleRate}: excessive whole-bin duplication`);
        }
    }
});

test('zero spectrum is truly dark and a flat signal retains its amplitude across bands', () => {
    const data = new Uint8Array(1024);
    for (const b of POINT_LIGHT_BANDS) {
        assert.equal(pointLightBandEnergy(data, 48000, 2048, b.from, b.to, 1), 0);
    }
    data.fill(180);
    const values = POINT_LIGHT_BANDS.map(b => pointLightBandEnergy(data, 48000, 2048, b.from, b.to, 1));
    assert.ok(Math.max(...values) - Math.min(...values) < 1e-8);
});

test('stronger input keeps brightness headroom instead of clipping before compression', () => {
    for (const b of POINT_LIGHT_BANDS) {
        const levels = [180, 200, 220, 255].map(value => pointLightLevel(
            pointLightBandEnergy(new Uint8Array(1024).fill(value), 48000, 2048, b.from, b.to, 150)));
        levels.forEach((level, i) => {
            assert.ok(level < 1);
            if (i) assert.ok(level > levels[i - 1]);
        });
    }
});

test('orange and theme endpoints preserve all RGB channels', () => {
    assert.deepEqual(pointLightColor(0, [212, 218, 228]), [249, 115, 22]);
    assert.deepEqual(pointLightColor(1, [212, 218, 228]), [212, 218, 228]);
    assert.deepEqual(pointLightColor(1, [255, 255, 255]), [255, 255, 255]);
});

test('wide soft falloff has neither a pin-core nor a visible hard outer rim', () => {
    assert.equal(pointLightFalloff(0), 1);
    assert.equal(pointLightFalloff(1), 0);
    assert.ok(pointLightFalloff(0.45) > 0.43);
    assert.ok(pointLightFalloff(0.45) < 0.46);
    const eps = 0.0001;
    assert.ok((1 - pointLightFalloff(eps)) / eps < 0.001);
    assert.ok(pointLightFalloff(1 - eps) / eps < 0.001);
    for (let i = 1; i <= 100; i++) {
        assert.ok(pointLightFalloff(i / 100) <= pointLightFalloff((i - 1) / 100));
    }
});

test('exposure stays monotonic and crowd relief changes opacity, not radius', () => {
    let prev = 0;
    for (let i = 0; i <= 100; i++) {
        const alpha = pointLightOpacity(i / 100, 0, 0.08, 0.5);
        assert.ok(alpha >= prev && alpha < 0.62);
        prev = alpha;
    }
    assert.equal(pointLightOpacity(0, 0, 0.08, 1), 0);
    assert.ok(pointLightOpacity(0.8, 0, 0.08, 1) < pointLightOpacity(0.8, 0, 0.08, 0));
});

test('incumbent broad radius and expansion remain, independent of brightness compression', () => {
    const lights = new PointLights();
    assert.equal(lights.lights.length, 16);
    lights.lights.forEach(light => {
        assert.ok(light.r >= 0.055 && light.r <= 0.12);
        assert.ok(light.alpha >= 0.075 && light.alpha <= 0.085);
    });
    // Radius uses the old energy response, not the revised exposure curve.
    lights.setSpectrum({ data: new Uint8Array(1024).fill(255), sampleRate: 48000, fftSize: 2048 });
    assert.ok(lights.radiusLevels.every(level => level > 0));
    assert.notDeepEqual(Array.from(lights.radiusLevels), Array.from(lights.levels));
});
