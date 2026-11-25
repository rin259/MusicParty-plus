<template>
  <div id="tutorial-queue" class="h-full flex flex-col bg-surface-1 border-l border-medical-200">
    <div class="queue-list-heading p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center">
      <div class="min-w-0">
        <h2 class="m-0 text-sm font-bold text-medical-900">播放队列 <span class="text-accent-text text-xs">[{{ queue.length }}]</span></h2>
        <div class="mt-0.5 font-mono text-[9px] tracking-[0.14em] text-medical-500 tabular-nums">
          READY {{ readyCount }}
          <span class="mx-1 text-medical-300">|</span>
          WAITING {{ waitingCount }}
          <span class="mx-1 text-medical-300">|</span>
          PREPARING {{ preparingCount }}
          <span v-if="failedCount" ref="failedChip" class="ml-1 text-danger">| FAILED {{ failedCount }}</span>
        </div>
        <!-- 队列可解释性：顺序规则 + 接下来几首还要多久。公平性若不可见，就等于没做。 -->
        <div v-if="queue.length" class="mt-0.5 font-mono text-[9px] tracking-[0.14em] tabular-nums">
          <!-- 顺序模式下队列本身就是轮转序；公平随机模式下的抽取规则同样是轮转 -->
          <template v-if="!player.isShuffle || player.isFairShuffle">
            <span class="text-accent-text">ROUND-ROBIN</span>
            <span class="mx-1 text-medical-300">|</span>
          </template>
          <!-- 只有顺序模式下「接下来几首」才是有意义的预测 -->
          <span v-if="!player.isShuffle" class="text-medical-500">NEXT {{ nextItems.length }} · ~{{ etaLabel }}</span>
        </div>
      </div>
      <button v-if="dismissible" @click="$emit('close')" class="flex items-center justify-center w-11 h-11 -mr-2 text-medical-500 hover:text-medical-900" aria-label="关闭播放队列">
        <X class="w-5 h-5" />
      </button>
    </div>

    <!-- 正在播放：队列只列待播，用户在队列里找不到当前这首，会以为被跳过了。
         注意它必须与下面的列表相互独立——曾经用同一条 v-if/v-else 链，
         结果只要有歌在播，整个队列就被替换掉了。 -->
    <div
        v-if="player.nowPlaying"
        class="now-playing-strip flex shrink-0 items-center gap-2 border-b border-medical-200 bg-accent/5 px-2 py-2"
        aria-current="true"
    >
      <span class="w-1 self-stretch flex-shrink-0 bg-accent" aria-hidden="true"></span>
      <span class="font-mono text-[9px] leading-4 tracking-[0.14em] text-accent-text">
        {{ player.isPaused ? 'PAUSED' : 'ON AIR' }}
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-sm font-bold text-medical-900" :title="player.nowPlaying.music.name">
          {{ player.nowPlaying.music.name }}
        </span>
        <span class="block truncate font-mono text-[10px] text-medical-500">
          {{ player.nowPlaying.music.artists?.[0] }}
          <template v-if="player.nowPlaying.enqueuedByName"> · {{ player.nowPlaying.enqueuedByName }}</template>
        </span>
      </span>
      <span class="flex-shrink-0 font-mono text-[10px] tabular-nums text-medical-500">
        {{ formatDuration(player.localProgress) }} / {{ formatDuration(player.nowPlaying.music.duration) }}
      </span>
    </div>

    <!-- 空态独立判断：没有待播且也没在播时才提示 -->
    <div v-if="queue.length === 0" class="flex flex-1 flex-col items-center justify-center gap-2 px-4 text-center">
      <div class="font-mono text-xs text-medical-500">{{ player.nowPlaying ? 'NO UPCOMING TRACKS' : 'IDLE · QUEUE EMPTY' }}</div>
      <div class="text-[11px] leading-relaxed text-medical-600">
        {{ player.nowPlaying ? '队列里没有待播歌曲，点右上角「添加歌曲」继续点歌。' : '还没有歌曲，点右上角「添加歌曲」开始。' }}
      </div>
    </div>

    <!-- Shuffle Mode View: Grouped by User (ONLY for Fair Shuffle) -->
    <div v-else-if="queue.length > 0 && player.isShuffle && player.isFairShuffle" class="flex-1 overflow-y-auto p-2">
      <!-- Shuffle Indicator -->
      <div class="mb-4 bg-accent/10 border border-accent/20 p-2 flex items-center gap-2 rounded-sm text-accent-text text-xs font-bold">
        <Shuffle class="w-4 h-4" />
        <span>随机播放中</span>
      </div>

      <!-- Top Songs (Always at top) -->
      <div v-if="topItems.length > 0" class="mb-4">
        <div class="text-xs font-mono text-medical-500 mb-2 pl-1">TOP PRIORITY</div>
        <QueueItem
            v-for="(item, idx) in topItems"
            :key="item.queueId"
            :item="item"
            :index="idx"
        />
      </div>

      <!-- User Groups (Drawers) -->
      <div class="space-y-2">
        <div v-for="group in userGroups" :key="group.token" class="border border-medical-100 bg-surface-1">
          <button
              @click="toggleUser(group.token)"
              class="w-full flex items-center justify-between p-3 hover:bg-medical-50 transition-colors"
          >
            <div class="flex items-center gap-3">
              <div class="w-8 h-8 rounded bg-medical-100 flex items-center justify-center text-medical-500">
                <User class="w-4 h-4" />
              </div>
              <div class="text-left">
                <div class="text-sm font-bold text-medical-800">
                  {{ userStore.resolveName(group.token, group.name) }}
                </div>
                <div class="text-[10px] text-medical-500 font-mono">
                  {{ group.items.length }} SONGS
                </div>
              </div>
            </div>
            <component :is="expandedUsers[group.token] ? ChevronDown : ChevronRight" class="w-4 h-4 text-medical-500" />
          </button>

          <!-- Drawer Content -->
          <div v-show="expandedUsers[group.token]" class="bg-medical-50/50 p-2 border-t border-medical-100">
            <QueueItem
                v-for="(item, idx) in group.items"
                :key="item.queueId"
                :item="item"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Normal Mode: Fixed-height native list avoids virtual-scroll range churn on queue sync. -->
    <div v-else-if="queue.length > 0" class="flex-1 min-h-0 overflow-y-auto p-2">
      <QueueItem
          v-for="(item, index) in queue"
          :key="item.queueId"
          :item="item"
          :index="index"
      />
    </div>

    <!-- 最近播放：队列播空后仍能回看刚才放过什么。默认收起，不抢待播列表的空间。 -->
    <div v-if="history.length" class="shrink-0 border-t border-medical-200 bg-medical-50">
      <button
          type="button"
          class="flex w-full min-h-11 items-center justify-between gap-2 px-3 font-mono text-[10px] tracking-[0.12em] text-medical-600 hover:text-medical-900"
          :aria-expanded="historyOpen"
          aria-controls="queue-history-list"
          @click="historyOpen = !historyOpen"
      >
        <span class="flex items-center gap-1.5">
          <History class="h-3.5 w-3.5" aria-hidden="true" />
          最近播放 {{ history.length }}
        </span>
        <ChevronDown v-if="historyOpen" class="h-4 w-4" aria-hidden="true" />
        <ChevronRight v-else class="h-4 w-4" aria-hidden="true" />
      </button>

      <ol v-show="historyOpen" id="queue-history-list" class="m-0 max-h-64 list-none space-y-1 overflow-y-auto px-2 pb-2">
        <li
            v-for="(song, i) in history"
            :key="`${song.platform}:${song.id}:${i}`"
            class="flex items-center gap-2 border border-medical-200 bg-surface-1 px-2 py-1.5"
        >
          <span class="w-4 flex-shrink-0 font-mono text-[10px] text-medical-400">{{ String(i + 1).padStart(2, '0') }}</span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-xs font-bold text-medical-700" :title="song.name">{{ song.name }}</span>
            <span class="block truncate font-mono text-[10px] text-medical-500">
              {{ song.artists?.[0] }} · {{ formatDuration(song.duration) }}
            </span>
          </span>
          <a
              v-if="sourceUrlFor(song)"
              :href="sourceUrlFor(song)"
              target="_blank"
              rel="noopener noreferrer"
              class="flex flex-shrink-0 items-center justify-center px-2 text-medical-500 hover:text-accent-text"
              :title="`在原站打开：${song.name}`"
              :aria-label="`在原站打开 ${song.name}`"
          ><ExternalLink class="h-3.5 w-3.5" aria-hidden="true" /></a>
        </li>
      </ol>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick } from 'vue';
import { usePlayerStore } from '../stores/player';
import { Shuffle, ChevronDown, ChevronRight, User, X, History, ExternalLink } from 'lucide-vue-next';
import { formatDuration } from '../utils/format';
import { useUserStore } from '../stores/user';
import QueueItem from './QueueItem.vue';
import { corruptOnce, globalGate } from '../logic/Glitch.js';
import { sourceUrlFor } from '../logic/sourceUrl';

defineProps({ dismissible: { type: Boolean, default: false } });
defineEmits(['close']);

const player = usePlayerStore();
const queue = computed(() => player.queue);
const userStore = useUserStore();

// WAITING includes the prefetch tail and queued/retrying items, not active work.
const readyCount = computed(() => queue.value.filter((item) => !item.status || item.status === 'READY').length);
const waitingCount = computed(() => queue.value.filter((item) => item.status === 'PENDING').length);
const preparingCount = computed(() => queue.value.filter((item) => item.status === 'DOWNLOADING').length);
const failedCount = computed(() => queue.value.filter((item) => item.status === 'FAILED').length);

// 历史默认收起；有新历史时也不自动展开，免得播完一首就把列表顶走。
const history = computed(() => player.playHistory || []);
const historyOpen = ref(false);

// G2：失败计数增加时，把 FAILED 这个词坏一个字符——失败本身就是「数据损坏」的语义。
// 只坏 1 个字符、90ms，且与其它 glitch 共用全局闸门，不额外占用频率预算。
// 接下来 3 首的实际总时长（按队列真实顺序，GLOBAL_TOP 本就排在最前）
const nextItems = computed(() => queue.value.slice(0, 3));
const etaLabel = computed(() => {
  const ms = nextItems.value.reduce((sum, item) => sum + (item.music?.duration || 0), 0);
  if (!ms) return '—:—';
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
});

const failedChip = ref(null);
watch(failedCount, async (next, prev) => {
  if (!next || next <= (prev || 0)) return;
  await nextTick();
  corruptOnce(failedChip.value, globalGate, { chars: 1 });
});

// --- Shuffle Mode Logic ---

const topItems = computed(() => {
  return queue.value.filter(item => item.priority === 'GLOBAL_TOP');
});

const userGroups = computed(() => {
  const normalItems = queue.value.filter(item => item.priority !== 'GLOBAL_TOP');
  const groupsMap = new Map();

  normalItems.forEach(item => {
    const token = item.enqueuedBy.token;
    if (!groupsMap.has(token)) {
      groupsMap.set(token, {
        token: token,
        name: item.enqueuedBy.name,
        items: []
      });
    }
    groupsMap.get(token).items.push(item);
  });

  // 对每个组内的歌曲进行排序：个人置顶 (USER_TOP) 放在最前面
  return Array.from(groupsMap.values()).map(group => ({
    ...group,
    items: [...group.items].sort((a, b) => {
      const aIsUserTop = a.priority === 'USER_TOP';
      const bIsUserTop = b.priority === 'USER_TOP';
      if (aIsUserTop && !bIsUserTop) return -1;
      if (!aIsUserTop && bIsUserTop) return 1;
      return 0; // 保持原有相对顺序
    })
  }));
});

const expandedUsers = ref({});

const toggleUser = (token) => {
  // Use simple boolean toggle. Need to ensure reactivity works.
  // Directly setting property on object might require spread if it was not initialized.
  expandedUsers.value = {
    ...expandedUsers.value,
    [token]: !expandedUsers.value[token]
  };
};
</script>
