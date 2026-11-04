<template>
  <div class="min-h-[100dvh] w-screen overflow-y-auto bg-medical-50 text-medical-900">
    <div class="mx-auto w-full max-w-6xl px-4 py-8 md:px-8 md:py-12">
      <header class="flex items-end justify-between gap-4 border-b border-medical-200 pb-5">
        <div class="min-w-0">
          <div class="mb-3 h-3 w-3 bg-accent"></div>
          <h1 class="m-0 text-3xl font-bold tracking-tighter md:text-4xl">MUSIC PARTY</h1>
          <p class="mt-2 font-mono text-[10px] tracking-[0.24em] text-medical-500">
            SELECT OR CREATE A ROOM
          </p>
        </div>
        <div class="hidden text-right md:block">
          <div class="font-mono text-[9px] tracking-[0.16em] text-medical-500">DEVICE</div>
          <div class="font-mono text-[10px] text-medical-600">{{ deviceShort }}</div>
        </div>
      </header>

      <div v-if="roomStore.error" role="alert" class="mt-4 border border-danger/40 bg-danger-bg px-3 py-2 font-mono text-[11px] text-danger">
        > {{ roomStore.error }}
      </div>

      <div class="mt-6 grid gap-6 lg:grid-cols-[1fr_380px]">
        <!-- 房间列表 -->
        <section class="min-w-0">
          <div class="mb-3 flex items-center justify-between">
            <h2 class="m-0 font-mono text-[10px] tracking-[0.18em] text-medical-500">MY ROOMS</h2>
            <button
                type="button"
                @click="refresh"
                :disabled="roomStore.loading"
                class="flex min-h-9 items-center gap-1.5 border border-medical-200 bg-surface-1 px-2.5 font-mono text-[10px] text-medical-600 transition-colors hover:border-accent hover:text-accent-text disabled:opacity-50"
            >
              <RefreshCw class="h-3.5 w-3.5" :class="{ 'animate-spin': roomStore.loading }" />
              REFRESH
            </button>
          </div>

          <div v-if="roomStore.loading && !roomStore.rooms.length" class="border border-medical-200 bg-surface-1 p-6 text-center font-mono text-[10px] tracking-[0.16em] text-medical-500">
            LOADING ROOMS...
          </div>

          <div v-else class="space-y-2">
            <button
                v-for="room in roomStore.rooms"
                :key="room.id"
                type="button"
                @click="selectRoom(room)"
                :disabled="busy"
                class="group flex w-full items-stretch gap-3 border border-medical-200 bg-surface-1 p-3 text-left transition-all hover:border-accent hover:shadow-sm disabled:opacity-60"
            >
              <span class="w-1 flex-shrink-0" :class="room.mainRoom ? 'bg-accent' : 'bg-medical-200'"></span>
              <span class="min-w-0 flex-1">
                <span class="flex items-center gap-2">
                  <span class="truncate text-sm font-bold text-medical-900">{{ room.name }}</span>
                  <span v-if="room.owner" class="flex-shrink-0 border border-accent/40 px-1 font-mono text-[9px] leading-4 text-accent-text">OWNER</span>
                  <Lock v-if="room.hasPassword" class="h-3.5 w-3.5 flex-shrink-0 text-medical-500" aria-label="需要密码" />
                </span>
                <span class="mt-0.5 block truncate font-mono text-[10px] text-medical-500">
                  {{ room.id }} · {{ room.onlineCount }} ONLINE
                </span>
              </span>
              <ChevronRight class="h-4 w-4 flex-shrink-0 self-center text-medical-400 transition-colors group-hover:text-accent-text" />
            </button>

            <div v-if="!roomStore.rooms.length" class="border border-dashed border-medical-300 bg-medical-50 p-6 text-center">
              <div class="font-mono text-[10px] tracking-[0.16em] text-medical-500">NO ROOMS YET</div>
              <div class="mt-2 text-xs text-medical-600">在右侧创建一个房间，或输入房间码加入。</div>
            </div>
          </div>
        </section>

        <!-- 创建 / 加入 -->
        <aside class="min-w-0 space-y-5">
          <!-- 名字 -->
          <section class="border border-medical-200 bg-surface-1 p-4">
            <div class="mb-2 flex items-baseline justify-between gap-2">
              <h2 class="m-0 font-mono text-[10px] tracking-[0.18em] text-medical-500">PROFILE NAME</h2>
              <span v-if="hasName" class="font-mono text-[9px] text-accent-text">READY</span>
            </div>
            <p class="mb-2 text-[11px] leading-relaxed text-medical-600">
              创建房间前必须有一个名字；只加入别人的房间可以先不起名。
            </p>
            <div class="flex gap-1.5">
              <input
                  v-model="nameDraft"
                  @keyup.enter="applyName"
                  maxlength="20"
                  placeholder="ENTER CODENAME"
                  aria-label="你的名字"
                  class="min-h-10 min-w-0 flex-1 border border-medical-200 bg-medical-50 px-2.5 text-sm font-bold text-medical-900 outline-none placeholder:text-medical-500 focus:border-accent"
              />
              <button
                  type="button"
                  @click="applyName"
                  :disabled="!nameDraft.trim()"
                  class="min-h-10 flex-shrink-0 bg-surface-strong px-3 text-[11px] font-bold text-white transition-colors hover:bg-accent hover:text-accent-fg disabled:opacity-50"
              >SAVE</button>
            </div>
            <div v-if="nameError" class="mt-1.5 font-mono text-[10px] text-danger">{{ nameError }}</div>
          </section>

          <!-- 创建 -->
          <section class="border border-medical-200 bg-surface-1 p-4">
            <h2 class="m-0 mb-3 font-mono text-[10px] tracking-[0.18em] text-medical-500">CREATE ROOM</h2>
            <input
                v-model="createName"
                maxlength="30"
                placeholder="房间名（可留空）"
                aria-label="房间名"
                class="mb-1.5 min-h-10 w-full border border-medical-200 bg-medical-50 px-2.5 text-sm text-medical-900 outline-none placeholder:text-medical-500 focus:border-accent"
            />
            <input
                v-model="createPassword"
                type="password"
                maxlength="64"
                placeholder="可选：房间密码（至少 4 位）"
                aria-label="房间密码（可选）"
                class="mb-2 min-h-10 w-full border border-medical-200 bg-medical-50 px-2.5 text-sm text-medical-900 outline-none placeholder:text-medical-500 focus:border-accent"
            />
            <button
                type="button"
                @click="createRoom"
                :disabled="busy || !canCreate"
                class="flex min-h-10 w-full items-center justify-center gap-2 bg-accent px-3 text-xs font-bold text-accent-fg transition-colors hover:bg-accent-hover disabled:opacity-50"
            >
              <Plus class="h-4 w-4" />
              {{ busy ? 'CREATING...' : 'CREATE & ENTER' }}
            </button>
            <div v-if="!hasName" class="mt-2 font-mono text-[10px] text-medical-500">
              先在上面保存一个名字才能创建。
            </div>
          </section>

          <!-- 加入 -->
          <section class="border border-medical-200 bg-surface-1 p-4">
            <h2 class="m-0 mb-3 font-mono text-[10px] tracking-[0.18em] text-medical-500">JOIN BY CODE</h2>
            <div class="flex gap-1.5">
              <input
                  v-model="joinCode"
                  @keyup.enter="joinByCode"
                  placeholder="房间码，或粘贴邀请链接"
                  aria-label="房间码"
                  class="min-h-10 min-w-0 flex-1 border border-medical-200 bg-medical-50 px-2.5 font-mono text-sm lowercase text-medical-900 outline-none placeholder:text-medical-500 focus:border-accent"
              />
              <button
                  type="button"
                  @click="joinByCode"
                  :disabled="busy || !joinCode.trim()"
                  class="flex min-h-10 flex-shrink-0 items-center gap-1 bg-surface-strong px-3 text-[11px] font-bold text-white transition-colors hover:bg-accent hover:text-accent-fg disabled:opacity-50"
              >ENTER <ArrowRight class="h-3.5 w-3.5" /></button>
            </div>
          </section>
        </aside>
      </div>

      <footer class="mt-10 border-t border-medical-200 pt-3 font-mono text-[9px] leading-relaxed tracking-[0.12em] text-medical-500">
        DEVICE PROFILE {{ deviceShort }} · 清空浏览器存储会丢失房主身份 · 房间码即邀请凭证
      </footer>
    </div>

    <!-- 密码弹层 -->
    <div v-if="pendingRoom" class="fixed inset-0 z-[140] flex items-center justify-center bg-scrim/80 p-4 backdrop-blur-sm">
      <div role="dialog" aria-modal="true" aria-labelledby="room-pass-title" class="w-full max-w-sm border border-medical-200 bg-surface-1 p-6 shadow-2xl chamfer-br">
        <h2 id="room-pass-title" class="m-0 text-xl font-bold tracking-tighter text-medical-900">ROOM LOCKED</h2>
        <p class="mb-4 mt-1 font-mono text-[10px] tracking-[0.14em] text-medical-500">
          {{ pendingRoom.name }} · {{ pendingRoom.id }}
        </p>
        <input
            v-model="pendingPassword"
            type="password"
            maxlength="64"
            placeholder="INPUT ROOM PASSWORD"
            aria-label="房间密码"
            @keyup.enter="confirmPassword"
            class="mb-2 min-h-11 w-full border border-medical-200 bg-medical-50 p-3 text-center font-mono text-lg tracking-widest text-medical-900 outline-none placeholder:text-medical-500 focus:border-accent"
            autofocus
        />
        <div v-if="passwordError" role="alert" class="mb-2 font-mono text-[10px] text-danger">> {{ passwordError }}</div>
        <div class="flex gap-2">
          <button type="button" @click="cancelPassword" class="min-h-11 flex-1 border border-medical-200 px-3 text-xs font-bold text-medical-600 hover:bg-medical-100">CANCEL</button>
          <button type="button" @click="confirmPassword" :disabled="busy || !pendingPassword" class="min-h-11 flex-1 bg-surface-strong px-3 text-xs font-bold text-white hover:bg-accent hover:text-accent-fg disabled:opacity-50">
            {{ busy ? 'VERIFYING...' : 'UNLOCK' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ArrowRight, ChevronRight, Lock, Plus, RefreshCw } from 'lucide-vue-next';
import { useRoomStore } from '../stores/room';
import { useUserStore } from '../stores/user';
import { STORAGE_KEYS } from '../constants/keys';

const emit = defineEmits(['enter']);
const roomStore = useRoomStore();
const userStore = useUserStore();

const profileName = ref(localStorage.getItem(STORAGE_KEYS.USERNAME) || '');
const nameDraft = ref(profileName.value);
const nameError = ref('');
const createName = ref('');
const createPassword = ref('');
const joinCode = ref('');
const busy = ref(false);
const pendingRoom = ref(null);
const pendingPassword = ref('');
const passwordError = ref('');

const hasName = computed(() => !!profileName.value && !isGuestName(profileName.value));
const canCreate = computed(() => hasName.value);
const deviceShort = computed(() => (roomStore.deviceId || '').slice(0, 8));

function isGuestName(name) {
  const value = (name || '').trim().toLowerCase();
  return !value || value.startsWith('guest') || value.startsWith('游客');
}

function applyName() {
  const name = nameDraft.value.trim();
  if (isGuestName(name)) {
    nameError.value = '不能使用“游客”作为名字';
    return false;
  }
  localStorage.setItem(STORAGE_KEYS.USERNAME, name);
  profileName.value = name;
  userStore.setLocalName(name);
  nameError.value = '';
  return true;
}

async function refresh() {
  await roomStore.loadRooms();
}

function normalizeCode(input) {
  const raw = (input || '').trim();
  if (!raw) return '';
  try {
    const url = new URL(raw, window.location.origin);
    const fromQuery = url.searchParams.get('room');
    if (fromQuery) return fromQuery.trim().toLowerCase();
  } catch { /* 不是 URL，按房间码处理 */ }
  return raw.replace(/\s+/g, '').toLowerCase();
}

async function enterRoom(id, password) {
  busy.value = true;
  roomStore.clearError();
  try {
    await roomStore.enter(id, password);
    pendingRoom.value = null;
    pendingPassword.value = '';
    emit('enter');
  } catch (error) {
    if (error?.code === 'PASSWORD_REQUIRED') {
      pendingRoom.value = roomStore.info || { id, name: id };
      passwordError.value = '';
    } else if (error?.code === 'INVALID_PASSWORD') {
      passwordError.value = '密码错误';
    } else if (!roomStore.error) {
      roomStore.error = error?.response?.data?.message || '无法进入房间';
    }
  } finally {
    busy.value = false;
  }
}

async function selectRoom(room) {
  if (room.hasPassword && !roomStore.passwordFor(room.id)) {
    pendingRoom.value = room;
    pendingPassword.value = '';
    passwordError.value = '';
    return;
  }
  await enterRoom(room.id);
}

function confirmPassword() {
  if (!pendingRoom.value || !pendingPassword.value) return;
  enterRoom(pendingRoom.value.id, pendingPassword.value);
}

function cancelPassword() {
  pendingRoom.value = null;
  pendingPassword.value = '';
  passwordError.value = '';
}

async function createRoom() {
  if (!applyName()) return;
  busy.value = true;
  roomStore.clearError();
  try {
    const password = createPassword.value;
    const room = await roomStore.createRoom(createName.value.trim(), password);
    createName.value = '';
    createPassword.value = '';
    // 刚设置的密码直接用于首次进入，不要马上再问一遍
    await enterRoom(room.id, password);
  } catch (error) {
    const payload = error?.response?.data;
    roomStore.error = payload?.message || '创建房间失败';
  } finally {
    busy.value = false;
  }
}

async function joinByCode() {
  const code = normalizeCode(joinCode.value);
  if (!code) return;
  await enterRoom(code);
}

onMounted(async () => {
  await roomStore.loadRooms();
  const query = new URLSearchParams(window.location.search).get('room');
  if (query) {
    joinCode.value = query;
    await enterRoom(query.trim().toLowerCase());
  }
});
</script>
