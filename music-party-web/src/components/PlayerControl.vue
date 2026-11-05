<template>
  <div class="min-h-24 md:h-24 bg-surface-1 border-t border-medical-200 flex items-center px-3 md:px-8 py-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] md:py-0 md:pb-0 relative z-50 shadow-lg">
    <!-- 封面 -->
    <button
        id="tutorial-source"
        type="button"
        @click="openSourcePage"
        class="w-14 h-14 md:w-20 md:h-20 mt-0 shadow-lg border-2 border-surface-1 chamfer-br flex-shrink-0 relative z-10 bg-surface-strong cursor-pointer group overflow-hidden"
        title="打开歌曲源页面"
        aria-label="打开歌曲源页面"
    >
      <CoverImage :src="nowPlaying?.music.coverUrl" class="w-full h-full transition-transform duration-300 group-hover:scale-110 group-hover:opacity-50" />

      <!-- 悬浮时的遮罩和图标 -->
      <div class="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300 bg-scrim/40">
        <ExternalLink class="w-6 h-6 text-white" aria-hidden="true" />
      </div>
    </button>

    <!-- 中间：信息与进度 -->
    <div class="flex-1 ml-3 mr-0 md:mr-8 flex flex-col justify-center min-w-0">
      <div class="flex justify-between items-end mb-1">
        <div class="overflow-hidden w-full">
          <!-- 标题显示逻辑与样式 -->
          <h2 ref="titleRef" class="text-lg font-bold truncate leading-tight transition-colors duration-300"
              :class="!player.connected ? 'text-danger animate-pulse' : 'text-medical-900'"
          >
            {{
              !player.connected
                  ? '!CONNECTION LOST!'
                  : (nowPlaying ? nowPlaying.music.name : 'WAITING FOR SIGNAL...')
            }}
          </h2>

          <!-- 副标题显示逻辑与样式 -->
          <p class="text-xs font-sans truncate transition-colors duration-300"
             :class="!player.connected ? 'text-danger animate-pulse' : 'text-medical-500'"
          >
            {{
              !player.connected
                  ? 'RECONNECT SERVER...'
                  : (nowPlaying ? nowPlaying.music.artists.join(' / ') : 'SYSTEM IDLE')
            }}
          </p>
        </div>

        <!-- 时间显示 -->
        <div class="hidden md:block font-mono text-xs text-medical-500 flex-shrink-0 ml-2">
           <span v-if="player.isLoading" class="text-accent-text animate-pulse">SYNCING SERVER...</span>
           <span v-if="player.isBuffering" class="animate-pulse text-accent-text">BUFFERING...</span>
           <span v-else>{{ formatDuration(displayProgress) }} / {{ formatDuration(nowPlaying?.music.duration || 0) }}</span>
        </div>
      </div>

      <!-- 进度条 -->
      <div
          ref="progressTrackRef"
          class="group/progress h-8 md:h-8 w-full relative flex items-center cursor-pointer touch-none select-none"
          role="slider"
          aria-label="播放进度"
          :aria-valuemin="0"
          :aria-valuemax="nowPlaying?.music.duration || 0"
          :aria-valuenow="Math.round(displayProgress)"
          :aria-valuetext="`${formatDuration(displayProgress)} / ${formatDuration(nowPlaying?.music.duration || 0)}`"
          tabindex="0"
          @pointerdown="startSeek"
          @pointermove="moveSeek"
          @pointerup="commitSeek"
          @pointercancel="cancelSeek"
          @keydown.left.prevent="seekByKeyboard(-5000)"
          @keydown.right.prevent="seekByKeyboard(5000)"
      >
        <div class="h-1.5 bg-medical-200 w-full relative overflow-visible">

        <div
            v-for="(marker, index) in likeMarkers"
            :key="index"
            class="absolute z-20 transition-all duration-300 transform -translate-x-1/2 -translate-y-1/2 top-1/2"
            :style="{ left: (marker / (nowPlaying?.music.duration || 1)) * 100 + '%' }"
        >
          <Zap
              class="w-3 h-3 drop-shadow-md animate-[pulse_1.5s_ease-in-out_infinite]"
              :class="'text-accent-text fill-accent'"
          />
        </div>

        <div
            class="h-full transition-all duration-300 ease-linear relative"
            :class="player.isErrorState ? 'bg-danger' : 'bg-accent'"
            :style="{ width: progressPercent + '%' }"
        >
          <div
              v-if="!player.isErrorState"
              class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-2 rotate-45 transition-all duration-150 bg-accent group-hover/progress:scale-150"
              :class="isSeeking ? 'scale-150 shadow-[0_0_8px_rgba(249,115,22,0.65)]' : ''"
          ></div>
        </div>
        <div v-if="isSeeking" class="absolute bottom-full mb-1 -translate-x-1/2 rounded-sm bg-scrim px-2 py-1 font-mono text-[11px] text-white shadow-lg" :style="{ left: progressPercent + '%' }">
          {{ formatDuration(seekPreview) }}
        </div>
        </div>
      </div>
      
      <!-- 移动端简易控制 -->
      <div class="flex md:hidden items-center justify-between gap-1.5 mt-1.5 min-w-0">
        <div class="min-w-0 truncate font-mono text-[11px] text-medical-500 tabular-nums whitespace-nowrap">
          <span v-if="player.isLoading" class="text-accent-text animate-pulse">SYNCING...</span>
          <span v-else-if="player.isBuffering" class="text-accent-text animate-pulse">BUFFERING...</span>
          <span v-else>{{ formatDuration(displayProgress) }} / {{ formatDuration(nowPlaying?.music.duration || 0) }}</span>
        </div>
        <div class="flex gap-1.5 flex-shrink-0">
        <button id="tutorial-download-mobile" @click="downloadCurrentMusic" class="hidden min-[360px]:flex items-center justify-center w-11 h-11 bg-medical-50 border border-medical-200 rounded-sm text-medical-500 active:bg-medical-200" aria-label="下载当前歌曲">
          <Download class="w-4 h-4" />
        </button>
        <button
            id="tutorial-random-mobile"
            @click="player.cyclePlayMode"
            :disabled="!player.connected || player.isPlayModeLocked"
            class="flex items-center justify-center w-11 h-11 border rounded-sm disabled:opacity-50 transition-colors bg-medical-50 border-medical-200 text-medical-500"
            :aria-label="modeTitle"
            :title="modeTitle"
        >
          <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-4 h-4" />
          <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-4 h-4" />
          <Repeat1 v-else-if="player.playMode === 'REPEAT_ONE'" class="w-4 h-4" />
          <Repeat v-else class="w-4 h-4" />
        </button>
         <button id="tutorial-pause-mobile" @click="player.togglePause" :disabled="player.isPauseLocked && !player.isPaused" class="flex items-center justify-center w-11 h-11 bg-surface-strong text-white border border-surface-strong rounded-sm disabled:opacity-50" :aria-label="player.isPaused ? '播放' : '暂停'">
             <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4 text-medical-500" />
             <template v-else>
                 <Play v-if="player.isPaused" class="w-4 h-4" />
                 <Pause v-else class="w-4 h-4" />
             </template>
         </button>
         <button 
             @click="player.playNext" 
             :disabled="isSkipDisabled" 
             class="flex items-center justify-center w-11 h-11 bg-medical-50 border border-medical-200 rounded-sm text-medical-600 disabled:opacity-50 relative"
             aria-label="切歌"
         >
             <SkipForward class="w-4 h-4" />
             <!-- Badge -->
             <div v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0" class="absolute -top-1 -right-1 bg-accent text-accent-fg text-[11px] font-bold px-1 min-w-[12px] h-3 flex items-center justify-center rounded-sm shadow-sm">
                {{ player.currentVotes }}
             </div>
         </button>
        </div>
      </div>
    </div>

    <!-- PC端：右侧控制区 -->
    <div class="hidden md:flex items-center gap-6 flex-shrink-0">
      
      <!-- 播放控制 -->
      <div class="flex items-center gap-2 border-r border-medical-200 pr-4">
        <!-- 新增：下载按钮 (放在 Shuffle 旁边或者 Next 后面) -->
        <button id="tutorial-download" @click="downloadCurrentMusic" class="flex items-center justify-center w-11 h-11 text-medical-500 hover:text-accent-text transition-colors" title="下载当前歌曲" aria-label="下载当前歌曲">
          <Download class="w-5 h-5" />
        </button>

        <button id="tutorial-random" @click="player.cyclePlayMode" :disabled="player.isPlayModeLocked" :class="['flex items-center justify-center w-11 h-11 text-medical-500', player.isPlayModeLocked ? 'opacity-50 cursor-not-allowed' : '']" :title="modeTitle" :aria-label="modeTitle">
            <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-5 h-5" />
            <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-5 h-5" />
            <Repeat1 v-else-if="player.playMode === 'REPEAT_ONE'" class="w-5 h-5" />
            <Repeat v-else class="w-5 h-5" />
        </button>
        
        <button 
            id="tutorial-pause"
            @click="player.togglePause" 
            :disabled="player.isPauseLocked && !player.isPaused"
            class="w-11 h-11 bg-surface-strong text-white flex items-center justify-center hover:bg-accent hover:text-accent-fg transition-colors chamfer-tl disabled:opacity-50 disabled:hover:bg-surface-strong disabled:hover:text-white disabled:cursor-not-allowed"
            :aria-label="player.isPaused ? '播放' : '暂停'"
        >
            <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4 text-white" />
            <template v-else>
                <Play v-if="player.isPaused" class="w-4 h-4 fill-current" />
                <Pause v-else class="w-4 h-4 fill-current" />
            </template>
        </button>

        <button 
            @click="player.playNext" 
            :disabled="isSkipDisabled" 
            class="flex items-center justify-center w-11 h-11 text-medical-800 hover:text-accent-text transition-colors disabled:opacity-50 disabled:cursor-not-allowed relative group/skip" 
            :title="skipBtnTitle"
            :aria-label="skipBtnTitle"
        >
            <SkipForward class="w-6 h-6 fill-current" />
            <!-- Badge -->
            <div v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0" class="absolute -top-0.5 -right-0.5 bg-accent text-accent-fg text-[11px] font-bold px-1 min-w-[14px] h-3.5 flex items-center justify-center rounded-sm shadow-sm">
                {{ player.currentVotes }}
            </div>
            <!-- Wait Timer Hint -->
            <div v-if="player.isVoteSkipEnabled && canVote && waitTimeLeft > 0" class="absolute -bottom-6 left-1/2 -translate-x-1/2 bg-scrim text-white text-[11px] px-1 py-0.5 rounded-sm opacity-0 group-hover/skip:opacity-100 transition-opacity whitespace-nowrap">
                {{ waitTimeLeft }}s 后可投票
            </div>
        </button>
      </div>

      <!-- 音量控制 -->
      <div class="flex items-center gap-2 group">
        <button @click="toggleMute" class="flex items-center justify-center w-11 h-11 flex-shrink-0 text-medical-500 hover:text-medical-900 transition-colors" :aria-label="ui.volume === 0 ? '取消静音' : '静音'">
          <VolumeX v-if="ui.volume === 0" class="w-5 h-5" />
          <Volume1 v-else-if="ui.volume < 0.5" class="w-5 h-5" />
          <Volume2 v-else class="w-5 h-5" />
        </button>

        <!-- 滑块：原生 range，支持键盘与触摸 -->
        <input
            type="range"
            min="0"
            max="1"
            step="0.01"
            v-model.number="ui.volume"
            class="mp-range w-24 flex-shrink-0"
            aria-label="音量"
        />

        <div class="w-8 text-[11px] font-mono text-medical-500 text-right">
          {{ Math.round(ui.volume * 100) }}%
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { formatDuration } from '../utils/format';
import { Download, ListOrdered, Repeat1, Repeat, Shuffle, SkipForward, Play, Pause, Volume2, Volume1, VolumeX, ExternalLink, Zap, Lock } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { useToast } from '../composables/useToast';
import { positionFromPointer } from '../logic/audioSync';
import { TextDecode } from '../logic/Glitch.js';

const player = usePlayerStore();
const ui = useUiStore();
const progressTrackRef = ref(null);
const isSeeking = ref(false);
const seekPreview = ref(0);
const { info, error } = useToast();

const titleRef = ref(null);
const titleDecode = new TextDecode();
const nowPlaying = computed(() => player.nowPlaying);

// --- D：元数据解码 ---
// 换曲时标题由乱码逐字落定（等宽同位替换，外层已 truncate，故无回流）。
// 首次挂载不抖；reduced-motion 时直接落定（见 TextDecode）。
const titleText = computed(() => (!player.connected
  ? '!CONNECTION LOST!'
  : (nowPlaying.value ? nowPlaying.value.music.name : 'WAITING FOR SIGNAL...')));

watch(titleText, async (text, prev) => {
  if (!prev || text === prev) return;
  await nextTick();
  titleDecode.attach(titleRef.value);
  titleDecode.run(text);
});

onUnmounted(() => titleDecode.cancel());
const likeMarkers = computed(() => nowPlaying.value?.likeMarkers || []);

const modeTitle = computed(() => {
    switch (player.playMode) {
        case 'SEQUENTIAL': return '顺序播放';
        case 'SHUFFLE': return '随机播放';
        case 'REPEAT_ONE': return '单曲循环';
        case 'LIST_LOOP': return '列表循环';
        default: return '';
    }
});

// 投票切歌相关计算
const waitTimeLeft = computed(() => {
    if (!player.isVoteSkipEnabled || !nowPlaying.value) return 0;
    const elapsedSec = Math.floor(player.localProgress / 1000);
    return Math.max(0, player.voteSkipWaitTime - elapsedSec);
});

const isSkipDisabled = computed(() => {
    if (player.isSkipLocked) return true;
    if (player.isVoteSkipEnabled) {
        // 如果是点歌者（不可投票者），允许直接切歌，不受 15s 限制
        if (!canVote.value) return false;
        // 投票模式下：只有在等待时间结束后才能点击
        return waitTimeLeft.value > 0;
    }
    return false;
});

const canVote = computed(() => {
    // 只有非点歌者能投票
    const userStore = useUserStore();
    return nowPlaying.value && nowPlaying.value.enqueuedById !== userStore.userToken;
});

const skipBtnTitle = computed(() => {
    if (player.isSkipLocked) return '切歌功能已被锁定';
    if (player.isVoteSkipEnabled) {
        if (!canVote.value) return '强制切歌';
        if (waitTimeLeft.value > 0) return `${waitTimeLeft.value}s 后可投票`;
        return `投票切歌 (${player.currentVotes}/${player.eligibleUsers})`;
    }
    return 'Next';
});

// 计算进度百分比
const progressPercent = computed(() => {
  if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
  return Math.min(100, (displayProgress.value / nowPlaying.value.music.duration) * 100);
});

const displayProgress = computed(() => isSeeking.value ? seekPreview.value : player.localProgress);

const updateSeekPreview = (event) => {
  if (!progressTrackRef.value || !nowPlaying.value) return;
  const rect = progressTrackRef.value.getBoundingClientRect();
  seekPreview.value = positionFromPointer(event.clientX, rect.left, rect.width, nowPlaying.value.music.duration);
};

const startSeek = (event) => {
  if (!nowPlaying.value || !player.connected) return;
  isSeeking.value = true;
  progressTrackRef.value?.setPointerCapture(event.pointerId);
  updateSeekPreview(event);
};

const moveSeek = (event) => {
  if (isSeeking.value) updateSeekPreview(event);
};

const commitSeek = (event) => {
  if (!isSeeking.value) return;
  updateSeekPreview(event);
  isSeeking.value = false;
  player.seekTo(seekPreview.value);
};

const cancelSeek = () => {
  isSeeking.value = false;
};

const seekByKeyboard = (deltaMs) => {
  if (!nowPlaying.value || !player.connected) return;
  const target = Math.max(0, Math.min(nowPlaying.value.music.duration, player.localProgress + deltaMs));
  player.seekTo(target);
};

// --- 音量逻辑 ---
const lastVolume = ref(0.5);

const toggleMute = () => {
  if (ui.volume > 0) {
    lastVolume.value = ui.volume;
    ui.setVolume(0);
  } else {
    ui.setVolume(lastVolume.value > 0 ? lastVolume.value : 0.5);
  }
};

// --- 下载逻辑 ---
const downloadCurrentMusic = async () => {
  if (!nowPlaying.value) return;
  const music = nowPlaying.value.music;
  info(`Starting download: ${music.name}...`);
  try {
    const response = await fetch(music.url);
    if (!response.ok) throw new Error('Network error');
    const blob = await response.blob();
    const blobUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = `${music.name} - ${music.artists[0]}.mp3`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(blobUrl);
  } catch (e) {
    window.open(music.url, '_blank');
    error('Blob download failed, opening new tab.');
  }
};

// 跳转源页面
// SoundCloud 的数字 ID 没有页面路径：`soundcloud.com/tracks/<id>` 会 301 到排行榜，
// 所以优先用后台解析时带回的 `url`（真实 permalink），拼不出页面的平台退回搜索页。
const openSourcePage = () => {
  if (!nowPlaying.value) return;
  const { platform, id, name, artists, url } = nowPlaying.value.music;
  const direct = typeof url === 'string' && /^https?:\/\//.test(url) ? url : null;
  const templates = {
    netease: `https://music.163.com/#/song?id=${encodeURIComponent(id)}`,
    bilibili: `https://www.bilibili.com/video/${encodeURIComponent(id)}`,
    ytmusic: `https://music.youtube.com/watch?v=${encodeURIComponent(id)}`
  };
  // 老队列没有 url：走服务端 302 反查真实 permalink，而不是跳到搜索页
  const fallback = templates[platform]
      || (platform === 'soundcloud' && id
          ? `/api/track/url?platform=soundcloud&id=${encodeURIComponent(id)}`
          : null);
  window.open(direct || fallback, '_blank', 'noopener');
};
</script>
