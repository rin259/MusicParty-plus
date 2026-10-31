<template>
  <div v-if="!passed" class="fixed inset-0 z-[130] bg-medical-50 flex items-center justify-center p-4">
    <div ref="dialogRef" role="dialog" aria-modal="true" aria-labelledby="room-gate-title" class="bg-surface-1 p-8 shadow-2xl border border-medical-200 w-full max-w-md chamfer-br relative">
      <!-- 装饰 -->
      <div class="absolute top-0 left-0 w-full h-0.5 bg-surface-strong"></div>

      <div class="mb-6">
        <h2 id="room-gate-title" class="text-2xl font-bold text-medical-900 tracking-tighter">
          {{ isSetupMode ? 'INITIALIZE SYSTEM' : 'ROOM SECURITY ACCESS' }}
        </h2>
        <p class="text-xs font-mono text-medical-500 mt-1">
          {{ isSetupMode ? 'PLEASE CONFIGURE ROOM ACCESS.' : 'RESTRICTED AREA. ENTER PASSCODE.' }}
        </p>
      </div>

      <div class="space-y-4">
        <input
            v-if="!isSetupMode || (isSetupMode && setupType === 'password')"
            v-model="inputPassword"
            type="password"
            :placeholder="isSetupMode ? 'SET NEW PASSWORD' : 'INPUT PASSWORD'"
            :aria-label="isSetupMode ? '设置房间密码' : '输入房间密码'"
            @keyup.enter="handleAction"
            class="w-full min-h-12 bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-mono text-center tracking-widest text-lg text-medical-900 placeholder:text-medical-500"
            autofocus
        />

        <button
            v-if="!isSetupMode || setupType === 'password'"
            @click="handleAction"
            :disabled="loading"
            class="w-full min-h-11 bg-surface-strong text-white font-bold py-3 hover:bg-accent hover:text-accent-fg transition-colors disabled:opacity-50"
        >
          {{ loading ? 'VERIFYING...' : (isSetupMode ? 'CONFIRM PASSWORD' : 'UNLOCK') }}
        </button>

        <div v-if="isSetupMode && setupType === 'initial'" class="space-y-3">
          <button
              @click="setupType = 'password'"
              class="w-full min-h-11 bg-surface-strong text-white font-bold py-3 hover:bg-accent hover:text-accent-fg transition-colors chamfer-br"
          >
            SET PASSWORD PROTECTION
          </button>

          <div class="relative flex py-2 items-center">
            <div class="flex-grow border-t border-medical-200"></div>
            <span class="flex-shrink-0 mx-4 text-medical-500 text-xs font-mono">OR</span>
            <div class="flex-grow border-t border-medical-200"></div>
          </div>

          <button
              @click="setupNoPassword"
              class="w-full min-h-11 bg-surface-1 border border-medical-300 text-medical-600 font-bold py-3 hover:bg-medical-100 transition-colors hover:text-medical-900"
          >
            NO PASSWORD (PUBLIC)
          </button>
        </div>

        <button
            v-if="isSetupMode && setupType === 'password'"
            @click="setupType = 'initial'"
            class="w-full min-h-11 text-xs text-medical-600 hover:text-medical-900 mt-2 underline"
        >
          &lt; BACK
        </button>

      </div>

      <div v-if="errorMsg" role="alert" class="mt-4 text-center text-danger font-mono text-xs animate-pulse">
        > ERROR: {{ errorMsg }}
      </div>
    </div>
  </div>
</template>

<script setup>
import {ref, computed, onMounted} from 'vue';
import {authApi} from '../api/auth';
import {STORAGE_KEYS} from '../constants/keys';
import { useFocusTrap } from '../composables/useFocusTrap';

const emit = defineEmits(['unlocked']);

const passed = ref(false);
const isSetupMode = ref(false);
const setupType = ref('initial'); // 'initial' | 'password'
const inputPassword = ref('');
const errorMsg = ref('');
const loading = ref(false);
const dialogRef = ref(null);
useFocusTrap(computed(() => !passed.value), dialogRef);

const checkStatus = async () => {
  loading.value = true;
  try {
    // 🟢 修复点：直接获取数据，不再需要 .data
    // 我们的 api/client.js 里的拦截器已经帮我们把 data 取出来了
    const data = await authApi.getStatus();
    const {isSetup, hasProtection} = data;

    if (!isSetup) {
      isSetupMode.value = true;
    } else {
      if (!hasProtection) {
        passed.value = true;
        emit('unlocked');
      } else {
        const cachedPass = localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD);
        if (cachedPass) {
          await verify(cachedPass, true);
        }
      }
    }
  } catch (e) {
    console.error("Auth Status Error:", e); // 在控制台打印真实错误
    errorMsg.value = "CONNECTION FAILED";
  } finally {
    loading.value = false;
  }
};

const verify = async (pwd, isAuto = false) => {
  try {
    await authApi.verify(pwd);
    localStorage.setItem(STORAGE_KEYS.ROOM_PASSWORD, pwd);
    passed.value = true;
    emit('unlocked');
  } catch (e) {
    if (!isAuto) {
      errorMsg.value = "INVALID PASSWORD";
      inputPassword.value = '';
    } else {
      localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD);
    }
  }
};

const setup = async () => {
  if (!inputPassword.value) {
    errorMsg.value = "PASSWORD CANNOT BE EMPTY";
    return;
  }
  await performSetup(inputPassword.value);
};

const setupNoPassword = async () => {
  await performSetup("");
};

const performSetup = async (pwd) => {
  loading.value = true;
  try {
    await authApi.setup(pwd);
    if (pwd) localStorage.setItem(STORAGE_KEYS.ROOM_PASSWORD, pwd);
    passed.value = true;
    emit('unlocked');
  } catch (e) {
    errorMsg.value = "SETUP FAILED";
    loading.value = false;
  }
};

const handleAction = () => {
  if (loading.value) return;
  errorMsg.value = '';
  if (isSetupMode.value) {
    setup();
  } else {
    verify(inputPassword.value);
  }
};

onMounted(() => {
  checkStatus();
});
</script>