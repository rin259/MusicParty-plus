import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from '../../src/App.vue';
import { usePlayerStore } from '../../src/stores/player.js';
import { useRoomStore } from '../../src/stores/room.js';
import { useUiStore } from '../../src/stores/ui.js';
import { useAppearanceStore } from '../../src/stores/appearance.js';
import { useAdminStore } from '../../src/stores/admin.js';
import { useChatStore } from '../../src/stores/chat.js';
import { useUserStore } from '../../src/stores/user.js';
import '../../src/style.css';

const pinia = createPinia();
const app = createApp(App).use(pinia);
const player = usePlayerStore(pinia), room = useRoomStore(pinia);
player.connect = () => { player.connected = true; };
player.disconnect = () => { player.connected = false; };
window.appearanceFixture = {
    player, room, ui: useUiStore(pinia), appearance: useAppearanceStore(pinia),
    admin: useAdminStore(pinia), chat: useChatStore(pinia), user: useUserStore(pinia)
};
app.mount('#app');
