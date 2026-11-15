import dayjs from 'dayjs';

export const formatDuration = (ms) => {
    if (!ms || ms < 0) return "00:00";
    const total = Math.round(ms / 1000);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
    }
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
};

export const formatTimeNow = () => {
    return dayjs().format('HH:mm:ss');
};