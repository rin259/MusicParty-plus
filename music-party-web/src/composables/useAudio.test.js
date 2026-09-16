import test from 'node:test';
import assert from 'node:assert/strict';
import { positionFromPointer, shouldRestartRepeat } from '../logic/audioSync.js';
import { advanceBassMass, logFrequencyPosition, sampleSpectrumFrequency } from '../logic/AudioVisualizer.js';

test('restarts an ended track when repeat-one rewinds the server position', () => {
    assert.equal(shouldRestartRepeat({
        playMode: 'REPEAT_ONE', targetTime: 0, ended: true, duration: 180, currentTime: 180
    }), true);
});

test('restarts near the media end even if the ended event has not fired', () => {
    assert.equal(shouldRestartRepeat({
        playMode: 'REPEAT_ONE', targetTime: 900, ended: false, duration: 180, currentTime: 179.5
    }), true);
});

test('does not treat ordinary playback drift as a repeat restart', () => {
    assert.equal(shouldRestartRepeat({
        playMode: 'REPEAT_ONE', targetTime: 60_000, ended: false, duration: 180, currentTime: 58
    }), false);
});

test('does not restart an ended track outside repeat-one mode', () => {
    assert.equal(shouldRestartRepeat({
        playMode: 'SEQUENTIAL', targetTime: 0, ended: true, duration: 180, currentTime: 180
    }), false);
});

test('maps and clamps pointer coordinates to a media position', () => {
    assert.equal(positionFromPointer(150, 100, 200, 120_000), 30_000);
    assert.equal(positionFromPointer(20, 100, 200, 120_000), 0);
    assert.equal(positionFromPointer(400, 100, 200, 120_000), 120_000);
});

test('interpolates between adjacent FFT bins when sampling a frequency', () => {
    const spectrum = Uint8Array.from({ length: 16 }, (_, index) => index * 16);
    const sampleRate = 48000;
    const fftSize = 1024;
    const binWidth = sampleRate / fftSize;
    const firstEdge = binWidth;
    const midBin = 5 * binWidth + binWidth / 2;
    assert.equal(spectrum[0], 0);
    assert.equal(spectrum[15], 240);
    const atMid = sampleSpectrumFrequency(spectrum, sampleRate, fftSize, midBin);
    const expected = spectrum[5] + (spectrum[6] - spectrum[5]) * 0.5;
    assert.ok(Math.abs(atMid - expected) < 0.001);
    assert.equal(spectrum[0], sampleSpectrumFrequency(spectrum, sampleRate, fftSize, 0));
    assert.equal(spectrum[15], sampleSpectrumFrequency(spectrum, sampleRate, fftSize, 15 * binWidth));
});

test('maps a log-frequency range onto a [0,1] position', () => {
    assert.equal(Math.round(logFrequencyPosition(0)), 45);
    assert.equal(Math.round(logFrequencyPosition(1)), 16000);
    const halfway = logFrequencyPosition(0.5);
    assert.ok(halfway > 45 && halfway < 16000);
});

test('models a damped bass mass that eventually settles on its target', () => {
    let state = { displacement: 0, velocity: 0 };
    for (let step = 0; step < 200; step++) {
        state = advanceBassMass(state, 0.5);
    }
    assert.ok(Math.abs(state.displacement - 0.5) < 0.001);
});
