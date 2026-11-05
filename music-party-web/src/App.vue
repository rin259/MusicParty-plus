<!-- src/App.vue -->
<template>
  <!-- 全局 Toast 挂载点 -->
  <ToastNotification />

  <div
      class="w-full font-sans transition-colors duration-500"
      :class="roomStore.entered ? 'h-app overflow-hidden' : 'min-h-app overflow-x-hidden'"
  >
    <!-- 0. 房间选择 / 创建（landing） -->
    <RoomLanding v-if="!roomStore.entered" />

    <template v-else>
      <AudioEngine />

      <!-- 自动播放被浏览器拦截时，必须在真实点击手势里重新 play()；放这里以便精简模式也能看到 -->
      <button
          v-if="player.audioBlocked"
          type="button"
          @click="resumeAudio"
          class="fixed left-1/2 z-[75] flex min-h-11 -translate-x-1/2 items-center gap-2 border border-accent bg-accent px-4 font-mono text-[11px] font-bold text-accent-fg shadow-2xl"
          :class="uiStore.isLiteMode
              ? 'bottom-[max(1.5rem,env(safe-area-inset-bottom))]'
              : 'bottom-[calc(env(safe-area-inset-bottom)+9rem)] md:bottom-28'"
          aria-label="点击开启声音"
      >
        <VolumeX class="h-4 w-4" />
        点击开启声音
      </button>

      <!-- 主界面 -->
      <MainLayout v-if="hasStarted" @search="handleSearchClick">
        <CenterConsole />
        <template #player>
          <PlayerControl />
        </template>
      </MainLayout>

      <!-- 全局弹窗 -->
      <SearchModal :isOpen="showSearch" @close="showSearch = false" />
      <NamePromptModal />
      <ChatOverlay v-if="hasStarted && !uiStore.isLiteMode" />
      <TutorialOverlay v-if="hasStarted && !uiStore.isLiteMode" />
      <AdminAuthModal />
      <AdminDashboard />
    </template>
  </div>
</template>

<script setup>
import { onUnmounted, ref, watch, nextTick } from 'vue';
import { VolumeX } from 'lucide-vue-next';
import { useEventListener } from '@vueuse/core';
import { usePlayerStore } from './stores/player';
import { useUserStore } from './stores/user';
import { useUiStore } from './stores/ui';
import { useAdminStore } from './stores/admin';
import { useChatStore } from './stores/chat';
import { useRoomStore } from './stores/room';
import { setPageScrollable } from './utils/viewport';

// Components
import MainLayout from './components/layout/MainLayout.vue';
import CenterConsole from './components/CenterConsole.vue';
import PlayerControl from './components/PlayerControl.vue';
import AudioEngine from './components/AudioEngine.vue';
import RoomLanding from './components/RoomLanding.vue';
import SearchModal from './components/SearchModal.vue';
import NamePromptModal from './components/NamePromptModal.vue';
import ChatOverlay from './components/ChatOverlay.vue';
import ToastNotification from './components/ToastNotification.vue';
import TutorialOverlay from './components/TutorialOverlay.vue';
import AdminAuthModal from './components/AdminAuthModal.vue';
import AdminDashboard from './components/AdminDashboard.vue';

const player = usePlayerStore();
const userStore = useUserStore();
const uiStore = useUiStore();
const adminStore = useAdminStore();
const hasStarted = ref(false);
const showSearch = ref(false);
const chatStore = useChatStore();
const roomStore = useRoomStore();

// landing 用文档级滚动（body 默认 overflow:hidden 会裁掉长页面）；
// 进入房间后恢复锁定，避免主界面被页面滚动带着跑。
const syncPageScroll = () => {
  setPageScrollable(!roomStore.entered);
};
watch(() => roomStore.entered, syncPageScroll, { immediate: true });
onUnmounted(() => {
  document.body.classList.remove('mp-scrollable');
  document.documentElement.classList.remove('mp-scrollable');
});

// 键盘可达性(antislop R-32)：Escape 自上而下关闭最上层浮层
useEventListener(window, 'keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (adminStore.showAuthModal) { adminStore.showAuthModal = false; return; }
  if (userStore.showNameModal) { userStore.showNameModal = false; return; }
  if (adminStore.showDashboard) { adminStore.showDashboard = false; return; }
  if (showSearch.value) { showSearch.value = false; return; }
  if (chatStore.isOpen) chatStore.toggleChat();
});

// Cmd/Ctrl+K：随处唤起搜索（房间内、无更高层浮层时）
useEventListener(window, 'keydown', (e) => {
  if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'k') return;
  if (!hasStarted.value || adminStore.showAuthModal || adminStore.showDashboard || userStore.showNameModal) return;
  e.preventDefault();
  showSearch.value = true;
});

// 进入/离开房间只以 roomStore.entered 为准：
// landing 组件在 entered 变 true 的同一 tick 会被卸载，从子组件 emit 出来的事件可能丢失。
watch(() => roomStore.entered, async (entered) => {
  // landing 是可滚动的长页，房间是固定的单屏：切换时把文档滚动归零，
  // 否则从房间回到 landing 会停在上一次的滚动位置（看起来像页面自己跳了）。
  await nextTick();
  window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
  if (entered) {
    hasStarted.value = true;
    player.connect();
  } else {
    hasStarted.value = false;
    player.disconnect();
    // 房间级状态必须一起清掉，不能让上一个房间的聊天/在线列表带到落地页
    chatStore.messages = [];
    chatStore.unreadCount = 0;
    userStore.setOnlineUsers([]);
  }
});

// 自动性能优化：切后台自动进入精简模式
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'hidden' && hasStarted.value && !player.isPaused && uiStore.autoLiteMode) {
    uiStore.isLiteMode = true;
  }
});

const resumeAudio = () => window.dispatchEvent(new CustomEvent('music-party:resume-audio'));

const handleSearchClick = () => {
  // 游客也可先逛：只在「加入队列」时才要求起名，并保留这次点歌意图。
  showSearch.value = true;
};
</script>
