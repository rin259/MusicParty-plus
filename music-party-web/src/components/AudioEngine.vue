<template>
  <div class="hidden">
    <!-- 主播放器 -->
    <audio
        ref="audioRef"
        :src="audioSrc"
        @error="handleError"
        @waiting="handleBufferingStart"
        @stalled="handleBufferingStart"
        @playing="handleBufferingEnd"
        @canplay="onCanPlay"
        @seeked="onCanPlay"
        referrerpolicy="no-referrer"
    ></audio>

    <!-- 极弱音轨保活 (Keep-Alive) -->
    <audio
        v-if="ui.keepAliveEnabled"
        ref="aliveAudioRef"
        :src="ALIVE_WAV"
        loop
    ></audio>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useAudio } from '../composables/useAudio';

const player = usePlayerStore();
const ui = useUiStore();
const audioRef = ref(null);
const aliveAudioRef = ref(null);
let audioContext;
let analyser;
let frequencyData;
let visualizerFrame;

// 极弱底噪 WAV (人耳难以察觉)
const ALIVE_WAV = 'data:audio/wav;base64,UklGRjIAAABXQVZFRm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YRAAAAAAAAAAAAAAAAD//w==';

const {
  localProgress,
  isBuffering,
  isErrorState,
  handleError,
  handleBufferingStart,
  handleBufferingEnd,
  checkAutoPlay
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

// 监听音量
watch(() => ui.volume, (newVol) => {
  if (audioRef.value) {
    audioRef.value.volume = newVol;
  }
}, { immediate: true });

const onCanPlay = () => {
  handleBufferingEnd();
  checkAutoPlay();
};

const publishSpectrum = () => {
  if (analyser) {
    analyser.getByteFrequencyData(frequencyData);
    window.dispatchEvent(new CustomEvent('music-party:spectrum', {
      detail: {
        data: frequencyData.slice(0),
        sampleRate: audioContext.sampleRate,
        fftSize: analyser.fftSize
      }
    }));
  }
  visualizerFrame = requestAnimationFrame(publishSpectrum);
};

const enableSpectrum = () => {
  if (analyser || !audioRef.value) return;
  audioContext = new AudioContext();
  const source = audioContext.createMediaElementSource(audioRef.value);
  analyser = audioContext.createAnalyser();
  // 2048 gives ~23Hz bins at 48kHz, sufficient to isolate frequencies <=80Hz.
  analyser.fftSize = 2048;
  analyser.smoothingTimeConstant = 0.55;
  frequencyData = new Uint8Array(analyser.frequencyBinCount);
  source.connect(analyser);
  analyser.connect(audioContext.destination);
  publishSpectrum();
};

onMounted(() => {
  if (audioRef.value) {
    audioRef.value.volume = ui.volume;
    audioRef.value.addEventListener('play', enableSpectrum, { once: true });
  }
  // 初始尝试播放保活音轨
  if (!player.isPaused && ui.keepAliveEnabled) {
    if (aliveAudioRef.value) {
      aliveAudioRef.value.volume = 0.001;
      aliveAudioRef.value.play().catch(() => {});
    }
  }
});

onUnmounted(() => {
  cancelAnimationFrame(visualizerFrame);
  audioContext?.close();
});
</script>
