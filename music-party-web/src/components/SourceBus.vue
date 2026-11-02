<template>
  <div ref="rootEl" class="select-none" role="group" aria-label="音乐源状态">
    <div class="mb-1 hidden md:flex items-baseline justify-between font-mono text-[9px] tracking-[0.2em] text-medical-500">
      <span>SOURCE_BUS</span>
      <span class="tabular-nums">
        {{ done }}/{{ total }}
        <span class="mx-1 text-medical-300">|</span>
        {{ elapsedLabel }}
      </span>
    </div>

    <!-- 手机上默认折叠：这里只是监测信息，不该抢走搜索结果的位置 -->
    <button
        type="button"
        class="md:hidden w-full flex items-center justify-between px-2 py-1.5 border border-medical-200 bg-medical-50 font-mono text-[9px] tracking-[0.12em] text-medical-500 tabular-nums"
        :aria-expanded="mobileExpanded"
        @click="mobileExpanded = !mobileExpanded"
    >
      <span>SOURCE_BUS · {{ done }}/{{ total }} · {{ elapsedLabel }}</span>
      <span class="text-accent-text">{{ mobileExpanded ? '收起' : '详情' }}</span>
    </button>

    <div class="grid-cols-2 gap-1 md:grid-cols-4"
         :class="mobileExpanded ? 'grid' : 'hidden md:grid'">
      <button
          v-for="id in platforms"
          :key="id"
          type="button"
          class="group relative overflow-hidden border px-2 pb-2 pt-1.5 text-left transition-colors duration-300"
          :class="active === id
            ? 'border-accent/60 bg-accent/[0.045]'
            : 'border-medical-200 bg-medical-50 hover:border-medical-300'"
          :aria-pressed="active === id"
          :title="titleFor(id)"
          :data-src="id"
          @click="$emit('select', id)"
      >
        <div class="flex items-center gap-1.5">
          <span class="bus-dot" :class="dotClass(id)" aria-hidden="true"></span>
          <span class="flex-1 truncate font-mono text-[9px] tracking-[0.14em] text-medical-700">{{ labelFor(id) }}</span>
          <span class="font-mono text-[9px] tabular-nums" :class="valueClass(id)">{{ latencyLabel(id) }}</span>
        </div>

        <!-- 时延标尺：0–6s。细刻度叠层，填充为平滑 scaleX，不逐帧重排。 -->
        <span class="relative mt-1.5 block h-[3px] overflow-hidden bg-medical-200/80">
          <i class="bus-fill" :class="fillClass(id)" :style="{ transform: `scaleX(${ratio(id)})` }"></i>
          <span class="bus-ticks" aria-hidden="true"></span>
        </span>

        <span class="mt-1 flex items-center justify-between font-mono text-[8px] leading-none text-medical-500">
          <span class="bus-meta truncate">{{ metaFor(id) }}</span>
          <span v-if="isSlow(id)" class="text-accent-text">SLOW</span>
        </span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { PLATFORM_LABEL } from '../logic/searchMerge.js';
import { corruptOnce, globalGate } from '../logic/Glitch.js';

const props = defineProps({
  platforms: { type: Array, required: true },
  sources: { type: Object, default: () => ({}) },
  health: { type: Object, default: () => ({}) },
  active: { type: String, default: 'all' },
  probing: { type: Boolean, default: false },
  summary: { type: Object, default: () => ({}) }
});

defineEmits(['select']);

const mobileExpanded = ref(false);

const SHORT = {
  netease: 'NETEASE',
  bilibili: 'BILIBILI',
  soundcloud: 'SOUNDCLOUD',
  ytmusic: 'YOUTUBE'
};

// 3s 作为满格：常见的 0.1–1s 差异才看得见；超过 3s 直接视为「慢」
const BUDGET_MS = 3000;

const done = computed(() => props.summary?.done || 0);
const total = computed(() => props.summary?.sources || props.platforms.length);
const elapsedLabel = computed(() => {
  const ms = props.summary?.elapsedMs || 0;
  return ms > 0 ? `${Math.round(ms)}ms` : 'IDLE';
});

function stateOf(id) {
  const searching = props.sources?.[id];
  if (searching && searching.state && searching.state !== 'idle') {
    return { ...searching, origin: 'search' };
  }
  const probed = props.health?.[id];
  if (probed) {
    if (!probed.enabled) return { state: 'disabled', origin: 'health' };
    return {
      state: probed.ok ? 'ok' : 'error',
      latencyMs: probed.latencyMs,
      count: probed.sampleCount,
      slow: probed.latencyMs > 2500,
      origin: 'health'
    };
  }
  if (props.probing) return { state: 'loading', origin: 'health', slow: false };
  return { state: 'idle', origin: 'none' };
}

const labelFor = (id) => SHORT[id] || PLATFORM_LABEL[id] || id.toUpperCase();

function latencyLabel(id) {
  const s = stateOf(id);
  if (s.state === 'loading') return '···';
  if (s.state === 'idle') return '—';
  if (s.state === 'disabled') return 'OFF';
  if (s.latencyMs == null) return '—';
  return `${Math.round(s.latencyMs)}ms`;
}

function metaFor(id) {
  const s = stateOf(id);
  if (s.state === 'disabled') return 'DISABLED';
  if (s.state === 'loading') return s.slow ? 'WAITING' : 'PROBING';
  if (s.state === 'timeout') return 'TIMEOUT';
  if (s.state === 'error') return 'UNREACHABLE';
  if (s.state === 'ok') {
    if (s.origin !== 'search') return 'READY';
    return `${s.count ?? 0} HITS${s.fromCache ? ' · CACHED' : ''}`;
  }
  return 'IDLE';
}

const rootEl = ref(null);

// G2：某个源刚进入错误/超时，就把它的状态词（UNREACHABLE / TIMEOUT）坏一个字符。
// 只在真实故障时触发，且与其它 glitch 共用全局闸门。
const errorIds = computed(() => props.platforms.filter((id) => {
    const st = stateOf(id).state;
    return st === 'error' || st === 'timeout';
}));
watch(() => errorIds.value.join(','), (next, prev) => {
    if (!next) return;
    const before = new Set((prev || '').split(',').filter(Boolean));
    const fresh = next.split(',').find((id) => !before.has(id));
    if (!fresh) return;
    const el = rootEl.value?.querySelector(`[data-src="${fresh}"] .bus-meta`);
    corruptOnce(el, globalGate, { chars: 1 });
});

function isSlow(id) {
  const s = stateOf(id);
  return s.state === 'loading' ? !!s.slow : (s.slow || (s.latencyMs || 0) > 2500);
}

function ratio(id) {
  const s = stateOf(id);
  const ms = s.latencyMs || 0;
  if (!ms) return 0;
  return Math.max(0.04, Math.min(1, ms / BUDGET_MS));
}

const dotClass = (id) => `is-${stateOf(id).state}`;
const fillClass = (id) => {
  const s = stateOf(id);
  if (s.state === 'error' || s.state === 'timeout') return 'is-error';
  if (s.state === 'disabled' || s.state === 'idle') return 'is-idle';
  return 'is-ok';
};
const valueClass = (id) => {
  const s = stateOf(id);
  if (s.state === 'error' || s.state === 'timeout') return 'text-danger';
  if (s.state === 'ok') return 'text-medical-800';
  return 'text-medical-500';
};

function titleFor(id) {
  const s = stateOf(id);
  if (s.error) return `${labelFor(id)}: ${s.error}`;
  if (s.state === 'ok') return `${labelFor(id)}: ${s.count ?? 0} 条结果 / ${Math.round(s.latencyMs || 0)}ms`;
  return `${labelFor(id)}: ${metaFor(id)}`;
}
</script>

<style scoped>
.bus-dot {
  width: 5px;
  height: 5px;
  flex: none;
  background: rgb(var(--medical-300));
  transition: background-color 300ms cubic-bezier(0.22, 1, 0.36, 1),
              box-shadow 300ms cubic-bezier(0.22, 1, 0.36, 1);
}
.bus-dot.is-ok { background: rgb(var(--accent)); }
.bus-dot.is-loading { background: rgb(var(--accent)); animation: bus-blink 1.1s steps(1, end) infinite; }
.bus-dot.is-error, .bus-dot.is-timeout { background: rgb(var(--danger)); }
.bus-dot.is-disabled { background: transparent; box-shadow: inset 0 0 0 1px rgb(var(--medical-300)); }
.bus-dot.is-idle { background: rgb(var(--medical-300)); }

@keyframes bus-blink { 50% { opacity: 0.2; } }

.bus-fill {
  position: absolute;
  inset: 0;
  transform-origin: left center;
  transform: scaleX(0);
  background: rgb(var(--accent) / 0.85);
  transition: transform 620ms cubic-bezier(0.22, 1, 0.36, 1),
              background-color 300ms linear;
}
.bus-fill.is-error { background: rgb(var(--danger) / 0.8); }
.bus-fill.is-idle { background: rgb(var(--medical-300)); }

/* 24 格刻度：静态结构，不参与动画 */
.bus-ticks {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
      90deg,
      rgb(var(--medical-50)) 0 1px,
      transparent 1px 4px
  );
  opacity: 0.9;
  pointer-events: none;
}

</style>
