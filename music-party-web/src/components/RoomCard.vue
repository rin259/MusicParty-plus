<template>
  <div class="group flex items-stretch border border-medical-200 bg-surface-1 transition-all hover:border-accent hover:shadow-sm">
    <button
        type="button"
        @click="$emit('select', room)"
        :disabled="disabled"
        class="flex min-w-0 flex-1 items-stretch gap-3 p-3 text-left transition-colors hover:bg-medical-50 disabled:opacity-60"
    >
      <span class="w-1 flex-shrink-0" :class="room.mainRoom ? 'bg-accent' : 'bg-medical-200'"></span>
      <span class="min-w-0 flex-1">
        <span class="flex items-center gap-2">
          <span class="truncate text-sm font-bold text-medical-900">{{ room.name }}</span>
          <span v-if="room.owner" class="flex-shrink-0 border border-accent/40 px-1 font-mono text-[9px] leading-4 text-accent-text">OWNER</span>
          <span v-if="!room.listed" class="flex-shrink-0 border border-medical-300 bg-medical-100 px-1 font-mono text-[9px] leading-4 text-medical-500">LINK</span>
          <Lock v-if="room.hasPassword" class="h-3.5 w-3.5 flex-shrink-0 text-medical-500" aria-label="需要密码" />
        </span>
        <span class="mt-0.5 block truncate font-mono text-[10px] text-medical-500">
          {{ room.id }} · {{ room.onlineCount }} ONLINE
        </span>
      </span>
      <ChevronRight class="h-4 w-4 flex-shrink-0 self-center text-medical-400 transition-colors group-hover:text-accent-text" />
    </button>

    <button
        v-if="room.owner && !room.mainRoom"
        type="button"
        @click.stop="$emit('edit', room)"
        :disabled="disabled"
        class="flex w-12 flex-shrink-0 items-center justify-center border-l border-medical-200 text-medical-500 transition-colors hover:bg-medical-100 hover:text-medical-900 disabled:opacity-50"
        aria-label="编辑房间"
        title="编辑房间"
    >
      <Pencil class="h-4 w-4" />
    </button>

    <button
        v-if="room.owner && !room.mainRoom"
        type="button"
        @click.stop="$emit('delete', room)"
        :disabled="disabled"
        class="flex w-12 flex-shrink-0 items-center justify-center border-l border-medical-200 text-medical-500 transition-colors hover:bg-danger-bg hover:text-danger disabled:opacity-50"
        aria-label="关闭房间"
        title="关闭房间"
    >
      <Trash2 class="h-4 w-4" />
    </button>
  </div>
</template>

<script setup>
import { ChevronRight, Lock, Pencil, Trash2 } from 'lucide-vue-next';

defineProps({
  room: { type: Object, required: true },
  disabled: { type: Boolean, default: false }
});
defineEmits(['select', 'edit', 'delete']);
</script>
