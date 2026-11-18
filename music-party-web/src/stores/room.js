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

const passwordError = (code, room) => Object.assign(new Error(code), { code, room });

export const useRoomStore = defineStore('room', () => {
    const deviceId = computed(() => localStorage.getItem(STORAGE_KEYS.TOKEN) || '');

    const myRooms = ref([]);
    const publicRooms = ref([]);
    const rooms = computed(() => [...myRooms.value, ...publicRooms.value]);
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
            // mine = 默认房 + 本设备创建；list = 其他设备创建的房间
            const [mine, all] = await Promise.all([roomsApi.mine(), roomsApi.list()]);
            myRooms.value = mine || [];
            const known = new Set(myRooms.value.map((room) => room.id));
            publicRooms.value = (all || []).filter((room) => !known.has(room.id));
            return rooms.value;
        } catch (e) {
            error.value = roomError(e, '无法读取房间列表');
            return [];
        } finally {
            loading.value = false;
        }
    };

    /** 房主关闭房间（服务端会广播 ROOM_CLOSED 并删除元数据/快照）。 */
    const deleteRoom = async (id) => {
        await roomsApi.remove(id);
        await loadRooms();
    };

    const createRoom = async (name, password, visibility = 'public') => {
        const data = await roomsApi.create({ name, password: password || null, visibility });
        await loadRooms();
        return data;
    };

    const updateRoom = async (id, payload) => {
        const data = await roomsApi.update(id, payload);
        await loadRooms();
        return data;
    };

    /** 保留当前房间，直到新房间的信息和密码都已校验。 */
    const enter = async (id, password, signal) => {
        error.value = '';
        let room;
        const candidate = password ?? passwordFor(id);
        try {
            room = await roomsApi.info(id, signal);
            if (room.hasPassword) {
                if (!candidate) throw passwordError('PASSWORD_REQUIRED', room);
                try {
                    await roomsApi.verify(id, candidate, signal);
                } catch (cause) {
                    if (cause?.response?.status !== 403) throw cause;
                    signal?.throwIfAborted();
                    forgetPassword(id);
                    throw passwordError('INVALID_PASSWORD', room);
                }
            }
        } catch (e) {
            signal?.throwIfAborted();
            if (!e?.room) {
                error.value = e?.response?.status === 404 ? '房间不存在或已关闭' : roomError(e, '无法进入房间');
            }
            throw e;
        }

        signal?.throwIfAborted();
        if (room.hasPassword) {
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
        // 清掉 ?room=，否则 landing 挂载时会再次自动进入一个已不存在的房间
        try {
            const url = new URL(window.location.href);
            if (url.searchParams.has('room')) {
                url.searchParams.delete('room');
                window.history.replaceState({}, '', url);
            }
        } catch { /* ignore */ }
    };

    const clearError = () => { error.value = ''; };

    return {
        deviceId, rooms, myRooms, publicRooms, roomId, info, entered, loading, error,
        currentName, hasPassword,
        passwordFor, rememberPassword, forgetPassword,
        loadRooms, createRoom, updateRoom, enter, leave, reject, clearError, deleteRoom
    };
});
