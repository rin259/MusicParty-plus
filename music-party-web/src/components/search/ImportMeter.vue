<template>
  <div v-if="job" class="mt-3 border-t border-medical-200 pt-2 font-mono text-[9px] tracking-[0.14em]">
    <div class="flex items-baseline justify-between gap-2 text-medical-500">
      <span class="text-medical-700">
        {{ finished ? 'INGEST COMPLETE' : 'INGEST' }}
        <span class="ml-1 tabular-nums">{{ done }}/{{ total || '—' }}</span>
      </span>
      <span class="tabular-nums">
        <span class="text-accent-text">+{{ job.added || 0 }}</span>
        <span class="mx-1 text-medical-300">/</span>
        <span>−{{ job.skipped || 0 }}</span>
      </span>
    </div>

    <div class="relative mt-1.5 h-[3px] overflow-hidden bg-medical-200/80">
      <i class="meter-fill" :class="{ 'is-done': finished }" :style="{ transform: `scaleX(${percent})` }"></i>
      <span class="meter-ticks" aria-hidden="true"></span>
    </div>

    <div v-if="finished" class="mt-1 truncate text-medical-500" :title="job.message || ''">
      {{ job.message }}
    </div>
    <div v-else class="mt-1 flex justify-between text-medical-500">
      <span>QUEUED {{ job.added || 0 }}</span>
      <span>{{ platformLabel }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { PLATFORM_LABEL } from '../../logic/searchMerge.js';

const props = defineProps({
  job: { type: Object, default: null }
});

const done = computed(() => props.job?.done || 0);
const total = computed(() => props.job?.total || 0);
const finished = computed(() => !!props.job?.finished);
const percent = computed(() => {
  if (finished.value && !total.value) return 1;
  if (!total.value) return 0.04;
  return Math.max(0.04, Math.min(1, done.value / total.value));
});
const platformLabel = computed(() => PLATFORM_LABEL[props.job?.platform] || props.job?.platform || '');
</script>

<style scoped>
.meter-fill {
  position: absolute;
  inset: 0;
  transform-origin: left center;
  transform: scaleX(0);
  background: rgb(var(--accent) / 0.85);
  transition: transform 400ms cubic-bezier(0.22, 1, 0.36, 1);
}
.meter-fill.is-done { background: rgb(var(--success) / 0.85); }
.meter-ticks {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(90deg, rgb(var(--medical-50)) 0 1px, transparent 1px 4px);
  opacity: 0.9;
}
</style>
