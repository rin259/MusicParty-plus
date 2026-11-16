<template>
  <div class="hidden">
    <!-- 主播放器 -->
    <audio
        ref="audioRef"
        :src="audioSrc"
        preload="auto"
        playsinline
        @error="handleError"
        @waiting="handleBufferingStart"
        @stalled="handleBufferingStart"
        @playing="handleBufferingEnd"
        @canplay="onCanPlay"
        @seeked="onCanPlay"
        referrerpolicy="no-referrer"
    ></audio>

    <!--
      iOS 17 之前 Web Audio 跟随侧边静音开关；在同一个用户手势里播放一个
      静音媒体元素，可以把音频通道切到 media（媒体元素不受静音开关影响）。
      音频数据本身是静音 WAV，听不见。
    -->
    <audio
        ref="silentRef"
        :src="ALIVE_WAV"
        loop
        playsinline
    ></audio>

    <!-- 极弱音轨保活 (Keep-Alive) -->
    <audio
        v-if="ui.keepAliveEnabled"
        ref="aliveAudioRef"
        :src="ALIVE_WAV"
        loop
        playsinline
    ></audio>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useAudio } from '../composables/useAudio';
import {
  attachAudioGraph,
  createAudioContext,
  elementVolumeIsWritable,
  forgetAudioGraph,
  needsSilentChannel,
  resumeAudioContext
} from '../logic/audioGraph';

const player = usePlayerStore();
const ui = useUiStore();
const audioRef = ref(null);
const aliveAudioRef = ref(null);
const silentRef = ref(null);
let context = null;
let graph = null;
let frequencyData;
let spectrumCopy;
let visualizerFrame;
let lastPublish = 0;

// 静音 WAV（16 个近乎 0 的采样），用于把 Web Audio 拉到媒体通道
const ALIVE_WAV = 'data:audio/wav;base64,UklGRjIAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YRAAAAAAAAAAAAAAAAD//w==';

const {
  localProgress,
  isBuffering,
  isErrorState,
  handleError,
  handleBufferingStart,
  handleBufferingEnd,
  checkAutoPlay,
  resumeAudio
} = useAudio(audioRef, player);

const audioSrc = computed(() => player.nowPlaying?.music.url || '');

// 同步状态到 playerStore
watch(localProgress, (val) => {
  player.localProgress = val;
});
watch(isBuffering, (val) => {
  player.isBuffering = val;
});
watch(isErrorState, (val) => {
  player.isErrorState = val;
});

// 监听播放状态以维持保活音轨
watch(() => player.isPaused, (paused) => {
  if (!paused && ui.keepAliveEnabled) {
    aliveAudioRef.value?.play().catch(() => {});
  } else {
    aliveAudioRef.value?.pause();
  }
});

/**
 * 音量输出统一入口。
 *
 * 接图之前：element.volume + muted（桌面端有效，iOS 只有 muted 有效）。
 * 接图之后：Safari 会忽略 element.volume/muted，必须用 GainNode。
 */
const applyOutputVolume = (value) => {
  const level = Number.isFinite(Number(value)) ? Math.min(1, Math.max(0, Number(value))) : 0.5;
  if (graph) {
    graph.applyVolume(level);
    return;
  }
  if (!audioRef.value) return;
  audioRef.value.volume = level;
  audioRef.value.muted = level === 0;
};

watch(() => ui.volume, applyOutputVolume, { immediate: true });

const onCanPlay = () => {
  handleBufferingEnd();
  checkAutoPlay();
};

const publishSpectrum = () => {
  visualizerFrame = requestAnimationFrame(publishSpectrum);
  // 固定 60Hz 发布，而不是跟着 rAF 走：smoothingTimeConstant 是「每次读取」生效的，
  // 按 144Hz 读取时等效时间常数只有 60Hz 的 1/2.4，响应速度会随刷新率/掉帧来回跳变。
  const now = performance.now();
  if (!graph?.analyser || now - lastPublish < 1000 / 60 - 0.2) return;
  lastPublish = now;

  graph.analyser.getByteFrequencyData(frequencyData);
  // 复用同一块缓冲：原来每帧 slice(0) 会以 60–144Hz 产生 1KB 垃圾，周期性触发 GC 卡顿。
  spectrumCopy.set(frequencyData);
  window.dispatchEvent(new CustomEvent('music-party:spectrum', {
    detail: {
      data: spectrumCopy,
      sampleRate: graph.context.sampleRate,
      fftSize: graph.analyser.fftSize
    }
  }));
};

const startSpectrum = () => {
  if (!graph?.analyser || visualizerFrame) return;
  frequencyData = new Uint8Array(graph.analyser.frequencyBinCount);
  spectrumCopy = new Uint8Array(graph.analyser.frequencyBinCount);
  publishSpectrum();
};

// iOS 在锁屏 / 来电 / 切到后台后会挂起 AudioContext，元素还在「播放」但一点声音没有。
// 这种情况只能靠一次真实点击恢复，所以把「点击开启声音」按钮重新亮出来。
let promptForGesture = false;

const watchContextState = () => {
  if (!context) return;
  context.onstatechange = () => {
    const running = context?.state === 'running';
    if (!running && !player.isPaused && player.nowPlaying) {
      promptForGesture = true;
      player.audioBlocked = true;
    } else if (running && promptForGesture) {
      promptForGesture = false;
      player.audioBlocked = false;
    }
  };
};

/**
 * iOS 17 之前的静音开关绕行：必须在用户手势里播放一个静音媒体元素。
 * 任何异常都不能影响主播放器。
 */
const startSilentChannel = () => {
  if (!needsSilentChannel()) return;
  const element = silentRef.value;
  if (!element) return;
  try {
    element.volume = 0.001;
    if (element.paused) element.play().catch(() => {});
  } catch {
    /* ignore */
  }
};

/**
 * 建立/恢复音频图。必须在用户手势的调用栈里执行。
 *
 * 关键：只有 context 真的 running 才允许接图。把元素接进 suspended 的图
 * 会让本来正常的声音彻底消失（iOS 和 macOS Safari 都会），这正是
 * 「时不时有声音时不时没声音」的来源。
 */
const ensureAudioGraph = async () => {
  if (!audioRef.value) return;

  startSilentChannel();

  if (!context) {
    context = createAudioContext();
    if (!context) return; // 没有 Web Audio 的环境：保持原生播放
  }

  const running = await resumeAudioContext(context);
  if (!running) {
    console.info('[audio] AudioContext 未 running，保持原生播放，等待下一次用户手势');
    return;
  }

  if (!graph) {
    graph = attachAudioGraph(audioRef.value, context);
    if (!graph) return;
    applyOutputVolume(ui.volume);
    startSpectrum();
    watchContextState();
    console.info(
      `[audio] Web Audio graph attached (state=${graph.state}, `
      + `elementVolume=${elementVolumeIsWritable() ? 'writable' : 'read-only -> gain'})`
    );
  }
};

// iPhone 上 pointerdown 之外还要兜 touchstart（老 iOS / 某些内嵌浏览器）
const GESTURE_EVENTS = ['pointerdown', 'touchstart', 'keydown'];

const onUserGesture = () => {
  ensureAudioGraph();
};

const onResumeAudio = () => {
  // 用户点「开启声音」：同一个手势里建图 + resume + play
  ensureAudioGraph();
  resumeAudio();
};

const onPageVisible = () => {
  if (!context || document.visibilityState === 'hidden') return;
  // 从后台/锁屏回来时 iOS 会挂起 AudioContext。没有手势时 resume 可能失败，
  // 但能成功的话就不用等用户再点一次（失败时由 onstatechange 亮出「点击开启声音」）。
  resumeAudioContext(context).catch(() => {});
};

onMounted(() => {
  GESTURE_EVENTS.forEach((event) => {
    window.addEventListener(event, onUserGesture, { capture: true, passive: true });
  });
  window.addEventListener('music-party:resume-audio', onResumeAudio);
  document.addEventListener('visibilitychange', onPageVisible);
  window.addEventListener('pageshow', onPageVisible);

  applyOutputVolume(ui.volume);

  // 初始尝试播放保活音轨
  if (!player.isPaused && ui.keepAliveEnabled) {
    if (aliveAudioRef.value) {
      aliveAudioRef.value.volume = 0.001;
      aliveAudioRef.value.play().catch(() => {});
    }
  }
});

onUnmounted(() => {
  GESTURE_EVENTS.forEach((event) => {
    window.removeEventListener(event, onUserGesture, { capture: true });
  });
  window.removeEventListener('music-party:resume-audio', onResumeAudio);
  document.removeEventListener('visibilitychange', onPageVisible);
  window.removeEventListener('pageshow', onPageVisible);
  cancelAnimationFrame(visualizerFrame);
  visualizerFrame = 0;
  try {
    context?.close?.()?.catch?.(() => {});
  } catch {
    /* 已关闭的 context 再 close 会抛，卸载时忽略 */
  }
  // 图已随 context 关闭而失效，元素如果再被复用不能拿到旧图（否则永久静音）
  forgetAudioGraph(audioRef.value);
  graph = null;
  context = null;
  silentRef.value?.pause?.();
});
</script>