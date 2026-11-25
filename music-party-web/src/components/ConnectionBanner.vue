<template>
  <Transition name="conn-banner">
    <div
        v-if="visible"
        role="status"
        aria-live="polite"
        class="pointer-events-none fixed inset-x-0 top-0 z-[85] flex justify-center pt-[max(0.5rem,env(safe-area-inset-top))]"
    >
      <div
          class="pointer-events-auto flex items-center gap-2 border px-3 py-1.5 font-mono text-[10px] tracking-[0.14em] shadow-lg"
          :class="reconnecting
            ? 'border-medical-300 bg-surface-1 text-medical-600'
            : 'border-danger/50 bg-danger-bg text-danger'"
      >
        <SignalLight :fault="!reconnecting" :active="reconnecting" :tick="reconnecting" />
        <span>{{ reconnecting ? 'RECONNECTING…' : 'CONNECTION LOST' }}</span>
        <span v-if="!reconnecting" class="text-medical-500">{{ offlineSeconds }}s</span>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue';
import SignalLight from './SignalLight.vue';
import { usePlayerStore } from '../stores/player';
import { useRoomStore } from '../stores/room';

const player = usePlayerStore();
const roomStore = useRoomStore();

// Only announce a loss that outlives a room switch or a brief blip; flashing the
// banner on every reconnect attempt is worse than saying nothing.
const GRACE_MS = 3000;
const offlineSince = ref(0);
const now = ref(Date.now());
let tickTimer = null;

const lost = computed(() => !player.connected);
const elapsed = computed(() => (offlineSince.value ? now.value - offlineSince.value : 0));
const visible = computed(() => lost.value && !!roomStore.entered && elapsed.value >= GRACE_MS);
// While the socket client is still retrying, say so; once it has stopped, say it
// plainly instead of implying progress that is not happening.
const reconnecting = computed(() => lost.value && elapsed.value < GRACE_MS * 6);
const offlineSeconds = computed(() => Math.floor(elapsed.value / 1000));

function stopClock() {
  if (tickTimer) clearInterval(tickTimer);
  tickTimer = null;
}

watch(lost, (isLost) => {
  if (isLost) {
    offlineSince.value = Date.now();
    now.value = Date.now();
    if (!tickTimer) tickTimer = setInterval(() => { now.value = Date.now(); }, 1000);
  } else {
    offlineSince.value = 0;
    stopClock();
  }
}, { immediate: true });

// Leaving the room ends the story; the landing page has its own error line.
watch(() => roomStore.entered, (entered) => {
  if (!entered) offlineSince.value = 0;
});

onUnmounted(stopClock);
</script>

<style scoped>
.conn-banner-enter-active,
.conn-banner-leave-active { transition: opacity 180ms ease, transform 180ms ease; }
.conn-banner-enter-from,
.conn-banner-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
