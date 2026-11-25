// 曲目在原站的页面地址。
//
// SoundCloud 的数字 ID 没有页面路径（`soundcloud.com/tracks/<id>` 会 301 到排行榜），
// 所以优先用后端解析时带回的真实 permalink；拼不出页面的平台退回服务端反查。

const TEMPLATES = {
  netease: (id) => `https://music.163.com/#/song?id=${encodeURIComponent(id)}`,
  bilibili: (id) => `https://www.bilibili.com/video/${encodeURIComponent(id)}`,
  ytmusic: (id) => `https://music.youtube.com/watch?v=${encodeURIComponent(id)}`
};

/** 是否有可直接打开的地址（真实 permalink）。 */
export function hasDirectUrl(song) {
  return typeof song?.url === 'string' && /^https?:\/\//.test(song.url);
}

/**
 * 解析一个可以打开的地址；解析不出时返回 null（调用方应禁用链接而不是给个 `#`）。
 * SoundCloud 需要服务端反查，因此返回站内路径。
 */
export function sourceUrlFor(song) {
  if (!song) return null;
  if (hasDirectUrl(song)) return song.url;
  const template = TEMPLATES[song.platform];
  if (template && song.id) return template(song.id);
  if (song.platform === 'soundcloud' && song.id) {
    return `/api/track/url?platform=soundcloud&id=${encodeURIComponent(song.id)}`;
  }
  return null;
}
