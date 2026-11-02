<!-- src/App.vue -->
<template>
  <!-- 全局 Toast 挂载点 -->
  <ToastNotification />

  <div class="h-screen w-screen overflow-hidden font-sans transition-colors duration-500">
    <AudioEngine />
    <!-- 1. 认证遮罩 -->
    <AuthOverlay @unlocked="userStore.isAuthPassed = true" v-if="!userStore.isAuthPassed" />

    <!-- 2. 启动页 (Start Screen) -->
    <!-- 注意：点击 Connect 后，我们先不销毁它，直到 socket 连接成功，或者直接切换布局 -->
    <div v-if="userStore.isAuthPassed && !hasStarted" class="absolute inset-0 z-40 bg-medical-50 flex flex-col items-center justify-center space-y-8">
      <h1 class="text-4xl font-bold tracking-tighter text-medical-900">MUSIC PARTY</h1>
      <p class="font-mono text-xs text-medical-500 tracking-widest">SYSTEM READY</p>
      <button
          @click="startGame"
          class="px-12 py-4 min-h-12 bg-surface-strong text-white font-bold text-xl hover:bg-accent hover:text-accent-fg transition-colors chamfer-br"
      >
        CONNECT
      </button>
    </div>

    <!-- 3. 主界面 (当 hasStarted 为 true 时显示) -->
    <MainLayout v-if="hasStarted" @search="handleSearchClick">
      <!-- 中间插槽: 视觉控制台 -->
      <CenterConsole />

      <!-- 底部插槽: 播放器 -->
      <!-- 注意：这里不使用 v-if，而是 v-show，或者因为在 MainLayout 里是 slot，
           只有 MainLayout 渲染了，它才会渲染。
           关键是 useAudio 里的逻辑已经修好了，会自动处理播放。
      -->
      <template #player>
        <PlayerControl />
      </template>
    </MainLayout>

    <!-- 4. 全局弹窗 -->
    <SearchModal :isOpen="showSearch" @close="showSearch = false" />
    <NamePromptModal />
    <ChatOverlay v-if="hasStarted && !uiStore.isLiteMode" />
    <TutorialOverlay v-if="hasStarted && !uiStore.isLiteMode" />
    <AdminAuthModal />
    <AdminDashboard />
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { usePlayerStore } from './stores/player';
import { useUserStore } from './stores/user';
import { useUiStore } from './stores/ui';
import { useAdminStore } from './stores/admin';
import { useChatStore } from './stores/chat';

// Components
import MainLayout from './components/layout/MainLayout.vue';
import CenterConsole from './components/CenterConsole.vue';
import PlayerControl from './components/PlayerControl.vue';
import AudioEngine from './components/AudioEngine.vue';
import AuthOverlay from './components/AuthOverlay.vue';
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

const startGame = () => {
  hasStarted.value = true;
  player.connect();
};

// 自动性能优化：切后台自动进入精简模式
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'hidden' && hasStarted.value && !player.isPaused && uiStore.autoLiteMode) {
    uiStore.isLiteMode = true;
  }
});

const handleSearchClick = () => {
  // 游客也可先逛：只在「加入队列」时才要求起名，并保留这次点歌意图。
  showSearch.value = true;
};
</script>
