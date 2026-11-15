import { computed, onMounted, onUnmounted, ref } from 'vue';

/**
 * visualViewport 跟踪。
 *
 * iOS Safari / Android Chrome 在键盘弹出时只缩小视觉视口，布局视口和 100dvh 不变。
 * 任何"贴底"的聊天窗/输入条都必须读 visualViewport，否则会被键盘盖住。
 *
 * 这里只暴露只读数据，由使用方决定怎么布局（聊天窗用自己的高度公式）。
 */
export function useVisualViewport() {
    const supported = typeof window !== 'undefined' && !!window.visualViewport;
    const height = ref(typeof window !== 'undefined' ? window.innerHeight : 0);
    const offsetTop = ref(0);
    const scale = ref(1);
    let frame = 0;

    const read = () => {
        const viewport = window.visualViewport;
        height.value = viewport ? viewport.height : window.innerHeight;
        offsetTop.value = viewport ? viewport.offsetTop : 0;
        scale.value = viewport ? viewport.scale : 1;
    };

    const schedule = () => {
        if (frame) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            read();
        });
    };

    onMounted(() => {
        read();
        const viewport = window.visualViewport;
        viewport?.addEventListener('resize', schedule);
        viewport?.addEventListener('scroll', schedule);
        window.addEventListener('resize', schedule);
        window.addEventListener('orientationchange', schedule);
    });

    onUnmounted(() => {
        if (frame) cancelAnimationFrame(frame);
        const viewport = window.visualViewport;
        viewport?.removeEventListener('resize', schedule);
        viewport?.removeEventListener('scroll', schedule);
        window.removeEventListener('resize', schedule);
        window.removeEventListener('orientationchange', schedule);
    });

    // 捏合缩放也会改变 vv.height；这种时候不要把窗口缩小（用户只是想放大看）
    const visibleHeight = computed(() => (scale.value > 1.05 ? Math.max(height.value, window.innerHeight) : height.value));

    return { supported, height, offsetTop, scale, visibleHeight };
}
