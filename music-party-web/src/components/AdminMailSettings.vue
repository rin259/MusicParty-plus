<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { Mail, Save } from 'lucide-vue-next';
import { adminApi } from '../api/admin';

const props = defineProps({ adminPassword: { type: String, required: true } });
const fields = ['smtpHost', 'smtpPort', 'smtpUsername', 'baseUrl', 'summonCooldownHours'];
const draft = reactive({ smtpHost: '', smtpPort: 587, smtpUsername: '', baseUrl: '', summonCooldownHours: 6, smtpPassword: '' });
const saved = ref(null);
const loading = ref(true);
const saving = ref(false);
const error = ref('');
const notice = ref('');
const inputClass = 'w-full min-w-0 min-h-11 border border-medical-200 bg-medical-50 px-2.5 py-1.5 text-sm text-medical-900 outline-none focus:border-accent placeholder:text-medical-500 disabled:opacity-60';
const dirty = computed(() => saved.value && (draft.smtpPassword.trim()
  || fields.some(field => draft[field] !== saved.value[field])));

function sync(settings) {
  saved.value = settings;
  for (const field of fields) draft[field] = settings[field];
  draft.smtpPassword = '';
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    sync(await adminApi.mailSettings(props.adminPassword));
  } catch {
    error.value = '无法读取邮件设置，请重试。';
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!dirty.value || saving.value) return;
  saving.value = true;
  error.value = '';
  notice.value = '';
  const patch = Object.fromEntries(fields.filter(field => draft[field] !== saved.value[field])
    .map(field => [field, draft[field]]));
  if (draft.smtpPassword.trim()) patch.smtpPassword = draft.smtpPassword;
  try {
    sync(await adminApi.updateMailSettings(props.adminPassword, patch));
    notice.value = '邮件设置已保存';
  } catch (cause) {
    error.value = cause?.response?.data?.message || '保存邮件设置失败，请重试。';
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section aria-labelledby="admin-mail-heading" class="overflow-hidden border border-medical-200 bg-surface-1 shadow-sm chamfer-br">
    <header class="flex items-center justify-between gap-2 bg-surface-strong p-3 text-white">
      <h3 id="admin-mail-heading" class="m-0 flex items-center gap-2 text-xs font-bold">
        <Mail class="h-4 w-4" aria-hidden="true" /> 邮件设置
      </h3>
      <span v-if="saved" class="text-xs text-white/80">{{ saved.enabled ? '发信已配置' : '发信未启用' }}</span>
    </header>
    <p v-if="loading" role="status" class="p-4 text-sm text-medical-600">正在读取邮件设置…</p>
    <div v-else-if="!saved" class="space-y-2 p-4">
      <p role="alert" class="text-sm text-danger">{{ error }}</p>
      <button type="button" class="min-h-11 border border-medical-200 px-3 text-sm text-medical-700 hover:bg-medical-100" @click="load">重试</button>
    </div>
    <form v-else class="space-y-3 p-4" @submit.prevent="save" @input="notice = ''">
      <fieldset :disabled="saving" class="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-3">
        <div class="min-w-0 sm:col-span-2">
          <label for="admin-mail-host" class="mb-1 block text-xs font-bold text-medical-600">SMTP 服务器</label>
          <input id="admin-mail-host" v-model.trim="draft.smtpHost" :class="inputClass" placeholder="smtp.gmail.com" autocomplete="off" spellcheck="false" />
        </div>
        <div>
          <label for="admin-mail-port" class="mb-1 block text-xs font-bold text-medical-600">SMTP 端口</label>
          <input id="admin-mail-port" v-model.number="draft.smtpPort" :class="inputClass" type="number" min="1" max="65535" step="1" required />
        </div>
        <div class="sm:col-span-3">
          <label for="admin-mail-username" class="mb-1 block text-xs font-bold text-medical-600">发件邮箱</label>
          <input id="admin-mail-username" v-model.trim="draft.smtpUsername" :class="inputClass" type="email" maxlength="254" autocomplete="off" spellcheck="false" />
        </div>
        <div class="sm:col-span-3">
          <label for="admin-mail-password" class="mb-1 block text-xs font-bold text-medical-600">SMTP 密码</label>
          <input id="admin-mail-password" v-model="draft.smtpPassword" :class="inputClass" type="password" autocomplete="new-password" :placeholder="saved.passwordConfigured ? '已配置，留空保留' : 'Gmail 请填应用专用密码'" />
        </div>
        <div class="sm:col-span-3">
          <label for="admin-mail-url" class="mb-1 block text-xs font-bold text-medical-600">邮件链接地址</label>
          <input id="admin-mail-url" v-model.trim="draft.baseUrl" :class="inputClass" type="url" placeholder="https://music.example.com" autocomplete="off" spellcheck="false" required />
        </div>
        <div class="sm:col-span-3">
          <label for="admin-mail-cooldown" class="mb-1 block text-xs font-bold text-medical-600">默认召唤冷却（小时）</label>
          <input id="admin-mail-cooldown" v-model.number="draft.summonCooldownHours" :class="inputClass" type="number" min="0" max="24" step="1" aria-describedby="admin-mail-cooldown-help" required />
          <p id="admin-mail-cooldown-help" class="mt-1 text-xs text-medical-600">适用于跟随站点默认的房间，0 表示无冷却。</p>
        </div>
      </fieldset>
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
      <p v-if="notice" role="status" class="text-sm text-success">{{ notice }}</p>
      <button type="submit" :disabled="!dirty || saving" class="flex min-h-11 w-full items-center justify-center gap-2 bg-surface-strong px-3 font-bold text-white hover:bg-accent hover:text-accent-fg disabled:opacity-50">
        <Save class="h-4 w-4" aria-hidden="true" /> {{ saving ? '正在保存…' : '保存邮件设置' }}
      </button>
    </form>
  </section>
</template>
