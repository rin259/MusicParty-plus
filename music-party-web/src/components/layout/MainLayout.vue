<!-- src/components/layout/MainLayout.vue -->
<script setup>
import { computed, nextTick, ref, onMounted, onUnmounted, watch } from 'vue';
import { Search, Users, ListMusic, X, Menu, Minimize2, Maximize2, Volume2, Activity, Moon, Sun, LogOut, Share2, MessageSquare, Mail, BellRing, ArrowLeftRight, Palette, Image as ImageIcon } from 'lucide-vue-next';
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
import { useAdminStore } from '../../stores/admin';
import { roomMailApi } from '../../api/rooms';
import { useAppearanceStore } from '../../stores/appearance';
import { useWallpaperStore } from '../../stores/wallpaper.js';
import { useWallpaperEdges } from '../../composables/useWallpaperEdges.js';
import { WALLPAPER_EDGES } from '../../logic/wallpaper.js';

const emit = defineEmits(['search', 'switch-room']);
const userStore = useUserStore();
const uiStore = useUiStore();
const playerStore = usePlayerStore();
const roomStore = useRoomStore();
const chatStore = useChatStore();
const adminStore = useAdminStore();
const appearanceStore = useAppearanceStore();
const { success } = useToast();
const wallpaper = useWallpaperStore();
const showLite = computed(() => uiStore.isLiteMode && !wallpaper.active);
const layout = ref(null);
const wallpaperButton = ref(null);
const panels = { top: ref(null), right: ref(null), bottom: ref(null), left: ref(null) };
const { top: topPanel, right: rightPanel, bottom: bottomPanel, left: leftPanel } = panels;
const { shown, handlePointer, leave, focusOut } = useWallpaperEdges(wallpaper, layout, panels);
const offsets = computed(() => Object.fromEntries(WALLPAPER_EDGES.map(edge => [
  '--wallpaper-' + edge,
  `min(${wallpaper.settings.deadZone.edges[edge]}px, ${edge === 'top' || edge === 'bottom' ? '25dvh' : '25vw'})`
])));
const edgeHidden = edge => wallpaper.active && !shown[edge];

async function toggleWallpaper(event) {
  const shouldRestoreFocus = event.detail === 0;
  wallpaper.active = !wallpaper.active;
  await nextTick();
  if (!shouldRestoreFocus) return;
  if (wallpaper.active) layout.value?.focus({ preventScroll: true });
  else if (wallpaperButton.value?.getClientRects().length) wallpaperButton.value.focus({ preventScroll: true });
}

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
  if (mobileMenuOpen.value) {
    mobileMenuOpen.value = false;
    mobileMenuButton.value?.focus();
  }
  emit('switch-room');
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
const mobileMenuButton = ref(null);

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

watch(() => wallpaper.active, () => {
  mobileMenuOpen.value = mobileQueueOpen.value = mobileUserOpen.value = false;
  closeChat();
});

const handleSearchClick = () => {
  mobileMenuOpen.value = false;
  emit('search');
}
</script>

<template>
  <div
      ref="layout"
      class="h-app w-full flex flex-col relative overflow-hidden bg-medical-50"
      :class="{ 'wallpaper-layout': wallpaper.active }"
      :style="wallpaper.active ? offsets : undefined"
      tabindex="-1"
      @pointermove="event => event.pointerType !== 'touch' && handlePointer(event)"
      @pointerdown.capture="event => event.pointerType === 'touch' && handlePointer(event)"
      @pointerleave="leave"
      @focusout="focusOut"
  >
    <button v-if="wallpaper.active" type="button" class="wallpaper-keyboard-exit" @click="toggleWallpaper">退出壁纸模式</button>
    <!-- 1. 顶部栏 Header -->
    <header
        v-if="!showLite"
        ref="topPanel"
        data-wallpaper-edge="top"
        :class="{ 'wallpaper-edge-hidden': edgeHidden('top') }"
        :inert="edgeHidden('top')"
        class="min-h-14 pt-[env(safe-area-inset-top)] bg-surface-1 border-b border-medical-200 flex justify-between items-center gap-3 px-3 md:px-6 flex-shrink-0 relative z-50">
      <div class="room-header-identity flex min-w-0 items-center gap-2">
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
              aria-haspopup="dialog"
              type="button"
              @click="switchRoom"
              class="room-header-action inline-flex"
              aria-label="SWITCH 换房"
          >
            <ArrowLeftRight class="h-3 w-3" aria-hidden="true" />
            <span class="room-header-action-label" aria-hidden="true"><span>SWITCH</span><span>换房</span></span>
          </button>
          <button
              type="button"
              @click="invite"
              class="room-header-action inline-flex"
              aria-label="INVITE 邀请"
          >
            <Share2 class="h-3 w-3" aria-hidden="true" />
            <span class="room-header-action-label" aria-hidden="true"><span>INVITE</span><span>邀请</span></span>
          </button>
          <button v-if="roomMailEnabled" type="button" class="room-header-action hidden md:inline-flex" aria-label="SUB 订阅" @click="mailDialog.open('subscribe')">
            <Mail class="h-3 w-3" aria-hidden="true" />
            <span class="room-header-action-label" aria-hidden="true"><span>SUB</span><span>订阅</span></span>
          </button>
          <button v-if="roomMailEnabled" type="button" class="room-header-action hidden md:inline-flex" aria-label="CALL 召唤" @click="mailDialog.open('summon')">
            <BellRing class="h-3 w-3" aria-hidden="true" />
            <span class="room-header-action-label" aria-hidden="true"><span>CALL</span><span>召唤</span></span>
          </button>
        </div>
      </div>

      <div class="hidden md:flex items-center gap-2 lg:gap-4">
        <button
            ref="wallpaperButton"
            type="button"
            @click="toggleWallpaper"
            class="flex h-11 w-11 items-center justify-center rounded-sm border border-medical-200 bg-medical-50 text-medical-600 hover:bg-medical-100"
            :aria-label="wallpaper.active ? '退出壁纸模式' : '进入壁纸模式'"
            :title="wallpaper.active ? '退出壁纸模式' : '壁纸模式'"
        >
          <ImageIcon class="h-4 w-4" aria-hidden="true" />
        </button>
        <button v-if="wallpaper.active" type="button" @click="chatStore.toggleChat" class="flex h-11 w-11 items-center justify-center rounded-sm border border-medical-200 bg-medical-50 text-medical-600 hover:bg-medical-100" :aria-expanded="chatStore.isOpen" aria-label="聊天">
          <MessageSquare class="h-4 w-4" aria-hidden="true" />
        </button>
        <!-- 精简模式按钮 -->
        <button
            v-if="!wallpaper.active"
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

      <div class="room-header-compact flex md:hidden items-center gap-2 flex-shrink-0">
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
            ref="mobileMenuButton"
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
    <div v-if="!showLite && mobileMenuOpen" class="md:hidden fixed inset-0 z-[60] bg-scrim/55" @click="mobileMenuOpen = false"></div>
    <Transition name="drawer">
      <aside
          v-if="!showLite && mobileMenuOpen"
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
            aria-haspopup="dialog"
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
          <button v-if="!wallpaper.active" @click="uiStore.toggleLiteMode" class="flex items-center justify-center gap-2 min-h-11 p-3 border border-medical-200 text-medical-600">
            <Minimize2 class="w-4 h-4" /><span class="text-xs font-bold">精简</span>
          </button>
          <button type="button" @click="toggleWallpaper" class="flex h-11 w-11 items-center justify-center rounded-sm border border-medical-200 bg-medical-50 text-medical-600 hover:bg-medical-100" :aria-label="wallpaper.active ? '退出壁纸模式' : '进入壁纸模式'" :title="wallpaper.active ? '退出壁纸模式' : '壁纸模式'">
            <ImageIcon class="h-4 w-4" aria-hidden="true" />
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
    <div v-if="!showLite" class="flex-1 flex overflow-hidden relative">
      <aside ref="leftPanel" data-wallpaper-edge="left" aria-label="在线用户" :class="{ 'wallpaper-edge-hidden': edgeHidden('left') }" :inert="edgeHidden('left')" class="w-64 bg-medical-50 border-r border-medical-200 hidden md:block">
        <div class="h-full overflow-y-auto">
          <UserList />
        </div>
      </aside>

      <main class="flex-1 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <slot></slot>
      </main>

      <aside ref="rightPanel" data-wallpaper-edge="right" aria-label="播放队列" :class="{ 'wallpaper-edge-hidden': edgeHidden('right') }" :inert="edgeHidden('right')" class="w-80 bg-surface-1 border-l border-medical-200 hidden md:block overflow-hidden">
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
            aria-haspopup="dialog"
        >
          <LogOut class="w-4 h-4" />
          <span class="text-xs tracking-widest uppercase">切换房间 · {{ roomStore.currentName }}</span>
        </button>

        <button type="button" @click="appearanceStore.open" class="flex w-full min-h-11 items-center justify-center gap-2 border border-medical-200 text-xs font-bold text-medical-600 hover:border-accent hover:text-accent-text"><Palette class="h-4 w-4" />自定义主题</button>

        <button type="button" @click="toggleWallpaper" class="flex h-11 w-11 items-center justify-center rounded-sm border border-medical-200 bg-medical-50 text-medical-600 hover:bg-medical-100" aria-label="进入壁纸模式" title="壁纸模式">
          <ImageIcon class="h-4 w-4" aria-hidden="true" />
        </button>

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

    <div v-if="!showLite" ref="bottomPanel" data-wallpaper-edge="bottom" :class="{ 'wallpaper-edge-hidden': edgeHidden('bottom') }" :inert="edgeHidden('bottom')" class="shrink-0">
      <slot name="player"></slot>
    </div>
  </div>
</template>

<style scoped>
:global(html:has(.wallpaper-layout)) { scrollbar-gutter: auto; }
.wallpaper-layout:focus { outline: none; }
.wallpaper-layout [data-wallpaper-edge] {
  position: absolute;
  z-index: 48;
  opacity: 1;
  transition: transform 220ms cubic-bezier(.2,.75,.25,1), opacity 170ms ease-out, visibility 0s;
}
.wallpaper-layout [data-wallpaper-edge="top"],
.wallpaper-layout [data-wallpaper-edge="bottom"] {
  left: var(--wallpaper-left);
  right: var(--wallpaper-right);
  z-index: 50;
  container: wallpaper-bar / inline-size;
}
.wallpaper-layout [data-wallpaper-edge="top"] {
  top: var(--wallpaper-top);
  --edge-x: 0;
  --edge-y: -1;
  flex-wrap: wrap;
  row-gap: .25rem;
}
.wallpaper-layout [data-wallpaper-edge="bottom"] { bottom: var(--wallpaper-bottom); --edge-x: 0; --edge-y: 1; }
.wallpaper-layout aside[data-wallpaper-edge] {
  display: block;
  top: var(--wallpaper-top);
  bottom: var(--wallpaper-bottom);
  max-width: min(72%, calc(100% - var(--wallpaper-left) - var(--wallpaper-right)));
}
.wallpaper-layout [data-wallpaper-edge="left"] { left: var(--wallpaper-left); --edge-x: -1; --edge-y: 0; }
.wallpaper-layout [data-wallpaper-edge="right"] { right: var(--wallpaper-right); --edge-x: 1; --edge-y: 0; overflow: visible; }

/* The bars keep their inset content boxes. Their backgrounds reach the physical edges. */
.wallpaper-layout {
  --wallpaper-fill-top: calc(-1 * var(--wallpaper-top));
  --wallpaper-fill-right: calc(-1 * var(--wallpaper-right));
  --wallpaper-fill-bottom: calc(-1 * var(--wallpaper-bottom));
  --wallpaper-fill-left: calc(-1 * var(--wallpaper-left));
}
.wallpaper-layout [data-wallpaper-edge]::before,
.wallpaper-layout [data-wallpaper-edge="right"] :deep(.queue-list-heading::after) {
  content: "";
  position: absolute;
  z-index: -1;
  background-color: inherit;
  pointer-events: none;
}
.wallpaper-layout [data-wallpaper-edge="top"]::before {
  inset: var(--wallpaper-fill-top) var(--wallpaper-fill-right) -1px var(--wallpaper-fill-left);
  border-bottom: inherit;
}
.wallpaper-layout [data-wallpaper-edge="bottom"] { background-color: rgb(var(--surface-1)); }
.wallpaper-layout [data-wallpaper-edge="bottom"]::before {
  inset: 0 var(--wallpaper-fill-right) var(--wallpaper-fill-bottom) var(--wallpaper-fill-left);
  border-top: 1px solid rgb(var(--medical-200));
}
.wallpaper-layout [data-wallpaper-edge="bottom"] :deep(.player-bar) { box-shadow: none !important; }
.wallpaper-layout [data-wallpaper-edge="left"]::before {
  inset: var(--wallpaper-fill-top) -1px var(--wallpaper-fill-bottom) var(--wallpaper-fill-left);
  border-right: inherit;
}
.wallpaper-layout [data-wallpaper-edge="right"]::before {
  inset: var(--wallpaper-fill-top) var(--wallpaper-fill-right) var(--wallpaper-fill-bottom) -1px;
  border-left: inherit;
}
.wallpaper-layout [data-wallpaper-edge="right"] :deep(.queue-list-heading) {
  position: relative;
  isolation: isolate;
}
.wallpaper-layout [data-wallpaper-edge="right"] :deep(.queue-list-heading::after) {
  inset: var(--wallpaper-fill-top) var(--wallpaper-fill-right) -1px 0;
  border-bottom: inherit;
}
.wallpaper-layout .wallpaper-edge-hidden {
  visibility: hidden;
  pointer-events: none;
  opacity: 0;
  transform: translate(calc(var(--edge-x) * 100%), calc(var(--edge-y) * 100%));
  transition-delay: 0s, 0s, 220ms;
}
.wallpaper-keyboard-exit {
  position: absolute;
  z-index: 51;
  top: .75rem;
  left: .75rem;
  min-height: 2.75rem;
  padding-inline: .75rem;
  font-weight: 700;
  color: rgb(var(--medical-900));
  background: rgb(var(--surface-1));
}
.wallpaper-keyboard-exit:not(:focus-visible) { clip-path: inset(50%); pointer-events: none; }

.room-header-action {
  @apply min-h-11 shrink-0 items-center gap-1 border border-medical-200 bg-medical-50 px-1.5 font-mono text-[9px] tracking-[0.12em] text-medical-500 transition-colors hover:border-accent hover:text-accent-text;
}

.room-header-action-label {
  @apply grid whitespace-nowrap text-center;
}

.room-header-action-label > span { grid-area: 1 / 1; }
.room-header-action-label > :first-child { opacity: calc(1 - var(--room-header-label-zh, 0)); }
.room-header-action-label > :last-child { opacity: var(--room-header-label-zh, 0); }
.room-header-action:focus-visible { --room-header-label-zh: 1; }

@container wallpaper-bar (max-width: 40rem) {
  .room-header-action-label { display: none; }
}
@container wallpaper-bar (max-width: 14rem) {
  .room-header-identity { display: none; }
  .room-header-compact { width: 100%; justify-content: space-between; gap: 0; }
}

@media (hover: hover) {
  .room-header-action:hover { --room-header-label-zh: 1; }
}

.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }
.drawer-enter-active, .drawer-leave-active { transition: transform 0.25s ease, opacity 0.25s ease; }
.drawer-enter-from, .drawer-leave-to { transform: translateX(100%); opacity: 0; }
</style>
