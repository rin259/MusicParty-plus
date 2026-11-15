import axios from 'axios';

const client = axios.create({
    // 可以在这里配置 baseURL 或 timeout
    timeout: 10000
});

// 响应拦截器：可以在这里统一处理 401/403 等错误
client.interceptors.response.use(
    // 默认解包成 body；需要读响应头（例如歌单分页的 X-Playlist-*）时传 { raw: true }
    res => (res.config && res.config.raw ? res : res.data),
    error => Promise.reject(error)
);

export default client;