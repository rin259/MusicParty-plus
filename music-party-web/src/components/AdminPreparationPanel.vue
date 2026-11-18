<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { Activity } from 'lucide-vue-next';
import { adminApi } from '../api/admin';

const props = defineProps({ adminPassword: { type: String, required: true } });
const stats = ref(null);
const error = ref('');
let timer = null;

async function load() {
  try {
    stats.value = await adminApi.preparation(props.adminPassword);
    error.value = '';
  } catch {
    error.value = '无法读取准备队列状态';
  }
}

const count = (value) => (value ?? 0).toString();
const sources = () => Object.entries(stats.value?.activeBySource || {});

onMounted(() => {
  load();
  timer = setInterval(load, 5000);
});
onUnmounted(() => clearInterval(timer));
</script>

<template>
  <section aria-labelledby="admin-preparation-heading" class="overflow-hidden border border-medical-200 bg-surface-1 shadow-sm chamfer-br">
    <header class="flex items-center justify-between gap-2 bg-surface-strong p-3 text-white">
      <h3 id="admin-preparation-heading" class="m-0 flex items-center gap-2 text-xs font-bold">
        <Activity class="h-4 w-4" aria-hidden="true" /> 准备队列
      </h3>
      <span v-if="stats" class="font-mono text-[10px] tracking-[0.12em] text-white/80">
        ACTIVE {{ count(stats.active) }} · WAITING {{ count(stats.waiting) }}
      </span>
    </header>

    <p v-if="error" role="alert" class="p-4 text-sm text-danger">{{ error }}</p>
    <div v-else-if="stats" class="space-y-3 p-4">
      <dl class="grid grid-cols-2 gap-2 font-mono text-[10px] tracking-[0.1em] text-medical-600 sm:grid-cols-4">
        <div><dt class="text-medical-500">等槽位</dt><dd class="m-0 tabular-nums text-medical-900">{{ count(stats.waitingForLane) }}</dd></div>
        <div><dt class="text-medical-500">重试退避</dt><dd class="m-0 tabular-nums text-medical-900">{{ count(stats.retryBackoff) }}</dd></div>
        <div><dt class="text-medical-500">源冷却</dt><dd class="m-0 tabular-nums text-medical-900">{{ count(stats.sourceCooldown) }}</dd></div>
        <div><dt class="text-medical-500">满员推迟</dt><dd class="m-0 tabular-nums text-medical-900">{{ count(stats.admissionDeferrals) }}</dd></div>
      </dl>

      <p class="font-mono text-[10px] tracking-[0.1em] text-medical-500">
        ACTIVE_BY_SOURCE
        <span v-if="!sources().length">—</span>
        <span v-for="[source, n] in sources()" :key="source" class="ml-2 text-medical-800">{{ source }} {{ n }}</span>
      </p>

      <div v-if="stats.recentTasks?.length" class="max-h-40 overflow-y-auto">
        <table class="w-full border-collapse font-mono text-[10px] tabular-nums">
          <thead>
            <tr class="text-left text-medical-500">
              <th class="py-1 pr-2 font-normal">曲目</th>
              <th class="py-1 pr-2 font-normal">阶段</th>
              <th class="py-1 pr-2 font-normal text-right">等待</th>
              <th class="py-1 pr-2 font-normal text-right">取源</th>
              <th class="py-1 font-normal text-right">传输</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in stats.recentTasks.slice(0, 10)" :key="task.id" class="border-t border-medical-100">
              <td class="max-w-[9rem] truncate py-1 pr-2 text-medical-800" :title="task.id">{{ task.id }}</td>
              <td class="py-1 pr-2" :class="task.status === 'FAILED' ? 'text-danger' : 'text-medical-600'">{{ task.phase }}</td>
              <td class="py-1 pr-2 text-right text-medical-600">{{ task.queuedMs }}</td>
              <td class="py-1 pr-2 text-right text-medical-600">{{ task.resolveMs }}</td>
              <td class="py-1 text-right text-medical-600">{{ task.transferMs }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="font-mono text-[10px] text-medical-500">NO TASKS YET</p>
    </div>
    <p v-else role="status" class="p-4 text-sm text-medical-600">正在读取准备队列…</p>
  </section>
</template>