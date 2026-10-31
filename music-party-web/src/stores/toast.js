import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useToastStore = defineStore('toast', () => {
    const toasts = ref([]);
    let idCounter = 0;

    const add = (options) => {
        const id = idCounter++;
        const toast = {
            id,
            title: options.title || 'SYSTEM NOTICE',
            message: options.message,
            type: options.type || 'info', // success, error, info, warning
            duration: options.duration || 3000
        };

        toasts.value.push(toast);

        if (toast.duration > 0) {
            setTimeout(() => {
                remove(id);
            }, toast.duration);
        }
    };

    const remove = (id) => {
        const index = toasts.value.findIndex(t => t.id === id);
        if (index !== -1) toasts.value.splice(index, 1);
    };

    // 点赞专用：时间窗内的连续点赞堆叠为同一条，右上角 ×N 计数。
    // 渐消逻辑与普通 toast 完全一致（同一 duration 定时器 + remove + leave 过渡），
    // 仅在堆叠命中时重置计时器，等最后一点赞完再照常淡出。
    const like = (message, duration = 3000) => {
        const existing = toasts.value.find(t => t.stack === 'like');
        if (existing) {
            existing.count = (existing.count || 1) + 1;
            existing.message = message;
            clearTimeout(existing._timer);
            existing._timer = setTimeout(() => remove(existing.id), existing.duration);
            return existing;
        }

        const id = idCounter++;
        const toast = {
            id,
            title: 'LIKE',
            message,
            type: 'info',
            duration,
            count: 1,
            stack: 'like'
        };
        toasts.value.push(toast);
        toast._timer = setTimeout(() => remove(toast.id), duration);
        return toast;
    };

    const success = (message, title = 'SUCCESS') => add({ title, message, type: 'success' });
    const error = (message, title = 'ERROR') => add({ title, message, type: 'error' });
    const info = (message, title = 'INFO') => add({ title, message, type: 'info' });
    const warning = (message, title = 'WARNING') => add({ title, message, type: 'warning' });

    return {
        toasts,
        add,
        remove,
        like,
        success,
        error,
        info,
        warning
    };
});
