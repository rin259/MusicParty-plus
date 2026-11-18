import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import SearchModal from '../../src/components/SearchModal.vue';
import { usePlayerStore } from '../../src/stores/player.js';
import { useUserStore } from '../../src/stores/user.js';
import '../../src/style.css';

createApp({
    setup() {
        const player = usePlayerStore();
        const user = useUserStore();
        user.bindings.netease = 'layout-test-user';
        player.config = { neteaseEnabled: true, bilibiliEnabled: true, soundcloudEnabled: true, ytmusicEnabled: true };
        return () => h(SearchModal, { isOpen: true });
    }
}).use(createPinia()).mount('#app');
