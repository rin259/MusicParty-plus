/**
 * Apple WebKit（macOS Safari / iOS Safari）音频兼容层。
 *
 * 平台差异（都直接造成「时不时没声音 / 音量无效 / 静音无效」）：
 *
 * 1. iOS 上 HTMLMediaElement.volume 是**只读**的：赋值不报错，读回来永远是 1。
 * 2. 只要调用 createMediaElementSource()，元素的输出就交给音频图；
 *    Safari 之后会**完全忽略 element.volume 和 element.muted**（macOS 也一样）。
 *    音量/静音因此必须由 GainNode 负责。
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

/** 建一个 AudioContext；必须在用户手势里调用，iOS 才会直接 running。 */
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
export const attachAudioGraph = (element, context) => {
  if (!element || !context) return null;

  const existing = graphs.get(element);
  if (existing) return existing;
  if (context.state !== 'running') return null;

  let source;
  let gain;
  let analyser = null;
  try {
    source = context.createMediaElementSource(element);
    gain = context.createGain();
    source.connect(gain);
    gain.connect(context.destination);

    // 频谱是可选装饰：分析器建不出来也不能影响出声
    if (typeof context.createAnalyser === 'function') {
      analyser = context.createAnalyser();
      analyser.fftSize = 2048;
      analyser.smoothingTimeConstant = 0.55;
      gain.connect(analyser);
    }
  } catch {
    return null;
  }

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
      // 未接图时 element.volume 可用；接图后 Safari 会忽略它，但设置无害
      try {
        element.volume = level;
        element.muted = level === 0;
      } catch {
        /* 某些 WebView 会抛 */
      }
      try {
        const now = context.currentTime;
        if (typeof gain.gain.cancelScheduledValues === 'function') {
          gain.gain.cancelScheduledValues(now);
        }
        if (typeof gain.gain.setTargetAtTime === 'function') {
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