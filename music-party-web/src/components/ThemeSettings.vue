<template>
  <dialog ref="dialog" aria-labelledby="theme-settings-title" class="theme-dialog border border-medical-200 bg-surface-1 text-medical-900 shadow-2xl" @cancel.prevent="appearance.cancel" @click="onBackdrop">
    <div class="flex max-h-[inherit] min-h-0 flex-col">
      <header class="flex shrink-0 items-center justify-between gap-3 border-b border-medical-200 px-4 py-3 md:px-5">
        <div class="min-w-0">
          <h2 id="theme-settings-title" class="m-0 text-lg font-bold">外观与主题</h2>
          <p class="mt-1 text-xs text-medical-500">即时预览全站效果，保存后应用到本浏览器。</p>
        </div>
        <button type="button" autofocus class="flex h-11 w-11 shrink-0 items-center justify-center text-medical-600 hover:bg-medical-100" aria-label="关闭主题设置" @click="appearance.cancel"><X class="h-5 w-5" /></button>
      </header>

      <div class="min-h-0 space-y-6 overflow-y-auto overscroll-contain p-4 md:p-5">
        <p v-if="notice" role="status" class="border border-medical-200 bg-medical-50 p-3 text-xs text-medical-600">{{ notice }}</p>
        <fieldset>
          <legend class="mb-2 text-sm font-bold">显示模式</legend>
          <div class="grid grid-cols-3 gap-2">
            <button v-for="item in modes" :key="item.value" type="button" :aria-pressed="draft.mode === item.value" class="flex min-h-11 items-center justify-center gap-1.5 border px-2 text-xs font-bold" :class="selected(draft.mode === item.value)" @click="draft.mode = item.value; preview()"><component :is="item.icon" class="h-4 w-4 shrink-0" />{{ item.label }}</button>
          </div>
          <p class="mt-2 text-xs text-medical-500">当前为{{ appearance.resolvedMode === 'dark' ? '深色' : '浅色' }}，跟随系统会自动响应设备外观变化。</p>
        </fieldset>

        <section aria-labelledby="wallpaper-settings-title" class="border-t border-medical-200 pt-5">
          <h3 id="wallpaper-settings-title" class="text-sm font-bold">壁纸模式</h3>
          <p class="mt-1.5 text-xs leading-relaxed text-medical-600">死区是从屏幕边缘向内的偏移。鼠标越过死区、进入反应距离内时，对应栏会展开。</p>
          <fieldset v-for="metric in wallpaperMetrics" :key="metric.key" class="mt-4 border border-medical-200 bg-medical-50 px-3 pb-3">
            <legend class="px-1 text-xs font-bold">{{ metric.label }}</legend>
            <label class="mb-2 flex min-h-9 w-fit cursor-pointer items-center gap-2 text-xs font-medium text-medical-700">
              <input v-model="wallpaperDraft[metric.key].linked" type="checkbox" class="h-4 w-4 accent-[rgb(var(--accent))]" @change="syncWallpaperEdges(metric.key)" />
              四边联动
            </label>
            <div class="grid gap-2" :class="wallpaperDraft[metric.key].linked ? 'grid-cols-1' : 'grid-cols-2 sm:grid-cols-4'">
              <label v-for="edge in (wallpaperDraft[metric.key].linked ? ['top'] : WALLPAPER_EDGES)" :key="edge" class="min-w-0 text-xs text-medical-700">
                <span class="block pb-1">{{ wallpaperDraft[metric.key].linked ? '四边' : WALLPAPER_EDGE_LABELS[edge] }}</span>
                <span class="flex max-w-32 items-center gap-1 border border-medical-200 bg-surface-1 px-2 focus-within:border-accent">
                  <input
                      v-model.number="wallpaperDraft[metric.key].edges[edge]"
                      :aria-label="metric.label + ' · ' + (wallpaperDraft[metric.key].linked ? '四边' : WALLPAPER_EDGE_LABELS[edge])"
                      class="h-10 min-w-0 flex-1 bg-transparent tabular-nums text-medical-900 outline-none"
                      type="number"
                      :min="metric.min"
                      :max="WALLPAPER_MAX_DISTANCE"
                      step="1"
                      inputmode="numeric"
                      @change="syncWallpaperEdges(metric.key)"
                  />
                  <span class="shrink-0 text-medical-600" aria-hidden="true">px</span>
                </span>
              </label>
            </div>
          </fieldset>
        </section>

        <fieldset>
          <legend class="mb-2 text-sm font-bold">配色来源</legend>
          <div class="grid grid-cols-2 gap-2">
            <button type="button" :aria-pressed="draft.source === 'manual'" class="min-h-11 border px-3 text-xs font-bold" :class="selected(draft.source === 'manual')" @click="draft.source = 'manual'; preview()">手动配色</button>
            <button type="button" :aria-pressed="draft.source === 'monet'" class="min-h-11 border px-3 text-xs font-bold" :class="selected(draft.source === 'monet')" @click="draft.source = 'monet'; preview()">Monet · 跟随封面</button>
          </div>
          <p class="mt-2 text-xs leading-relaxed text-medical-500">Monet 用 Material You 的 HCT 色调板为封面生成强调色和轻微染色的背景。封面不支持取色时使用下方颜色作为种子；不上传图片，不影响播放。</p>
          <p v-if="draft.source === 'monet'" class="mt-2 flex items-center gap-2 text-xs text-accent-text"><span class="h-3 w-3 shrink-0 bg-accent" aria-hidden="true"></span>{{ appearance.snapshot.monetAccent ? `封面种子 ${appearance.snapshot.monetAccent.toUpperCase()}` : '当前使用手动种子，等待可读取的封面' }}</p>
        </fieldset>

        <fieldset>
          <legend class="mb-2 text-sm font-bold">{{ draft.source === 'monet' ? '备用种子色' : '主题强调色' }}</legend>
          <div class="grid grid-cols-4 gap-2 sm:grid-cols-8">
            <button v-for="preset in presets" :key="preset.color" type="button" :aria-pressed="normalizeHex(draft.accent) === preset.color" :aria-label="preset.name" :title="preset.name" class="flex min-h-11 items-center justify-center border-2" :class="normalizeHex(draft.accent) === preset.color ? 'border-medical-900' : 'border-transparent'" :style="{ backgroundColor: preset.color, color: presetForeground(preset.color) }" @click="draft.accent = preset.color; preview()"><Check v-if="normalizeHex(draft.accent) === preset.color" class="h-5 w-5" /></button>
          </div>
          <ThemeColorField v-model="draft.accent" label="自定义强调色" class="mt-3" @update:model-value="preview" />
        </fieldset>

        <fieldset>
          <legend class="mb-2 text-sm font-bold">背景与面板</legend>
          <div class="grid gap-3 sm:grid-cols-2">
            <ThemeColorField v-for="field in surfaces" :key="field.mode + field.key" v-model="draft[field.mode][field.key]" :label="field.label" @update:model-value="preview" />
          </div>
          <p class="mt-2 text-xs leading-relaxed text-medical-500">两种模式独立保存。极端亮度会自动校正，文字与悬停色保持可读；错误、警告、成功保留各自的语义色。</p>
        </fieldset>

        <section aria-label="主题预览" class="border border-medical-200 bg-medical-50 p-3">
          <div class="flex items-center gap-2"><span class="h-2.5 w-2.5 bg-accent" aria-hidden="true"></span><span class="text-sm font-bold">MUSIC PARTY</span><span class="ml-auto text-xs text-medical-500">主题预览</span></div>
          <div class="mt-3 flex items-center gap-3 border border-medical-200 bg-surface-1 p-3"><div class="flex-1"><p class="text-sm font-bold">一起听歌</p><p class="text-xs text-medical-500">正文、面板、焦点和按钮同步更新</p></div><span class="shrink-0 bg-accent px-3 py-2 text-xs font-bold text-accent-fg">主操作</span></div>
          <div class="mt-2 flex flex-wrap gap-3 text-xs"><span class="text-accent-text">强调文字</span><span class="text-success">成功</span><span class="text-warn">警告</span><span class="text-danger">错误</span></div>
        </section>

        <details class="border-t border-medical-200 pt-3">
          <summary class="flex min-h-11 cursor-pointer items-center text-sm font-bold">导入／导出主题</summary>
          <p class="my-2 text-xs text-medical-500">JSON 只包含配色，不含账号、房间或歌曲信息。导入后先预览，再点保存。</p>
          <label class="block text-xs text-medical-600" for="theme-json">主题 JSON</label>
          <textarea id="theme-json" v-model="jsonDraft" rows="5" spellcheck="false" class="mt-1 w-full resize-y border border-medical-200 bg-medical-50 p-2 font-mono text-xs text-medical-900" placeholder="粘贴主题 JSON"></textarea>
          <div class="mt-2 flex flex-wrap gap-2">
            <button type="button" class="min-h-11 border border-medical-200 px-3 text-xs font-bold hover:border-accent" @click="importJson(jsonDraft)">预览导入</button>
            <button type="button" class="min-h-11 border border-medical-200 px-3 text-xs font-bold hover:border-accent" @click="exportJson">导出 JSON</button>
            <label class="flex min-h-11 cursor-pointer items-center border border-medical-200 px-3 text-xs font-bold hover:border-accent">读取文件<input type="file" accept="application/json,.json" class="sr-only" aria-label="读取主题文件" @change="importFile" /></label>
          </div>
        </details>
        <p v-if="error" role="alert" class="border border-danger-border bg-danger-bg p-3 text-xs text-danger">{{ error }}</p>
        <p v-if="appearance.snapshot.persistenceError" role="alert" class="border border-warn-border bg-warn-bg p-3 text-xs text-warn">{{ appearance.snapshot.persistenceError }}</p>
      </div>

      <footer class="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-medical-200 px-4 py-3 md:px-5">
        <button type="button" class="min-h-11 px-2 text-xs font-bold text-medical-600 hover:text-accent-text" @click="reset">恢复默认</button>
        <div class="flex gap-2"><button type="button" class="min-h-11 border border-medical-200 px-3 text-xs font-bold text-medical-600 hover:border-medical-400" @click="appearance.cancel">取消</button><button type="button" class="min-h-11 bg-accent px-4 text-xs font-bold text-accent-fg hover:bg-accent-hover" @click="save">保存设置</button></div>
      </footer>
    </div>
  </dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { Check, X, Sun, Moon, Monitor } from 'lucide-vue-next';
import { useAppearanceStore } from '../stores/appearance.js';
import { useWallpaperStore } from '../stores/wallpaper.js';
import { normalizeWallpaperSettings, WALLPAPER_EDGES, WALLPAPER_EDGE_LABELS, WALLPAPER_MAX_DISTANCE } from '../logic/wallpaper.js';
import { DEFAULT_APPEARANCE, THEME_PRESETS, normalizeAppearance, normalizeHex, parseAppearance, readableColor, hexRgb, rgbHex } from '../logic/theme.js';
import ThemeColorField from './ThemeColorField.vue';
import { useFocusTrap } from '../composables/useFocusTrap.js';

const appearance = useAppearanceStore();
const wallpaper = useWallpaperStore();
const wallpaperDraft = reactive(normalizeWallpaperSettings(wallpaper.settings));
const wallpaperMetrics = [
    { key: 'deadZone', label: '死区', min: 0 },
    { key: 'reaction', label: '反应距离', min: 1 }
];
function syncWallpaperEdges(metric) {
    wallpaperDraft[metric] = normalizeWallpaperSettings(wallpaperDraft)[metric];
}
const dialog = ref(null), error = ref(''), notice = ref(''), jsonDraft = ref('');
let fileRequest = 0;
useFocusTrap(computed(() => appearance.isOpen), dialog);
const draft = reactive(normalizeAppearance(appearance.snapshot.appearance));
const presets = THEME_PRESETS;
const modes = [{ value: 'system', label: '系统', icon: Monitor }, { value: 'light', label: '浅色', icon: Sun }, { value: 'dark', label: '深色', icon: Moon }];
const surfaces = [
    { mode: 'light', key: 'background', label: '浅色背景' }, { mode: 'light', key: 'surface', label: '浅色面板' },
    { mode: 'dark', key: 'background', label: '深色背景' }, { mode: 'dark', key: 'surface', label: '深色面板' }
];
const selected = active => active ? 'border-accent bg-accent/10 text-accent-text' : 'border-medical-200 bg-medical-50 text-medical-600 hover:border-medical-300';
const presetForeground = hex => rgbHex(readableColor([24, 18, 12], [hexRgb(hex)]));
const preview = () => {
    try { const valid = normalizeAppearance(draft, { strict: true }); error.value = ''; appearance.preview(valid); return true; }
    catch (e) { error.value = e.message; return false; }
};
const reset = () => {
    Object.assign(draft, normalizeAppearance(DEFAULT_APPEARANCE));
    Object.assign(wallpaperDraft, normalizeWallpaperSettings());
    notice.value = '已恢复默认值，点击保存后生效。';
    preview();
};
const save = () => {
    if (!preview()) return;
    const next = normalizeWallpaperSettings(wallpaperDraft);
    if (JSON.stringify(next) !== JSON.stringify(wallpaper.settings) && !wallpaper.save(next)) {
        error.value = '无法保存壁纸设置，请检查浏览器存储权限后重试。';
        return;
    }
    appearance.save();
};
const importJson = text => {
    try { const valid = parseAppearance(text); Object.assign(draft, valid); preview(); notice.value = '主题已导入预览，尚未保存。'; }
    catch (e) { error.value = e.message; }
};
const importFile = async event => {
    const request = ++fileRequest;
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (file.size > 16_384) { error.value = '主题文件过大（最多 16 KB）'; return; }
    try {
        const text = await file.text();
        if (request === fileRequest && appearance.isOpen) importJson(text);
    } catch { if (request === fileRequest && appearance.isOpen) error.value = '无法读取主题文件'; }
};
const exportJson = () => {
    if (!preview()) return;
    const text = JSON.stringify(normalizeAppearance(draft), null, 2);
    jsonDraft.value = text;
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = 'music-party-theme.json'; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice.value = '已导出主题 JSON；下方文本也可直接复制。';
};
const onBackdrop = event => {
    if (event.target !== dialog.value) return;
    const rect = dialog.value.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) appearance.cancel();
};
watch(() => appearance.isOpen, async open => {
    fileRequest++;
    if (open) {
        Object.assign(draft, normalizeAppearance(appearance.snapshot.appearance));
        Object.assign(wallpaperDraft, normalizeWallpaperSettings(wallpaper.settings));
        error.value = notice.value = jsonDraft.value = '';
        await nextTick();
        if (appearance.isOpen && !dialog.value.open) dialog.value.showModal();
    } else dialog.value?.close();
}, { flush: 'post' });
watch(() => appearance.externalRevision, () => {
    fileRequest++;
    Object.assign(draft, normalizeAppearance(appearance.snapshot.appearance));
    if (appearance.isOpen) notice.value = '另一个标签页更新了主题，已载入最新设置。';
});
onBeforeUnmount(() => { fileRequest++; dialog.value?.close(); });
</script>

<style scoped>
.theme-dialog { width: min(36rem, calc(100vw - 1.5rem)); max-height: min(90dvh, 48rem); padding: 0; overflow: hidden; }
.theme-dialog::backdrop { background: rgb(var(--scrim) / .7); backdrop-filter: blur(4px); }
</style>
