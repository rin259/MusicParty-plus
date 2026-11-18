<template>
  <div v-if="roomStore.loading && !roomStore.rooms.length" role="status" class="border border-medical-200 bg-surface-1 p-6 text-center font-mono text-[10px] tracking-[0.16em] text-medical-500">
    LOADING ROOMS...
  </div>

  <div v-else class="space-y-5">
    <section v-for="section in sections" :key="section.key">
      <div class="mb-2 flex items-center justify-between">
        <h3 class="m-0 font-mono text-[10px] tracking-[0.18em] text-medical-500">{{ section.title }}</h3>
        <span class="font-mono text-[10px] text-medical-500">{{ section.rooms.length }}<template v-if="section.total > section.rooms.length"> / {{ section.total }}</template></span>
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

      <div v-if="section.total" class="mt-2 flex items-center gap-2">
        <button
            v-if="hasMore(section)"
            type="button"
            class="min-h-11 flex-1 border border-medical-200 bg-surface-1 font-mono text-[10px] tracking-[0.12em] text-medical-600 hover:border-accent hover:text-accent-text"
            :aria-label="`展开更多${section.title === 'MY ROOMS' ? '我的' : '其他'}房间`"
            @click="loadMore(section)"
        >LOAD MORE · +{{ stepCount(section) }}</button>
        <button
            v-else-if="section.total > PAGE_SIZE"
            type="button"
            class="min-h-11 flex-1 border border-medical-200 bg-surface-1 font-mono text-[10px] tracking-[0.12em] text-medical-500 hover:border-accent hover:text-accent-text"
            @click="showLess(section)"
        >SHOW LESS</button>
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
import { computed, reactive, watch } from 'vue';
import { useRoomStore } from '../stores/room';
import RoomCard from './RoomCard.vue';

defineProps({
  busy: Boolean,
  manage: Boolean,
  currentRoomId: String
});
defineEmits(['select', 'edit', 'delete']);

const roomStore = useRoomStore();

// 房间目录按页展开：landing 和切换弹窗都不因为房间变多而变成一整页滚动。
const PAGE_SIZE = 6;
const visibleCounts = reactive({ mine: PAGE_SIZE, public: PAGE_SIZE });
const sections = computed(() => [
  { key: 'mine', title: 'MY ROOMS', all: roomStore.myRooms },
  { key: 'public', title: 'OTHER ROOMS', all: roomStore.publicRooms }
].map((section) => ({
  ...section,
  rooms: section.all.slice(0, visibleCounts[section.key]),
  total: section.all.length
})));
const hasMore = (section) => section.total > section.rooms.length;
const stepCount = (section) => Math.min(PAGE_SIZE, section.total - section.rooms.length);
const loadMore = (section) => { visibleCounts[section.key] += PAGE_SIZE; };
const showLess = (section) => { visibleCounts[section.key] = PAGE_SIZE; };
watch(() => [roomStore.myRooms.length, roomStore.publicRooms.length], () => {
  visibleCounts.mine = PAGE_SIZE;
  visibleCounts.public = PAGE_SIZE;
});
</script>
