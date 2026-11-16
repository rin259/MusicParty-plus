import test from 'node:test';
import assert from 'node:assert/strict';

import {
  applyPlaybackAudioSession,
  attachAudioGraph,
  createAudioContext,
  elementVolumeIsWritable,
  forgetAudioGraph,
  needsSilentChannel,
  resumeAudioContext
} from './audioGraph.js';

// 这些用例锁住的是 Safari 的四个坑：
//   1. iOS 上 element.volume 只读 → 必须走 GainNode
//   2. 接图后 Safari 连 element.muted 都忽略 → 静音也要走 gain
//   3. 接进 suspended 的 AudioContext = 永久静音 → 未 running 绝不接图
//   4. iOS 17 之前 Web Audio 跟随静音开关 → 需要一个静音媒体元素开路

const makeElement = ({ writable = true, startVolume = 1 } = {}) => {
  const state = { volume: startVolume, muted: false };
  return {
    get volume() { return state.volume; },
    set volume(v) { if (writable) state.volume = v; },
    get muted() { return state.muted; },
    set muted(v) { state.muted = !!v; },
    _state: state
  };
};

const makeContext = ({ state = 'suspended', failResume = false } = {}) => {
  const calls = [];
  const context = {
    state,
    sampleRate: 48000,
    currentTime: 0,
    destination: { connect: () => {} },
    calls,
    async resume() {
      calls.push('resume');
      if (failResume) return;
      context.state = 'running';
    },
    createMediaElementSource(element) {
      calls.push('source');
      return { element, connect(target) { calls.push(['source->', target.kind]); } };
    },
    createGain() {
      calls.push('gain');
      const node = {
        kind: 'gain',
        gain: {
          value: 1,
          cancelScheduledValues() {},
          setTargetAtTime(value) { node.gain.value = value; }
        },
        connect(target) { calls.push(['gain->', target.kind]); }
      };
      return node;
    },
    createAnalyser() {
      calls.push('analyser');
      return {
        kind: 'analyser',
        fftSize: 2048,
        smoothingTimeConstant: 0,
        frequencyBinCount: 1024,
        connect() {},
        getByteFrequencyData(array) { array.fill(7); }
      };
    }
  };
  return context;
};

// Node 的 globalThis.navigator 是「只有 getter」的属性，直接赋值在严格模式下会抛，
// 所以统一用 defineProperty 打补丁，结束后还原。
const setGlobal = (name, value) => {
  Object.defineProperty(globalThis, name, {
    value,
    configurable: true,
    writable: true,
    enumerable: true
  });
};

const withGlobals = (globals, fn) => {
  const restore = [];
  for (const name of ['window', 'document', 'navigator']) {
    if (!(name in globals)) continue;
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
    restore.push([name, descriptor]);
    setGlobal(name, globals[name]);
  }
  try {
    return fn();
  } finally {
    for (const [name, descriptor] of restore.reverse()) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
};

test('detects that iOS volume is read-only', () => {
  const ios = makeElement({ writable: false });
  const desktop = makeElement({ writable: true });

  assert.equal(withGlobals({ document: { createElement: () => ios } }, elementVolumeIsWritable), false);
  assert.equal(withGlobals({ document: { createElement: () => desktop } }, elementVolumeIsWritable), true);
});

test('refuses to attach into a suspended context (that is what silences Safari)', () => {
  const element = makeElement();
  const context = makeContext({ state: 'suspended' });

  assert.equal(attachAudioGraph(element, context), null);
  assert.equal(context.calls.includes('source'), false, 'suspended 图绝不能接管元素输出');

  // running 之后才允许接
  context.state = 'running';
  const graph = attachAudioGraph(element, context);
  assert.ok(graph);
  forgetAudioGraph(element);
});

test('routes source -> gain -> analyser -> destination once per element', () => {
  const element = makeElement();
  const context = makeContext({ state: 'running' });

  const graph = attachAudioGraph(element, context);
  assert.ok(graph, 'graph should be created');
  assert.ok(context.calls.includes('source'));
  assert.ok(context.calls.includes('gain'));
  assert.ok(context.calls.includes('analyser'));
  assert.ok(context.calls.some((c) => Array.isArray(c) && c[0] === 'source->' && c[1] === 'gain'));
  assert.ok(context.calls.some((c) => Array.isArray(c) && c[0] === 'gain->' && c[1] === 'analyser'));

  // 同一个元素第二次必须复用（第二次 createMediaElementSource 会抛 InvalidStateError）
  const again = attachAudioGraph(element, context);
  assert.equal(again, graph);
  assert.equal(context.calls.filter((c) => c === 'source').length, 1);
  forgetAudioGraph(element);
});

test('mute (volume 0) drives the gain to 0 because Safari ignores element.muted', () => {
  const element = makeElement();
  const graph = attachAudioGraph(element, makeContext({ state: 'running' }));

  graph.applyVolume(0);

  assert.equal(graph.gain.gain.value, 0);
  forgetAudioGraph(element);
});

test('unmute restores gain and clears element.muted', () => {
  const element = makeElement();
  const graph = attachAudioGraph(element, makeContext({ state: 'running' }));

  graph.applyVolume(0);
  graph.applyVolume(0.42);

  assert.ok(Math.abs(graph.gain.gain.value - 0.42) < 1e-6);
  assert.equal(element.muted, false);
  forgetAudioGraph(element);
});

test('clamps garbage volume values instead of sending NaN to the gain node', () => {
  const element = makeElement();
  const graph = attachAudioGraph(element, makeContext({ state: 'running' }));

  graph.applyVolume(Number.NaN);
  assert.ok(Math.abs(graph.gain.gain.value - 0.5) < 1e-6);

  graph.applyVolume(3);
  assert.equal(graph.gain.gain.value, 1);

  graph.applyVolume(-2);
  assert.equal(graph.gain.gain.value, 0);
  forgetAudioGraph(element);
});

test('resumeAudioContext reports whether the context is actually running', async () => {
  const context = makeContext({ state: 'suspended' });
  assert.equal(await resumeAudioContext(context), true);
  assert.equal(context.state, 'running');

  const stubborn = makeContext({ state: 'suspended', failResume: true });
  assert.equal(await resumeAudioContext(stubborn), false);

  assert.equal(await resumeAudioContext(null), false);
  assert.equal(await resumeAudioContext({ state: 'running' }), true);
});

test('createAudioContext returns null instead of throwing without Web Audio', () => {
  assert.equal(createAudioContext(() => { throw new Error('no audio'); }), null);
  assert.equal(createAudioContext(() => null), null);
  assert.equal(createAudioContext(() => ({})), null);
  assert.equal(withGlobals({ window: {} }, () => createAudioContext()), null);
});

test('declares a playback audio session so iOS ignores the silent switch', () => {
  const session = { type: 'auto' };

  assert.equal(withGlobals({ navigator: { audioSession: session } }, applyPlaybackAudioSession), true);
  assert.equal(session.type, 'playback');

  // 会话类型可能被别的页面逻辑改回 auto，每次播放前都要重申
  session.type = 'auto';
  assert.equal(withGlobals({ navigator: { audioSession: session } }, applyPlaybackAudioSession), true);
  assert.equal(session.type, 'playback');
});

test('audio session helper is a no-op on browsers without navigator.audioSession', () => {
  assert.doesNotThrow(() => withGlobals({ navigator: {} }, applyPlaybackAudioSession));
  assert.equal(withGlobals({ navigator: {} }, applyPlaybackAudioSession), false);
});

test('needs a silent media channel on iOS older than 17 (no audioSession)', () => {
  const iPhone = { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X)' };

  assert.equal(withGlobals({ navigator: iPhone }, needsSilentChannel), true);
  // iOS 17+ 有 audioSession，直接用 playback 类型，不需要静音元素开路
  assert.equal(withGlobals({
    navigator: { ...iPhone, audioSession: { type: 'auto' } }
  }, needsSilentChannel), false);
  // 桌面浏览器不受静音开关影响
  assert.equal(withGlobals({
    navigator: { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' }
  }, needsSilentChannel), false);
});