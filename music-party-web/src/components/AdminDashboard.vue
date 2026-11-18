<template>
  <Transition
      enter-active-class="transition duration-500 ease-out"
      enter-from-class="opacity-0 translate-y-4"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition duration-300 ease-in"
      leave-from-class="opacity-100 translate-y-0"
      leave-to-class="opacity-0 translate-y-4"
  >
    <div v-if="adminStore.showDashboard" class="fixed inset-0 z-[95] flex items-center justify-center p-4 bg-scrim/45 backdrop-blur-md">
      <div
          ref="dialogRef"
          role="dialog"
          aria-modal="true"
          aria-labelledby="admin-dashboard-title"
          class="w-full max-w-5xl h-[90vh] bg-medical-50 shadow-2xl border border-medical-200 flex flex-col chamfer-br overflow-hidden"
      >
        
        <!-- Header -->
        <div class="p-4 md:p-6 bg-surface-1 border-b border-medical-200 flex justify-between items-center flex-shrink-0">
          <div>
            <h2 id="admin-dashboard-title" class="text-2xl font-bold font-mono text-medical-900 flex items-center gap-3">
              <Settings class="w-6 h-6 text-accent-text animate-spin-slow" /> 管理终端
            </h2>
            <p class="text-[11px] font-mono text-medical-500 mt-1 uppercase tracking-[0.2em]">> 系统控制接口 / SYSTEM_CONTROL_INTERFACE</p>
          </div>
          <div class="flex items-center gap-4">
            <button @click="adminStore.showDashboard = false" class="flex items-center justify-center w-11 h-11 bg-medical-100 hover:bg-medical-200 text-medical-900 transition-colors" aria-label="关闭管理终端">
              <X class="w-6 h-6" />
            </button>
          </div>
        </div>

        <!-- Content Area -->
        <div class="flex-1 overflow-y-auto p-4 md:p-6 space-y-6 custom-scroll bg-medical-50/50">
          
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
            
            <!-- Left Column: Playback & Parameters (7 cols) -->
            <div class="lg:col-span-7 space-y-6">
              
              <!-- Section: Playback Mastery -->
              <div class="bg-surface-1 border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-surface-strong text-white flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <PlayCircle class="w-4 h-4 text-accent-text" />
                    <span class="text-xs font-bold uppercase tracking-widest font-mono">播放核心 / Playback_Core</span>
                  </div>
                  <span class="text-[11px] font-mono text-white/70">{{ playerStore.nowPlaying ? 'ON_AIR' : 'IDLE' }} · Q{{ playerStore.queue.length }}</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Control Grid -->
                  <div class="grid grid-cols-3 gap-3">
                    <button @click="execPlayerAction('PAUSE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent-text transition-all group">
                      <Pause v-if="!playerStore.isPaused" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Play v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[11px] font-bold font-mono">{{ playerStore.isPaused ? '恢复' : '暂停' }}</span>
                    </button>
                    <button @click="execPlayerAction('SKIP')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent-text transition-all group">
                      <SkipForward class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[11px] font-bold font-mono">切歌</span>
                    </button>
                    <button @click="execPlayerAction('SHUFFLE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent-text transition-all group">
                      <ListOrdered v-if="playerStore.playMode === 'SEQUENTIAL'" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Shuffle v-else-if="playerStore.playMode === 'SHUFFLE'" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Repeat1 v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[11px] font-bold font-mono">{{ playerStore.playMode === 'SEQUENTIAL' ? '顺序' : playerStore.playMode === 'SHUFFLE' ? '随机' : '单曲循环' }}</span>
                    </button>
                  </div>

                  <!-- Playback Core Settings (Shuffle & Vote Skip) -->
                  <div class="space-y-3 p-3 bg-medical-50 border border-medical-100 rounded-sm">
                    <!-- Shuffle Controls -->
                    <div v-if="playerStore.isShuffle" class="grid grid-cols-2 gap-3 pb-3 border-b border-medical-100">
                      <div class="flex items-center justify-between">
                        <div class="flex flex-col">
                          <span class="text-[11px] font-bold text-medical-800">{{ playerStore.isFairShuffle ? '公平模式' : '全部随机' }}</span>
                          <span class="text-[11px] text-medical-500 font-mono uppercase">算法类型</span>
                        </div>
                        <ToggleSwitch :checked="playerStore.isFairShuffle" label="公平随机" @toggle="execPlayerAction('TOGGLE_FAIR_SHUFFLE')" />
                      </div>
                      <div class="flex items-center justify-between border-l border-medical-100 pl-3">
                        <div class="flex flex-col">
                          <span class="text-[11px] font-bold text-medical-800">{{ playerStore.allowOfflineShuffle ? '含离线' : '仅在线' }}</span>
                          <span class="text-[11px] text-medical-500 font-mono uppercase">曲库范围</span>
                        </div>
                        <ToggleSwitch :checked="playerStore.allowOfflineShuffle" label="随机范围包含离线歌曲" @toggle="execPlayerAction('TOGGLE_ALLOW_OFFLINE')" />
                      </div>
                    </div>

                    <!-- Vote Skip Controls (Always Visible) -->
                    <div class="space-y-3">
                      <div class="flex items-center justify-between">
                        <div class="flex flex-col">
                          <span class="text-[11px] font-bold text-medical-800">投票切歌模式</span>
                          <span class="text-[11px] text-medical-500 font-mono uppercase">VOTE_SKIP_MODE</span>
                        </div>
                        <ToggleSwitch :checked="!!playerStore.config.voteSkipEnabled" label="投票切歌模式" @toggle="updateInstantConfig({ voteSkipEnabled: !playerStore.config.voteSkipEnabled })" />
                      </div>
                      <div v-if="playerStore.config.voteSkipEnabled" class="grid grid-cols-2 gap-3 pt-1 border-t border-medical-100">
                        <div class="space-y-1">
                          <label for="vote-skip-threshold" class="block text-[11px] font-bold text-medical-500 uppercase">比例 (0.1-1.0)</label>
                          <input 
                            id="vote-skip-threshold"
                            :value="playerStore.config.voteSkipThreshold" 
                            @change="e => updateInstantConfig({ voteSkipThreshold: parseFloat(e.target.value) })"
                            type="number" step="0.1" min="0.1" max="1.0" 
                            class="w-full min-h-9 bg-surface-1 border border-medical-200 px-2 py-1 text-[11px] text-medical-900 outline-none focus:border-accent" 
                          />
                        </div>
                        <div class="space-y-1">
                          <label for="vote-skip-wait" class="block text-[11px] font-bold text-medical-500 uppercase">等待时间 (秒)</label>
                          <input 
                            id="vote-skip-wait"
                            :value="playerStore.config.voteSkipWaitTime" 
                            @change="e => updateInstantConfig({ voteSkipWaitTime: parseInt(e.target.value) })"
                            type="number" min="0" 
                            class="w-full min-h-9 bg-surface-1 border border-medical-200 px-2 py-1 text-[11px] text-medical-900 outline-none focus:border-accent" 
                          />
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Permission Locks -->
                  <div class="grid grid-cols-3 gap-2">
                    <button v-for="lock in locks" :key="lock.key" class="flex items-center justify-center gap-2 min-h-11 py-2 px-1 border transition-all text-[11px] font-bold font-mono" @click="toggleLock(lock.key, !lock.value)"
                      :aria-pressed="lock.value"
                      :class="lock.value ? 'bg-danger-bg border-danger-border text-danger' : 'bg-surface-1 border-medical-200 text-medical-600 hover:border-accent hover:text-accent-text'">
                      <Lock v-if="lock.value" class="w-3 h-3" />
                      <Unlock v-else class="w-3 h-3" />
                      {{ lock.cnLabel }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- Section: System Parameters -->
              <div class="bg-surface-1 border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-surface-strong text-white flex items-center gap-2">
                  <Sliders class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">系统参数 / System_Parameters</span>
                </div>
                <div class="p-4 space-y-4">
                  <div class="grid grid-cols-2 gap-x-4 gap-y-3">
                  <div v-for="(val, key) in systemFields" :key="key" class="space-y-1">
                      <label :for="`sys-${val.field}`" class="block text-[11px] font-bold text-medical-500 font-mono uppercase">{{ key }}</label>
                      <input :id="`sys-${val.field}`" v-model.number="configProxy[val.field]" type="number" class="w-full min-h-9 bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs text-medical-900 outline-none focus:border-accent font-mono" />
                    </div>
                  </div>

                  <button @click="saveSystemConfig" class="w-full bg-surface-strong text-white py-2 text-xs font-bold hover:bg-accent hover:text-accent-fg transition-colors flex items-center justify-center gap-2 min-h-11">
                    <Save class="w-4 h-4" /> 应用并保存所有更改
                  </button>
                </div>
              </div>
            </div>

            <!-- Right Column: Environment & Danger Zone (5 cols) -->
            <div class="lg:col-span-5 space-y-6">
              
              <!-- Section: Room Environment -->
              <div class="bg-surface-1 border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-surface-strong text-white flex items-center gap-2">
                  <Globe class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">环境配置 / Environment</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Password -->
                  <div class="space-y-2">
                    <label class="block text-[11px] font-bold text-medical-500 uppercase font-mono">房间进入密码</label>
                    <div class="flex gap-2">
                      <input v-model="roomPassword" placeholder="留空则设为公开" aria-label="房间进入密码" class="flex-1 min-h-9 bg-medical-50 border border-medical-200 px-3 py-1.5 text-xs text-medical-900 outline-none focus:border-accent placeholder:text-medical-500" />
                      <button @click="updateRoomPassword" class="bg-surface-strong text-white px-3 min-h-10 text-[11px] font-bold hover:bg-accent hover:text-accent-fg">设置</button>
                    </div>
                  </div>
                  <!-- Toggles -->
                  <div class="grid grid-cols-1 gap-3">
                    <div class="p-3 bg-medical-50 border border-medical-100 flex flex-col gap-2 rounded-sm">
                      <span class="text-[11px] font-bold text-medical-500 uppercase font-mono text-center">数据清理</span>
                      <div class="flex flex-col gap-1 w-full">
                        <button @click="clearData('QUEUE')" class="w-full py-1 border border-medical-200 text-[11px] font-bold hover:bg-danger-bg hover:text-danger transition-all min-h-9">清理播放队列</button>
                        <button @click="clearData('OFFLINE')" class="w-full py-1 border border-medical-200 text-[11px] font-bold hover:bg-danger-bg hover:text-danger transition-all min-h-9">清理不在线成员歌曲</button>
                        <button @click="clearData('CHAT')" class="w-full py-1 border border-medical-200 text-[11px] font-bold hover:bg-danger-bg hover:text-danger transition-all min-h-9">清理聊天记录</button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Section: Credentials -->
              <AdminMailSettings :admin-password="adminStore.adminPassword" />

              <div class="bg-surface-1 border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-surface-strong text-white flex items-center gap-2">
                  <Database class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">平台凭据 / Credentials</span>
                </div>
                <div class="p-4 space-y-4">
                   <div v-for="plat in platforms" :key="plat.id" class="space-y-2 border-b border-medical-50 pb-3 last:border-0 last:pb-0">
                    <div class="flex justify-between items-center">
                      <span class="text-[11px] font-bold text-medical-600 font-mono">{{ plat.name }} // {{ plat.tokenName }}</span>
                      <ToggleSwitch :checked="!!playerStore.config[`${plat.id}Enabled`]" :label="`启用 ${plat.name}`" @toggle="togglePlatform(plat.id)" />
                    </div>
                    <div class="flex gap-2">
                      <input 
                        type="password"
                        v-model="plat.value" 
                        :placeholder="'输入新 ' + plat.tokenName + '...'" 
                        :aria-label="`输入新的 ${plat.tokenName}`"
                        class="flex-1 min-h-10 bg-medical-50 border border-medical-200 px-3 py-2 text-[11px] text-medical-900 outline-none focus:border-accent font-mono placeholder:text-medical-500"
                      />
                      <button @click="updateCookie(plat.id, plat.value)" class="bg-surface-strong text-white px-3 min-h-10 font-bold text-[11px] hover:bg-accent hover:text-accent-fg transition-colors">更新</button>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Danger Zone -->
              <div class="p-4 bg-danger-bg border border-danger-border chamfer-br flex flex-col gap-3">
                <h4 class="text-danger text-[11px] font-bold flex items-center gap-2 uppercase tracking-tighter">
                  <AlertTriangle class="w-4 h-4" /> 危险区域 / DANGER_ZONE.SH
                </h4>
                <button @click="handleReset" class="w-full py-2 bg-danger text-white font-bold text-[11px] hover:brightness-110 transition-all uppercase tracking-widest shadow-sm">
                  全系统重置 (慎用)
                </button>
              </div>
            </div>
          </div>

        </div>

        <!-- Footer -->
        <div class="p-3 bg-medical-100 border-t border-medical-200 flex justify-between items-center text-[11px] font-mono text-medical-500">
          <span class="flex items-center gap-1"><ShieldCheck class="w-3 h-3 text-success" aria-hidden="true" /> 管理员会话已解锁</span>
          <span>ROOM_CONTROL / {{ playerStore.connected ? 'LINK_UP' : 'LINK_DOWN' }}</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useAdminStore } from '../stores/admin';
import { usePlayerStore } from '../stores/player';
import { adminApi } from '../api/admin';
import { useToast } from '../composables/useToast';
import ToggleSwitch from './ui/ToggleSwitch.vue';
import AdminMailSettings from './AdminMailSettings.vue';
import { useFocusTrap } from '../composables/useFocusTrap';
import {
  Settings, X, Pause, Play, SkipForward, ListOrdered, Repeat1, Shuffle,
  Lock, Unlock, Save, AlertTriangle,
  PlayCircle, Database, Globe, Sliders, ShieldCheck
} from 'lucide-vue-next';

const adminStore = useAdminStore();
const playerStore = usePlayerStore();
const dialogRef = ref(null);
useFocusTrap(computed(() => adminStore.showDashboard), dialogRef);
const { success, error, warning } = useToast();

const roomPassword = ref('');

// Config Proxy for editing
const configProxy = ref({ ...playerStore.config });

const systemFields = {
  '队列最大长度': { field: 'maxQueueSize' },
  '历史记录容量': { field: 'maxHistorySize' },
  '用户点歌上限': { field: 'maxUserSongs' },
  '导入单次上限': { field: 'maxPlaylistImportSize' },
  '聊天记录容量': { field: 'maxChatHistorySize' },
  '发言频率限制(ms)': { field: 'minChatIntervalMs' }
};

watch(() => playerStore.config, (newVal) => {
  configProxy.value = { ...newVal };
}, { deep: true });

const saveSystemConfig = async () => {
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, configProxy.value);
    success(data.message);
  } catch (e) {
    error('配置同步失败');
  }
};

const updateInstantConfig = async (update) => {
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, update);
    success(data.message);
  } catch (e) {
    error('配置同步失败');
  }
};

const locks = computed(() => [
  { key: 'PAUSE', cnLabel: '锁定暂停', value: playerStore.isPauseLocked },
  { key: 'SKIP', cnLabel: '锁定切歌', value: playerStore.isSkipLocked },
  { key: 'SHUFFLE', cnLabel: '锁定模式', value: playerStore.isPlayModeLocked },
]);

const platforms = ref([
  { id: 'netease', name: '网易云音乐', tokenName: 'COOKIE', value: '' },
  { id: 'bilibili', name: '哔哩哔哩', tokenName: 'SESSDATA', value: '' }
]);

const execPlayerAction = async (action) => {
  try {
    const data = await adminApi.playerAction(adminStore.adminPassword, action);
    success(data.message);
  } catch (e) {
    error('指令执行失败');
  }
};

const toggleLock = async (type, locked) => {
  try {
    const data = await adminApi.setLock(adminStore.adminPassword, type, locked);
    success(data.message);
  } catch (e) {
    error('锁定同步失败');
  }
};

const updateRoomPassword = async () => {
  try {
    const data = await adminApi.setRoomPassword(adminStore.adminPassword, roomPassword.value);
    success(data.message);
  } catch (e) {
    error('密码更新失败');
  }
};

const toggleStream = async () => {
  try {
    const data = await adminApi.setStream(adminStore.adminPassword, !playerStore.streamActive);
    success(data.message);
  } catch (e) {
    error('直播流控制失败');
  }
};

const clearData = async (target) => {
  const targetName = {
    'QUEUE': '播放队列',
    'OFFLINE': '不在线成员的点播歌曲',
    'CHAT': '聊天记录'
  }[target] || target;
  
  if (!confirm(`确定要清空 ${targetName} 吗?`)) return;
  try {
    const data = await adminApi.clearData(adminStore.adminPassword, target);
    warning(data.message);
  } catch (e) {
    error('清理操作失败');
  }
};

const updateCookie = async (platform, value) => {
  if (!value) return;
  try {
    const data = await adminApi.setCookie(adminStore.adminPassword, platform, value);
    success(data.message);
  } catch (e) {
    error('凭据更新失败');
  }
};

const togglePlatform = async (platformId) => {
  const current = playerStore.config[`${platformId}Enabled`];
  const update = { [`${platformId}Enabled`]: !current };
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, update);
    success(data.message);
  } catch (e) {
    error('平台状态切换失败');
  }
};

const handleReset = async () => {
  if (!confirm('!!! 警告 !!! \n这将重置整个系统。 \n你确定要继续吗？')) return;
  try {
    const data = await adminApi.resetSystem(adminStore.adminPassword);
    warning(data.message);
  } catch (e) {
    error('系统重置失败');
  }
};
</script>

<style scoped>
.animate-spin-slow {
  animation: spin 8s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.custom-scroll::-webkit-scrollbar { width: 4px; }
.custom-scroll::-webkit-scrollbar-track { background: transparent; }
.custom-scroll::-webkit-scrollbar-thumb { @apply bg-medical-200 rounded-full; }
.custom-scroll::-webkit-scrollbar-thumb:hover { @apply bg-medical-300; }
</style>
