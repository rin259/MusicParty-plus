<script setup>
import { computed, onMounted, ref } from 'vue';
import { CircleDot, Save, Trash2, Youtube } from 'lucide-vue-next';
import { adminApi } from '../api/admin';

const props = defineProps({ adminPassword: { type: String, required: true } });
const status = ref(null);
const cookies = ref('');
const loading = ref(true);
const saving = ref(false);
const testing = ref(false);
const error = ref('');
const notice = ref('');
const testResult = ref(null);
const inputClass = 'w-full min-w-0 min-h-11 border border-medical-200 bg-medical-50 px-2.5 py-1.5 font-mono text-xs text-medical-900 outline-none focus:border-accent placeholder:text-medical-500 disabled:opacity-60';
const dirty = computed(() => cookies.value.trim().length > 0);

// A whole-browser export can be megabytes; send only the domains yt-dlp needs.
function filterCookies(text) {
  const kept = [];
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const bare = trimmed.startsWith('#HttpOnly_') ? trimmed.slice('#HttpOnly_'.length) : trimmed;
    if (bare.startsWith('#')) {
      if (bare.toLowerCase().includes('netscape http cookie file')) kept.push(trimmed);
      continue;
    }
    const domain = bare.split('\t', 1)[0].toLowerCase();
    if (domain.endsWith('youtube.com') || domain.endsWith('google.com') || domain.endsWith('googlevideo.com')) {
      kept.push(trimmed);
    }
  }
  return kept.join('\n');
}
const health = ref(null);
const checking = ref(false);
const cookieCount = computed(() => filterCookies(cookies.value).split('\n').filter((line) => line && !line.startsWith('#')).length);
const updatedLabel = computed(() => {
  if (!status.value?.updatedAt) return '';
  return new Date(status.value.updatedAt).toLocaleString('zh-CN', { hour12: false });
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    status.value = await adminApi.youtubeSettings(props.adminPassword);
  } catch {
    error.value = '无法读取 YouTube 设置，请重试。';
  } finally {
    loading.value = false;
  }
  loadHealth();
}

async function loadHealth() {
  try {
    health.value = await adminApi.youtubeHealth(props.adminPassword);
  } catch {
    health.value = null;
  }
}

const healthLabel = computed(() => {
  if (!health.value?.checked) return '';
  const when = new Date(health.value.checkedAt).toLocaleString('zh-CN', { hour12: false });
  return `${health.value.healthy ? '会话正常' : '会话异常'} · ${when}`;
});

async function recheckHealth() {
  if (checking.value) return;
  checking.value = true;
  try {
    health.value = await adminApi.probeYoutubeHealth(props.adminPassword);
  } catch {
    error.value = '健康检查失败，请稍后重试。';
  } finally {
    checking.value = false;
  }
}

async function save() {
  if (!dirty.value || saving.value) return;
  saving.value = true;
  error.value = '';
  notice.value = '';
  testResult.value = null;
  const filtered = filterCookies(cookies.value);
  if (!filtered.includes('\t')) {
    error.value = '没有找到 youtube.com 或 google.com 的 cookie，请确认导出的是 YouTube 登录态。';
    saving.value = false;
    return;
  }
  try {
    status.value = await adminApi.saveYoutubeCookies(props.adminPassword, filtered);
    cookies.value = '';
    notice.value = 'YouTube cookies 已保存，下一首立即生效';
  } catch (cause) {
    error.value = cause?.response?.data?.message || '保存失败，请检查内容后重试。';
  } finally {
    saving.value = false;
  }
}

async function test() {
  if (testing.value) return;
  testing.value = true;
  error.value = '';
  testResult.value = null;
  try {
    testResult.value = await adminApi.testYoutube(props.adminPassword);
  } catch (cause) {
    error.value = cause?.response?.data?.message || '测试失败，请稍后重试。';
  } finally {
    testing.value = false;
  }
}

async function clear() {
  if (saving.value) return;
  saving.value = true;
  error.value = '';
  notice.value = '';
  testResult.value = null;
  try {
    status.value = await adminApi.clearYoutubeCookies(props.adminPassword);
    cookies.value = '';
    notice.value = '已移除 YouTube cookies';
  } catch (cause) {
    error.value = cause?.response?.data?.message || '移除失败，请稍后重试。';
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section aria-labelledby="admin-youtube-heading" class="overflow-hidden border border-medical-200 bg-surface-1 shadow-sm chamfer-br">
    <header class="flex items-center justify-between gap-2 bg-surface-strong p-3 text-white">
      <h3 id="admin-youtube-heading" class="m-0 flex items-center gap-2 text-xs font-bold">
        <Youtube class="h-4 w-4" aria-hidden="true" /> YouTube 取流
      </h3>
      <span v-if="status" class="font-mono text-[10px] tracking-[0.12em] text-white/80">
        {{ status.configured ? `COOKIES ${Math.ceil(status.bytes / 1024)}KB` : 'NO COOKIES' }}
      </span>
    </header>

    <p v-if="loading" role="status" class="p-4 text-sm text-medical-600">正在读取 YouTube 设置…</p>
    <div v-else-if="!status" class="space-y-2 p-4">
      <p role="alert" class="text-sm text-danger">{{ error }}</p>
      <button type="button" class="min-h-11 border border-medical-200 px-3 text-sm text-medical-700 hover:bg-medical-100" @click="load">重试</button>
    </div>
    <form v-else class="space-y-3 p-4" @submit.prevent="save" @input="notice = ''">
      <p class="text-xs leading-relaxed text-medical-600">
        YouTube 会对机房 IP 返回「确认你不是机器人」。粘贴浏览器导出的
        <code class="bg-medical-50 px-1 font-mono text-[11px]">cookies.txt</code>（Netscape 格式）后，
        取流才会成功。内容只保存在服务端，读取接口不会返回。
      </p>
      <p v-if="status.configured" class="flex items-center gap-1.5 font-mono text-[10px] tracking-[0.12em]"
          :class="status.sessionReady ? 'text-success' : 'text-danger'">
        <CircleDot class="h-3 w-3" aria-hidden="true" />
        {{ status.sessionReady ? '登录态有效' : '无登录态' }} · {{ updatedLabel }}
      </p>
      <div v-if="status.configured && health?.checked" class="flex flex-wrap items-center justify-between gap-2 border border-medical-200 bg-medical-50 px-3 py-2">
        <span class="flex items-center gap-1.5 font-mono text-[10px] tracking-[0.12em]"
            :class="health.healthy ? 'text-success' : 'text-danger'">
          <CircleDot class="h-3 w-3" aria-hidden="true" />
          {{ healthLabel }}
        </span>
        <button type="button" :disabled="checking"
            class="min-h-9 border border-medical-200 bg-surface-1 px-2 font-mono text-[10px] text-medical-600 hover:border-accent hover:text-accent-text disabled:opacity-50"
            @click="recheckHealth">
          {{ checking ? '检测中…' : '立即检测' }}
        </button>
      </div>

      <p v-if="status.configured && !status.sessionReady" role="alert"
          class="border border-danger/40 bg-danger-bg px-3 py-2 text-xs leading-relaxed text-danger">
        这份 cookies 缺少登录必需的 HttpOnly 字段：{{ (status.missing || []).join('、') }}。
        用 <code class="font-mono">document.cookie</code> 或只读脚本 cookie 的扩展导出会漏掉它们，
        取流会一直失败。请重新导出完整 cookies。
      </p>
      <div>
        <div class="mb-1 flex items-baseline justify-between gap-2">
          <label for="admin-youtube-cookies" class="block text-xs font-bold text-medical-600">cookies.txt 内容</label>
          <span v-if="cookieCount" class="font-mono text-[10px] text-medical-500">YOUTUBE/GOOGLE {{ cookieCount }}</span>
        </div>
        <textarea
            id="admin-youtube-cookies"
            v-model="cookies"
            :class="inputClass"
            class="min-h-32 py-2"
            rows="6"
            spellcheck="false"
            autocomplete="off"
            wrap="off"
            :placeholder="status.configured ? '已配置。粘贴新内容可整体替换' : '# Netscape HTTP Cookie File'"
        />
      </div>
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
      <p v-if="notice" role="status" class="text-sm text-success">{{ notice }}</p>
      <p v-if="testResult" role="status" class="font-mono text-xs" :class="testResult.ok ? 'text-success' : 'text-danger'">
        {{ testResult.message }}
      </p>
      <div class="flex flex-wrap gap-2">
        <button type="submit" :disabled="!dirty || saving" class="flex min-h-11 flex-1 items-center justify-center gap-2 bg-surface-strong px-3 font-bold text-white hover:bg-accent hover:text-accent-fg disabled:cursor-not-allowed disabled:bg-medical-200 disabled:text-medical-500">
          <Save class="h-4 w-4" aria-hidden="true" /> {{ saving ? '正在保存…' : '保存 cookies' }}
        </button>
        <button type="button" :disabled="!status.configured || testing" class="flex min-h-11 items-center justify-center gap-2 border border-medical-200 px-3 text-sm font-bold text-medical-700 hover:border-accent hover:text-accent-text disabled:opacity-50" @click="test">
          {{ testing ? '正在验证…' : '验证取流' }}
        </button>
        <button v-if="status.configured" type="button" :disabled="saving" class="flex min-h-11 items-center justify-center gap-1.5 border border-medical-200 px-3 text-sm text-medical-600 hover:border-danger hover:text-danger disabled:opacity-50" @click="clear">
          <Trash2 class="h-4 w-4" aria-hidden="true" /> 移除
        </button>
      </div>
    </form>
  </section>
</template>