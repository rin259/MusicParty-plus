// ... existing code ...
<template>
  <div class="relative w-full h-full flex items-center justify-center overflow-hidden">

    <!-- LAYER 0: 静态背景层 (最底层) -->
    <div class="absolute inset-0 z-0 pointer-events-none">
      <!-- 随机点光源：16 颗各自绑定一段频带，只做即时响应（不漂移） -->
      <canvas ref="lightsRef" aria-hidden="true" class="absolute inset-0 w-full h-full"></canvas>
      <div class="absolute inset-0 bg-[linear-gradient(rgb(var(--medical-900)/0.04)_1px,transparent_1px),linear-gradient(90deg,rgb(var(--medical-900)/0.04)_1px,transparent_1px)] bg-[size:40px_40px]"></div>
      <!-- 频谱字标：取时频谱之思想（频率→位置、能量→强度、时间→余辉），落在本站字标上 -->
      <div aria-hidden="true" class="absolute inset-0 flex items-center justify-center select-none">
        <div ref="wordRef" class="spectral-word text-[12vw] font-bold tracking-tighter whitespace-nowrap blur-[3px]">
          <span v-for="(ch, i) in wordLetters" :key="i" class="spectral-letter">{{ ch === ' ' ? ' ' : ch }}</span>
        </div>
      </div>
      <!-- 四角标记 -->
      <div class="absolute top-10 left-10 w-8 h-8 border-t-2 border-l-2 border-medical-300"></div>
      <div class="absolute top-10 right-10 w-8 h-8 border-t-2 border-r-2 border-medical-300"></div>
      <div class="absolute bottom-10 left-10 w-8 h-8 border-b-2 border-l-2 border-medical-300"></div>
      <div class="absolute bottom-10 right-10 w-8 h-8 border-b-2 border-r-2 border-medical-300"></div>
    </div>

    <!-- LAYER 1: 动态视觉层 (Canvas) -->
    <div class="absolute inset-0 z-10 flex items-center justify-center pointer-events-none">
      <canvas
          ref="canvasRef"
          width="1200"
          height="1200"
          class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2"
      ></canvas>

      <!-- 旋转圈圈 (CSS动画) -->
      <div class="absolute inset-0 w-[320px] h-[320px] m-auto border border-medical-200 rounded-[24%] animate-[spin_10s_linear_infinite] opacity-30 border-dashed"></div>
      <div class="absolute inset-0 w-[340px] h-[340px] m-auto border border-medical-200 rounded-[24%] animate-[spin_15s_linear_infinite_reverse] opacity-20"></div>
    </div>

    <!-- LAYER 2: 信息层 (歌词 & 日志) -->
    <div class="absolute inset-0 z-20 pointer-events-none">
      <!-- 左侧：同步歌词 -->
      <div class="absolute font-mono transition-all duration-300
                  inset-x-0 bottom-7 flex flex-col items-center justify-end h-64 pb-2
                  md:inset-auto md:bottom-10 md:left-10 md:items-start md:justify-end md:h-auto md:w-80
      ">
        <div class="hidden md:block text-[10px] text-accent-text mb-1 tracking-widest border-b border-accent/30 pb-1">
          {{ parsedLyrics.length ? 'LYRIC_SYSTEM' : (player.nowPlaying ? 'TRACK_DATA · NO LYRIC' : 'TRACK_DATA') }}
        </div>
        <div class="w-full space-y-1 text-xs font-normal leading-tight flex flex-col md:justify-end min-h-0">
          <template v-if="parsedLyrics.length === 0">
            <!-- 无歌词（B站 / SoundCloud / YouTube 均无歌词）：不留空白，改显示可核对的曲目事实 -->
            <div v-if="player.nowPlaying" class="w-full font-mono text-[10px]">
              <div
                  v-for="fact in trackFacts"
                  :key="fact.k"
                  class="flex items-baseline justify-between gap-3 border-b border-medical-200/60 py-0.5 last:border-0"
              >
                <span class="tracking-[0.12em] text-medical-500">{{ fact.k }}</span>
                <span class="truncate text-medical-800">{{ fact.v }}</span>
              </div>
            </div>
            <div v-else class="opacity-70 flex items-center justify-center md:justify-start text-medical-500">
              <span class="text-accent-text mr-2 text-[10px]">></span>IDLE · NO TRACK
            </div>
          </template>
          <div
              v-else
              v-for="(line, i) in activeLines"
              :key="line.time"
              class="transition-all duration-300 flex items-center md:justify-start justify-center"
              :class="i === activeLines.length - 1 ? 'opacity-100 text-medical-900 font-bold' : 'opacity-100 text-medical-500'"
          >
            <span class="hidden md:inline text-accent-text mr-2 text-[10px]" :class="{'animate-pulse': i === activeLines.length - 1}">></span>
            <span :class="{'bg-ink text-ink-fg px-1': i === activeLines.length - 1 && isMobile}">
                     {{ line.text }}
            </span>
          </div>
        </div>
      </div>

      <!-- 右侧：音频遥测（真实测量；B1 三频段电平 + B4 带时间戳的滚动日志） -->
      <div class="absolute bottom-8 right-8 w-[240px] font-mono text-[10px] text-medical-500 hidden md:block select-none">
        <div class="absolute inset-0 bg-medical-50/72 chamfer-tl"></div>
        <!-- 屏读摘要：视觉面板是装饰，但「音频是否在跑」是唯一可信状态，2 秒低频播报一次 -->
        <span ref="srRef" role="status" aria-live="polite" class="sr-only"></span>
        <div ref="telemetryRef" aria-hidden="true" class="relative flex flex-col gap-1 px-2 py-1.5">
          <div class="flex items-baseline justify-between tracking-[0.18em] text-medical-500">
            <span>AUDIO_TELEMETRY</span>
            <span class="text-[9px]">REL dB</span>
          </div>

          <div v-for="band in telemetryBands" :key="band.key" :data-band="band.key" class="flex items-center gap-2">
            <span class="w-8 shrink-0 text-[9px] tracking-[0.12em]">{{ band.label }}</span>
            <span class="relative h-1.5 flex-1 overflow-hidden bg-medical-200/70">
              <span class="telem-bar"></span>
              <span class="telem-peak"></span>
            </span>
            <span class="telem-value w-10 shrink-0 text-right text-[10.5px] tabular-nums text-medical-800">---</span>
          </div>

          <div class="flex items-baseline justify-between border-t border-medical-200/60 pt-1 tabular-nums text-medical-600">
            <span>CREST <span data-meta="crest">0.0</span></span>
            <span>PEAK <span data-meta="peak">0.0</span></span>
            <span>RMS <span data-meta="rms">0.0</span></span>
            <span data-meta="clip" class="text-danger" style="visibility: hidden">CLIP!</span>
          </div>

          <div class="space-y-0.5 pt-0.5 text-[8.5px] leading-tight tabular-nums">
            <div data-line></div>
            <div data-line></div>
            <div data-line></div>
          </div>

          <div data-meta="link" class="pt-0.5 text-[9px] text-medical-500">LINK SEARCHING · QUEUE 0</div>
        </div>
      </div>
    </div>

    <!-- LAYER 2.5: live chat danmaku. It is visual-only and never blocks playback controls. -->
    <div aria-hidden="true" class="absolute inset-0 z-20 overflow-hidden pointer-events-none">
      <!-- 统一单侧弹出：原 lane%5 对双轨渲染导致 3/5 的弹幕被静默丢弃 -->
      <TransitionGroup name="danmaku" tag="div" class="danmaku-lane left-[4%] md:left-[8%]">
        <div v-for="message in danmakuMessages" :key="message.instanceId" class="danmaku-item max-w-[38vw] md:max-w-[30vw] truncate rounded-full border border-white/50 bg-scrim/75 px-3 py-1.5 text-xs text-white shadow-lg backdrop-blur-sm">
          <span class="mr-1.5 text-accent font-bold">{{ userStore.resolveName(message.userId, message.userName) }}</span>{{ message.content }}
        </div>
      </TransitionGroup>
    </div>

    <!-- LAYER 3: 核心实体层 (封面) -->
    <div class="relative z-30 flex items-center justify-center pointer-events-auto">
      <div class="relative">
        <div v-if="player.nowPlaying?.enqueuedById" class="absolute -top-5 right-0 text-[10px] font-mono text-accent-text flex items-center gap-2 z-20 select-none">
          <span>REQ_BY</span>
          <span class="font-bold text-medical-600 border-b border-medical-300 leading-tight">
            {{ userStore.resolveName(player.nowPlaying.enqueuedById, player.nowPlaying.enqueuedByName) }}
          </span>
        </div>

        <button
            id="tutorial-like"
            ref="coverBoxRef"
            type="button"
            class="relative w-64 h-64 md:w-[clamp(196px,26vh,288px)] md:h-[clamp(196px,26vh,288px)] bg-medical-50 chamfer-br flex items-center justify-center overflow-hidden transition-all duration-500 cursor-pointer border border-surface-1 shadow-2xl"
            :class="[
                 // 仅保留暂停时的缩放/灰度
                 player.isPaused ? 'scale-95 grayscale' : 'scale-100',
                  'cursor-pointer'
             ]"
            :aria-label="hasLiked ? '已点赞，再点一次再次点赞' : '为当前歌曲点赞'"
            :aria-pressed="!!hasLiked"
            @mouseenter="!isMobile && (isHovering = true)"
            @mouseleave="!isMobile && (isHovering = false)"
            @click="handleCoverClick"
        >
          <!-- Loading 状态 -->
          <div v-if="player.isLoading" class="absolute inset-0 z-50 bg-scrim/55 backdrop-blur-sm flex flex-col items-center justify-center text-white">
            <div class="w-12 h-12 border-4 border-white/30 border-t-white animate-spin mb-4"></div>
            <span class="font-mono text-xs animate-pulse tracking-widest">FETCHING_AUDIO...</span>
          </div>

          <!-- [MODIFIED START] 交互遮罩层：全息HUD风格 -->
          <Transition
              enter-active-class="transition-all duration-300 ease-out"
              enter-from-class="opacity-0 scale-90"
              enter-to-class="opacity-100 scale-100"
              leave-active-class="transition-all duration-300 ease-in"
              leave-from-class="opacity-100 scale-100"
              leave-to-class="opacity-0 scale-95"
          >
            <div
                v-if="isBursting || isHovering || mobileLikePending"
                class="absolute inset-0 z-40 flex items-center justify-center select-none"
                :class="[
                    // 已点赞状态下，只显示极淡的角落标记，不遮挡封面
                    // 交互或爆发时，增加暗色扫描背景
                    (isBursting || isHovering || mobileLikePending) ? 'bg-scrim/65' : ''
                ]"
            >
              <!-- 1. 动态网格背景 (仅在交互时显示) -->
              <div class="absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.1)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.1)_1px,transparent_1px)] bg-[size:20px_20px] opacity-20"></div>

              <!-- 2. 四角瞄准器 (HUD) -->
              <div class="absolute top-2 left-2 w-2 h-2 border-t border-l border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute top-2 right-2 w-2 h-2 border-t border-r border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute bottom-2 left-2 w-2 h-2 border-b border-l border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute bottom-2 right-2 w-2 h-2 border-b border-r border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>

              <!-- 3. 中央核心交互区 -->
              <div class="relative flex flex-col items-center justify-center gap-2 group">

                <!-- [MODIFIED START] 爆发动画：方形扩散 (去掉 rounded-full, 增加 border) -->
                <!-- -inset-6 确保方形初始大小包裹住文字和图标 -->
                <div v-if="isBursting" class="absolute -inset-6 border border-accent bg-accent/20 animate-ping duration-700 z-0"></div>
                <!-- [MODIFIED END] -->

                <!-- 图标逻辑: 闪电 -->
                <div
                    class="relative transition-all duration-300 transform z-10"
                    :class="[
                      isBursting ? 'scale-125 text-accent-text drop-shadow-[0_0_15px_rgb(var(--accent))]' :
                       'text-white scale-100 group-hover:scale-110 group-hover:text-white'
                  ]"
                >
                  <Activity
                      v-if="(isHovering || mobileLikePending) && !isBursting"
                      class="w-10 h-10 animate-pulse"
                  />

                  <Zap v-else class="w-10 h-10" :class="isBursting ? 'fill-current stroke-none' : ''" />
                </div>


                <!-- 状态文字 -->
                <div class="flex items-center gap-1 font-mono text-[10px] tracking-[0.2em] transition-colors duration-300"
                     :class="isBursting ? 'text-accent-text' : 'text-white'"
                >
                  <span v-if="isBursting">INJECTING...</span>
                  <span v-else>REACT_TO_BEAT</span>
                </div>
              </div>
            </div>
          </Transition>

          <img v-if="currentCover" :src="currentCover" alt="" class="absolute inset-0 w-full h-full object-cover opacity-80" :class="player.isPaused ? '' : 'animate-[pulse_4s_ease-in-out_infinite]'" />
          <div v-else class="flex flex-col items-center text-medical-500">
            <div class="w-16 h-16 border-2 border-medical-300 mb-2 rotate-45"></div>
            <span class="font-mono text-xs tracking-widest">NO MEDIA</span>
          </div>
          <div class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-20 pointer-events-none z-20"></div>

          <!-- 状态标签：悬停或点赞时隐藏 -->
          <div
              class="absolute top-0 left-0 z-50 px-3 py-1 font-mono text-xs font-bold chamfer-br transition-colors duration-300 bg-scrim/80 backdrop-blur-sm text-white"
          >
            {{ player.isPaused ? 'PAUSED' : 'PLAYING' }}
          </div>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch, nextTick } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import {useEventListener, useWindowSize} from '@vueuse/core';
import { parseLyrics } from '../utils/parser';
import { AudioVisualizer } from '../logic/AudioVisualizer';
import { SpectralWordmark } from '../logic/SpectralWordmark.js';
import { globalGate } from '../logic/Glitch.js';
import { fidelity } from '../logic/Fidelity.js';
import { AudioTelemetry } from '../logic/AudioTelemetry.js';
import { PointLights } from '../logic/PointLights.js';
import { useUiStore } from '../stores/ui';
import { formatDuration, PLATFORM_LABEL } from '../logic/searchMerge.js';
import { Activity, Zap } from 'lucide-vue-next';

const userStore = useUserStore();
const player = usePlayerStore();
const uiStore = useUiStore();
const canvasRef = ref(null);
const wordRef = ref(null);
const lightsRef = ref(null);
const telemetryRef = ref(null);
const srRef = ref(null);
const telemetry = new AudioTelemetry();
const telemetryBands = [
  { key: 'low', label: 'LOW' },
  { key: 'mid', label: 'MID' },
  { key: 'high', label: 'HIGH' }
];
const wordmark = new SpectralWordmark();
const lights = new PointLights();
const wordLetters = computed(() => Array.from((uiStore.backWords || 'MUSIC PARTY').toUpperCase()));
const coverBoxRef = ref(null);
const currentCover = computed(() => player.nowPlaying?.music.coverUrl);
const { width } = useWindowSize();
const isMobile = computed(() => width.value < 768);

// 无歌词时（B站 / SoundCloud / YouTube）不留空白：显示可核对的曲目事实，
// 同时把「解析/缓存/队列」这些真实状态露出来。
const trackFacts = computed(() => {
  const np = player.nowPlaying;
  if (!np?.music) return [];
  const music = np.music;
  const item = player.queue.find(
      (entry) => entry.music?.id === music.id && entry.music?.platform === music.platform
  );
  const pipeline = item?.status === 'READY' ? 'READY'
      : item?.status === 'FAILED' ? 'FAILED'
          : item ? 'RESOLVING' : 'ON AIR';
  return [
    { k: 'SOURCE', v: PLATFORM_LABEL[music.platform] || music.platform || '—' },
    { k: 'LENGTH', v: music.duration > 0 ? formatDuration(music.duration) : '—' },
    { k: 'REQUEST', v: userStore.resolveName(np.enqueuedById, np.enqueuedByName) },
    { k: 'PIPELINE', v: pipeline },
    { k: 'QUEUE', v: `${player.queue.length} TRACKS` }
  ];
});

// === 交互逻辑 ===
const isHovering = ref(false);       // PC Hover
const mobileLikePending = ref(false);// 移动端第一次点击
const mobileTimer = ref(null);       // 移动端定时器
const isBursting = ref(false);       // 爆发状态（本地+广播）


const hasLiked = computed(() => {
  return player.nowPlaying?.likedUserIds?.includes(userStore.userToken);
});

const danmakuMessages = ref([]);
const danmakuTimers = new Set();

const enqueueDanmaku = (event) => {
  const message = event.detail;
  if (message?.type !== 'CHAT') return;

  const instanceId = `${message.id}-${Date.now()}`;
  danmakuMessages.value.push({ ...message, instanceId });
  const timer = setTimeout(() => {
    danmakuMessages.value = danmakuMessages.value.filter(item => item.instanceId !== instanceId);
    danmakuTimers.delete(timer);
  }, 7200);
  danmakuTimers.add(timer);
};


// 特效冷却 (本地)
const EFFECT_COOLDOWN = 1000;
let lastEffectTime = 0;

const handleCoverClick = () => {
  if (isMobile.value) {
    if (!mobileLikePending.value) {
      // 第一次点击
      mobileLikePending.value = true;
      mobileTimer.value = setTimeout(() => {
        mobileLikePending.value = false;
      }, 2000);
    } else {
      // 第二次点击 (确认)
      clearTimeout(mobileTimer.value);
      mobileLikePending.value = false;
      confirmLike();
    }
  } else {
    // PC 直接点击
    confirmLike();
  }
};

const confirmLike = () => {
  player.sendLike();
  triggerBurst(); // 本地先爆发一次，提升手感
};

const triggerBurst = () => {
  const now = Date.now();
  if (now - lastEffectTime < EFFECT_COOLDOWN) return;
  lastEffectTime = now;

  isBursting.value = true;
  visualizer.impulse(); // 触发 Canvas 圆环爆发
  wordmark.pulse(1);    // 频谱字标同步提亮（参与者的动作要看得见）
  lights.pulse(1);      // 点光源同源一闪
  setTimeout(() => {
    isBursting.value = false;
  }, 500); // 边框高亮持续 0.5s
};

// === 歌词逻辑 ===
const parsedLyrics = ref([]);
const currentLineIndex = ref(-1);

const activeLines = computed(() => {
  const idx = currentLineIndex.value;
  if (parsedLyrics.value.length === 0) return [];
  const historyCount = isMobile.value ? 5 : 10;
  const start = Math.max(0, idx - historyCount);
  const end = Math.min(parsedLyrics.value.length, idx + 1);
  if (idx === -1) return parsedLyrics.value.slice(0, 3);
  return parsedLyrics.value.slice(start, end);
});


watch(() => player.lyricText, (newVal) => {
  parsedLyrics.value = parseLyrics(newVal);
  currentLineIndex.value = -1;
});

// === 系统日志逻辑 (Realtime) ===
let updateInterval;

const visualizer = new AudioVisualizer();
let resizeObserver = null;
const isVisualizerActive = computed(() => !!player.nowPlaying && !player.isPaused);

// 画布 CSS 尺寸变化（断点/旋屏/侧栏收合）时重设内部分辨率与环场几何。
// 画布恒为「容器内最大正方形」（封顶 1000px），由 JS 定尺寸而非 CSS aspect-ratio，
// 免短容器把方格压成矩形（矩形画布会把圆环画成椭圆）。
const refreshVisualizerMetrics = () => {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const host = canvas.parentElement;
  if (host) {
    const size = Math.max(200, Math.min(host.clientWidth, host.clientHeight, 1000));
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;
  }
  visualizer.fitResolution();
  visualizer.layoutRings(coverBoxRef.value?.offsetWidth || 0);
};

// 监听状态变化以控制 Visualizer
watch(isVisualizerActive, (active) => {
  visualizer.setPlaying(active);
});

// --- A：遥测抖动（仅真实事件触发；与标题解码共用闸门，全局同时只允许一次）---
// kind: 'update'（换曲等常态更新）| 'fault'（错误/断连）——色差只留给 fault。
const telemetryGlitch = (kind = 'update') => {
  if (!globalGate.take()) return;
  const block = telemetryRef.value;
  if (!block) return;

  // 幅度由「保真度」决定：健康时 1px，最差 ~1.8x。同一个动画只靠幅度表达严重程度。
  const amp = fidelity.amplify();
  block.style.setProperty('--ga', `${(1.4 * amp).toFixed(2)}px`);
  const cls = kind === 'fault' ? 'is-faulting' : 'is-glitching';
  block.classList.add(cls);

  // 单字符损坏：只在读数行/状态行上动手，避免破坏电平条；保真度低时坏 2 个字符
  const lines = block.querySelectorAll('[data-line], [data-meta="link"]');
  const line = lines[Math.floor(Math.random() * Math.max(1, lines.length))];
  if (line) {
    const original = line.textContent;
    const count = fidelity.value < 0.6 ? 2 : 1;
    const out = original.split('');
    let done = 0;
    while (done < count) {
      const idx = Math.floor(Math.random() * Math.max(1, out.length));
      if (out[idx] === ' ' || out[idx] === '█') continue;
      out[idx] = '█';
      done++;
    }
    line.textContent = out.join('');
    setTimeout(() => { line.textContent = original; }, 90);
  }
  setTimeout(() => block.classList.remove(cls), 190);
};

watch(() => player.nowPlaying?.music?.id, (next, prev) => {
  if (prev && next && next !== prev) telemetryGlitch('update');
});
watch(() => player.isErrorState, (v) => { if (v) { fidelity.noteFailure(); telemetryGlitch('fault'); } });
watch(() => player.connected, (v, prev) => { if (prev === true && v === false) { fidelity.noteFailure(); telemetryGlitch('fault'); } });

// 房间参与度：有人点歌 → 频谱字标基调轻微抬升（参与者的动作要能在背景上看见）
watch(() => player.queue.length, (next, prev) => {
  if (next > prev) {
    wordmark.bumpCrowd(0.45);
    lights.bumpCrowd(0.45);
  }
});

// 字标内容可被设置更改：重建字母后重新量测
watch(wordLetters, async () => {
  await nextTick();
  wordmark.remeasure();
});

// 脱敏工具

onMounted(() => {
  // 1. 挂载 Canvas & Visualizer
  if (canvasRef.value) {
    visualizer.mount(canvasRef.value);
    visualizer.setPlaying(isVisualizerActive.value);
    nextTick(refreshVisualizerMetrics);
    // 容器尺寸变化（侧栏收合、精简模式、断点切换）未必触发 window resize，
    // 故直接观察画布本体——环场几何须随实测定，方能处处不压封面、不出画布。
    if (typeof ResizeObserver === 'function') {
      resizeObserver = new ResizeObserver(() => refreshVisualizerMetrics());
      resizeObserver.observe(canvasRef.value.parentElement || canvasRef.value);
    }
  }
  if (wordRef.value) {
    wordmark.attach(wordRef.value);
    wordmark.start();
  }
  if (lightsRef.value) lights.mount(lightsRef.value);
  if (telemetryRef.value) telemetry.attach(telemetryRef.value, srRef.value);

  // 2. 监听全局自定义事件
  useEventListener(window, 'resize', refreshVisualizerMetrics);
  useEventListener(window, 'player:like', () => triggerBurst());
  useEventListener(window, 'music-party:spectrum', (event) => {
    visualizer.setSpectrum(event.detail);
    wordmark.setSpectrum(event.detail);
    lights.setSpectrum(event.detail);
    telemetry.setSpectrum(event.detail);
  });
  useEventListener(window, 'music-party:chat-message', enqueueDanmaku);

  // 3. 启动高频更新循环 (歌词进度 & 视觉同步)
  updateInterval = setInterval(() => {
    // 歌词进度更新
    if (player.nowPlaying && !player.isPaused && parsedLyrics.value.length > 0) {
      const currentTime = player.getCurrentProgress();
      let activeIdx = -1;
      for (let i = 0; i < parsedLyrics.value.length; i++) {
        if (currentTime >= parsedLyrics.value[i].time) activeIdx = i;
        else break;
      }
      if (activeIdx !== currentLineIndex.value) currentLineIndex.value = activeIdx;
    }
    // 音频遥测：内部节流到 12.5Hz；无频谱时电平自然回落
    telemetry.setState({ connected: player.connected, queue: player.queue.length });
    fidelity.setState({ connected: player.connected, buffering: !!player.isBuffering }).update();
    telemetry.tick();
  }, 100);

  // 初始化歌词
  if (player.lyricText) parsedLyrics.value = parseLyrics(player.lyricText);
});

onUnmounted(() => {
  resizeObserver?.disconnect();
  wordmark.unmount();
  lights.unmount();
  visualizer.unmount();
  clearInterval(updateInterval);
  danmakuTimers.forEach(clearTimeout);
});
</script>

<style scoped>
.danmaku-lane {
  position: absolute;
  bottom: 12%;
  display: flex;
  max-height: 72%;
  flex-direction: column-reverse;
  align-items: flex-start;
  gap: 10px;
}
/* A：遥测抖动——切片位移 + 轻 RGB 分离；170ms、离散步进；reduced-motion 关闭 */
/* 常态更新：只有切片位移，幅度由 --ga 给出（保真度越低越大） */
.is-glitching { animation: telemetry-slice 170ms steps(1, end) 1 both; }
@keyframes telemetry-slice {
  0% { transform: none; clip-path: none; }
  18% { transform: translateX(calc(-1 * var(--ga, 1.4px))); clip-path: inset(12% 0 58% 0); }
  38% { transform: translateX(var(--ga, 1.4px)); clip-path: inset(60% 0 14% 0); }
  60% { transform: translateX(calc(-0.66 * var(--ga, 1.4px))); clip-path: inset(34% 0 42% 0); }
  80% { transform: translateX(calc(0.66 * var(--ga, 1.4px))); clip-path: none; }
  100% { transform: none; clip-path: none; }
}
/* 故障专属：色差分离只允许出现在错误/断连——它是「坏了」的信号，不是通用特效 */
.is-faulting { animation: telemetry-fault 190ms steps(1, end) 1 both; }
@keyframes telemetry-fault {
  0% { transform: none; clip-path: none; text-shadow: none; }
  18% { transform: translateX(calc(-1 * var(--ga, 1.4px))); clip-path: inset(12% 0 58% 0);
        text-shadow: 1px 0 rgba(249, 115, 22, 0.45), -1px 0 rgba(56, 189, 248, 0.3); }
  38% { transform: translateX(var(--ga, 1.4px)); clip-path: inset(60% 0 14% 0);
        text-shadow: -1px 0 rgba(249, 115, 22, 0.45), 1px 0 rgba(56, 189, 248, 0.3); }
  60% { transform: translateX(calc(-0.66 * var(--ga, 1.4px))); clip-path: inset(34% 0 42% 0); text-shadow: none; }
  80% { transform: none; clip-path: none; }
  100% { transform: none; clip-path: none; }
}
/* 音频遥测：条用 transform（免重排），峰值保持用 1px 竖线 */
.telem-bar {
  position: absolute;
  inset: 0;
  transform-origin: left center;
  transform: scaleX(0);
  background: rgb(var(--accent));
  opacity: 0.85;
  /* 数据 12.5Hz，补齐到下一帧的平滑插值；纯合成层，成本很低 */
  transition: transform 90ms linear;
}
.telem-peak {
  position: absolute;
  top: -1px;
  bottom: -1px;
  left: 0;
  width: 1px;
  background: rgb(var(--medical-900) / 0.45);
  transition: left 90ms linear;
}
.spectral-word { display: flex; align-items: center; justify-content: center; }
.spectral-letter { display: inline-block; transform-origin: center; color: rgb(var(--medical-200)); opacity: .34; }
.danmaku-item { animation: danmaku-life 7.2s linear both; }
@keyframes danmaku-life {
  0% { transform: translateY(22px); opacity: 0; }
  10% { opacity: 1; }
  78% { opacity: 1; }
  100% { transform: translateY(-150px); opacity: 0; }
}
/* 环境光（时频谱）为画布层，样式已在模板内联，无需额外规则。 */
</style>
