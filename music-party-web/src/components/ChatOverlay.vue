<template>
  <Teleport to="body">
    <Transition
        enter-active-class="transition-all duration-200 ease-out"
        enter-from-class="opacity-0 scale-95"
        enter-to-class="opacity-100 scale-100"
        leave-active-class="transition-all duration-150 ease-in"
        leave-from-class="opacity-100 scale-100"
        leave-to-class="opacity-0 scale-95"
        @after-enter="() => scrollToBottom(true)"
    >
      <div
          v-if="chatStore.isOpen"
          ref="panelRef"
          role="dialog"
          :aria-modal="isMobile ? 'true' : 'false'"
          aria-label="聊天窗口"
          tabindex="-1"
          class="fixed z-[72] flex flex-col overflow-hidden border border-medical-200 bg-surface-1 shadow-2xl chamfer-br outline-none"
          :class="isMobile ? '' : 'w-[340px]'"
          :style="panelStyle"
      >
        <!-- Header（面板不再拖拽；拖拽只发生在 launcher 上） -->
        <div class="h-10 bg-medical-50 border-b border-medical-200 flex items-center justify-between px-3 flex-shrink-0 select-none">
          <div class="font-mono text-xs font-bold text-medical-500 flex items-center gap-2">
            <MessageSquare class="w-3 h-3"/> CHAT
          </div>
          <button @click="chatStore.toggleChat" class="flex items-center justify-center w-11 h-11 -mr-2 text-medical-500 hover:text-medical-900 cursor-pointer" aria-label="关闭聊天">
            <X class="w-4 h-4"/>
          </button>
        </div>

        <!-- 2. Tabs 切换栏 -->
        <div class="flex border-b border-medical-200 bg-medical-50/50" role="tablist" aria-label="聊天视图">
          <button
            v-for="tab in ['CHAT', 'SYSTEM INFO']"
              :key="tab"
              role="tab"
              :aria-selected="activeTab === tab"
              @click="activeTab = tab"
              class="flex-1 py-2 min-h-11 text-[11px] font-bold font-mono transition-colors relative"
              :class="activeTab === tab ? 'text-medical-900 bg-surface-1' : 'text-medical-600 hover:text-medical-800 hover:bg-medical-100'"
          >
            {{ tab }}
            <!-- 激活指示条 -->
            <div v-if="activeTab === tab" class="absolute top-0 left-0 w-full h-0.5 bg-accent"></div>
          </button>
        </div>

        <!-- 3. Messages List -->
        <div
            ref="msgListRef"
            @scroll.passive="handleScroll"
            class="flex-1 min-h-0 overflow-y-auto overscroll-contain p-3 space-y-4 bg-medical-50/30 chat-scroll mp-scroll"
            aria-live="polite"
        >
          <!-- Loading More Indicator -->
          <div v-if="chatStore.isLoadingMore" class="flex justify-center py-2">
            <Loader2 class="w-4 h-4 animate-spin text-accent-text/50" />
          </div>

          <div v-if="processedMessages.length === 0" class="text-center py-8 text-[10px] text-medical-500 font-mono">
            > NO RECORDS IN {{ activeTab }}
          </div>

          <div
              v-for="item in processedMessages"
              :key="item.id"
              :data-msg-id="item.id"
          >
            <!-- 时间戳 (如果与上一条间隔超过3分钟则显示) -->
            <div v-if="item.showTime" class="flex justify-center mb-3">
              <span class="text-[10px] font-mono text-medical-500 bg-medical-100/50 px-2 py-0.5 rounded-sm">
                {{ formatTime(item.msg.timestamp) }}
              </span>
            </div>

            <!-- 消息体 -->
            <!-- 情况A: 聊天消息 (CHAT) -->
            <div
                v-if="item.msg.type === 'CHAT'"
                class="flex flex-col text-sm group"
                :class="isSelf(item.msg) ? 'items-end' : 'items-start'"
            >
              <div class="flex items-center gap-2 text-[11px] text-medical-500 mb-0.5 font-sans">
                <span v-if="!isSelf(item.msg)">{{ userStore.resolveName(item.msg.userId, item.msg.userName) }}</span>
              </div>
              <div
                  class="max-w-[90%] px-3 py-1.5 text-xs break-words relative shadow-sm leading-relaxed select-text cursor-text"
                  :class="isSelf(item.msg)
                    ? 'bg-surface-strong text-white rounded-l-md rounded-tr-md'
                    : 'bg-surface-1 border border-medical-200 text-medical-800 rounded-r-md rounded-tl-md'"
              >
                {{ item.msg.content }}
              </div>
            </div>

            <!-- 情况B: 系统日志 (SYSTEM) -->
            <div
                v-else-if="item.msg.type === 'SYSTEM'"
                class="flex items-start gap-2 text-xs text-medical-600 px-2"
            >
              <Terminal class="w-3 h-3 mt-0.5 flex-shrink-0 opacity-50"/>
              <span class="font-sans text-xs leading-relaxed break-all select-text cursor-text">
                {{ item.msg.content }}
              </span>
            </div>

            <!-- 情况C: 点赞 (LIKE) -->
            <div
                v-else-if="item.msg.type === 'LIKE'"
                class="flex justify-center my-1"
            >
              <div class="bg-accent/5 border border-accent/20 text-accent-text px-3 py-1 rounded-full text-[11px] font-bold flex items-center gap-1 shadow-sm">
                <Zap class="w-3 h-3 fill-accent stroke-accent"/>
                <span>{{ item.msg.content }}</span>
                <span
                    v-if="item.count > 1"
                    class="ml-0.5 px-1.5 py-0.5 rounded-full bg-accent/10 border border-accent/30 text-[10px] leading-none"
                >×{{ item.count }}</span>
              </div>
            </div>

            <!-- 情况D: 开始播放 (PLAY_START) -->
            <div
                v-else-if="item.msg.type === 'PLAY_START'"
                class="flex justify-center my-2"
            >
               <div class="bg-medical-100/80 border border-medical-300 text-medical-600 px-3 py-1 rounded-sm text-[10px] font-mono flex items-center gap-2 shadow-sm">
                 <span class="w-2 h-2 rounded-full bg-accent animate-pulse"></span>
                 <span>{{ item.msg.content }}</span>
               </div>
            </div>

          </div>
        </div>

        <button
            v-if="newBelow > 0"
            type="button"
            @click="jumpToLatest"
            class="absolute left-1/2 -translate-x-1/2 bottom-[calc(4.5rem+env(safe-area-inset-bottom))] z-20 flex items-center gap-1.5 px-3 py-1.5 bg-surface-strong text-white text-[10px] font-mono tracking-[0.12em] shadow-lg"
            aria-label="跳到最新消息"
        >
          <ChevronDown class="w-3.5 h-3.5" />
          NEW {{ newBelow }}
        </button>

        <!-- 4. Input Area (仅在 Chat Tab 显示) -->
        <div v-if="activeTab === 'CHAT'" class="p-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] bg-surface-1 border-t border-medical-200 flex gap-2 flex-shrink-0">
          <input
              v-model="inputContent"
              @keyup.enter="send"
              @mousedown.stop
              @touchstart.stop
              enterkeyhint="send"
              autocomplete="off"
              placeholder="TYPE MESSAGE..."
              aria-label="输入聊天内容"
              class="flex-1 min-h-11 bg-medical-50 border border-medical-200 px-2 py-1.5 text-base md:text-xs outline-none focus:border-accent font-sans transition-colors rounded-sm text-medical-900 placeholder:text-medical-500"
          />
          <button
              @click="send"
              class="bg-accent hover:bg-accent-hover text-accent-fg px-3 py-1.5 min-w-11 min-h-11 transition-colors rounded-sm flex items-center justify-center shadow-sm shadow-accent/20"
              aria-label="发送"
          >
            <Send class="w-4 h-4" />
          </button>
        </div>

        <!-- System Tab 底部占位 -->
        <div v-else class="h-6 bg-medical-50 border-t border-medical-200 flex items-center justify-center">
          <span class="text-[10px] font-mono text-medical-500">SYSTEM LOG READ-ONLY</span>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- 唯一的拖拽元素：launcher。面板通过 Floating UI 跟随它，不参与布局。 -->
  <button
      id="tutorial-chat"
      ref="launcherRef"
      type="button"
      v-show="!isMobile"
      @pointerdown="handlePointerDown"
      @click="handleClick"
      :aria-label="chatStore.unreadCount > 0 ? `打开聊天，${chatStore.unreadCount} 条未读` : '打开聊天'"
      :aria-expanded="chatStore.isOpen"
      :style="launcherStyle"
      class="fixed left-0 top-0 z-[72] flex h-11 w-11 touch-none select-none items-center justify-center overflow-hidden rounded-sm border transition-all cursor-move"
      :class="chatStore.unreadCount > 0
          ? 'bg-accent border-accent text-accent-fg shadow-[0_0_15px_rgb(var(--accent)/0.6)] scale-110'
          : 'bg-surface-1 border-medical-200 text-medical-500 shadow-lg hover:text-medical-900 hover:border-medical-300'"
  >
    <span v-if="chatStore.unreadCount > 0" class="font-bold font-mono text-sm relative z-10 animate-pulse">
      {{ chatStore.unreadCount > 99 ? '99+' : chatStore.unreadCount }}
    </span>
    <MessageSquare v-else class="w-5 h-5 relative z-10"/>
  </button>
</template>


<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { useChatStore } from '../stores/chat';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useUiStore } from '../stores/ui';
import { useToast } from '../composables/useToast';
import { useDraggable, useEventListener, useWindowSize } from '@vueuse/core';
import { autoUpdate, flip, offset, shift, size, useFloating } from '@floating-ui/vue';
import { ChevronDown, Loader2, MessageSquare, Send, Terminal, X, Zap } from 'lucide-vue-next';
import { useVisualViewport } from '../composables/useVisualViewport';
import { buildChatRows } from '../logic/chatRows.js';
import dayjs from 'dayjs';

const chatStore = useChatStore();
const playerStore = usePlayerStore();
const userStore = useUserStore();
const uiStore = useUiStore();
const { success, error } = useToast();
const { width: windowWidth, height: windowHeight } = useWindowSize();
const vv = useVisualViewport();

// 新增: 创建一个计算属性来判断是否为移动端
const isMobile = computed(() => windowWidth.value < 768);

const inputContent = ref('');
const msgListRef = ref(null);
const launcherRef = ref(null);
const panelRef = ref(null);

const activeTab = ref('CHAT'); // 'CHAT' | 'SYSTEM'

const BUTTON_SIZE = 44;
const MARGIN = 10;

// === 1. 拖拽：只移动 launcher 一个元素，位置持久化 ===
const POS_KEY = 'mp_chat_pos';
// 移动端底部有固定播放器（约 142px + 安全区）：launcher 不能被拖/夹到播放器上，
// 否则会盖住歌名和播放按钮（360×780、320×700 下尤其明显）。
const bottomReserved = () => (isMobile.value ? 190 : 0);
const maxY = () => Math.max(MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN - bottomReserved());

const readPosition = () => {
  try {
    const parsed = JSON.parse(localStorage.getItem(POS_KEY) || 'null');
    if (parsed && Number.isFinite(parsed.x) && Number.isFinite(parsed.y)) {
      return clampToViewport({ x: parsed.x, y: parsed.y });
    }
  } catch { /* 忽略坏数据 */ }
  return {
    x: Math.max(MARGIN, window.innerWidth - BUTTON_SIZE - 16),
    y: Math.max(MARGIN, window.innerHeight - 210)
  };
};
const clampToViewport = (pos) => {
  pos.x = Math.min(Math.max(MARGIN, pos.x), Math.max(MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN));
  pos.y = Math.min(Math.max(MARGIN, pos.y), maxY());
  return pos;
};

const { x, y, style: launcherStyle } = useDraggable(launcherRef, {
  initialValue: readPosition(),
  preventDefault: true,
  onMove: (position) => clampToViewport(position)
});

let saveTimer = 0;
const persistPosition = () => {
  try { localStorage.setItem(POS_KEY, JSON.stringify({ x: Math.round(x.value), y: Math.round(y.value) })); } catch { /* ignore */ }
};
watch([x, y], () => {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(persistPosition, 300);
});
watch([windowWidth, windowHeight], () => {
  const pos = clampToViewport({ x: x.value, y: y.value });
  x.value = pos.x;
  y.value = pos.y;
});

// === 2. 点击与防误触 ===
let startDragPos = { x: 0, y: 0 };
const handlePointerDown = (e) => {
  startDragPos = { x: e.clientX, y: e.clientY };
};
const handleClick = (e) => {
  const dx = Math.abs(e.clientX - startDragPos.x);
  const dy = Math.abs(e.clientY - startDragPos.y);
  if (dx > 5 || dy > 5) return; // 位移过大视为拖拽

  if (userStore.isGuest) {
    userStore.setPostNameAction(() => {
      if (!chatStore.isOpen) chatStore.toggleChat();
    });
    userStore.showNameModal = true;
    return;
  }
  chatStore.toggleChat();
};

// === 3. 面板定位：Floating UI 锚定到 launcher ===
const isRightSide = computed(() => x.value + BUTTON_SIZE / 2 > windowWidth.value / 2);
const isBottomSide = computed(() => y.value + BUTTON_SIZE / 2 > windowHeight.value / 2);
const placement = computed(() => `${isBottomSide.value ? 'top' : 'bottom'}-${isRightSide.value ? 'end' : 'start'}`);

const { floatingStyles } = useFloating(launcherRef, panelRef, {
  placement,
  strategy: 'fixed',
  middleware: [
    offset(12),
    flip({ padding: MARGIN }),
    shift({ padding: MARGIN }),
    size({
      padding: MARGIN,
      apply({ availableHeight, availableWidth, elements }) {
        if (isMobile.value) {
          elements.floating.style.maxWidth = '';
          return;
        }
        const height = `${Math.min(480, Math.max(220, Math.floor(availableHeight)))}px`;
        const maxWidth = `${Math.min(340, Math.max(220, Math.floor(availableWidth)))}px`;
        if (elements.floating.style.height !== height) elements.floating.style.height = height;
        if (elements.floating.style.maxWidth !== maxWidth) elements.floating.style.maxWidth = maxWidth;
      }
    })
  ],
  // 拖拽改变的是 transform，不会触发 resize/scroll；用 rAF 持续跟随
  whileElementsMounted: (reference, floating, update) =>
      autoUpdate(reference, floating, update, { animationFrame: true })
});

// iOS/Android 键盘只缩视觉视口：移动端用 vv 定高，桌面用 Floating UI
const mobileWindowStyle = computed(() => {
  if (!isMobile.value) return {};
  const available = Math.max(240, vv.visibleHeight.value || window.innerHeight);
  // 键盘展开时 visualViewport 明显小于布局视口；此时让聊天占满可见区，
  // 键盘收起时给底部播放器留出 150px，避免面板压住歌名和播放按钮。
  const keyboardOpen = available < window.innerHeight - 80;
  const playerReserve = keyboardOpen ? 8 : 150;
  const headerReserve = 64;
  const height = Math.min(640, Math.max(240, Math.round(available - headerReserve - playerReserve)));
  return {
    position: 'fixed',
    left: '50%',
    top: `${Math.round(vv.offsetTop.value + headerReserve)}px`,
    width: 'min(92vw, 420px)',
    height: `${height}px`,
    transform: 'translateX(-50%)'
  };
});

const panelStyle = computed(() => (isMobile.value ? mobileWindowStyle.value : floatingStyles.value));

// === 4. 焦点与外部点击 ===
watch(() => chatStore.isOpen, async (open) => {
  await nextTick();
  if (open) {
    panelRef.value?.focus({ preventScroll: true });
  } else {
    launcherRef.value?.focus({ preventScroll: true });
  }
});

useEventListener(document, 'pointerdown', (event) => {
  if (!chatStore.isOpen) return;
  const target = event.target;
  if (panelRef.value?.contains(target) || launcherRef.value?.contains(target)) return;
  chatStore.toggleChat();
}, true);

// === 5. 消息处理 ===
const isSelf = (msg) => msg.userId === userStore.userToken;
const formatTime = (ts) => dayjs(ts).format('MM-DD HH:mm');
const processedMessages = computed(() => buildChatRows(chatStore.messages, activeTab.value));

// === 6. 滚动（聊天列表的契约：位置优先，贴底才跟随） ===
const NEAR_BOTTOM = 72;
const stickToBottom = ref(true);
const newBelow = ref(0);
const loadArmed = ref(true);
const pendingAnchor = ref(null);
let anchorTimeout = 0;

const isAtBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight <= NEAR_BOTTOM;

const scrollToBottom = async (force = false) => {
  if (!force && !stickToBottom.value) return;
  await nextTick();
  const el = msgListRef.value;
  if (!el) return;
  el.scrollTop = el.scrollHeight;
  stickToBottom.value = true;
  newBelow.value = 0;
  requestAnimationFrame(() => {
    const node = msgListRef.value;
    if (node) node.scrollTop = node.scrollHeight;
  });
};

const anchorSelector = (id) => `[data-msg-id="${String(id).replace(/["\\]/g, '\\$&')}"]`;

/** 记录当前视口里第一条可见消息，作为加载旧消息后的位置锚点。 */
const captureAnchor = (el) => {
  const top = el.getBoundingClientRect().top;
  for (const node of el.querySelectorAll('[data-msg-id]')) {
    const rect = node.getBoundingClientRect();
    if (rect.bottom > top + 1) {
      return { id: node.getAttribute('data-msg-id'), top: rect.top };
    }
  }
  return null;
};

const restoreAnchor = (el, anchor) => {
  if (!anchor) return;
  const node = el.querySelector(anchorSelector(anchor.id));
  if (!node) return;
  const delta = node.getBoundingClientRect().top - anchor.top;
  if (Math.abs(delta) > 0.5) el.scrollTop += delta;
};

const handleScroll = () => {
  const el = msgListRef.value;
  if (!el) return;

  stickToBottom.value = isAtBottom(el);
  if (stickToBottom.value) newBelow.value = 0;
  if (el.scrollTop > NEAR_BOTTOM) loadArmed.value = true;

  // 到顶只触发一次；离开顶部再回来才会触发下一页。
  // 没有消息时不发请求（初始历史可能还在路上，否则会重复请求 offset=0）。
  if (el.scrollTop <= 28 && loadArmed.value && chatStore.messages.length > 0
      && chatStore.hasMore && !chatStore.isLoadingMore) {
    loadArmed.value = false;
    pendingAnchor.value = captureAnchor(el);
    chatStore.loadMoreHistory();
    clearTimeout(anchorTimeout);
    anchorTimeout = setTimeout(() => { pendingAnchor.value = null; }, 12_000);
  }
};

const jumpToLatest = () => scrollToBottom(true);

/**
 * 列表短到无法滚动时，用户没有机会"滚到顶部"触发加载。
 * 打开时补 1–2 页，保证历史可达（有上限，避免一次拉完整段历史）。
 */
const fillViewport = async (attempts = 2) => {
  await nextTick();
  const el = msgListRef.value;
  if (!el || attempts <= 0 || !chatStore.messages.length) return;
  if (!chatStore.hasMore || chatStore.isLoadingMore) return;
  if (el.scrollHeight > el.clientHeight + 8) return;
  chatStore.loadMoreHistory();
  setTimeout(() => fillViewport(attempts - 1), 450);
};

watch(() => chatStore.messages.length, async (next, prev) => {
  if (next === prev) return;
  await nextTick();
  const el = msgListRef.value;
  if (!el) return;

  if (pendingAnchor.value) {
    restoreAnchor(el, pendingAnchor.value);
    pendingAnchor.value = null;
    clearTimeout(anchorTimeout);
    return;
  }

  if (stickToBottom.value) {
    await scrollToBottom(true);
    if (chatStore.isOpen) fillViewport(1);
  } else if (next > prev) {
    newBelow.value += next - prev;
  }
});

watch(() => chatStore.isLoadingMore, (loading) => {
  if (!loading) {
    setTimeout(() => {
      const el = msgListRef.value;
      // 只有离开顶部才重新武装，否则一次触底会把整段历史连续拉完
      if (el && el.scrollTop > NEAR_BOTTOM) loadArmed.value = true;
    }, 150);
  }
});

watch([() => chatStore.isOpen, activeTab], async ([open]) => {
  if (!open) return;
  chatStore.unreadCount = 0;
  stickToBottom.value = true;
  newBelow.value = 0;
  await scrollToBottom(true);
  fillViewport(2);
});

// 键盘弹出/收起、旋转都会改变视觉视口；原本贴底就继续贴底
watch(() => vv.height.value, () => {
  if (chatStore.isOpen && stickToBottom.value) scrollToBottom(true);
});

// === 7. 交互 ===
const send = () => {
  const text = inputContent.value.trim();
  if (!text) return;

  if (userStore.isGuest) {
    error('请先设置名字后再参与聊天');
    userStore.showNameModal = true;
    return;
  }

  if (text === '//alive') {
    uiStore.toggleKeepAlive();
    success(`Keep-Alive ${uiStore.keepAliveEnabled ? 'ENABLED' : 'DISABLED'}. Please refresh to apply.`);
    inputContent.value = '';
    return;
  }

  playerStore.sendChatMessage(text);
  inputContent.value = '';
  // 只让消息列表的 watcher 滚动一次；这里再 setTimeout 会在消息到达前后各滚一次
  stickToBottom.value = true;
  newBelow.value = 0;
};

onUnmounted(() => {
  clearTimeout(anchorTimeout);
  clearTimeout(saveTimer);
  persistPosition();
});
</script>

<style scoped>
.chat-scroll::-webkit-scrollbar {
  width: 4px;
}
.chat-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chat-scroll::-webkit-scrollbar-thumb {
  @apply bg-accent/20 rounded;
}
.chat-scroll::-webkit-scrollbar-thumb:hover {
  @apply bg-accent/50;
}
</style>
