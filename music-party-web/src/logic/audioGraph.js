/**
 * Apple WebKit（macOS Safari / iOS Safari）音频兼容层。
 *
 * 平台差异（都直接造成「时不时没声音 / 音量无效 / 静音无效」）：
 *
 * 1. iOS 上 HTMLMediaElement.volume 是**只读**的：赋值不报错，读回来永远是 1。
 * 2. createMediaElementSource() 把输出交给音频图。部分 Safari 版本会忽略
 *    原生音量，而 Chromium 会继续应用原生音量。统一将原生音量置 1、
 *    不设置原生静音，只由 GainNode 控制，避免 Chromium 双重衰减。
 * 3. 音频图如果接进一个 suspended 的 AudioContext，就是彻底静音：
 *    元素还在「播放」，但一个字都听不到。iOS 在手势之外创建的 context 就是 suspended。
 *    → 只有确认 context 处于 running 之后才允许接图，否则保持原生出声。
 * 4. iOS 的 Web Audio 默认走 ambient 会话，跟随侧边静音开关（媒体元素不跟随）。
 *    Safari 17+ 用 navigator.audioSession.type = 'playback' 切到媒体通道；
 *    更老的版本只能用「同时播放一个静音 audio 元素」把通道拉到媒体侧。
 */

const clamp01 = (value) => {
  const num = Number(value);
  if (!Number.isFinite(num)) return 0.5;
  return Math.min(1, Math.max(0, num));
};

/**
 * 声明这是媒体播放：忽略 iOS 静音开关、允许后台播放（Safari 17+）。
 * 每次都设一遍——切换过录音/会话类型的页面需要重新声明。
 */
export const applyPlaybackAudioSession = () => {
  const session = typeof navigator === 'undefined' ? null : navigator.audioSession;
  if (!session) return false;
  try {
    session.type = 'playback';
    return true;
  } catch {
    return false;
  }
};

/**
 * 运行时探测「element.volume 是否可写」。
 * iOS Safari 上赋 0.37 读回来是 1。
 */
export const elementVolumeIsWritable = () => {
  if (typeof document === 'undefined' || !document.createElement) return false;
  try {
    const probe = document.createElement('audio');
    probe.volume = 0.37;
    return Math.abs(Number(probe.volume) - 0.37) < 0.01;
  } catch {
    return false;
  }
};

const isIOS = () => {
  if (typeof navigator === 'undefined') return false;
  const ua = navigator.userAgent || '';
  return /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
};

/**
 * iOS 17 之前没有 audioSession：Web Audio 跟随静音开关。
 * 这时需要靠「同时播放静音 audio 元素」把音频通道拉到媒体侧。
 */
export const needsSilentChannel = () => {
  if (typeof navigator === 'undefined') return false;
  if (navigator.audioSession) return false;
  return isIOS();
};

// 兼容旧导出名（早期排查日志里用过）
export const warnsAboutSilentSwitch = needsSilentChannel;

const contextConstructor = () => {
  if (typeof window === 'undefined') return null;
  return window.AudioContext || window.webkitAudioContext || null;
};

const defaultFactory = () => {
  const Ctor = contextConstructor();
  if (!Ctor) return null;
  return new Ctor();
};

/** Try immediately; a gesture-gated engine may initially return a suspended context. */
export const createAudioContext = (factory = defaultFactory) => {
  let context = null;
  try {
    context = factory();
  } catch {
    return null;
  }
  if (!context || typeof context.createMediaElementSource !== 'function') return null;
  applyPlaybackAudioSession();
  return context;
};

/**
 * 尝试把 context 拉到 running。
 * 失败（没有手势 / 被系统中断）时返回 false —— 调用方必须保持原生播放，不能接图。
 */
export const resumeAudioContext = async (context) => {
  if (!context) return false;
  if (context.state === 'running') return true;
  try {
    await context.resume();
  } catch {
    return false;
  }
  return context.state === 'running';
};

/** element -> graph，一个元素只能建一次 MediaElementSource（第二次会抛 InvalidStateError）。 */
const graphs = new WeakMap();

/**
 * 把元素接进音频图：source -> gain -> analyser -> destination。
 *
 * 只有 context 已经 running 才允许接：接进 suspended 图 = 静音。
 *
 * @returns {null | {
 *   context: AudioContext,
 *   gain: GainNode,
 *   analyser: AnalyserNode | null,
 *   state: string,
 *   resume: () => Promise<boolean>,
 *   applyVolume: (value: number) => void,
 * }}
 */
export const attachAudioGraph = (element, context, initialVolume = element?.volume ?? 0.5) => {
  if (!element || !context) return null;

  const existing = graphs.get(element);
  if (existing) return existing;
  if (context.state !== 'running') return null;

  let source;
  let gain;
  let analyser = null;
  try {
    gain = context.createGain();
    // Seed BEFORE connecting. The native volume is neutral once gain owns volume:
    // setting both to 0.5 makes Chromium output 0.25 (Safari historically ignored native volume).
    gain.gain.value = clamp01(initialVolume);
    source = context.createMediaElementSource(element);
    source.connect(gain);
    gain.connect(context.destination);
  } catch (error) {
    // Once created, a MediaElementSource cannot be undone. Preserve an audible
    // connection if a later node connection failed instead of silently abandoning it.
    if (source) {
      try { source.disconnect(); source.connect(context.destination); } catch { /* report below */ }
    }
    console.warn('[audio] Output graph setup failed:', error);
    return null;
  }
  // Optional analysis must not discard an otherwise working output graph.
  try {
    if (typeof context.createAnalyser === 'function') {
      analyser = context.createAnalyser();
      analyser.fftSize = 2048;
      analyser.smoothingTimeConstant = 0.55;
      // Keep the analyser on the rendered path, including WebKit implementations
      // that do not pull an unconnected analysis branch.
      analyser.connect(context.destination);
      gain.disconnect();
      gain.connect(analyser);
    }
  } catch {
    try { analyser?.disconnect(); gain.disconnect(); gain.connect(context.destination); } catch { /* preserve best effort output */ }
    analyser = null;
  }

  try { element.volume = 1; element.muted = false; } catch { /* iOS volume may be read-only */ }

  const graph = {
    context,
    gain,
    analyser,
    get state() {
      return context.state;
    },
    resume() {
      return resumeAudioContext(context);
    },
    applyVolume(value) {
      const level = clamp01(value);
      // ONE attenuation stage on every engine. Never multiply native volume by gain.
      try {
        element.volume = 1;
        element.muted = false;
      } catch {
        /* 某些 WebView 会抛 */
      }
      try {
        const now = context.currentTime;
        if (typeof gain.gain.cancelScheduledValues === 'function') {
          gain.gain.cancelScheduledValues(now);
        }
        if (level === 0) {
          gain.gain.value = 0; // Manual mute must be immediate, not an asymptotic fade.
        } else if (typeof gain.gain.setTargetAtTime === 'function') {
          // 15ms 平滑，避免拖动滑块时的爆音（iOS 13+ 的 MediaElementSource 对突变很敏感）
          gain.gain.setTargetAtTime(level, now, 0.015);
        } else {
          gain.gain.value = level;
        }
      } catch {
        gain.gain.value = level;
      }
    },
  };

  graphs.set(element, graph);
  return graph;
};

/** 仅用于测试/卸载：显式解绑某个元素的图。 */
export const forgetAudioGraph = (element) => {
  if (element) graphs.delete(element);
};

/**
 * Shared output lifecycle, independent of Vue and of browser-name sniffing.
 * prepare() never waits for resume(): WebKit may leave that promise pending
 * until a future gesture. Native playback must not wait behind it.
 */
export function createAudioOutput({ element, getVolume, onGraph = () => {},
  onBlocked = () => {}, contextFactory = createAudioContext }) {
  let context = null;
  let graph = null;
  let disposed = false;
  let resumePending = false;

  const applyVolume = () => {
    const level = clamp01(getVolume());
    if (graph) graph.applyVolume(level);
    else {
      try { element.volume = level; element.muted = level === 0; } catch { /* native fallback */ }
    }
  };
  const attachIfRunning = () => {
    if (disposed || !context) return;
    if (!graph && context.state === 'running') {
      graph = attachAudioGraph(element, context, getVolume());
      if (graph) onGraph(graph);
    }
    // A suspended but UNATTACHED context cannot silence native playback.
    onBlocked(!!graph && context.state !== 'running');
  };
  const prepare = ({ gesture = false } = {}) => {
    if (disposed) return;
    applyPlaybackAudioSession();
    applyVolume();
    if (!context) {
      context = contextFactory();
      if (!context) return;
      context.onstatechange = attachIfRunning;
    }
    attachIfRunning();
    if (context.state === 'running') return;
    // A fresh gesture must retry even if an earlier non-gesture resume is pending.
    if (resumePending && !gesture) return;
    resumePending = true;
    try {
      const result = context.resume(); // Called synchronously inside the gesture.
      attachIfRunning();
      Promise.resolve(result).then(attachIfRunning, () => {
        if (!disposed) onBlocked(!!graph && context.state !== 'running');
      }).finally(() => { resumePending = false; });
    } catch {
      resumePending = false;
      attachIfRunning();
    }
  };
  return {
    prepare, applyVolume,
    get blocked() { return !!graph && context?.state !== 'running'; },
    dispose() {
      disposed = true;
      element.pause();
      if (context) context.onstatechange = null;
      try { context?.close?.()?.catch?.(() => {}); } catch { /* already closed */ }
    }
  };
}
