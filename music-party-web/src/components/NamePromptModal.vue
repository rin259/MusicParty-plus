<!-- File: music-party-web/src/components/NamePromptModal.vue -->
<template>
  <div v-if="userStore.showNameModal" class="fixed inset-0 z-[110] bg-scrim/90 backdrop-blur-sm flex items-center justify-center p-4">
    <div ref="dialogRef" role="dialog" aria-modal="true" aria-labelledby="name-modal-title" aria-describedby="name-modal-help" class="bg-surface-1 p-6 w-[calc(100vw-2rem)] max-w-sm max-h-[calc(100dvh-2rem)] overflow-y-auto chamfer-br shadow-2xl relative mp-scroll">
      <!-- 装饰条 -->
      <div class="absolute top-0 left-0 w-full h-0.5 bg-accent"></div>

      <h2 id="name-modal-title" class="text-xl font-bold text-medical-900 mb-2">IDENTIFICATION REQUIRED</h2>
      <p id="name-modal-help" class="text-xs font-sans text-medical-500 mb-6 leading-relaxed">
        在操作之前，先给自己取个名字吧<br>
        也可以在用户列表中点击自己的名字重命名
      </p>

      <input
          v-model="inputName"
          @keyup.enter="confirm"
          placeholder="ENTER CODENAME"
          aria-label="你的昵称"
          @focus="revealOnFocus"
          class="w-full min-h-11 bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-bold mb-4 text-medical-900 placeholder:text-medical-500"
          autofocus
      />
      
      <div v-if="errorMsg" role="alert" class="text-xs text-danger font-bold mb-4 animate-pulse">{{ errorMsg }}</div>

      <div class="flex gap-2">
        <button @click="userStore.showNameModal = false" class="flex-1 min-h-11 py-3 text-xs font-bold text-medical-600 hover:bg-medical-100">
          CANCEL
        </button>
        <button @click="confirm" class="flex-1 min-h-11 bg-surface-strong text-white font-bold py-3 hover:bg-accent hover:text-accent-fg transition-colors">
          CONFIRM
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';
import { useFocusTrap } from '../composables/useFocusTrap';
import { revealOnFocus } from '../utils/viewport';

const userStore = useUserStore();
const playerStore = usePlayerStore();
const dialogRef = ref(null);
useFocusTrap(computed(() => userStore.showNameModal), dialogRef);
const inputName = ref('');
const errorMsg = ref('');

watch(inputName, () => errorMsg.value = '');

const confirm = () => {
  const name = inputName.value.trim();
  if(!name) return;

  if (name.toLowerCase().startsWith('guest') || name.startsWith('游客')) {
    errorMsg.value = '不能使用“游客”作为正式名字';
    return;
  }
  
  // 调用 renameUser，等待后端 socket 确认后关闭
  playerStore.renameUser(name);
};
</script>