<template>
  <div v-if="isOpen" class="fixed inset-0 z-[60] bg-medical-900/80 backdrop-blur-sm flex items-center justify-center p-2 md:p-4">
    <div class="w-full max-w-4xl bg-medical-50 h-[90dvh] md:h-[80vh] flex flex-col shadow-2xl relative chamfer-br max-h-full overflow-hidden">

      <!-- 关闭按钮 -->
      <button @click="closeModal" class="absolute top-0 right-0 p-4 hover:text-accent z-50">
        <X class="w-6 h-6" />
      </button>

      <!-- 头部 -->
      <div class="p-3 md:p-6 border-b border-medical-200 bg-white flex-shrink-0">
        <h2 class="text-lg md:text-2xl font-bold font-mono mb-3 md:mb-4 text-medical-900 flex items-center gap-2">
          <Search class="w-5 h-5 text-accent"/> 添加歌曲
        </h2>

        <!-- 平台切换 TAB -->
        <div class="grid grid-cols-2 md:flex gap-1 mb-3 md:mb-4">
          <button
              v-for="p in platforms" :key="p.id"
              @click="switchPlatform(p.id)"
              :disabled="!isPlatformEnabled(p.id)"
              class="min-w-0 px-3 md:px-6 py-2 text-xs md:text-sm font-bold uppercase transition-all"
              :class="[
                platform === p.id ? 'bg-medical-900 text-white clip-tab' : 'bg-medical-200 text-medical-500 hover:bg-medical-300',
                !isPlatformEnabled(p.id) ? 'opacity-30 cursor-not-allowed grayscale' : ''
              ]"
          >
            {{ p.label }}
          </button>
        </div>

        <!-- 搜索框 -->
        <div class="flex gap-2">
          <input
              v-model="keyword"
              @keyup.enter="doSearch"
              placeholder="搜索音乐..."
              class="flex-1 border p-3 outline-none transition-colors duration-300 font-sans bg-medical-100 border-medical-200 focus:border-accent text-medical-900 placeholder:text-medical-400"
          />
          <button
              v-if="platform === 'netease'"
              @click="albumSearchMode = !albumSearchMode"
              class="px-3 py-2 text-xs font-bold transition-colors flex-shrink-0"
              :class="albumSearchMode ? 'bg-accent text-white' : 'bg-medical-200 text-medical-500 hover:bg-medical-300'"
              title="搜索专辑"
          >
            <Disc class="w-4 h-4" />
          </button>
          <button
              @click="handleSearchAction"
              class="text-white px-3 md:px-6 py-2 font-bold transition-colors text-xs md:text-base flex-shrink-0 font-sans bg-accent hover:bg-accent-hover"
          >
            搜索
          </button>
        </div>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-hidden flex flex-col md:flex-row relative">

        <!-- 左侧：我的歌单 / 绑定 -->
        <div v-if="supportsPlaylists" class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-medical-200 flex-col bg-white transition-all"
             :class="mobileView === 'playlists' ? 'flex w-full h-full' : 'hidden md:flex'"
        >
          <div class="p-2 md:p-3 bg-medical-100 text-xs font-bold text-medical-500 flex justify-between items-center font-sans">
            <span>用户歌单</span>
          </div>

          <div class="flex-1 overflow-y-auto p-2 space-y-2">
            <!-- 未绑定 -->
            <div v-if="!bindings[platform]" class="p-4 border border-dashed border-medical-300 bg-medical-50">
              <div class="text-xs text-medical-500 mb-2 text-center font-sans">{{ platform === 'soundcloud' ? '绑定公开主页以获取公开 Sets' : '绑定用户以获取用户歌单' }}</div>
              <div class="flex gap-1">
                <input v-model="searchUserKeyword" @keyup.enter="searchUser" :placeholder="platform === 'soundcloud' ? '输入 @handle 或主页 URL' : '搜索用户名'" class="flex-1 min-w-0 bg-white border border-medical-200 p-1 text-sm outline-none focus:border-accent font-sans" />
                <button @click="searchUser" class="bg-accent hover:bg-accent-hover p-1 transition-colors"><Search class="w-4 h-4 text-white"/></button>
              </div>
              <!-- 搜索结果列表 -->
              <div v-if="userSearchResults.length > 0" class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-medical-200 pt-2">
                <div v-for="user in userSearchResults" :key="user.id" @click="bindUser(user)" class="flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group">
                  <img v-if="user.avatarUrl" :src="user.avatarUrl" class="w-6 h-6 rounded-full bg-medical-200"/>
                  <div v-else class="w-6 h-6 rounded-full bg-accent/20 text-accent flex items-center justify-center text-xs font-bold">{{ user.name.slice(0, 1) }}</div>
                  <span class="text-xs font-bold truncate flex-1 text-medical-900">{{ user.name }}</span>
                </div>
              </div>
              <div v-if="isSearchingUser" class="text-center py-2"><Loader2 class="w-4 h-4 animate-spin mx-auto text-accent"/></div>
            </div>

            <!-- 已绑定：歌单列表 -->
            <template v-else>
              <div class="flex justify-between items-center px-2 py-1 bg-medical-50 border-b border-medical-100">
                <span class="text-[10px] font-mono text-medical-400">ID: {{ bindings[platform] }}</span>
                <button @click="playerStore.bindAccount(platform, '')" class="text-[10px] text-red-400 hover:underline">UNLINK</button>
              </div>
              
              <div v-if="isPlaylistsLoading" class="flex justify-center py-8">
                <Loader2 class="w-6 h-6 animate-spin text-accent" />
              </div>
              
              <div v-else v-for="pl in playlists" :key="pl.id" @click="handleSelectPlaylist(pl.id)" class="flex items-center gap-3 p-2 hover:bg-medical-50 cursor-pointer group transition-colors border-l-2 border-transparent hover:border-accent">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden"><CoverImage :src="pl.coverImgUrl" class="w-full h-full" /></div>
                <div class="overflow-hidden">
                  <div class="text-sm font-bold truncate group-hover:text-accent text-medical-900">{{ pl.name }}</div>
                  <div class="text-xs font-mono text-medical-400">{{ pl.platform === 'soundcloud' ? 'PUBLIC SET' : `${pl.trackCount} TRACKS` }}</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-300 md:hidden ml-auto" />
              </div>
            </template>
          </div>
        </div>

        <!-- 右侧：歌曲列表 -->
        <div class="bg-medical-50 flex-col min-h-0" :class="[supportsPlaylists ? 'md:flex-1' : 'flex-1', mobileView === 'songs' || !supportsPlaylists ? 'flex w-full h-full' : 'hidden md:flex']">
          <!-- 移动端返回条 -->
          <div class="md:hidden flex items-center gap-2 p-3 bg-white border-b border-medical-200 flex-shrink-0">
            <button @click="mobileView = 'playlists'" class="p-1 -ml-1 text-medical-500 hover:text-medical-900"><ArrowLeft class="w-5 h-5" /></button>
            <span class="font-bold text-sm text-medical-800">{{ albumSearchMode ? 'ALBUM SEARCH' : (listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS') }}</span>
          </div>

          <div ref="contentScrollRef" @scroll="onContentScroll" class="flex-1 overflow-y-auto p-2 md:p-4">
            <div v-if="loading" class="text-center py-10 font-mono text-accent animate-pulse">> LOADING DATA STREAM...</div>

            <!-- 专辑搜索结果 -->
            <div v-else-if="albumSearchMode && listMode === 'search' && !currentAlbumId" class="space-y-1">
              <div v-if="albums.length === 0" class="text-center py-10 text-medical-400 text-xs font-mono">NO ALBUMS FOUND</div>
              <div v-for="album in albums" :key="album.id" @click="handleSelectAlbum(album)" class="flex items-center p-3 border border-transparent transition-all bg-white hover:border-medical-300 hover:shadow-sm cursor-pointer group">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden mr-3"><CoverImage :src="album.coverUrl" class="w-full h-full" /></div>
                <div class="min-w-0 flex-1">
                  <div class="text-sm font-bold truncate text-medical-900">{{ album.name }}</div>
                  <div class="text-xs text-medical-500 truncate">{{ album.artist }} · {{ album.trackCount }} TRACKS</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-300 ml-2" />
              </div>
            </div>

            <!-- 专辑内歌曲列表 -->
            <div v-else-if="albumSearchMode && currentAlbumId">
              <div class="mb-4 p-4 bg-white border border-medical-200 shadow-sm">
                <div class="flex justify-between items-center gap-3">
                  <div class="min-w-0">
                    <button @click="currentAlbumId = null" class="text-xs text-accent hover:underline mb-1 flex items-center gap-1"><ArrowLeft class="w-3 h-3" /> 返回专辑列表</button>
                    <div class="font-bold text-lg truncate">{{ currentAlbumName }}</div>
                    <div class="text-xs text-medical-400 font-mono">{{ songs.length }} LOADED</div>
                  </div>
                  <button @click="handleImportAlbum" class="flex-shrink-0 bg-medical-900 text-white px-4 py-2 text-sm font-bold hover:bg-accent transition-colors flex items-center gap-2 font-sans">
                    <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">导入全部</span>
                  </button>
                </div>
              </div>
              <div class="space-y-1">
                <div v-for="song in songs" :key="song.id" class="flex items-center p-3 border border-transparent transition-all group bg-white hover:border-medical-300 hover:shadow-sm">
                  <div class="flex-1 w-0 flex items-center gap-3">
                    <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" /></div>
                    <div class="min-w-0 flex-1">
                      <div class="text-sm font-bold truncate text-medical-900">{{ song.name }}</div>
                      <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                    </div>
                  </div>
                  <button @click="handleAddClick(song)" class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                    :class="[
                      isInQueue(song.id) ? 'text-green-500 cursor-default' :
                      pendingIds.has(song.id) ? 'text-accent cursor-wait' :
                      'text-medical-300 hover:text-accent'
                    ]"
                    :disabled="pendingIds.has(song.id) || isInQueue(song.id)">
                    <Loader2 v-if="pendingIds.has(song.id)" class="w-5 h-5 animate-spin" />
                    <Check v-else-if="isInQueue(song.id)" class="w-5 h-5" />
                    <PlusCircle v-else class="w-5 h-5"/>
                  </button>
                </div>
              </div>
            </div>

            <!-- 歌单操作头 -->
            <div v-else-if="currentPlaylistId && listMode === 'playlist'" class="mb-4 p-4 bg-white border border-medical-200 shadow-sm">
              <div class="flex justify-between items-center gap-3">
                <div class="min-w-0">
                  <div class="text-xs font-sans text-medical-400">用户歌单</div>
                  <div class="font-bold text-lg truncate text-medical-900">{{ currentPlaylistId }}</div>
                  <div class="text-xs text-medical-400 font-mono">{{ songs.length }} LOADED</div>
                </div>
                <button @click="handleImportPlaylist" class="flex-shrink-0 bg-medical-900 text-white px-4 py-2 text-sm font-bold hover:bg-accent transition-colors flex items-center gap-2 font-sans">
                  <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">导入全部</span>
                </button>
              </div>
              <div v-if="platform === 'netease'" class="relative mt-3">
                <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-medical-400" />
                <input v-model="playlistKeyword" placeholder="在此歌单中搜索歌曲或歌手" class="w-full border border-medical-200 bg-medical-50 py-2 pl-9 pr-3 text-sm outline-none focus:border-accent" />
              </div>
            </div>

            <!-- 歌曲列表渲染 -->
            <div class="space-y-1">
              <div v-if="displayedSongs.length === 0 && !loading" class="text-center py-10 text-medical-400 text-xs font-mono">{{ playlistKeyword ? 'NO MATCHES FOUND' : 'NO DATA FOUND' }}</div>
              <div v-for="song in displayedSongs" :key="song.id" class="flex items-center p-3 border border-transparent transition-all group" :class="isUnplayable(song) ? 'opacity-50 grayscale bg-medical-50 cursor-not-allowed' : 'bg-white hover:border-medical-300 hover:shadow-sm'">
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" /></div>
                  <div class="min-w-0 flex-1">
                    <div class="text-sm font-bold truncate text-medical-900">{{ song.name }}</div>
                    <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                  </div>
                </div>
                <div v-if="isUnplayable(song)" class="ml-2 flex-shrink-0"><span class="px-1.5 py-0.5 text-[10px] font-mono font-bold text-medical-400 border border-medical-300 bg-medical-100 rounded-sm">>10MIN</span></div>

                <a
                    v-if="listMode === 'playlist' && getSourceUrl(song)"
                    :href="getSourceUrl(song)"
                    target="_blank"
                    rel="noopener noreferrer"
                    @click.stop
                    class="ml-2 p-2 flex-shrink-0 text-medical-400 hover:text-accent transition-colors"
                    title="打开原曲页面"
                    aria-label="打开原曲页面"
                >
                  <ExternalLink class="w-4 h-4" />
                </a>

                <button
                    v-if="!isUnplayable(song)"
                    @click="handleAddClick(song)"
                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                    :class="[
                        isInQueue(song.id) ? 'text-green-500 cursor-default' :
                        pendingIds.has(song.id) ? 'text-accent cursor-wait' :
                        'text-medical-300 hover:text-accent'
                    ]"
                    :disabled="pendingIds.has(song.id) || isInQueue(song.id)"
                >
                  <!-- 状态 1: 正在添加 -->
                  <Loader2 v-if="pendingIds.has(song.id)" class="w-5 h-5 animate-spin" />

                  <!-- 状态 2: 已经在队列中 -->
                  <Check v-else-if="isInQueue(song.id)" class="w-5 h-5" />

                  <!-- 状态 3: 普通添加按钮 -->
                  <PlusCircle v-else class="w-5 h-5"/>
                </button>
              </div>
            </div>

            <!-- 加载更多 -->
            <div v-if="listMode === 'search' && !albumSearchMode" class="py-4 text-center">
              <div v-if="loading && songs.length > 0" class="flex justify-center text-accent"><Loader2 class="w-6 h-6 animate-spin" /></div>
              <div v-else-if="!hasMoreSearch && songs.length > 0" class="text-xs font-mono text-medical-400">-- END OF RESULTS --</div>
            </div>

            <!-- 加载更多 -->
            <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
              <div v-if="isLoadingMore" class="flex justify-center text-accent"><Loader2 class="w-6 h-6 animate-spin" /></div>
              <div v-else-if="!hasMore && songs.length > 0" class="text-xs font-mono text-medical-400">-- END OF PLAYLIST --</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useSearchLogic } from '../composables/useSearchLogic';
import { usePlaylistLogic } from '../composables/usePlaylistLogic';
import { X, Search, PlusCircle, ListPlus, Loader2, ArrowLeft, ChevronRight, Check, ExternalLink, Disc } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { musicApi } from '../api/music';

const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();
const platforms = [
  { id: 'netease', label: '网易云' },
  { id: 'bilibili', label: 'Bilibili' },
  { id: 'soundcloud', label: 'SoundCloud' },
  { id: 'ytmusic', label: 'YT Music' }
];

const isPlatformEnabled = (p) => {
  if (p === 'netease') return playerStore.config?.neteaseEnabled !== false;
  if (p === 'bilibili') return playerStore.config?.bilibiliEnabled !== false;
  if (p === 'soundcloud') return playerStore.config?.soundcloudEnabled !== false;
  if (p === 'ytmusic') return playerStore.config?.ytmusicEnabled !== false;
  return true;
};

// 1. 引入搜索逻辑
const {
  platform, keyword, songs, loading, listMode, hasMore: hasMoreSearch, doSearch, loadMore
} = useSearchLogic(emit);

const supportsPlaylists = computed(() => ['netease', 'bilibili', 'soundcloud'].includes(platform.value));

const handleSearchAction = async () => {
  if (albumSearchMode.value && platform.value === 'netease') {
    await searchAlbums();
  } else {
    await doSearch();
  }
  mobileView.value = 'songs';
};

// 2. 引入歌单逻辑 (注入依赖)
const {
  playlists, currentPlaylistId, searchUserKeyword, userSearchResults,
  isSearchingUser, isPlaylistsLoading, hasMore, isLoadingMore, bindings,
  searchUser, bindUser, loadPlaylist, handleScroll, loadRemainingSongs
} = usePlaylistLogic(platform, songs, listMode, loading);

// 3. 专辑搜索状态
const albumSearchMode = ref(false);
const albums = ref([]);
const currentAlbumId = ref(null);
const currentAlbumName = ref('');

const searchAlbums = async () => {
  if (!keyword.value.trim()) return;
  loading.value = true;
  albums.value = [];
  currentAlbumId.value = null;
  try {
    const data = await musicApi.searchAlbums(platform.value, keyword.value);
    albums.value = data || [];
  } catch (e) {
    console.error('Album search error:', e);
  } finally {
    loading.value = false;
  }
};

const handleSelectAlbum = async (album) => {
  currentAlbumId.value = album.id;
  currentAlbumName.value = album.name;
  loading.value = true;
  songs.value = [];
  try {
    const data = await musicApi.getAlbumSongs(platform.value, album.id);
    songs.value = data || [];
  } catch (e) {
    console.error('Get album songs error:', e);
  } finally {
    loading.value = false;
  }
};

const handleImportAlbum = () => {
  playerStore.enqueueAlbum(platform.value, currentAlbumId.value);
  closeModal();
};

// 4. UI 状态
const mobileView = ref('playlists');
const playlistKeyword = ref('');
const contentScrollRef = ref(null);
const MAX_DURATION = 10 * 60 * 1000;
const SCROLL_STORAGE_KEY = 'mp_search_scroll_positions';
const savedScrollPositions = ref(loadScrollPositions());
let playlistSearchTimer = null;

const displayedSongs = computed(() => {
  const query = playlistKeyword.value.trim().toLocaleLowerCase();
  if (listMode.value !== 'playlist' || platform.value !== 'netease' || !query) {
    return songs.value;
  }
  return songs.value.filter((song) => {
    const haystack = [song.name, ...(song.artists || [])].join(' ').toLocaleLowerCase();
    return haystack.includes(query);
  });
});

// 4. 交互胶水代码
const isUnplayable = (song) => {
  return platform.value === 'bilibili' && song.duration > MAX_DURATION;
};

const handleSelectPlaylist = async (pid) => {
  saveScrollPosition();
  playlistKeyword.value = '';
  await loadPlaylist(pid);
  mobileView.value = 'songs';
  restoreScrollPosition();
};

const handleImportPlaylist = () => {
  playerStore.enqueuePlaylist(platform.value, currentPlaylistId.value);
  closeModal();
};

const getSourceUrl = (song) => {
  const sourcePlatform = song.platform || platform.value;
  if (sourcePlatform === 'netease') return `https://music.163.com/song?id=${encodeURIComponent(song.id)}`;
  if (sourcePlatform === 'bilibili' && song.id) return `https://www.bilibili.com/video/${encodeURIComponent(song.id)}`;
  if (sourcePlatform === 'ytmusic' && song.id) return `https://music.youtube.com/watch?v=${encodeURIComponent(song.id)}`;
  return null;
};

function loadScrollPositions() {
  try {
    return JSON.parse(localStorage.getItem(SCROLL_STORAGE_KEY) || '{}');
  } catch {
    return {};
  }
}

const scrollKey = () => `${platform.value}:${listMode.value}:${currentPlaylistId.value || 'results'}`;

const saveScrollPosition = () => {
  if (!contentScrollRef.value) return;
  savedScrollPositions.value[scrollKey()] = contentScrollRef.value.scrollTop;
  localStorage.setItem(SCROLL_STORAGE_KEY, JSON.stringify(savedScrollPositions.value));
};

const restoreScrollPosition = async () => {
  await nextTick();
  if (contentScrollRef.value) {
    contentScrollRef.value.scrollTop = savedScrollPositions.value[scrollKey()] || 0;
  }
};

const onContentScroll = (event) => {
  handleScroll(event);
  saveScrollPosition();
  const el = event.target;
  if (listMode.value === 'search' && !albumSearchMode.value && hasMoreSearch.value && !loading.value) {
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 200) {
      console.log('[scroll] triggering loadMore, scrollHeight:', el.scrollHeight, 'scrollTop:', el.scrollTop, 'clientHeight:', el.clientHeight);
      loadMore();
    }
  }
};

const closeModal = () => {
  saveScrollPosition();
  emit('close');
};

const switchPlatform = (nextPlatform) => {
  saveScrollPosition();
  platform.value = nextPlatform;
};

// 本地维护正在添加中的歌曲 ID 集合
const pendingIds = ref(new Set());

// 检查歌曲是否已经在队列中
const isInQueue = (songId) => {
  return playerStore.queue.some(item => item.music.id === songId);
};

// 处理点击添加
const handleAddClick = (song) => {
  // 防抖：如果正在添加或已在队列，直接忽略
  if (pendingIds.value.has(song.id) || isInQueue(song.id)) return;

  // 1. 设置 loading 状态
  pendingIds.value.add(song.id);

  // 2. 发送请求
  playerStore.enqueue(platform.value, song.id);

  // 3. 设定一个“冷却时间”用于视觉反馈 (2秒)
  // WebSocket 是异步的，我们不需要一直等到服务器返回，
  // 这里的 loading 主要是为了告诉用户“操作已受理”并防止连点
  setTimeout(() => {
    pendingIds.value.delete(song.id);
  }, 2000);
};

// 监听搜索动作 -> 自动切视图
watch(listMode, (mode) => {
  if (mode === 'search') mobileView.value = 'songs';
  if (props.isOpen) restoreScrollPosition();
});

watch(platform, () => {
  songs.value = [];
  albums.value = [];
  currentAlbumId.value = null;
  listMode.value = 'search';
  playlistKeyword.value = '';
  albumSearchMode.value = false;
  mobileView.value = supportsPlaylists.value ? 'playlists' : 'songs';
  if (props.isOpen) restoreScrollPosition();
});

watch(playlistKeyword, (value) => {
  if (playlistSearchTimer) clearTimeout(playlistSearchTimer);
  if (platform.value !== 'netease' || listMode.value !== 'playlist' || !value.trim()) return;
  playlistSearchTimer = setTimeout(() => loadRemainingSongs(), 250);
});

watch(() => props.isOpen, async (val) => {
  if (val) {
    mobileView.value = 'playlists';
    await restoreScrollPosition();
  } else {
    saveScrollPosition();
  }
});
</script>

<style scoped>
.clip-tab { clip-path: polygon(0 0, 100% 0, 90% 100%, 0 100%); }
</style>
