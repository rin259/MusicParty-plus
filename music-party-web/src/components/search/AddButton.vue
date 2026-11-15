<template>
  <button
      type="button"
      class="ml-2 flex min-h-11 min-w-11 flex-shrink-0 items-center justify-center p-2 transition-all duration-300"
      :class="buttonClass"
      :disabled="disabled"
      :title="title"
      :aria-label="title"
      @click.stop="$emit('add')"
  >
    <Loader2 v-if="submitting" class="h-5 w-5 animate-spin" />
    <Check v-else-if="done" class="h-5 w-5" />
    <RotateCcw v-else-if="failed" class="h-5 w-5" />
    <PlusCircle v-else class="h-5 w-5" />
  </button>
</template>

<script setup>
import { computed } from 'vue';
import { Check, Loader2, PlusCircle, RotateCcw } from 'lucide-vue-next';

const props = defineProps({
  song: { type: Object, required: true },
  state: { type: Object, default: null },
  queued: { type: Boolean, default: false },
  blocked: { type: Boolean, default: false }
});

defineEmits(['add']);

const submitting = computed(() => props.state?.state === 'submitting');
const failed = computed(() => props.state?.state === 'failed');
// 「已入队」只认队列里的真实存在：歌播完离队后应恢复可点（否则会永久锁死）
const done = computed(() => props.queued);
const disabled = computed(() => props.blocked || submitting.value || done.value);

const buttonClass = computed(() => {
  if (props.blocked) return 'text-medical-400 cursor-not-allowed';
  if (done.value) return 'text-success cursor-default';
  if (submitting.value) return 'text-accent-text cursor-wait';
  if (failed.value) return 'text-danger hover:text-danger';
  return 'text-medical-500 hover:text-accent-text';
});

const title = computed(() => {
  if (props.blocked) return 'B 站视频超过 10 分钟，不支持点歌';
  if (submitting.value) return '正在提交…';
  if (done.value) return '已在队列中';
  if (failed.value) return props.state?.message || '添加失败，点击重试';
  return '加入队列';
});
</script>
