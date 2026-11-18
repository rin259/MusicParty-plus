<template>
  <div v-if="roomStore.loading && !roomStore.rooms.length" role="status" class="border border-medical-200 bg-surface-1 p-6 text-center font-mono text-[10px] tracking-[0.16em] text-medical-500">
    LOADING ROOMS...
  </div>

  <div v-else class="space-y-5">
    <section v-for="section in sections" :key="section.key">
      <div class="mb-2 flex items-center justify-between">
        <h3 class="m-0 font-mono text-[10px] tracking-[0.18em] text-medical-500">{{ section.title }}</h3>
        <span class="font-mono text-[10px] text-medical-500">{{ section.rooms.length }}</span>
      </div>

      <div v-if="section.rooms.length" class="space-y-2">
        <RoomCard
            v-for="room in section.rooms"
            :key="room.id"
            :room="room"
            :disabled="busy"
            :current="room.id === currentRoomId"
            :manage="manage"
            @select="$emit('select', room)"
            @edit="$emit('edit', room)"
            @delete="$emit('delete', room)"
        />
      </div>

      <div v-else class="border border-dashed border-medical-300 bg-medical-50 p-5 text-center">
        <div class="font-mono text-[10px] tracking-[0.16em] text-medical-500">NO ROOMS YET</div>
        <div v-if="manage" class="mt-1.5 text-xs text-medical-600">
          {{ section.key === 'public' ? '还没有其他人公开的房间。稍后刷新，或直接用房间码加入。' : '还没有自己的房间。在右侧创建一个，或用房间码加入。' }}
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRoomStore } from '../stores/room';
import RoomCard from './RoomCard.vue';

defineProps({
  busy: Boolean,
  manage: Boolean,
  currentRoomId: String
});
defineEmits(['select', 'edit', 'delete']);

const roomStore = useRoomStore();
const sections = computed(() => [
  { key: 'mine', title: 'MY ROOMS', rooms: roomStore.myRooms },
  { key: 'public', title: 'OTHER ROOMS', rooms: roomStore.publicRooms }
]);
</script>
