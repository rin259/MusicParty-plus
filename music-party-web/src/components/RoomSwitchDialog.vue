<template>
  <dialog
      ref="dialog"
      aria-labelledby="room-switch-title"
      class="room-switch-dialog m-auto w-[calc(100%-2rem)] max-w-xl max-h-none overflow-hidden border border-medical-200 bg-medical-50 p-0 text-medical-900 shadow-2xl"
      @cancel.prevent="close"
      @click.self="close"
      @close="$emit('close')"
  >
    <div class="flex max-h-[min(42rem,calc(100dvh-2rem))] flex-col">
      <header class="flex shrink-0 items-center justify-between gap-3 border-b border-medical-200 bg-surface-1 px-4 py-2.5 sm:px-5">
        <h2 id="room-switch-title" class="m-0 text-base font-bold">切换房间</h2>
        <div class="flex items-center gap-2">
          <button
              v-if="!pendingRoom"
              type="button"
              :disabled="roomStore.loading || joining"
              class="flex min-h-11 items-center gap-1.5 border border-medical-200 bg-surface-1 px-2.5 font-mono text-[10px] text-medical-600 transition-colors hover:border-accent hover:text-accent-text disabled:opacity-50"
              @click="roomStore.loadRooms()"
          >
            <RefreshCw class="h-3.5 w-3.5" :class="{ 'motion-safe:animate-spin': roomStore.loading }" aria-hidden="true" />
            REFRESH
          </button>
          <button
              ref="closeButton"
              type="button"
              aria-label="关闭房间列表"
              autofocus
              class="flex h-11 w-11 items-center justify-center border border-medical-200 bg-surface-1 text-medical-600 transition-colors hover:border-accent hover:text-accent-text"
              @click="close"
          >
            <X class="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      </header>

      <div class="mp-scroll min-h-0 overflow-y-auto overscroll-contain p-4 sm:p-5">
        <p v-if="roomStore.error || passwordError" role="alert" class="mb-3 border border-danger/40 bg-danger-bg px-3 py-2 text-xs text-danger">
          {{ passwordError || roomStore.error }}
        </p>
        <form v-if="pendingRoom" class="space-y-3 border border-medical-200 bg-surface-1 p-4" @submit.prevent="joinRoom(pendingRoom.id, password)">
          <label for="room-switch-password" class="block break-words text-sm font-bold">
            {{ pendingRoom.name }}的房间密码
          </label>
          <input
              id="room-switch-password"
              ref="passwordInput"
              v-model="password"
              type="password"
              maxlength="64"
              :disabled="joining"
              class="min-h-11 w-full border border-medical-200 bg-medical-50 px-3 text-sm text-medical-900 outline-none focus:border-accent"
          />
          <div class="flex justify-end gap-2">
            <button type="button" :disabled="joining" class="min-h-11 border border-medical-200 px-3 text-xs font-bold text-medical-600 hover:bg-medical-100 disabled:opacity-50" @click="backToList">返回列表</button>
            <button type="submit" :disabled="joining || !password" class="min-h-11 bg-surface-strong px-3 text-xs font-bold text-white hover:bg-accent hover:text-accent-fg disabled:opacity-50">
              {{ joining ? '正在校验…' : '加入房间' }}
            </button>
          </div>
        </form>
        <template v-else>
          <p v-if="joining" role="status" class="mb-3 text-xs text-medical-600">正在加入…</p>
          <RoomDirectory :busy="joining" :current-room-id="roomStore.roomId" @select="joinRoom($event.id)" />
        </template>
      </div>

      <footer class="shrink-0 border-t border-medical-200 bg-surface-1 px-4 py-2.5 sm:px-5">
        <button
            type="button"
            class="inline-flex min-h-11 items-center gap-2 border border-medical-200 bg-medical-50 px-3 text-xs font-bold text-medical-700 transition-colors hover:border-accent hover:text-accent-text"
            @click="goHome"
        >
          <Home class="h-4 w-4" aria-hidden="true" />
          返回主页
        </button>
      </footer>
    </div>
  </dialog>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Home, RefreshCw, X } from 'lucide-vue-next';
import { useRoomStore } from '../stores/room';
import RoomDirectory from './RoomDirectory.vue';

defineEmits(['close']);

const roomStore = useRoomStore();
const dialog = ref(null);
const closeButton = ref(null);
const passwordInput = ref(null);
const pendingRoom = ref(null);
const password = ref('');
const passwordError = ref('');
const joining = ref(false);
let joinRequest;

onMounted(() => {
  dialog.value.showModal();
  if (!roomStore.loading) roomStore.loadRooms();
});
onBeforeUnmount(() => joinRequest?.abort());

function close() {
  joinRequest?.abort();
  roomStore.clearError();
  dialog.value.close();
}

function goHome() {
  close();
  roomStore.leave();
}

async function backToList() {
  pendingRoom.value = null;
  password.value = '';
  passwordError.value = '';
  roomStore.clearError();
  await nextTick();
  closeButton.value?.focus();
}

async function joinRoom(id, candidate) {
  if (joining.value || id === roomStore.roomId) return;
  joinRequest = new AbortController();
  const { signal } = joinRequest;
  joining.value = true;
  passwordError.value = '';
  try {
    await roomStore.enter(id, candidate, signal);
    close();
  } catch (error) {
    if (!signal.aborted && error?.room) {
      pendingRoom.value = error.room;
      if (error.code === 'INVALID_PASSWORD') passwordError.value = '密码错误';
    }
  } finally {
    joining.value = false;
    joinRequest = undefined;
  }
  if (pendingRoom.value && !signal.aborted) {
    await nextTick();
    passwordInput.value?.focus();
  }
}
</script>

<style scoped>
.room-switch-dialog::backdrop {
  background: rgb(var(--scrim) / 0.55);
}
</style>
