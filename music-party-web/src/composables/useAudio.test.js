import test from 'node:test';
import assert from 'node:assert/strict';
import { positionFromPointer, shouldRestartRepeat } from '../logic/audioSync.js';
import { logFrequencyPosition, sampleSpectrumFrequency } from '../logic/AudioVisualizer.js';
import { createRenderer, reactive, ref, nextTick } from 'vue';
import { useAudio } from './useAudio.js';

// Mount the actual composable with Vue's real watchers/lifecycle, without a DOM.
async function withAudio(fn) {
    const saved = ['window', 'document'].map(k => [k, Object.getOwnPropertyDescriptor(globalThis, k)]);
    for (const key of ['window', 'document']) Object.defineProperty(globalThis, key, {
        configurable: true, value: { addEventListener() {}, removeEventListener() {}, visibilityState: 'visible' }
    });
    const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} });
    const player = reactive({ nowPlaying: { music: { id: 'same', url: '/a.mp3' } }, isPaused: false,
        audioBlocked: false, getCurrentProgress: () => 0 });
    const audio = { paused: false, readyState: 1, duration: 120,
        pause() { this.paused = true; }, load() {}, play() { return Promise.resolve(); } };
    let api;
    let prepares = 0;
    let outputBlocked = false;
    const app = renderer.createApp({ setup() {
        api = useAudio(ref(audio), player, { beforePlay: () => prepares++, isOutputBlocked: () => outputBlocked });
        return () => null;
    } });
    app.mount({});
    try { await fn({ api, audio, player, app, prepared: () => prepares, blockOutput: () => { outputBlocked = true; } }); }
    finally {
        app.unmount();
        for (const [key, descriptor] of saved) {
            if (descriptor) Object.defineProperty(globalThis, key, descriptor);
            else delete globalThis[key];
        }
    }
}

test('prepares output synchronously before play without waiting for another click', async () => {
    await withAudio(async ({ api, audio, prepared }) => {
        audio.play = () => { assert.equal(prepared(), 1); return Promise.resolve(); };
        await api.resumeAudio();
    });
});

test('local sound-unlock cannot resume a room paused by its owner', async () => {
    await withAudio(async ({ api, audio, player }) => {
        player.isPaused = true;
        await nextTick();
        let calls = 0;
        audio.play = () => { calls++; return Promise.resolve(); };
        await api.resumeAudio();
        assert.equal(calls, 0);
    });
});

test('old play rejection after changing source cannot block the new track', async () => {
    await withAudio(async ({ api, audio, player }) => {
        let reject;
        audio.play = () => new Promise((_, r) => { reject = r; });
        const pending = api.resumeAudio();
        player.nowPlaying.music.url = '/b.mp3'; // same ID, a different source
        await nextTick();
        reject(Object.assign(new Error('old autoplay failure'), { name: 'NotAllowedError' }));
        await pending;
        assert.equal(player.audioBlocked, false);
    });
});

test('resolved media play cannot hide a suspended output-context prompt', async () => {
    await withAudio(async ({ api, player, blockOutput }) => {
        blockOutput();
        await api.resumeAudio();
        assert.equal(player.audioBlocked, true);
    });
});

test('leaving a room pauses its media even if the next room is also playing', async () => {
    await withAudio(async ({ audio, player }) => {
        player.nowPlaying = null;
        await nextTick();
        assert.equal(audio.paused, true);
    });
});

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
