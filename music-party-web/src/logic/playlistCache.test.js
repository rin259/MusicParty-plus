import test from 'node:test';
import assert from 'node:assert/strict';

// playlistCache 依赖 localStorage；这里给一个最小实现，测的是缓存策略本身
const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k)
};

const {
  readPlaylistCache,
  writePlaylistCache,
  updatePlaylistScrollTop,
  dropPlaylistCache,
  getUserPlaylistsCache,
  setUserPlaylistsCache
} = await import('./playlistCache.js');

const song = (id) => ({ id, name: `song-${id}`, artists: ['a'], duration: 1000 });

test('round-trips songs, paging state and scroll position', () => {
  writePlaylistCache('netease', 'p1', {
    songs: [song('1'), song('2')], offset: 50, hasMore: true, scrollTop: 420
  });

  const entry = readPlaylistCache('netease', 'p1');
  assert.equal(entry.songs.length, 2);
  assert.equal(entry.offset, 50);
  assert.equal(entry.hasMore, true);
  assert.equal(entry.scrollTop, 420);
});

test('keeps platforms and playlists apart', () => {
  writePlaylistCache('netease', 'p1', { songs: [song('a')], offset: 1, hasMore: false, scrollTop: 0 });
  writePlaylistCache('soundcloud', 'p1', { songs: [song('b')], offset: 2, hasMore: true, scrollTop: 10 });

  assert.equal(readPlaylistCache('netease', 'p1').songs[0].id, 'a');
  assert.equal(readPlaylistCache('soundcloud', 'p1').songs[0].id, 'b');
});

test('expires entries past the 7-day ttl', () => {
  writePlaylistCache('netease', 'old', { songs: [song('x')], offset: 1, hasMore: false, scrollTop: 0 });
  const raw = JSON.parse(store.get('mp_playlist_cache_v2'));
  // 超过 7 天
  raw['netease:old'].at = Date.now() - 8 * 24 * 60 * 60 * 1000;
  store.set('mp_playlist_cache_v2', JSON.stringify(raw));

  assert.equal(readPlaylistCache('netease', 'old'), null);
});

test('survives within 7-day ttl without unexpected expiration', () => {
  writePlaylistCache('netease', 'recent', { songs: [song('y')], offset: 1, hasMore: false, scrollTop: 100 });
  const raw = JSON.parse(store.get('mp_playlist_cache_v2'));
  // 2 天前
  raw['netease:recent'].at = Date.now() - 2 * 24 * 60 * 60 * 1000;
  store.set('mp_playlist_cache_v2', JSON.stringify(raw));

  const entry = readPlaylistCache('netease', 'recent');
  assert.ok(entry);
  assert.equal(entry.scrollTop, 100);
});

test('lightweight updatePlaylistScrollTop updates scrollTop without rewriting whole object', () => {
  writePlaylistCache('netease', 'scroll_test', {
    songs: [song('s1')], offset: 10, hasMore: true, scrollTop: 0
  });
  updatePlaylistScrollTop('netease', 'scroll_test', 850);
  const entry = readPlaylistCache('netease', 'scroll_test');
  assert.equal(entry.scrollTop, 850);
  assert.equal(entry.songs.length, 1);
  assert.equal(entry.offset, 10);
});

test('evicts the least recently used beyond the 64 cap', () => {
  for (let i = 0; i < 70; i++) {
    writePlaylistCache('netease', `p${i}`, { songs: [song(String(i))], offset: 1, hasMore: false, scrollTop: 0 });
  }
  // 最多保留 64 个：最早的几个应被淘汰
  assert.equal(readPlaylistCache('netease', 'p0'), null);
  assert.equal(readPlaylistCache('netease', 'p5'), null);
  assert.ok(readPlaylistCache('netease', 'p69'));
});

test('drop removes a single entry', () => {
  writePlaylistCache('netease', 'gone', { songs: [song('z')], offset: 1, hasMore: false, scrollTop: 0 });
  dropPlaylistCache('netease', 'gone');
  assert.equal(readPlaylistCache('netease', 'gone'), null);
});

test('user playlists list caching works and survives', () => {
  setUserPlaylistsCache('netease', 'u123', [{ id: 'pl1', name: '我的歌单' }]);
  const cached = getUserPlaylistsCache('netease', 'u123');
  assert.ok(Array.isArray(cached));
  assert.equal(cached.length, 1);
  assert.equal(cached[0].name, '我的歌单');
});

test('survives corrupt storage', () => {
  store.set('mp_playlist_cache_v2', '{not json');
  assert.equal(readPlaylistCache('netease', 'whatever'), null);
  writePlaylistCache('netease', 'ok', { songs: [], offset: 0, hasMore: true, scrollTop: 0 });
  assert.ok(readPlaylistCache('netease', 'ok'));
});