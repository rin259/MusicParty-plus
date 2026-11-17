<script setup>
import { computed, ref } from 'vue';
import { roomMailApi } from '../api/rooms';

const props = defineProps({
  room: { type: String, default: '' },
  token: { type: String, default: '' },
  action: { type: String, required: true }
});

const confirm = computed(() => props.action === 'confirm');
const title = computed(() => confirm.value ? '确认房间订阅' : '取消房间订阅');
const busy = ref(false);
const done = ref(false);
const error = ref('');
const roomLink = computed(() => props.room ? `?room=${encodeURIComponent(props.room)}` : window.location.pathname);

async function submit() {
  if (busy.value || done.value) return;
  if (!props.room || !props.token) {
    error.value = '订阅链接无效，请重新打开邮件中的完整链接。';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    if (confirm.value) await roomMailApi.confirm(props.room, props.token);
    else await roomMailApi.unsubscribe(props.room, props.token);
    done.value = true;
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
  } catch (cause) {
    error.value = cause?.response?.data?.message || '暂时无法完成操作，请稍后再试。';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="grid min-h-app place-items-center overflow-auto bg-medical-50 p-4 text-medical-900">
    <section aria-labelledby="room-mail-link-title" class="w-full max-w-sm space-y-4 border border-medical-200 bg-surface-1 p-5">
      <h1 id="room-mail-link-title" class="m-0 text-lg font-bold">{{ title }}</h1>
      <p v-if="done" role="status" class="text-sm leading-relaxed text-medical-700">
        {{ confirm ? '订阅已确认。房间发出召唤时，你会收到一封听歌邀请。' : '订阅已取消。' }}
      </p>
      <p v-else class="text-sm leading-relaxed text-medical-700">
        {{ confirm ? '确认后，你将收到这个房间的听歌邀请。' : '取消后，你将停止接收这个房间的听歌邀请。' }}
      </p>
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
      <div class="flex flex-wrap gap-2">
        <button v-if="!done" type="button" :disabled="busy" class="min-h-11 bg-accent px-4 text-sm font-bold text-accent-fg hover:bg-accent-hover disabled:opacity-60" @click="submit">{{ busy ? '处理中…' : title }}</button>
        <a :href="roomLink" class="inline-flex min-h-11 items-center border border-medical-200 px-4 text-sm text-medical-700 hover:bg-medical-100">{{ room ? '打开房间' : '返回首页' }}</a>
      </div>
    </section>
  </main>
</template>
