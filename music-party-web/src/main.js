import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'

const pinia = createPinia()
const app = createApp(App)

// SPA 自己管理滚动：刷新/回退时不要浏览器擅自恢复到上一次位置，
// 那会让 room select 打开时停在页面中段（一次明显的上下跳）。
if ('scrollRestoration' in history) history.scrollRestoration = 'manual'

app.use(pinia)
app.mount('#app')
