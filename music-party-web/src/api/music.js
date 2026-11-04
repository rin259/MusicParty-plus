import client from './client';

export const musicApi = {
    // 搜索歌曲（关键词走 query：歌名含 "/" 时不会再 404）
    search: (platform, keyword, offset = 0, limit = 30, config = {}) =>
        client.get('/api/search', {
            params: { platform, q: keyword, offset, limit },
            ...config
        }),

    // 四源健康探测（服务端 60s 缓存）
    sourcesHealth: (config = {}) => client.get('/api/sources/health', config),

    // 粘贴链接解析
    resolveLink: (url, config = {}) => client.get('/api/resolve', { params: { url }, ...config }),

    // 获取歌词
    getLyric: (platform, songId) => client.get(`/api/music/lyric/${platform}/${songId}`),

    // 获取用户歌单
    getUserPlaylists: (platform, userId) => client.get(`/api/user/playlists/${platform}/${userId}`),

    // 获取歌单详情 (分页)
    getPlaylistSongsPage: (platform, playlistId, offset, limit) =>
        client.get(`/api/playlist/songs/${platform}/${encodeURIComponent(playlistId)}`,
            { params: { offset, limit }, raw: true }),

    // 搜索用户
    searchUser: (platform, keyword) => client.get(`/api/user/search/${platform}`, { params: { keyword } }),
    soundcloudOauthStatus: () => client.get('/api/soundcloud/oauth/status'),

    // 搜索专辑（query 参数，含 "/" 的专辑名不会再 404）
    searchAlbums: (platform, keyword) => client.get('/api/search/albums', { params: { platform, q: keyword } }),

    // 获取专辑歌曲
    getAlbumSongs: (platform, albumId) => client.get(`/api/album/songs/${platform}/${albumId}`)
};
