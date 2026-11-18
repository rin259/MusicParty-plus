<template>
  <div
      class="group flex items-stretch border bg-surface-1 transition-all"
      :class="current ? 'border-accent/50' : 'border-medical-200 hover:border-accent hover:shadow-sm'"
  >
    <button
        type="button"
        @click="$emit('select', room)"
        :disabled="disabled || current"
        :aria-current="current ? 'true' : undefined"
        class="flex min-w-0 flex-1 items-stretch gap-3 p-3 text-left transition-colors"
        :class="current ? 'bg-medical-50' : 'hover:bg-medical-50 disabled:opacity-60'"
    >
      <span class="w-1 flex-shrink-0" :class="room.mainRoom ? 'bg-accent' : 'bg-medical-200'"></span>
      <span class="min-w-0 flex-1">
        <span class="flex items-center gap-2">
          <span class="truncate text-sm font-bold text-medical-900" :title="room.name">{{ room.name }}</span>
          <span v-if="room.mainRoom" class="flex-shrink-0 border border-medical-300 px-1 font-mono text-[9px] leading-4 text-medical-500">DEFAULT</span>
          <span v-else-if="room.owner" class="flex-shrink-0 border border-accent/40 px-1 font-mono text-[9px] leading-4 text-accent-text">OWNER</span>
          <span v-if="!room.listed" class="flex-shrink-0 border border-medical-300 bg-medical-100 px-1 font-mono text-[9px] leading-4 text-medical-500" title="仅通过房间码/邀请链接可见">LINK ONLY</span>
          <Lock v-if="room.hasPassword" class="h-3.5 w-3.5 flex-shrink-0 text-medical-500" aria-label="需要密码" title="需要密码" />
        </span>
        <span class="mt-0.5 block truncate font-mono text-[10px] text-medical-500">
          CODE {{ room.id }} · {{ room.onlineCount }} ONLINE
        </span>
      </span>
      <span v-if="current" class="shrink-0 self-center text-[10px] text-accent-text">当前房间</span>
      <ChevronRight v-else class="h-4 w-4 flex-shrink-0 self-center text-medical-400 transition-colors group-hover:text-accent-text" aria-hidden="true" />
    </button>

    <button
        v-if="manage && room.owner && !room.mainRoom"
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
        v-if="manage && room.owner && !room.mainRoom"
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
  disabled: Boolean,
  current: Boolean,
  manage: Boolean
});
defineEmits(['select', 'edit', 'delete']);
</script>
