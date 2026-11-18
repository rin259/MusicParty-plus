<template>
  <div
      class="group relative flex items-center gap-2 p-2 bg-surface-1 border border-medical-200 hover:border-medical-300 transition-colors mb-2 h-14"
  >
    <!-- 序号 -->
    <div v-if="index !== undefined" class="w-6 text-center font-mono text-xs text-medical-500">{{ String(index + 1).padStart(2, '0') }}</div>
    <div v-else class="w-6 text-center font-mono text-xs text-medical-500">#</div>

    <div class="flex-1 min-w-0 pr-[6.5rem] md:pr-0">
      <div class="text-sm font-bold text-medical-800 truncate">{{ item.music.name }}</div>
      <div class="flex justify-between items-center">
        <div v-if="!item.status || item.status === 'READY'" class="flex min-w-0 items-center gap-1.5">
          <span class="truncate text-xs text-medical-500">{{ item.music.artists[0] }}</span>
          <span class="flex-shrink-0 border border-medical-200 bg-medical-50 px-1 font-mono text-[9px] leading-4 text-medical-500">{{ platformLabel }}</span>
          <span class="flex-shrink-0 font-mono text-[9px] tabular-nums text-medical-500">{{ durationLabel }}</span>
        </div>

        <div v-else-if="item.status === 'DOWNLOADING' || item.status === 'PENDING'" class="flex min-w-0 items-center gap-1.5 font-mono font-bold" :class="preparing ? 'text-accent-text' : 'text-medical-500'">
          <SignalLight :active="preparing" :tick="preparing" />
          <span class="truncate text-[10px] tracking-[0.12em]">{{ preparing ? 'PREPARING' : 'WAITING' }}</span>
          <SignalRail :active="preparing" class="w-[26px] shrink-0 max-[359px]:hidden" />
        </div>

        <!-- 失败状态 -->
        <div v-else-if="item.status === 'FAILED'" class="text-xs font-mono font-bold text-danger">
          SOURCE UNAVAILABLE
        </div>
        <div class="text-[11px] text-medical-500 bg-medical-50 px-1 border border-medical-200">
          {{ userStore.resolveName(item.enqueuedBy.token, item.enqueuedBy.name) }}
        </div>
      </div>
    </div>

    <!-- 操作遮罩 -->
    <div v-if="!userStore.isGuest" class="queue-actions absolute inset-y-0 right-0 bg-surface-1 px-1 flex items-center gap-1 opacity-100 md:opacity-0 md:group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
      <button @click="player.topSong(item.queueId)" title="置顶" aria-label="置顶这首歌" class="flex items-center justify-center w-11 h-11 text-medical-600 hover:text-accent-text"><ArrowUpToLine class="w-4 h-4"/></button>
      <button @click="player.removeSong(item.queueId)" title="移除" aria-label="从队列移除这首歌" class="flex items-center justify-center w-11 h-11 text-medical-600 hover:text-danger"><Trash2 class="w-4 h-4"/></button>
    </div>

    <!-- 标记置顶的歌曲 -->
    <div v-if="item.priority === 'GLOBAL_TOP'" class="absolute top-0 right-0 w-2 h-2 bg-accent" title="全局置顶"></div>
    <div v-else-if="item.priority === 'USER_TOP'" class="absolute top-0 right-0 w-3 h-3 bg-accent [clip-path:polygon(100%_0,0_0,100%_100%)]" title="个人置顶"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { Trash2, ArrowUpToLine } from 'lucide-vue-next';
import { formatDuration, PLATFORM_LABEL } from '../logic/searchMerge.js';
import SignalLight from './SignalLight.vue';
import SignalRail from './SignalRail.vue';

const props = defineProps({
  item: {
    type: Object,
    required: true
  },
  index: {
    type: Number,
    default: undefined
  }
});

const player = usePlayerStore();
const userStore = useUserStore();
const preparing = computed(() => props.item.status === 'DOWNLOADING');

const platformLabel = computed(() => PLATFORM_LABEL[props.item.music.platform] || props.item.music.platform || '—');
const durationLabel = computed(() => {
  const ms = props.item.music.duration || 0;
  return ms > 0 ? formatDuration(ms) : '—:—';
});
</script>

<style scoped>
/* 触屏设备没有 hover：队列操作必须常显，否则置顶/移除根本不可达 */
@media (hover: none) {
  .queue-actions { opacity: 1 !important; }
}

</style>
