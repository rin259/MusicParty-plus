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

    // Sustained sub-bass test: ensure AC drive keeps cone oscillating rather than settling to DC 0
    let minXw = Infinity;
    let maxXw = -Infinity;
    for (let frame = 0; frame < 60; frame++) {
        vis.setSpectrum({
            data: new Uint8Array(1024).fill(250), // 100% full loud signal
            sampleRate: 48000,
            fftSize: 2048
        });
        vis.draw(1 / 60);
        minXw = Math.min(minXw, vis.radState.xw);
        maxXw = Math.max(maxXw, vis.radState.xw);
    }
    assert.ok(minXw < 0 && maxXw > 0, `Cone must oscillate across 0: min=${minXw}, max=${maxXw}`);
    assert.ok(maxXw - minXw > 0.0001, 'Cone must retain active dynamic range under sustained loud bass');

    vis.unmount();
});

test('SpectralWordmark responds to spectrum, remeasures cleanly and restores amber glow', async () => {
    const { SpectralWordmark } = await import('./SpectralWordmark.js');
    const wordmark = new SpectralWordmark();

    const letters = Array.from({ length: 11 }, () => ({
        style: { opacity: '', color: '' }
    }));
    const mockRoot = {
        querySelectorAll: () => letters
    };

    wordmark.attach(mockRoot);
    assert.equal(wordmark.letters.length, 11);

    // Feed spectrum across a few frames to simulate real audio attack
    for (let frame = 0; frame < 5; frame++) {
        wordmark.setSpectrum({
            data: new Uint8Array(1024).fill(220),
            sampleRate: 48000,
            fftSize: 2048
        });
        await new Promise(r => setTimeout(r, 16));
    }

    // Remeasure must not destroy letters or kill active state
    wordmark.remeasure();
    assert.equal(wordmark.letters.length, 11);

    // Draw one frame
    wordmark.draw(1 / 30);

    // Verify letters lit up with warm amber glow
    for (let i = 0; i < 11; i++) {
        assert.ok(Number(letters[i].style.opacity) > 0.25, `Letter ${i} must respond with visible opacity`);
        assert.ok(letters[i].style.color.startsWith('rgb('), `Letter ${i} must have dynamic color`);
    }

    wordmark.unmount();
    assert.equal(wordmark.letters.length, 0);
});
