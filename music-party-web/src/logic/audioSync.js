export const shouldRestartRepeat = ({ playMode, targetTime, ended, duration, currentTime }) => (
    playMode === 'REPEAT_ONE'
    && targetTime < 1500
    && (ended || (duration > 0 && currentTime >= duration - 1))
);

export const positionFromPointer = (clientX, left, width, durationMs) => {
    if (width <= 0 || durationMs <= 0) return 0;
    const ratio = Math.max(0, Math.min(1, (clientX - left) / width));
    return Math.round(ratio * durationMs);
};
