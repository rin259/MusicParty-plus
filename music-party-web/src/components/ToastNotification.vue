<template>
  <TransitionGroup
      tag="div"
      enter-active-class="transition duration-300 ease-soft"
      enter-from-class="translate-y-2 opacity-0 scale-95"
      enter-to-class="translate-y-0 opacity-100 scale-100"
      leave-active-class="transition duration-200 ease-soft"
      leave-from-class="translate-y-0 opacity-100 scale-100"
      leave-to-class="translate-y-2 opacity-0 scale-95"
      class="fixed top-4 md:top-10 left-1/2 -translate-x-1/2 z-[100] flex w-[calc(100vw-2rem)] max-w-[380px] flex-col items-center gap-2 pointer-events-none"
      role="status"
      aria-live="polite"
      aria-atomic="false"
  >
    <div
        v-for="toast in toastStore.toasts"
        :key="toast.id"
        class="w-full bg-surface-1 text-medical-800 px-4 md:px-6 py-3 shadow-xl backdrop-blur-sm flex items-center gap-3 border border-t-2 border-medical-200 pointer-events-auto cursor-pointer"
        :class="getTypeClass(toast.type)"
        role="button"
        tabindex="0"
        :aria-label="`关闭通知：${toast.title}`"
        @click="toastStore.remove(toast.id)"
        @keydown.enter.prevent="toastStore.remove(toast.id)"
        @keydown.space.prevent="toastStore.remove(toast.id)"
    >
      <!-- 内层以 count 为 key：计数递增时重挂节点，重放 bump 动画 -->
      <div
          :key="toast.count || 0"
          class="flex items-center gap-3 flex-1 min-w-0"
          :class="toast.stack === 'like' && (toast.count || 0) > 1 ? 'toast-bump' : ''"
      >
        <component :is="getIcon(toast.type)" class="w-5 h-5 flex-shrink-0" :class="getIconClass(toast.type)" aria-hidden="true" />
        <div class="flex-1 min-w-0">
          <div class="font-bold text-sm font-mono flex items-center gap-2">
            <span class="truncate">{{ toast.title }}</span>
            <span
                v-if="toast.count > 1"
                class="flex-shrink-0 px-1.5 py-0.5 rounded-full bg-accent/10 border border-accent/30 text-accent-text text-[10px] font-bold leading-none"
            >×{{ toast.count }}</span>
          </div>
          <div v-if="toast.message" class="text-xs text-medical-500 truncate">{{ toast.message }}</div>
        </div>
      </div>
    </div>
  </TransitionGroup>
</template>

<script setup>
import { useToastStore } from '../stores/toast';
import { CheckCircle, AlertCircle, Info, AlertTriangle } from 'lucide-vue-next';

const toastStore = useToastStore();

const getTypeClass = (type) => {
  switch (type) {
    case 'success': return 'border-t-success';
    case 'error': return 'border-t-danger';
    case 'warning': case 'warn': return 'border-t-warn';
    default: return 'border-t-medical-400';
  }
};

const getIconClass = (type) => {
  switch (type) {
    case 'success': return 'text-success';
    case 'error': return 'text-danger';
    case 'warning': case 'warn': return 'text-warn';
    default: return 'text-medical-500';
  }
};

const getIcon = (type) => {
  switch (type) {
    case 'success': return CheckCircle;
    case 'error': return AlertCircle;
    case 'warning': case 'warn': return AlertTriangle;
    default: return Info;
  }
};
</script>
