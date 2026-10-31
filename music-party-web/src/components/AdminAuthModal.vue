<template>
  <Transition
      enter-active-class="transition duration-300 ease-out"
      enter-from-class="opacity-0 scale-95"
      enter-to-class="opacity-100 scale-100"
      leave-active-class="transition duration-200 ease-in"
      leave-from-class="opacity-100 scale-100"
      leave-to-class="opacity-0 scale-95"
  >
    <div v-if="adminStore.showAuthModal" class="fixed inset-0 z-[90] flex items-center justify-center p-4 bg-scrim/65 backdrop-blur-sm">
      <div ref="dialogRef" role="dialog" aria-modal="true" aria-labelledby="admin-auth-title" class="w-full max-w-sm bg-surface-1 shadow-2xl border border-medical-200 chamfer-br overflow-hidden">
        <div class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center">
          <h3 id="admin-auth-title" class="text-sm font-bold font-mono text-medical-900 flex items-center gap-2">
            <Lock class="w-4 h-4 text-accent-text" aria-hidden="true" /> ADMIN LOGIN
          </h3>
          <button @click="adminStore.showAuthModal = false" class="flex items-center justify-center w-11 h-11 -mr-2 text-medical-500 hover:text-medical-900" aria-label="关闭管理员登录">
            <X class="w-4 h-4" />
          </button>
        </div>

        <div class="p-6">
          <div class="mb-4">
            <label for="admin-password" class="block text-[11px] font-bold text-medical-500 mb-1 uppercase tracking-wider font-mono">Password</label>
            <input
                id="admin-password"
                ref="inputRef"
                v-model="password"
                type="password"
                placeholder="ENTER ADMIN PASSWORD"
                class="w-full min-h-11 bg-medical-50 border border-medical-200 px-3 py-2 text-sm outline-none focus:border-accent font-sans transition-colors text-medical-900 placeholder:text-medical-500"
                @keyup.enter="handleVerify"
            />
          </div>

          <button
              @click="handleVerify"
              :disabled="loading"
              class="w-full min-h-11 bg-surface-strong hover:bg-accent hover:text-accent-fg text-white py-2 font-bold text-sm transition-all duration-300 flex items-center justify-center gap-2 group disabled:opacity-50"
          >
            <Loader2 v-if="loading" class="w-4 h-4 animate-spin" />
            <span v-else>ACCESS SYSTEM</span>
            <ChevronRight v-if="!loading" class="w-4 h-4 group-hover:translate-x-1 transition-transform" />
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, watch, nextTick, computed } from 'vue';
import { useAdminStore } from '../stores/admin';
import { adminApi } from '../api/admin';
import { useToast } from '../composables/useToast';
import { Lock, X, ChevronRight, Loader2 } from 'lucide-vue-next';
import { useFocusTrap } from '../composables/useFocusTrap';

const adminStore = useAdminStore();
const { error, success } = useToast();
const dialogRef = ref(null);
useFocusTrap(computed(() => adminStore.showAuthModal), dialogRef, {
  initialFocus: () => inputRef.value
});
const password = ref('');
const loading = ref(false);
const inputRef = ref(null);

const handleVerify = async () => {
  if (!password.value.trim() || loading.value) return;

  loading.value = true;
  try {
    await adminApi.verify(password.value);
    adminStore.setAdminPassword(password.value);
    adminStore.isVerified = true;
    adminStore.showAuthModal = false;
    adminStore.showDashboard = true;
    success('ACCESS GRANTED');
    password.value = '';
  } catch (e) {
    error(e.response?.data?.message || 'INVALID PASSWORD');
  } finally {
    loading.value = false;
  }
};

watch(() => adminStore.showAuthModal, (val) => {
  if (val) {
    nextTick(() => inputRef.value?.focus());
  }
});
</script>