import test from 'node:test';
import assert from 'node:assert/strict';
import {
    besselJ,
    AudioVisualizer,
    sampleSpectrumFrequency,
    logFrequencyPosition,
    spectrumStats,
    spectrumBandEnergy
} from './AudioVisualizer.js';

test('Bessel J_m series accurately captures circular membrane eigenmodes and roots', () => {
    // J_0(0) = 1, J_1(0) = 0, J_2(0) = 0
    assert.equal(besselJ(0, 0), 1);
    assert.equal(besselJ(1, 0), 0);
    assert.equal(besselJ(2, 0), 0);

    // Known first zeros of Bessel functions:
    // J_0: 2.404825
    // J_1: 3.831706
    // J_2: 5.135622
    assert.ok(Math.abs(besselJ(0, 2.404825)) < 1e-4, 'J0 root at 2.4048');
    assert.ok(Math.abs(besselJ(1, 3.831706)) < 1e-4, 'J1 root at 3.8317');
    assert.ok(Math.abs(besselJ(2, 5.135622)) < 1e-4, 'J2 root at 5.1356');
});

test('spectrum helper functions preserve linear dynamic range and log mapping', () => {
    const raw = new Uint8Array(256).fill(128);
    const stats = spectrumStats(raw, 48000, 2048, 20, 20000);
    assert.ok(stats.mean > 0);
    assert.equal(stats.clip, false);

    const energy = spectrumBandEnergy(raw, 48000, 2048, 20, 300, 60);
    assert.ok(energy > 0 && energy <= 1);

    const logLow = logFrequencyPosition(0);
    const logHigh = logFrequencyPosition(1);
    assert.equal(Math.round(logLow), 45);
    assert.equal(Math.round(logHigh), 16000);
});

test('AudioVisualizer initializes, calculates acoustic graticule and surrounds cleanly', () => {
    const vis = new AudioVisualizer();
    assert.equal(vis.rings.length, 4);

    // Mock canvas
    const calls = [];
    const mockCtx = {
        setTransform: () => {},
        clearRect: () => {},
        save: () => {},
        restore: () => {},
        beginPath: () => {},
        closePath: () => {},
        moveTo: (x, y) => calls.push(['moveTo', x, y]),
        lineTo: (x, y) => calls.push(['lineTo', x, y]),
        arc: () => {},
        stroke: () => {},
        fill: () => {},
        setLineDash: () => {},
        createRadialGradient: () => ({ addColorStop: () => {} })
    };
    const mockCanvas = {
        clientWidth: 800,
        clientHeight: 800,
        width: 1200,
        height: 1200,
        getContext: () => mockCtx
    };

    vis.mount(mockCanvas);
    vis.layoutRings(300);

    assert.ok(vis.innerRadius > 0);
    assert.ok(vis.maxOuter > vis.innerRadius);
    assert.ok(vis.radialSpan > 0);

    // Simulate spectrum input
    vis.setPlaying(true);
    vis.setSpectrum({
        data: new Uint8Array(1024).fill(140),
        sampleRate: 48000,
        fftSize: 2048
    });

    vis.impulse();
    assert.ok(vis.speedMultiplier > 1);

    // Draw one step
    vis.draw(1 / 60);

    const status = vis.getStatus();
    assert.equal(status.active, true);
    assert.equal(status.rings, 4);

    vis.unmount();
});
