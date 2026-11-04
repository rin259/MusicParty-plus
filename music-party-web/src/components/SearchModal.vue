<template>
  <div v-if="isOpen" class="fixed inset-0 z-[80] bg-scrim/80 backdrop-blur-sm flex items-center justify-center p-2 md:p-4">
    <div
        ref="dialogRef"
        role="dialog"
        aria-modal="true"
        aria-labelledby="search-modal-title"
        class="w-full max-w-4xl bg-medical-50 h-[90dvh] md:h-[80vh] flex flex-col shadow-2xl relative chamfer-br max-h-full overflow-hidden"
    >

      <!-- 关闭按钮 -->
      <button @click="closeModal" class="absolute top-0 right-0 flex items-center justify-center w-12 h-12 hover:text-accent-text z-50" aria-label="关闭搜索">
        <X class="w-6 h-6" />
      </button>

      <!-- 头部 -->
      <div class="p-3 md:p-5 border-b border-medical-200 bg-surface-1 flex-shrink-0">
        <div class="mb-2.5 flex items-baseline justify-between gap-3 pr-10">
          <h2 id="search-modal-title" class="text-lg md:text-xl font-bold font-mono text-medical-900 flex items-center gap-2">
            <Search class="w-5 h-5 text-accent-text" aria-hidden="true"/> 添加歌曲
          </h2>
          <span class="font-mono text-[9px] tracking-[0.16em] text-medical-500 tabular-nums">
            QUOTA {{ player.mySongCount }}/{{ player.mySongLimit || '—' }}
            <span class="mx-1 text-medical-300">|</span>
            QUEUE {{ player.queue.length }}
          </span>
        </div>

        <!-- 平台切换 TAB -->
        <div class="grid grid-cols-3 md:flex gap-1 mb-2.5" role="tablist" aria-label="音乐平台">
          <button
              v-for="p in platforms" :key="p.id"
              role="tab"
              :aria-selected="platform === p.id"
              @click="switchPlatform(p.id)"
              :disabled="!isPlatformEnabled(p.id)"
              class="min-w-0 min-h-9 px-2 md:px-4 py-1.5 text-[11px] md:text-xs font-bold uppercase transition-all"
              :class="[
                platform === p.id ? 'bg-ink text-ink-fg clip-tab' : 'bg-medical-200 text-medical-700 hover:bg-medical-300',
                !isPlatformEnabled(p.id) ? 'opacity-40 cursor-not-allowed grayscale' : ''
              ]"
          >
            {{ p.label }}
          </button>
        </div>

        <!-- 源总线：可达性 / 时延 / 结果数 -->
        <SourceBus
            class="mb-2.5"
            :platforms="enabledPlatforms"
            :sources="sources"
            :health="health"
            :probing="healthLoading"
            :active="platform"
            :summary="summary"
            @select="switchPlatform"
        />

        <!-- 搜索框 -->
        <div class="flex gap-2">
          <input
              ref="searchInputRef"
              v-model="keyword"
              @compositionstart="isComposing = true"
              @compositionend="isComposing = false"
              @keydown.enter="onEnter"
              @input="activeIndex = -1"
              @keydown.down.prevent="moveActive(1)"
              @keydown.up.prevent="moveActive(-1)"
              placeholder="搜索歌曲、歌手，或粘贴链接 / BV 号…"
              aria-label="搜索音乐"
              class="flex-1 min-h-10 border p-2.5 outline-none transition-colors duration-300 font-sans bg-medical-100 border-medical-200 focus:border-accent text-medical-900 placeholder:text-medical-500"
          />
          <button
              v-if="platform === 'netease'"
              @click="albumSearchMode = !albumSearchMode"
              class="flex items-center justify-center min-w-10 min-h-10 px-2.5 py-2 text-xs font-bold transition-colors flex-shrink-0"
              :class="albumSearchMode ? 'bg-accent text-accent-fg' : 'bg-medical-200 text-medical-700 hover:bg-medical-300'"
              title="搜索专辑"
              aria-label="搜索专辑"
              :aria-pressed="albumSearchMode"
          >
            <Disc class="w-4 h-4" />
          </button>
          <button
              @click="handleSearchAction"
              :disabled="loading && platform === 'all'"
              class="text-accent-fg px-3 md:px-5 py-2 font-bold transition-colors text-xs md:text-sm flex-shrink-0 font-sans bg-accent hover:bg-accent-hover min-h-10 disabled:opacity-60"
          >
            {{ looksLikeLink(keyword) ? '点歌' : '搜索' }}
          </button>
        </div>

        <!-- 搜索状态：真实的命中 / 归并 / 源进度 / 耗时 -->
        <div class="mt-1.5 flex items-center justify-between gap-3 font-mono text-[9px] tracking-[0.12em] text-medical-500 tabular-nums">
          <span class="truncate">
            <template v-if="loading">
              SEARCHING {{ summary.done }}/{{ summary.sources }} · {{ Math.round(summary.elapsedMs) }}ms
            </template>
            <template v-else-if="summary.hits > 0">
              HITS {{ summary.hits }} · MERGED {{ summary.merged }} · {{ summary.done }}/{{ summary.sources }} SRC · {{ Math.round(summary.elapsedMs) }}ms
            </template>
            <template v-else-if="lastQuery">
              NO MATCHES FOR “{{ lastQuery }}”
            </template>
            <template v-else>
              <!-- 未查询时这里留空：零态已经说清楚能做什么，不再重复一遍 -->
            </template>
          </span>
          <span v-if="slowSources.length" class="shrink-0 text-accent-text">SLOW {{ slowSources.join('·') }}</span>
          <span v-else-if="filteredOut > 0" class="shrink-0">DEDUP −{{ filteredOut }}</span>
        </div>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-hidden flex flex-col md:flex-row relative">

        <!-- 左侧：我的歌单 / 绑定 -->
        <div v-if="supportsPlaylists" class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-medical-200 flex-col bg-surface-1 transition-all"
             :class="mobileView === 'playlists' ? 'flex w-full h-full' : 'hidden md:flex'"
        >
          <div class="p-2 md:p-3 bg-medical-100 text-xs font-bold text-medical-600 flex justify-between items-center font-sans">
            <span>用户歌单</span>
          </div>

          <div class="flex-1 overflow-y-auto p-2 space-y-2">
            <!-- 未绑定 -->
            <div v-if="!bindings[platform]" class="p-4 border border-dashed border-medical-300 bg-medical-50">
              <div class="text-xs text-medical-500 mb-2 text-center font-sans">{{ platform === 'soundcloud' ? '绑定公开主页以获取公开 Sets' : '绑定用户以获取用户歌单' }}</div>
              <!-- 一键绑定：完整 OAuth 授权，身份由服务端确认 -->
              <button
                  v-if="platform === 'soundcloud' && scOauthConfigured"
                  type="button"
                  @click="startSoundcloudOauth"
                  class="w-full min-h-11 bg-accent hover:bg-accent-hover text-accent-fg font-bold text-sm flex items-center justify-center gap-2 transition-colors mb-2"
              >
                <Search class="w-4 h-4" />
                使用 SoundCloud 账号一键绑定
              </button>
              <div v-if="platform === 'soundcloud' && scOauthConfigured" class="mb-2 flex items-center gap-2 text-[10px] font-mono text-medical-500">
                <span class="flex-1 border-t border-medical-200"></span><span>或手动输入</span><span class="flex-1 border-t border-medical-200"></span>
              </div>
              <div class="flex gap-1">
                <input v-model="searchUserKeyword" @keyup.enter="searchUser" :placeholder="platform === 'soundcloud' ? '粘贴主页/短链，或 @handle' : '搜索用户名'" aria-label="搜索用户" class="flex-1 min-w-0 min-h-11 bg-surface-1 border border-medical-200 p-2 text-sm outline-none focus:border-accent font-sans text-medical-900 placeholder:text-medical-500" />
                <button @click="searchUser" :disabled="isSearchingUser || !searchUserKeyword.trim()" class="flex items-center justify-center w-11 h-11 bg-accent hover:bg-accent-hover transition-colors flex-shrink-0 disabled:opacity-50" aria-label="搜索用户"><Search class="w-4 h-4 text-accent-fg"/></button>
              </div>
              <div v-if="platform === 'soundcloud'" class="mt-1.5 text-[10px] leading-relaxed text-medical-500 font-sans">
                支持 <span class="font-mono">soundcloud.com/用户名</span>、带 /sets/ 的歌单链接、<span class="font-mono">on.soundcloud.com</span> 短链，或直接输入 @handle。
              </div>
              <div v-if="userSearchAttempted && !isSearchingUser && userSearchResults.length === 0" class="mt-2 text-xs text-danger font-sans">
                没找到这个用户。主页未公开、链接复制错了，或该账号已注销都可能造成这个结果。
              </div>
              <!-- 搜索结果列表 -->
              <div v-if="userSearchResults.length > 0" class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-medical-200 pt-2">
                <button v-for="user in userSearchResults" :key="user.id" @click="bindUser(user)" class="w-full min-h-11 flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group text-left">
                  <img v-if="user.avatarUrl" :src="user.avatarUrl" alt="" class="w-6 h-6 rounded-full bg-medical-200"/>
                  <div v-else class="w-6 h-6 rounded-full bg-accent/20 text-accent-text flex items-center justify-center text-xs font-bold">{{ user.name.slice(0, 1) }}</div>
                  <span class="text-xs font-bold truncate flex-1 text-medical-900">{{ user.name }}</span>
                </button>
              </div>
              <div v-if="isSearchingUser" class="text-center py-2"><Loader2 class="w-4 h-4 animate-spin mx-auto text-accent-text"/></div>
            </div>

            <!-- 已绑定：歌单列表 -->
            <template v-else>
              <div class="flex justify-between items-center px-2 py-1.5 bg-medical-50 border-b border-medical-200 gap-2">
                <div class="flex items-center gap-2 min-w-0">
                  <img v-if="bindingProfiles[platform]?.avatarUrl" :src="bindingProfiles[platform].avatarUrl" alt="" class="w-5 h-5 rounded-full bg-medical-200 flex-shrink-0"/>
                  <div class="min-w-0">
                    <div v-if="bindingProfiles[platform]?.name" class="text-xs font-bold text-medical-900 truncate">{{ bindingProfiles[platform].name }}</div>
                    <div class="text-[10px] font-mono text-medical-500 truncate">@{{ bindings[platform] }}</div>
                  </div>
                </div>
                <button @click="player.bindAccount(platform, '')" class="text-[11px] font-bold text-danger hover:underline px-2 min-h-9 flex-shrink-0">UNLINK</button>
              </div>
              
              <!-- 刷新当前歌单：重新核对已加载曲目，但不清空缓存数据 -->
              <div v-if="currentPlaylistId && !isPlaylistsLoading" class="px-2 pb-2">
                <button
                    type="button"
                    @click="refreshPlaylist"
                    :disabled="refreshState.active"
                    class="w-full min-h-9 flex items-center justify-center gap-2 border border-medical-200 bg-surface-1 font-mono text-[10px] tracking-[0.12em] text-medical-600 hover:border-accent hover:text-accent-text transition-colors disabled:opacity-60"
                >
                  <Loader2 v-if="refreshState.active" class="w-3.5 h-3.5 animate-spin text-accent-text" />
                  <RefreshCw v-else class="w-3.5 h-3.5" />
                  <span>{{ refreshState.active ? `REFRESH ${refreshState.done}/${refreshState.target}` : 'REFRESH LIST' }}</span>
                </button>
              </div>

              <div v-if="isPlaylistsLoading" class="flex justify-center py-8">
                <Loader2 class="w-6 h-6 animate-spin text-accent-text" />
              </div>
              
              <button v-else v-for="pl in playlists" :key="pl.id" @click="handleSelectPlaylist(pl.id)" class="w-full min-h-14 flex items-center gap-3 p-2 hover:bg-medical-100 cursor-pointer group transition-colors text-left">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden"><CoverImage :src="pl.coverImgUrl" class="w-full h-full" /></div>
                <div class="overflow-hidden">
                  <div class="text-sm font-bold truncate group-hover:text-accent-text text-medical-900">{{ pl.name }}</div>
                  <div class="text-xs font-mono text-medical-500">{{ pl.platform === 'soundcloud' ? 'PUBLIC SET' : `${pl.trackCount} TRACKS` }}</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-500 md:hidden ml-auto" />
              </button>
            </template>
          </div>
        </div>

        <!-- 右侧：歌曲列表 -->
        <div class="bg-medical-50 flex-col min-h-0" :class="[supportsPlaylists ? 'md:flex-1' : 'flex-1', mobileView === 'songs' || !supportsPlaylists ? 'flex w-full h-full' : 'hidden md:flex']">
          <!-- 移动端返回条 -->
          <div class="md:hidden flex items-center gap-2 p-2 bg-surface-1 border-b border-medical-200 flex-shrink-0">
            <button v-if="supportsPlaylists" @click="mobileView = 'playlists'" class="flex items-center justify-center w-11 h-11 -ml-1 text-medical-500 hover:text-medical-900" aria-label="返回歌单"><ArrowLeft class="w-5 h-5" /></button>
            <span class="font-bold text-sm text-medical-800">{{ albumSearchMode ? 'ALBUM SEARCH' : (listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS') }}</span>
          </div>

          <div ref="contentScrollRef" @scroll="onContentScroll" class="flex-1 overflow-y-auto p-2 md:p-4">
            <div v-if="loading && songs.length === 0" class="text-center py-10 font-mono text-accent-text animate-pulse">> LOADING DATA STREAM...</div>

            <!-- 专辑搜索结果 -->
            <div v-else-if="albumSearchMode && listMode === 'search' && !currentAlbumId" class="space-y-1">
              <div v-if="albums.length === 0" class="text-center py-10 text-medical-500 text-xs font-mono">NO ALBUMS FOUND / 换个专辑名试试</div>
              <button v-for="album in albums" :key="album.id" @click="handleSelectAlbum(album)" class="w-full min-h-14 flex items-center p-3 border border-transparent transition-all bg-surface-1 hover:border-medical-300 hover:shadow-sm cursor-pointer group text-left">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden mr-3"><CoverImage :src="album.coverUrl" class="w-full h-full" /></div>
                <div class="min-w-0 flex-1">
                  <div class="text-sm font-bold truncate text-medical-900">{{ album.name }}</div>
                  <div class="text-xs text-medical-500 truncate">{{ album.artist }} · {{ album.trackCount }} TRACKS</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-500 ml-2" />
              </button>
            </div>

            <!-- 专辑内歌曲列表 -->
            <div v-else-if="albumSearchMode && currentAlbumId">
              <div class="mb-4 p-4 bg-surface-1 border border-medical-200 shadow-sm">
                <div class="flex justify-between items-center gap-3">
                  <div class="min-w-0">
                    <button @click="currentAlbumId = null" class="text-xs text-accent-text hover:underline mb-1 flex items-center gap-1"><ArrowLeft class="w-3 h-3" /> 返回专辑列表</button>
                    <div class="font-bold text-lg truncate">{{ currentAlbumName }}</div>
                    <div class="text-xs text-medical-500 font-mono">{{ songs.length }} LOADED</div>
                  </div>
                  <button @click="handleImportAlbum" class="flex-shrink-0 bg-surface-strong text-white px-3 py-2 text-sm font-bold hover:bg-accent hover:text-accent-fg transition-colors flex items-center gap-2 font-sans min-h-10">
                    <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">导入全部</span>
                  </button>
                </div>
                <ImportMeter :job="importJob" />
              </div>
              <div class="space-y-1">
                <div v-for="(song, i) in songs" :key="rowKey(song)" class="flex items-center p-3 border border-transparent transition-all group bg-surface-1 hover:border-medical-300 hover:shadow-sm" :class="{ 'border-accent/40 bg-accent/[0.03]': activeIndex === i }" @mouseenter="activeIndex = i">
                  <div class="flex-1 w-0 flex items-center gap-3">
                    <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" /></div>
                    <div class="min-w-0 flex-1">
                      <div class="text-sm font-bold truncate text-medical-900">{{ song.name }}</div>
                      <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                    </div>
                  </div>
                  <AddButton :song="song" :state="rowState(song)" :queued="inQueue(song)" :blocked="isUnplayable(song)" @add="handleAddClick(song)" />
                </div>
              </div>
            </div>

            <!-- 歌单操作头 -->
            <div v-else-if="currentPlaylistId && listMode === 'playlist'" class="mb-4 p-4 bg-surface-1 border border-medical-200 shadow-sm">
              <div class="flex justify-between items-center gap-3">
                <div class="min-w-0">
                  <div class="text-xs font-sans text-medical-500">用户歌单</div>
                  <div class="font-bold text-lg truncate text-medical-900">{{ currentPlaylistId }}</div>
                  <div class="text-xs text-medical-500 font-mono">{{ songs.length }} LOADED</div>
                </div>
                <button @click="handleImportPlaylist" class="flex-shrink-0 bg-surface-strong text-white px-3 py-2 text-sm font-bold hover:bg-accent hover:text-accent-fg transition-colors flex items-center gap-2 font-sans min-h-10">
                  <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">导入全部</span>
                </button>
              </div>
              <ImportMeter :job="importJob" />
              <div v-if="platform === 'netease'" class="relative mt-3">
                <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-medical-500" />
                <input v-model="playlistKeyword" placeholder="在此歌单中搜索歌曲或歌手" class="w-full min-h-10 border border-medical-200 bg-medical-50 py-2 pl-9 pr-3 text-sm outline-none focus:border-accent" />
              </div>
            </div>

            <!-- 歌曲列表渲染 -->
            <div class="space-y-1">
              <!-- 零态 vs 真·无结果：未查询时显示「能做什么」，只有搜过之后才说「没找到」 -->
              <div v-if="displayedSongs.length === 0 && !loading" class="text-center py-10 px-6">
                <template v-if="!lastQuery && !playlistKeyword">
                  <div class="font-mono text-[10px] tracking-[0.16em] text-medical-500">
                    ENTER A QUERY · 输入歌名、歌手，或粘贴链接
                  </div>
                  <div v-if="recentSearches.length" class="mt-5">
                    <div class="font-mono text-[9px] tracking-[0.14em] text-medical-500 mb-2">RECENT</div>
                    <div class="flex flex-wrap justify-center gap-2">
                      <button
                          v-for="q in recentSearches"
                          :key="q"
                          type="button"
                          @click="useRecent(q)"
                          class="px-2.5 py-1.5 border border-medical-200 bg-surface-1 font-sans text-xs text-medical-700 hover:border-accent hover:text-accent-text transition-colors"
                      >{{ q }}</button>
                    </div>
                  </div>
                  <div class="mt-5 text-sm text-medical-600 leading-relaxed">
                    一次查 {{ enabledPlatforms.length }} 个源；也可以粘贴网易云 / B站 / YouTube / SoundCloud 链接，或输入 BV 号。
                  </div>
                </template>
                <template v-else>
                  <div class="font-mono text-xs text-medical-500 mb-2">{{ playlistKeyword ? 'NO MATCHES FOUND' : 'NO RESULTS' }}</div>
                  <div class="text-sm text-medical-600 leading-relaxed">
                    {{ playlistKeyword ? '换个关键词，或清空筛选再看整张歌单。' : '换个关键词试试；或切到「全部」同时查多个源，也可以直接粘贴链接。' }}
                  </div>
                </template>
              </div>
              <div
                  v-for="(song, i) in displayedSongs"
                  :key="rowKey(song)"
                  class="flex items-center p-3 border border-transparent transition-all group"
                  :class="[
                    isUnplayable(song) ? 'opacity-50 grayscale bg-medical-50 cursor-not-allowed' : 'bg-surface-1 hover:border-medical-300 hover:shadow-sm',
                    activeIndex === i ? 'border-accent/40 bg-accent/[0.03]' : ''
                  ]"
                  @mouseenter="activeIndex = i"
              >
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" /></div>
                  <div class="min-w-0 flex-1">
                    <div class="text-sm font-bold truncate text-medical-900">{{ song.name }}</div>
                    <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                    <div class="mt-1 flex items-center gap-1.5 font-mono text-[9px] text-medical-500">
                      <span class="border border-medical-200 bg-medical-50 px-1 leading-4 text-medical-600">{{ PLATFORM_LABEL[song.platform] || song.platform }}</span>
                      <span class="tabular-nums">{{ song.duration > 0 ? formatDuration(song.duration) : '—:—' }}</span>
                      <span v-if="(song._sources?.length || 0) > 1" class="text-accent-text">+{{ song._sources.length - 1 }} SRC</span>
                      <span v-if="ingestLabel(song)" class="text-accent-text">{{ ingestLabel(song) }}</span>
                    </div>
                    <div v-if="rowState(song)?.state === 'failed'" class="mt-0.5 max-w-full truncate text-[9px] font-mono text-danger" :title="rowState(song)?.message">
                      {{ rowState(song)?.message }}
                    </div>
                  </div>
                </div>

                <div v-if="isUnplayable(song)" class="ml-2 flex-shrink-0"><span class="px-1.5 py-0.5 text-[10px] font-mono font-bold text-medical-500 border border-medical-300 bg-medical-100 rounded-sm">>10MIN</span></div>

                <a
                    v-if="listMode === 'playlist' && getSourceUrl(song)"
                    :href="getSourceUrl(song)"
                    target="_blank"
                    rel="noopener noreferrer"
                    @click.stop
                    class="ml-2 p-2 flex-shrink-0 text-medical-500 hover:text-accent-text transition-colors"
                    title="打开原曲页面"
                    aria-label="打开原曲页面"
                >
                  <ExternalLink class="w-4 h-4" />
                </a>

                <AddButton :song="song" :state="rowState(song)" :queued="inQueue(song)" :blocked="isUnplayable(song)" @add="handleAddClick(song)" />
              </div>
            </div>

            <!-- 加载更多 / 结束 -->
            <div v-if="listMode === 'search' && !albumSearchMode" class="py-4 text-center">
              <div v-if="loading && songs.length > 0" class="flex justify-center text-accent-text"><Loader2 class="w-6 h-6 animate-spin" /></div>
              <div v-else-if="platform === 'all' && songs.length > 0" class="text-[10px] font-mono tracking-[0.16em] text-medical-500">
                {{ summary.merged }} MERGED FROM {{ summary.hits }} HITS · 选择单个源可继续翻页
              </div>
              <div v-else-if="!hasMoreSearch && songs.length > 0" class="text-xs font-mono text-medical-500">-- END OF RESULTS --</div>
            </div>

            <!-- 加载更多 -->
            <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
              <div v-if="isLoadingMore" class="flex justify-center text-accent-text"><Loader2 class="w-6 h-6 animate-spin" /></div>
              <div v-else-if="!hasMore && songs.length > 0" class="text-xs font-mono text-medical-500">-- END OF PLAYLIST --</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useSearchLogic } from '../composables/useSearchLogic';
import { usePlaylistLogic } from '../composables/usePlaylistLogic';
import { X, Search, ListPlus, Loader2, ArrowLeft, ChevronRight, ExternalLink, Disc, RefreshCw } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import SourceBus from './SourceBus.vue';
import AddButton from './search/AddButton.vue';
import ImportMeter from './search/ImportMeter.vue';
import { musicApi } from '../api/music';
import { useFocusTrap } from '../composables/useFocusTrap';
import { useEventListener } from '@vueuse/core';
import { useToast } from '../composables/useToast';
import { formatDuration, looksLikeLink, PLATFORM_LABEL, songKey as mergeKey } from '../logic/searchMerge.js';

const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);

const dialogRef = ref(null);
const searchInputRef = ref(null);
useFocusTrap(computed(() => props.isOpen), dialogRef, {
  initialFocus: () => searchInputRef.value
});
const player = usePlayerStore();
const userStore = useUserStore();
const bindingProfiles = computed(() => userStore.bindingProfiles || {});
const { info, error: toastError } = useToast();

const platforms = [
  { id: 'all', label: '全部' },
  { id: 'netease', label: '网易云' },
  { id: 'bilibili', label: 'Bilibili' },
  { id: 'soundcloud', label: 'SoundCloud' },
  { id: 'ytmusic', label: 'YT Music' }
];

const isPlatformEnabled = (p) => {
  if (p === 'all') return ['netease', 'bilibili', 'soundcloud', 'ytmusic'].some((id) => isPlatformEnabled(id));
  if (p === 'netease') return player.config?.neteaseEnabled !== false;
  if (p === 'bilibili') return player.config?.bilibiliEnabled !== false;
  if (p === 'soundcloud') return player.config?.soundcloudEnabled !== false;
  if (p === 'ytmusic') return player.config?.ytmusicEnabled !== false;
  return true;
};

const enabledPlatforms = computed(() => ['netease', 'bilibili', 'soundcloud', 'ytmusic'].filter(isPlatformEnabled));

// 1. 搜索逻辑
const {
  platform, keyword, songs, loading, listMode, hasMore: hasMoreSearch,
  sources, health, healthLoading, summary, lastQuery, doSearch, loadMore, probeHealth
} = useSearchLogic();

const supportsPlaylists = computed(() => ['netease', 'bilibili', 'soundcloud'].includes(platform.value));

const filteredOut = computed(() => Math.max(0, (summary.value.hits || 0) - (summary.value.merged || 0)));
const SLOW_LABEL = { netease: 'NET', bilibili: 'BILI', soundcloud: 'SC', ytmusic: 'YT' };
const slowSources = computed(() => Object.entries(sources.value)
  .filter(([, s]) => s.state === 'loading' ? s.slow : (s.slow || ((s.latencyMs || 0) > 2500 && s.state === 'ok')))
  .map(([p]) => SLOW_LABEL[p] || p));

// SoundCloud OAuth 一键绑定：弹窗走完整授权，回跳页面用 postMessage 把身份交回本窗
const scOauthConfigured = ref(false);
const scOauthChecked = ref(false);
const checkScOauth = async () => {
  if (scOauthChecked.value) return;
  try {
    const data = await musicApi.soundcloudOauthStatus();
    scOauthConfigured.value = !!data?.configured;
  } catch {
    scOauthConfigured.value = false;
  }
  scOauthChecked.value = true;
};
watch(() => platform.value === 'soundcloud', (onSoundcloud) => {
  if (onSoundcloud) checkScOauth();
}, { immediate: true });

const startSoundcloudOauth = () => {
  const popup = window.open('/api/soundcloud/oauth/start', 'soundcloud-oauth', 'width=520,height=720');
  if (!popup) {
    toastError('弹窗被拦截：请允许本站弹窗，或改用手动输入 handle');
  }
};

useEventListener(window, 'message', (event) => {
  if (event.origin !== window.location.origin) return;
  const data = event.data;
  if (!data || data.source !== 'soundcloud-oauth') return;
  const profile = data.payload?.profile;
  if (data.payload?.ok && profile?.handle) {
    player.bindAccount('soundcloud', profile.handle);
    userStore.saveBindingProfile('soundcloud', { name: profile.name, avatarUrl: profile.avatarUrl });
    userSearchResults.value = [];
    userSearchAttempted.value = false;
    fetchPlaylists();
  } else {
    toastError('SoundCloud 绑定失败：' + (data.payload?.message || '未知错误'));
  }
});

// 2. 歌单逻辑
const {
  playlists, currentPlaylistId, searchUserKeyword, userSearchResults,
  isSearchingUser, isPlaylistsLoading, hasMore, isLoadingMore, bindings,
  searchUser, bindUser, loadPlaylist, handleScroll, loadRemainingSongs, userSearchAttempted,
  saveCurrentPlaylist, refreshPlaylist, refreshState, setPlaylistScrollTop, takeRestoreScrollTop
} = usePlaylistLogic(platform, songs, listMode, loading);

// 3. 专辑搜索
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
  if (userStore.isGuest) return promptNameThen(() => handleImportAlbum());
  player.enqueueAlbum(platform.value, currentAlbumId.value);
};

// 4. UI 状态
const mobileView = ref('playlists');
const playlistKeyword = ref('');
const contentScrollRef = ref(null);
const activeIndex = ref(-1);
const isComposing = ref(false);
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

const isUnplayable = (song) => {
  return (song.platform || platform.value) === 'bilibili' && song.duration > MAX_DURATION;
};

const rowKey = (song) => mergeKey(song);

// === 行级点歌状态机 ===
const rowState = (song) => player.enqueueStates[rowKey(song)] || null;
const inQueue = (song) => player.isInQueue(song.platform, song.id);
const isNowPlaying = (song) => player.nowPlaying?.music?.id === song.id
  && player.nowPlaying?.music?.platform === song.platform;

const ingestLabel = (song) => {
  if (isNowPlaying(song)) return 'ON AIR';
  const state = rowState(song);
  if (state?.state === 'submitting') return 'SUBMITTING';
  if (!inQueue(song)) return state?.state === 'failed' ? 'FAILED' : '';
  const item = player.queue.find((q) => q.music?.id === song.id && q.music?.platform === song.platform);
  if (!item) return 'QUEUED';
  if (item.status === 'FAILED') return 'SOURCE FAILED';
  if (item.status === 'READY') return 'READY';
  return 'RESOLVING';
};

const promptNameThen = (action) => {
  userStore.setPostNameAction(action);
  userStore.showNameModal = true;
};

const handleAddClick = (song) => {
  if (isUnplayable(song)) {
    toastError('B 站视频超过 10 分钟，不支持点歌');
    return;
  }
  if (inQueue(song) || rowState(song)?.state === 'submitting') return;
  if (player.mySongLimit && player.mySongCount >= player.mySongLimit) {
    toastError(`你的点歌已达上限 ${player.mySongLimit} 首`);
    return;
  }
  if (userStore.isGuest) {
    promptNameThen(() => handleAddClick(song));
    return;
  }
  player.enqueue(song.platform, song.id);
};

// === 搜索 / 链接 ===
const onEnter = (event) => {
  if (isComposing.value || event?.isComposing) return;
  if (activeIndex.value >= 0 && displayedSongs.value[activeIndex.value]) {
    handleAddClick(displayedSongs.value[activeIndex.value]);
    return;
  }
  submitSearch();
};

const submitSearch = async () => {
  const raw = (keyword.value || '').trim();
  if (!raw) return;
  activeIndex.value = -1;

  if (looksLikeLink(raw)) {
    await resolveAndEnqueue(raw);
    return;
  }
  await doSearch();
};

const resolveAndEnqueue = async (raw) => {
  try {
    const link = await musicApi.resolveLink(raw);
    const enqueueLink = () => {
      if (link.kind === 'playlist') {
        player.enqueuePlaylist(link.platform, link.id);
        info(`已识别 ${PLATFORM_LABEL[link.platform] || link.platform} 歌单 · 开始导入`);
      } else if (link.kind === 'album') {
        player.enqueueAlbum(link.platform, link.id);
        info(`已识别 ${PLATFORM_LABEL[link.platform] || link.platform} 专辑 · 开始导入`);
      } else {
        player.enqueue(link.platform, link.id);
        info(`已识别 ${PLATFORM_LABEL[link.platform] || link.platform} 单曲 · 已提交`);
      }
    };
    if (userStore.isGuest) {
      promptNameThen(enqueueLink);
      return;
    }
    enqueueLink();
  } catch (e) {
    toastError(e?.response?.data?.message || '无法识别这个链接');
  }
};

// 最近搜索（本机保存，最多 5 条）：零态下给出可点的起点，而不是一片空白
const RECENT_KEY = 'mp_recent_queries';
const recentSearches = ref([]);
const loadRecent = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
    recentSearches.value = Array.isArray(raw) ? raw.slice(0, 5) : [];
  } catch {
    recentSearches.value = [];
  }
};
const rememberQuery = (value) => {
  const term = (value || '').trim();
  if (!term || looksLikeLink(term)) return;
  const next = [term, ...recentSearches.value.filter((item) => item !== term)].slice(0, 5);
  recentSearches.value = next;
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(next)); } catch { /* 隐私模式下忽略 */ }
};
const useRecent = (term) => {
  keyword.value = term;
  handleSearchAction();
};
onMounted(loadRecent);

const handleSearchAction = async () => {
  rememberQuery(keyword.value);
  if (albumSearchMode.value && platform.value === 'netease') {
    await searchAlbums();
  } else {
    await submitSearch();
  }
  mobileView.value = 'songs';
};

const moveActive = (delta) => {
  const list = displayedSongs.value;
  if (!list.length) return;
  activeIndex.value = Math.max(0, Math.min(list.length - 1, activeIndex.value + delta));
};

// === 导入进度 ===
const importJob = computed(() => {
  const listId = currentAlbumId.value || currentPlaylistId.value;
  if (!listId) return null;
  const jobs = Object.values(player.importJobs).filter((job) => job.listId === listId);
  if (!jobs.length) return null;
  return jobs.sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0))[0];
});

// === 交互胶水 ===
const handleSelectPlaylist = async (pid) => {
  saveScrollPosition();
  playlistKeyword.value = '';
  await loadPlaylist(pid);
  mobileView.value = 'songs';
  // 命中缓存时，列表内容已经就位，按缓存里的滚动位置还原才有意义
  const cachedTop = takeRestoreScrollTop();
  await nextTick();
  if (contentScrollRef.value && cachedTop > 0) {
    contentScrollRef.value.scrollTop = cachedTop;
  } else {
    restoreScrollPosition();
  }
};

const handleImportPlaylist = () => {
  if (userStore.isGuest) return promptNameThen(() => handleImportPlaylist());
  player.enqueuePlaylist(platform.value, currentPlaylistId.value);
};

const getSourceUrl = (song) => {
  // 后台若带回真实页面地址（SoundCloud 的 permalink 只能这样拿到），优先用它
  if (typeof song.url === 'string' && /^https?:\/\//.test(song.url)) return song.url;
  const sourcePlatform = song.platform || platform.value;
  if (sourcePlatform === 'netease') return `https://music.163.com/song?id=${encodeURIComponent(song.id)}`;
  if (sourcePlatform === 'bilibili' && song.id) return `https://www.bilibili.com/video/${encodeURIComponent(song.id)}`;
  if (sourcePlatform === 'ytmusic' && song.id) return `https://music.youtube.com/watch?v=${encodeURIComponent(song.id)}`;
  if (sourcePlatform === 'soundcloud' && song.id) {
    // 数字 ID 拼不出页面：由服务端反查 permalink 后 302 过去
    return `/api/track/url?platform=soundcloud&id=${encodeURIComponent(song.id)}`;
  }
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
  if (listMode.value === 'playlist') {
    setPlaylistScrollTop(event.target.scrollTop);
  }
  const el = event.target;
  if (listMode.value === 'search' && !albumSearchMode.value && hasMoreSearch.value && !loading.value) {
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 200) {
      loadMore();
    }
  }
};

const closeModal = () => {
  saveScrollPosition();
  saveCurrentPlaylist();
  emit('close');
};

const switchPlatform = (nextPlatform) => {
  if (!isPlatformEnabled(nextPlatform) && nextPlatform !== 'all') return;
  saveScrollPosition();
  saveCurrentPlaylist();
  platform.value = nextPlatform;
};

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
  activeIndex.value = -1;
  mobileView.value = supportsPlaylists.value ? 'playlists' : 'songs';
  if (props.isOpen) restoreScrollPosition();
  // 已搜过词时，切源立即用同一关键词重搜（用户的预期是「换个源再看」）
  if (props.isOpen && keyword.value.trim() && !albumSearchMode.value) {
    doSearch();
  }
});

watch(playlistKeyword, (value) => {
  if (playlistSearchTimer) clearTimeout(playlistSearchTimer);
  if (platform.value !== 'netease' || listMode.value !== 'playlist' || !value.trim()) return;
  playlistSearchTimer = setTimeout(() => loadRemainingSongs(), 250);
});

watch(() => props.isOpen, async (val) => {
  if (val) {
    mobileView.value = supportsPlaylists.value ? 'playlists' : 'songs';
    await restoreScrollPosition();
    probeHealth();
  } else {
    saveScrollPosition();
  }
});
</script>

<style scoped>
.clip-tab { clip-path: polygon(0 0, 100% 0, 90% 100%, 0 100%); }
</style>
