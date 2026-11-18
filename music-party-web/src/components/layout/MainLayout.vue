<!-- src/components/layout/MainLayout.vue -->
<script setup>
import { computed, ref, onMounted, onUnmounted, watch } from 'vue';
import { Search, Users, ListMusic, X, Menu, Minimize2, Maximize2, Volume2, Activity, Moon, Sun, LogOut, Share2, MessageSquare, Palette, Mail, BellRing } from 'lucide-vue-next';
import UserList from '../UserList.vue';
import QueueList from '../QueueList.vue';
import CoverImage from '../CoverImage.vue';
import RoomMailDialog from '../RoomMailDialog.vue';
import { useUserStore } from '../../stores/user';
import { useUiStore } from '../../stores/ui';
import { usePlayerStore } from '../../stores/player';
import { useRoomStore } from '../../stores/room';
import { useChatStore } from '../../stores/chat';
import { useToast } from '../../composables/useToast';
import { useAppearanceStore } from '../../stores/appearance';
import { useAdminStore } from '../../stores/admin';
import { roomMailApi } from '../../api/rooms';

const emit = defineEmits(['search']);
const userStore = useUserStore();
const uiStore = useUiStore();
const playerStore = usePlayerStore();
const roomStore = useRoomStore();
const chatStore = useChatStore();
const appearanceStore = useAppearanceStore();
const adminStore = useAdminStore();
const { success } = useToast();

const roomMailEnabled = ref(false);
const mailDialog = ref(null);

watch([() => roomStore.roomId, () => adminStore.showDashboard], async ([id, dashboardOpen], _, onCleanup) => {
  if (dashboardOpen) return;
  let stale = false;
  onCleanup(() => { stale = true; });
  roomMailEnabled.value = false;
  try {
    const { enabled } = await roomMailApi.status(id);
    if (!stale) roomMailEnabled.value = enabled;
  } catch {
    // Email features remain hidden if the server cannot report their availability.
  }
}, { immediate: true });

const switchRoom = () => {
  mobileMenuOpen.value = false;
  // App 监听 roomStore.entered，会自动断开连接并清空房间状态
  roomStore.leave();
};

const inviteLink = computed(() => `${window.location.origin}${window.location.pathname}?room=${roomStore.roomId}`);

/** 房间码就是邀请凭证：优先系统分享，其次复制链接，最后退回手选。 */
const invite = async () => {
  mobileMenuOpen.value = false;
  const title = roomStore.currentName || 'MUSIC PARTY';
  const link = inviteLink.value;
  if (navigator.share) {
    try {
      await navigator.share({ title: `加入「${title}」一起听歌`, text: `房间码 ${roomStore.roomId}`, url: link });
      return;
    } catch (e) {
      if (e?.name === 'AbortError') return;
    }
  }
  try {
    await navigator.clipboard.writeText(link);
    success(`邀请链接已复制 · 房间码 ${roomStore.roomId}`);
  } catch {
    window.prompt('复制邀请链接', link);
  }
};

const closeChat = () => {
  if (chatStore.isOpen) chatStore.toggleChat();
};

onMounted(() => {
  uiStore.fetchConfig();
});


const mobileQueueOpen = ref(false);
const mobileUserOpen = ref(false);
const mobileMenuOpen = ref(false);

const toggleMobileQueue = () => {
  mobileQueueOpen.value = !mobileQueueOpen.value;
  if(mobileQueueOpen.value) { mobileUserOpen.value = false; closeChat(); }
  mobileMenuOpen.value = false;
};

const toggleMobileUser = () => {
  mobileUserOpen.value = !mobileUserOpen.value;
  if(mobileUserOpen.value) { mobileQueueOpen.value = false; closeChat(); }
  mobileMenuOpen.value = false;
};

const toggleMobileMenu = () => {
  mobileMenuOpen.value = !mobileMenuOpen.value;
  if (mobileMenuOpen.value) { mobileQueueOpen.value = false; mobileUserOpen.value = false; closeChat(); }
};

watch([mobileMenuOpen, mobileQueueOpen, mobileUserOpen], ([menu, queue, users]) => {
  uiStore.mobileOverlayOpen = !!(menu || queue || users);
});

onUnmounted(() => {
  uiStore.mobileOverlayOpen = false;
});

const handleSearchClick = () => {
  mobileMenuOpen.value = false;
  emit('search');
}
</script>

<template>
  <div class="h-app w-full flex flex-col relative overflow-hidden bg-medical-50">
    <!-- 1. 顶部栏 Header -->
    <header v-if="!uiStore.isLiteMode" class="min-h-14 pt-[env(safe-area-inset-top)] bg-surface-1 border-b border-medical-200 flex justify-between items-center gap-3 px-3 md:px-6 flex-shrink-0 relative z-50">
      <div class="flex min-w-0 items-center gap-2">
        <div class="w-2.5 h-2.5 md:w-3 md:h-3 bg-accent flex-shrink-0"></div>
        <div class="min-w-0 flex flex-col justify-center">
          <h1 class="font-bold text-sm md:text-xl tracking-tighter text-medical-900 whitespace-nowrap m-0 leading-tight">MUSIC PARTY</h1>
          <span class="sm:hidden truncate max-w-[7.5rem] font-mono text-[9px] leading-4 tracking-[0.08em] text-medical-500">{{ roomStore.currentName }}</span>
        </div>
        <div class="hidden sm:flex min-w-0 items-center gap-2 ml-1">
          <span class="font-mono text-[10px] text-medical-500 truncate max-w-[120px] md:max-w-[180px]" :title="roomStore.currentName">
            {{ roomStore.currentName }}
          </span>
          <button
              type="button"
              @click="switchRoom"
              class="inline-flex min-h-11 items-center font-mono text-[9px] tracking-[0.12em] px-1.5 border border-medical-200 bg-medical-50 text-medical-500 hover:border-accent hover:text-accent-text transition-colors"
              title="切换房间"
          >SWITCH</button>
          <button
              type="button"
              @click="invite"
              class="flex min-h-11 items-center gap-1 border border-medical-200 bg-medical-50 px-1.5 font-mono text-[9px] tracking-[0.12em] text-medical-500 transition-colors hover:border-accent hover:text-accent-text"
              title="复制邀请链接"
          ><Share2 class="h-3 w-3" />INVITE</button>
          <button v-if="roomMailEnabled" type="button" class="hidden min-h-11 shrink-0 items-center gap-1 border border-medical-200 bg-medical-50 px-2 text-xs text-medical-700 hover:border-accent hover:text-accent-text md:inline-flex" @click="mailDialog.open('subscribe')">
            <Mail class="h-3.5 w-3.5" aria-hidden="true" />订阅
          </button>
          <button v-if="roomMailEnabled" type="button" class="hidden min-h-11 shrink-0 items-center gap-1 border border-medical-200 bg-medical-50 px-2 text-xs text-medical-700 hover:border-accent hover:text-accent-text md:inline-flex" @click="mailDialog.open('summon')">
            <BellRing class="h-3.5 w-3.5" aria-hidden="true" />召唤
          </button>
        </div>
      </div>

      <div class="hidden md:flex items-center gap-4">
        <!-- 精简模式按钮 -->
        <button
            @click="uiStore.toggleLiteMode"
            class="flex items-center justify-center w-11 h-11 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-600 transition-all rounded-sm"
            title="精简模式"
            aria-label="进入精简模式"
        >
          <Minimize2 class="w-4 h-4" />
        </button>

        <button
            @click="uiStore.toggleTheme"
            class="flex items-center justify-center w-11 h-11 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-600 transition-all rounded-sm"
            :title="uiStore.theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
            :aria-label="uiStore.theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
        >
          <Sun v-if="uiStore.theme === 'dark'" class="w-4 h-4" />
          <Moon v-else class="w-4 h-4" />
        </button>
        <button @click="appearanceStore.open" class="flex items-center justify-center w-11 h-11 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-600 transition-all rounded-sm" title="自定义主题" aria-label="自定义主题">
          <Palette class="w-4 h-4" />
        </button>

        <button id="tutorial-search" @click="handleSearchClick" class="flex items-center justify-center h-11 px-4 border border-accent bg-accent hover:bg-accent-hover font-bold text-sm text-accent-fg transition-all rounded-sm gap-2">
          <Search class="w-4 h-4" />
          <span>添加歌曲</span>
        </button>
      </div>

      <div class="flex md:hidden items-center gap-2 flex-shrink-0">
        <!-- 移动端聊天入口放在头部：不再用浮动 launcher，避免它压在歌词/曲目卡片和播放器上 -->
        <button
            id="tutorial-chat-mobile"
            @click="chatStore.toggleChat"
            :aria-expanded="chatStore.isOpen"
            class="relative flex items-center justify-center w-11 h-11 bg-medical-50 border border-medical-200 text-medical-600 transition-colors rounded-sm"
            :class="chatStore.unreadCount > 0 ? 'border-accent text-accent-text' : ''"
            aria-label="聊天"
        >
          <MessageSquare class="w-5 h-5" />
          <span
              v-if="chatStore.unreadCount > 0"
              class="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 font-mono text-[9px] font-bold leading-none text-accent-fg shadow-sm"
          >{{ chatStore.unreadCount > 99 ? '99+' : chatStore.unreadCount }}</span>
        </button>
        <button id="tutorial-search-mobile" @click="handleSearchClick" class="flex items-center justify-center w-11 h-11 border border-accent bg-accent hover:bg-accent-hover font-bold text-sm text-accent-fg transition-all rounded-sm" aria-label="添加歌曲">
          <Search class="w-5 h-5" />
        </button>
        <button
            @click="toggleMobileMenu"
            class="flex items-center justify-center w-11 h-11 bg-medical-50 border border-medical-200 text-medical-600 transition-colors rounded-sm"
            :class="mobileMenuOpen ? 'bg-medical-200 text-medical-900 border-medical-300' : 'hover:bg-medical-100'"
            :aria-expanded="mobileMenuOpen"
            aria-controls="mobile-menu"
            :aria-label="mobileMenuOpen ? '关闭菜单' : '打开菜单'"
        >
          <X v-if="mobileMenuOpen" class="w-5 h-5" />
          <Menu v-else class="w-5 h-5" />
        </button>
      </div>
    </header>

    <!-- 移动端菜单：放在 header 之外，避免被 header 的 z-50 堆叠上下文压在播放条下面 -->
    <div v-if="!uiStore.isLiteMode && mobileMenuOpen" class="md:hidden fixed inset-0 z-[60] bg-scrim/55" @click="mobileMenuOpen = false"></div>
    <Transition name="drawer">
      <aside
          v-if="!uiStore.isLiteMode && mobileMenuOpen"
          id="mobile-menu"
          role="dialog"
          aria-modal="true"
          aria-label="菜单"
          class="md:hidden fixed inset-y-0 right-0 z-[70] w-[min(18rem,calc(100vw-1rem))] bg-surface-1 border-l border-medical-200 shadow-2xl p-4 pb-[max(1rem,env(safe-area-inset-bottom))] flex flex-col gap-3 overflow-y-auto mp-scroll"
      >
        <div class="flex items-center justify-between border-b border-medical-200 pb-3">
          <span class="font-mono text-xs font-bold text-medical-500">MENU</span>
          <button @click="mobileMenuOpen = false" class="flex items-center justify-center w-11 h-11 -mr-2 text-medical-500 hover:text-medical-900" aria-label="关闭菜单"><X class="w-5 h-5" /></button>
        </div>
        <button
            @click="switchRoom"
            class="w-full min-h-11 flex items-center justify-between gap-2 p-3 border border-medical-200 bg-medical-50 text-medical-700"
        >
          <span class="flex items-center gap-2 text-sm font-bold"><LogOut class="w-4 h-4" /> 切换房间</span>
          <span class="font-mono text-[10px] text-medical-500 truncate max-w-[7rem]">{{ roomStore.currentName }}</span>
        </button>
        <button
            @click="invite"
            class="w-full min-h-11 flex items-center justify-between gap-2 p-3 border border-accent/40 bg-accent/5 text-medical-800"
        >
          <span class="flex items-center gap-2 text-sm font-bold"><Share2 class="w-4 h-4" /> 邀请 / 房间码</span>
          <span class="font-mono text-[10px] text-accent-text truncate max-w-[7rem]">{{ roomStore.roomId }}</span>
        </button>
        <button v-if="roomMailEnabled" type="button" class="flex min-h-11 items-center gap-2 border border-medical-200 bg-medical-50 p-3 text-sm font-bold text-medical-700 hover:bg-medical-100" @click="mailDialog.open('subscribe')">
          <Mail class="h-4 w-4" aria-hidden="true" />订阅房间
        </button>
        <button v-if="roomMailEnabled" type="button" class="flex min-h-11 items-center gap-2 border border-medical-200 bg-medical-50 p-3 text-sm font-bold text-medical-700 hover:bg-medical-100" @click="mailDialog.open('summon')">
          <BellRing class="h-4 w-4" aria-hidden="true" />召唤
        </button>
        <button id="tutorial-rename-mobile" @click="toggleMobileUser" class="w-full min-h-11 flex items-center justify-between p-3 border border-medical-200 bg-medical-50 text-medical-700">
          <span class="flex items-center gap-2 text-sm font-bold"><Users class="w-4 h-4" /> 在线用户</span>
          <span class="font-mono text-xs text-accent-text">{{ userStore.onlineUsers.length }}</span>
        </button>
        <button id="tutorial-queue-mobile" @click="toggleMobileQueue" class="w-full min-h-11 flex items-center justify-between p-3 border border-medical-200 bg-medical-50 text-medical-700">
          <span class="flex items-center gap-2 text-sm font-bold"><ListMusic class="w-4 h-4" /> 播放队列</span>
          <span class="font-mono text-xs text-accent-text">{{ playerStore.queue.length }}</span>
        </button>
        <div class="mt-auto grid grid-cols-2 gap-2 pt-4 border-t border-medical-200">
          <button @click="uiStore.toggleTheme" class="flex items-center justify-center gap-2 min-h-11 p-3 border border-medical-200 text-medical-600">
            <Sun v-if="uiStore.theme === 'dark'" class="w-4 h-4" /><Moon v-else class="w-4 h-4" />
            <span class="text-xs font-bold">主题</span>
          </button>
          <button @click="uiStore.toggleLiteMode" class="flex items-center justify-center gap-2 min-h-11 p-3 border border-medical-200 text-medical-600">
            <Minimize2 class="w-4 h-4" /><span class="text-xs font-bold">精简</span>
          </button>
          <button @click="appearanceStore.open" class="col-span-2 flex items-center justify-center gap-2 min-h-11 p-3 border border-accent/40 bg-accent/5 text-accent-text"><Palette class="w-4 h-4" /><span class="text-xs font-bold">自定义主题</span></button>
        </div>
      </aside>
    </Transition>

    <RoomMailDialog
      ref="mailDialog"
      :room-id="roomStore.roomId"
      :room-name="roomStore.currentName"
      :room-password="roomStore.passwordFor(roomStore.roomId)"
    />

    <!-- 2. 主体内容区 -->
    <div v-if="!uiStore.isLiteMode" class="flex-1 flex overflow-hidden relative">
      <aside class="w-64 bg-medical-50 border-r border-medical-200 hidden md:block overflow-y-auto">
        <UserList />
      </aside>

      <main class="flex-1 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <slot></slot>
      </main>

      <aside class="w-80 bg-surface-1 border-l border-medical-200 hidden md:block overflow-hidden">
        <QueueList />
      </aside>

      <Transition name="slide-fade">
        <div v-if="mobileQueueOpen" class="md:hidden absolute inset-0 bg-surface-1 z-30 overflow-y-auto">
          <QueueList dismissible @close="mobileQueueOpen = false" />
        </div>
      </Transition>

      <Transition name="slide-fade">
        <div v-if="mobileUserOpen" class="md:hidden absolute inset-0 bg-medical-50 z-30 overflow-y-auto">
          <UserList dismissible @close="mobileUserOpen = false" />
        </div>
      </Transition>
    </div>

    <!-- 3. 精简模式视图 -->
    <div v-else class="relative flex-1 min-h-0 overflow-y-auto bg-medical-50 p-4 md:p-6 mp-scroll">
      <!-- 背景装饰 -->
      <div class="fixed inset-0 z-0 pointer-events-none opacity-40">
        <div class="absolute inset-0 bg-[linear-gradient(rgb(var(--medical-900)/0.04)_1px,transparent_1px),linear-gradient(90deg,rgb(var(--medical-900)/0.04)_1px,transparent_1px)] bg-[size:32px_32px]"></div>
        <div class="absolute top-0 left-0 w-full h-full bg-[repeating-linear-gradient(45deg,transparent,transparent_20px,rgb(var(--medical-900)/0.03)_20px,rgb(var(--medical-900)/0.03)_21px)]"></div>
      </div>

      <div class="relative z-10 mx-auto w-full max-w-lg flex flex-col items-center gap-6 md:gap-10 py-6 pb-[max(1.5rem,env(safe-area-inset-bottom))]">
        <!-- 头部状态 (文案简化) -->
        <div class="flex items-center gap-3 text-[11px] text-medical-500 tracking-[0.2em] uppercase font-sans">
          <Activity class="w-3 h-3 text-accent-text animate-pulse" />
          <span>精简模式</span>
        </div>

        <!-- 核心显示卡片 -->
        <div class="w-full bg-surface-1 border border-medical-200 shadow-2xl relative p-8 chamfer-br">
           <!-- 四角修饰 -->
          <div class="absolute top-2 left-2 w-2 h-2 border-t border-l border-medical-300"></div>
          <div class="absolute top-2 right-2 w-2 h-2 border-t border-r border-medical-300"></div>
          <div class="absolute bottom-2 left-2 w-2 h-2 border-b border-l border-medical-300"></div>
          <div class="absolute bottom-2 right-2 w-2 h-2 border-b border-r border-medical-300"></div>

          <div class="flex flex-col md:flex-row items-center gap-8">
            <!-- 封面区 -->
            <div class="relative flex-shrink-0">
               <div class="w-24 h-24 border-2 border-medical-100 flex items-center justify-center bg-medical-50 shadow-inner overflow-hidden">
                  <CoverImage :src="playerStore.nowPlaying?.music.coverUrl" class="w-full h-full object-cover" />
               </div>
               <!-- 状态环 -->
               <div v-if="!playerStore.isPaused" class="absolute -inset-2 border border-accent/20 animate-[spin_8s_linear_infinite] rounded-full border-dashed"></div>
            </div>

            <!-- 歌曲信息区 -->
            <div class="flex-1 min-w-0 flex flex-col items-center md:items-start text-center md:text-left font-sans">
               <span class="text-[11px] font-mono text-accent-text mb-1 tracking-widest uppercase">正在播放</span>
               <h2 class="text-2xl md:text-3xl font-bold text-medical-900 tracking-tighter leading-tight mb-2 line-clamp-2">
                 {{ playerStore.nowPlaying?.music.name || '系统待机' }}
               </h2>
               <div class="flex items-center gap-2 text-sm font-bold text-medical-500">
                 <span class="w-2 h-2 bg-medical-200"></span>
                 {{ playerStore.nowPlaying?.music.artists.join(' / ') || '无内容' }}
               </div>
            </div>
          </div>
        </div>

        <!-- 音量控制 -->
        <div class="w-full max-w-[320px] bg-surface-1/80 backdrop-blur-sm border border-medical-200 p-4 flex flex-col gap-3 shadow-sm">
           <div class="flex justify-between items-center text-[11px] font-mono text-medical-500 uppercase tracking-wider">
              <span>音量</span>
              <span class="text-medical-900 font-bold">{{ Math.round(uiStore.volume * 100) }}%</span>
           </div>
           <div class="flex items-center gap-3">
              <Volume2 class="w-4 h-4 text-medical-500 flex-shrink-0" aria-hidden="true" />
              <div class="flex-1 flex items-center h-11">
                 <input
                    type="range" min="0" max="1" step="0.01"
                    v-model.number="uiStore.volume"
                    class="mp-range w-full"
                    aria-label="音量"
                 />
              </div>
           </div>
        </div>

        <!-- 主操作：精简模式下也必须能点歌——否则访客的第一任务是隐藏的 -->
        <button
            type="button"
            @click="$emit('search')"
            class="w-full bg-accent text-accent-fg font-bold py-4 flex items-center justify-center gap-3 transition-all hover:bg-accent-hover active:scale-[0.98] shadow-lg"
        >
          <Search class="w-5 h-5" />
          <span class="text-sm tracking-widest">添加歌曲</span>
        </button>

        <button
            type="button"
            @click="switchRoom"
            class="w-full border border-medical-200 text-medical-600 font-bold py-3 flex items-center justify-center gap-2 transition-all hover:border-accent hover:text-accent-text active:scale-[0.98]"
        >
          <LogOut class="w-4 h-4" />
          <span class="text-xs tracking-widest uppercase">切换房间 · {{ roomStore.currentName }}</span>
        </button>

        <button type="button" @click="appearanceStore.open" class="flex w-full min-h-11 items-center justify-center gap-2 border border-medical-200 text-xs font-bold text-medical-600 hover:border-accent hover:text-accent-text"><Palette class="h-4 w-4" />自定义主题</button>

        <!-- 退出动作（次级） -->
        <button
            @click="uiStore.toggleLiteMode"
            class="w-full border border-medical-300 text-medical-700 font-bold py-3 flex items-center justify-center gap-3 transition-all hover:border-medical-400 hover:text-medical-900 active:scale-[0.98] group"
        >
          <Maximize2 class="w-4 h-4 transition-transform group-hover:scale-110" />
          <span class="text-xs tracking-widest uppercase">退出精简模式</span>
        </button>
      </div>
    </div>

    <slot v-if="!uiStore.isLiteMode" name="player"></slot>
  </div>
</template>

<style scoped>
.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }
.drawer-enter-active, .drawer-leave-active { transition: transform 0.25s ease, opacity 0.25s ease; }
.drawer-enter-from, .drawer-leave-to { transform: translateX(100%); opacity: 0; }
</style>
