import { onBeforeUnmount, onMounted, watch } from 'vue';

const FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])'
].join(',');

/**
 * Keeps keyboard focus inside a dialog while it is open and restores focus
 * to the element that opened it on close.
 *
 * @param {import('vue').Ref<boolean>} isOpen  reactive open state
 * @param {import('vue').Ref<HTMLElement|null>} containerRef  dialog root
 * @param {{ initialFocus?: () => HTMLElement|null }} options
 */
export function useFocusTrap(isOpen, containerRef, options = {}) {
    let previouslyFocused = null;

    const focusables = () => {
        if (!containerRef.value) return [];
        return Array.from(containerRef.value.querySelectorAll(FOCUSABLE))
            .filter((el) => el.offsetWidth > 0 || el.offsetHeight > 0);
    };

    const focusFirst = () => {
        const preferred = options.initialFocus?.();
        const target = preferred || focusables()[0] || containerRef.value;
        target?.focus?.();
    };

    const onKeydown = (event) => {
        if (event.key !== 'Tab' || !containerRef.value) return;
        const items = focusables();
        if (items.length === 0) {
            event.preventDefault();
            containerRef.value.focus?.();
            return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement;

        if (event.shiftKey && (active === first || !containerRef.value.contains(active))) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && (active === last || !containerRef.value.contains(active))) {
            event.preventDefault();
            first.focus();
        }
    };

    const activate = () => {
        previouslyFocused = document.activeElement;
        document.addEventListener('keydown', onKeydown, true);
        requestAnimationFrame(focusFirst);
    };

    const deactivate = () => {
        document.removeEventListener('keydown', onKeydown, true);
        if (previouslyFocused && document.contains(previouslyFocused)) {
            previouslyFocused.focus?.();
        }
        previouslyFocused = null;
    };

    watch(isOpen, (open) => (open ? activate() : deactivate()));

    onMounted(() => {
        if (isOpen.value) activate();
    });

    onBeforeUnmount(() => {
        document.removeEventListener('keydown', onKeydown, true);
    });
}
