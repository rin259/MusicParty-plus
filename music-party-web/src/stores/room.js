import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { roomsApi } from '../api/rooms';
import { STORAGE_KEYS } from '../constants/keys';

const readAccess = () => {
    try {
        const raw = JSON.parse(localStorage.getItem(STORAGE_KEYS.ROOM_ACCESS) || '{}');
        return raw && typeof raw === 'object' ? raw : {};
    } catch {
        return {};
    }
};

const roomError = (error, fallback) => {
    const payload = error?.response?.data;
    if (payload?.message) return payload.message;
    if (typeof payload === 'string' && payload) return payload;
    return error?.message || fallback;
};

export const useRoomStore = defineStore('room', () => {
    const deviceId = computed(() => localStorage.getItem(STORAGE_KEYS.TOKEN) || '');

    const rooms = ref([]);
    const roomId = ref(localStorage.getItem(STORAGE_KEYS.ROOM_ID) || '');
    const info = ref(null);
    const entered = ref(false);
    const loading = ref(false);
    const error = ref('');
    const access = ref(readAccess());

    const currentName = computed(() => info.value?.name || (roomId.value === 'main' ? '主房间' : roomId.value || '未选择'));
    const hasPassword = computed(() => !!info.value?.hasPassword);

    const passwordFor = (id) => access.value[id] || '';

    const rememberPassword = (id, password) => {
        if (!id || !password) return;
        access.value = { ...access.value, [id]: password };
        localStorage.setItem(STORAGE_KEYS.ROOM_ACCESS, JSON.stringify(access.value));
    };

    const forgetPassword = (id) => {
        if (!id) return;
        const next = { ...access.value };
        delete next[id];
        access.value = next;
        localStorage.setItem(STORAGE_KEYS.ROOM_ACCESS, JSON.stringify(next));
    };

    const loadRooms = async () => {
        loading.value = true;
        error.value = '';
        try {
            rooms.value = (await roomsApi.mine()) || [];
            return rooms.value;
        } catch (e) {
            error.value = roomError(e, '无法读取房间列表');
            return [];
        } finally {
            loading.value = false;
        }
    };

    const fetchInfo = async (id) => {
        const data = await roomsApi.info(id);
        info.value = data;
        return data;
    };

    const createRoom = async (name, password) => {
        const data = await roomsApi.create({ name, password: password || null });
        await loadRooms();
        return data;
    };

    const verify = async (id, password) => {
        try {
            await roomsApi.verify(id, password);
            return true;
        } catch {
            return false;
        }
    };

    /** 进入房间：有密码则先校验；成功后记录 roomId（真正连接由 App 触发）。 */
    const enter = async (id, password) => {
        error.value = '';
        let room;
        try {
            room = await fetchInfo(id);
        } catch (e) {
            error.value = e?.response?.status === 404 ? '房间不存在或已关闭' : roomError(e, '无法读取房间信息');
            throw e;
        }

        if (room?.hasPassword) {
            const candidate = password !== undefined && password !== null ? password : passwordFor(id);
            if (!candidate) {
                const need = new Error('PASSWORD_REQUIRED');
                need.code = 'PASSWORD_REQUIRED';
                throw need;
            }
            const ok = await verify(id, candidate);
            if (!ok) {
                forgetPassword(id);
                const invalid = new Error('INVALID_PASSWORD');
                invalid.code = 'INVALID_PASSWORD';
                throw invalid;
            }
            rememberPassword(id, candidate);
        } else {
            forgetPassword(id);
        }

        roomId.value = id;
        info.value = room;
        entered.value = true;
        localStorage.setItem(STORAGE_KEYS.ROOM_ID, id);
        const url = new URL(window.location.href);
        url.searchParams.set('room', id);
        window.history.replaceState({}, '', url);
        return room;
    };

    const leave = () => {
        entered.value = false;
        info.value = null;
        const url = new URL(window.location.href);
        url.searchParams.delete('room');
        window.history.replaceState({}, '', url);
    };

    /** 服务端拒绝连接（密码错/房间已关闭）时由 socket 层调用。 */
    const reject = (message) => {
        entered.value = false;
        error.value = message || '无法进入房间';
        if (info.value) forgetPassword(info.value.id);
    };

    const clearError = () => { error.value = ''; };

    return {
        deviceId, rooms, roomId, info, entered, loading, error,
        currentName, hasPassword,
        passwordFor, rememberPassword, forgetPassword,
        loadRooms, fetchInfo, createRoom, verify, enter, leave, reject, clearError
    };
});
