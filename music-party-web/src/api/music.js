import client from './client';

export const musicApi = {
    // 搜索歌曲
    search: (platform, keyword, offset = 0, limit = 30) =>
        client.get(`/api/search/${platform}/${keyword}`, { params: { offset, limit } }),

    // 获取歌词
    getLyric: (platform, songId) => client.get(`/api/music/lyric/${platform}/${songId}`),

    // 获取用户歌单
    getUserPlaylists: (platform, userId) => client.get(`/api/user/playlists/${platform}/${userId}`),

    // 获取歌单详情 (分页)
    getPlaylistSongs: (platform, playlistId, offset, limit) =>
        client.get(`/api/playlist/songs/${platform}/${playlistId}`, { params: { offset, limit } }),

    // 搜索用户
    searchUser: (platform, keyword) => client.get(`/api/user/search/${platform}`, { params: { keyword } }),

    // 搜索专辑
    searchAlbums: (platform, keyword) => client.get(`/api/search/albums/${platform}/${keyword}`),

    // 获取专辑歌曲
    getAlbumSongs: (platform, albumId) => client.get(`/api/album/songs/${platform}/${albumId}`)
};
