<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { useToast } from '../composables/useToast';
import { roomMailApi } from '../api/rooms';

const props = defineProps({
  roomId: { type: String, required: true },
  roomName: { type: String, required: true },
  roomPassword: { type: String, default: '' }
});

const { success } = useToast();
const dialog = ref(null);
const emailInput = ref(null);
const backButton = ref(null);
const mode = ref('subscribe');
const email = ref('');
const busy = ref(false);
const error = ref('');
const subscribing = computed(() => mode.value === 'subscribe');
const unsubscribing = computed(() => mode.value === 'unsubscribe');
const title = computed(() => {
  if (unsubscribing.value) return '该邮箱已订阅本房间。是否退订？';
  return subscribing.value ? '订阅房间' : '召唤订阅者';
});
const action = computed(() => {
  if (busy.value) return unsubscribing.value ? '正在退订…' : '处理中…';
  if (unsubscribing.value) return '退订';
  return subscribing.value ? '继续' : '发送邀请';
});

watch(unsubscribing, (show) => {
  if (dialog.value?.open) (show ? backButton.value : emailInput.value)?.focus();
}, { flush: 'post' });

async function open(nextMode) {
  mode.value = nextMode;
  email.value = '';
  error.value = '';
  await nextTick();
  dialog.value?.showModal();
}

function back() {
  if (busy.value) return;
  if (unsubscribing.value) {
    mode.value = 'subscribe';
    error.value = '';
  } else {
    dialog.value?.close();
  }
}

async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    if (subscribing.value) {
      email.value = email.value.trim();
      const response = await roomMailApi.subscribe(props.roomId, email.value, props.roomPassword);
      if (response.status === 'ALREADY_SUBSCRIBED') {
        mode.value = 'unsubscribe';
        return;
      }
      success('如果此邮箱尚未确认，请查收确认邮件。');
    } else if (unsubscribing.value) {
      await roomMailApi.unsubscribeByEmail(props.roomId, email.value, props.roomPassword);
      success('已退订本房间。');
    } else {
      const { queued } = await roomMailApi.summon(props.roomId, props.roomPassword);
      success(`正在向 ${queued} 位订阅者发送听歌邀请。`);
    }
    dialog.value?.close();
  } catch (cause) {
    error.value = cause?.response?.data?.message || '暂时无法完成操作，请稍后再试。';
  } finally {
    busy.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <dialog
    ref="dialog"
    aria-labelledby="room-mail-title"
    class="room-mail-dialog w-[calc(100%-2rem)] max-w-sm border border-medical-200 bg-surface-1 text-medical-900"
    @cancel.prevent="back"
    @keydown.stop
  >
    <form class="space-y-4 p-5" @submit.prevent="submit">
      <div>
        <h2 id="room-mail-title" class="m-0 break-words text-lg font-bold">{{ title }}</h2>
        <p class="mt-1 break-words text-sm leading-relaxed text-medical-600">
          <template v-if="unsubscribing">{{ email }}</template>
          <template v-else-if="subscribing">首次订阅需确认邮件，已订阅的邮箱也可在此退订。</template>
          <template v-else>向已订阅「{{ roomName }}」的人发送一封听歌邀请。</template>
        </p>
      </div>
      <div v-if="subscribing">
        <label for="room-subscription-email" class="mb-1.5 block text-sm font-medium">邮箱地址</label>
        <input
          id="room-subscription-email"
          ref="emailInput"
          v-model="email"
          autofocus
          type="email"
          autocomplete="email"
          required
          maxlength="254"
          :disabled="busy"
          class="min-h-11 w-full border border-medical-300 bg-medical-50 px-3 text-base text-medical-900 focus:border-accent focus:outline-none disabled:opacity-60"
          @input="error = ''"
        />
      </div>
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
      <div class="flex flex-wrap justify-end gap-2">
        <button ref="backButton" type="button" :disabled="busy" class="min-h-11 border border-medical-200 px-4 text-sm text-medical-700 hover:bg-medical-100 disabled:opacity-60" @click="back">
          {{ unsubscribing ? '返回' : '取消' }}
        </button>
        <button type="submit" :disabled="busy" class="min-h-11 px-4 text-sm font-bold disabled:opacity-60"
          :class="unsubscribing ? 'border border-danger-border bg-danger-bg text-danger hover:border-danger' : 'bg-accent text-accent-fg hover:bg-accent-hover'">
          {{ action }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.room-mail-dialog {
  margin: auto;
  padding: 0;
  max-height: calc(100dvh - 2rem);
}

.room-mail-dialog::backdrop {
  background: rgb(var(--scrim) / 0.55);
}
</style>
