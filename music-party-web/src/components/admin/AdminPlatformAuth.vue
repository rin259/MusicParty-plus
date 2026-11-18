<script setup>
import { computed, onMounted, ref } from 'vue';
import { CircleDot, RefreshCw } from 'lucide-vue-next';
import { adminApi } from '../../api/admin';
import { musicApi } from '../../api/music';

const props = defineProps({
  adminPassword: { type: String, required: true },
  platform: { type: Object, required: true }
});

const configured = ref(false);
const loading = ref(true);
const busy = ref(false);
const error = ref('');
const notice = ref('');

const isSoundCloud = computed(() => props.platform.id === 'soundcloud');

async function loadStatus() {
  loading.value = true;
  error.value = '';
  try {
    if (isSoundCloud.value) {
      const data = await musicApi.soundcloudOauthStatus();
      configured.value = !!data?.configured;
    } else {
      const status = await adminApi.youtubeSettings(props.adminPassword);
      configured.value = !!status?.configured;
    }
  } catch {
    error.value = '无法读取授权状态';
  } finally {
    loading.value = false;
  }
}

// Reuse the search modal's flow: open the popup synchronously, then poll the
// server-side state. window.open after an await would be blocked.
async function startSoundCloud() {
  if (busy.value) return;
  const popup = window.open('', 'soundcloud-oauth', 'width=520,height=720');
  if (!popup) {
    error.value = '弹窗被拦截：请允许本站弹窗后再试';
    return;
  }
  busy.value = true;
  error.value = '';
  notice.value = '';
  let session;
  try {
    session = await musicApi.soundcloudOauthStart();
  } catch {
    try { popup.close(); } catch { /* ignore */ }
    busy.value = false;
    error.value = 'SoundCloud 授权未配置或暂时不可用';
    return;
  }
  if (!session?.url || !session?.state) {
    try { popup.close(); } catch { /* ignore */ }
    busy.value = false;
    error.value = 'SoundCloud 授权未配置';
    return;
  }
  try {
    popup.location.href = session.url;
  } catch {
    try { popup.close(); } catch { /* ignore */ }
    busy.value = false;
    error.value = '无法打开授权页，请稍后重试';
    return;
  }
  let attempts = 0;
  const timer = setInterval(async () => {
    attempts += 1;
    try {
      const result = await musicApi.soundcloudOauthResult(session.state);
      if (result?.done) {
        clearInterval(timer);
        try { popup.close(); } catch { /* user may have closed it */ }
        busy.value = false;
        configured.value = !!result.ok;
        if (result.ok) notice.value = 'SoundCloud 授权完成';
        else error.value = result.message || 'SoundCloud 授权失败';
        return;
      }
    } catch { /* keep polling through transient errors */ }
    if (attempts >= 100) {
      clearInterval(timer);
      busy.value = false;
      error.value = 'SoundCloud 授权超时，请重试';
    }
  }, 1200);
}

onMounted(loadStatus);
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between gap-2">
      <span class="font-mono text-[10px] tracking-[0.1em]" :class="configured ? 'text-success' : 'text-medical-500'">
        <CircleDot class="mr-1 inline h-3 w-3" aria-hidden="true" />
        {{ loading ? 'CHECKING…' : configured ? 'AUTHORIZED' : 'NOT AUTHORIZED' }}
      </span>
      <button
          type="button"
          :disabled="loading || busy"
          class="flex min-h-9 items-center gap-1.5 border border-medical-200 bg-medical-50 px-2 font-mono text-[10px] text-medical-600 hover:border-accent hover:text-accent-text disabled:opacity-50"
          @click="isSoundCloud ? startSoundCloud() : loadStatus()"
      >
        <RefreshCw class="h-3 w-3" :class="{ 'animate-spin': busy || loading }" aria-hidden="true" />
        {{ isSoundCloud ? (busy ? 'AUTHORIZING…' : 'AUTHORIZE') : 'REFRESH' }}
      </button>
    </div>

    <p v-if="isSoundCloud" class="m-0 font-mono text-[10px] leading-relaxed text-medical-500">
      授权后搜索与歌单使用你自己的 SoundCloud 账号；未配置 OAuth 时回退到公开搜索。
    </p>
    <p v-else class="m-0 font-mono text-[10px] leading-relaxed text-medical-500">
      YouTube 取流所需的 cookies 在下方「YouTube 取流」面板维护。
    </p>

    <p v-if="error" role="alert" class="m-0 text-[11px] text-danger">{{ error }}</p>
    <p v-if="notice" role="status" class="m-0 text-[11px] text-success">{{ notice }}</p>
  </div>
</template>