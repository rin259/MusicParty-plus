/**
 * 聚焦输入框时的「最小滚动」。
 *
 * 旧实现对所有输入框统一 scrollIntoView({ block: 'center' })：iOS / Android
 * 已经会把聚焦的元素滚进可视区，我们再居中一次，用户看到的就是页面先跳
 * 一下、再跳一下。这里只在元素真的被视觉视口（键盘）或它自己的滚动容器
 * 遮住时，才做一次最小幅度的滚动。
 */
const REVEAL_DELAY = 320;
const EDGE_GAP = 16;

function findScrollParent(el) {
  let node = el?.parentElement;
  while (node && node !== document.body && node !== document.documentElement) {
    const style = window.getComputedStyle(node);
    const scrollable = /(auto|scroll)/.test(style.overflowY);
    if (scrollable && node.scrollHeight > node.clientHeight + 2) return node;
    node = node.parentElement;
  }
  return null;
}

function nudge(target, delta) {
  if (!delta) return;
  try {
    target.scrollBy({ top: delta, behavior: 'smooth' });
  } catch {
    target.scrollBy(0, delta);
  }
}

/**
 * landing 的文档滚动开关。
 *
 * iOS Safari 在 body 变成 overflow:hidden 的瞬间会把文档弹回顶部；
 * 打开密码/编辑浮层时整页「往上一跳」就是这么来的。这里先记下位置，
 * 切完类名同步恢复，视觉上不发生移动。
 */
export function setPageScrollable(scrollable) {
  const y = window.scrollY || window.pageYOffset || 0;
  document.body.classList.toggle('mp-scrollable', scrollable);
  document.documentElement.classList.toggle('mp-scrollable', scrollable);
  if (!scrollable) window.scrollTo(0, y);
}

export function revealOnFocus(event) {
  const el = event?.target;
  if (!el || typeof window === 'undefined') return;

  window.setTimeout(() => {
    if (document.activeElement !== el) return;

    const vv = window.visualViewport;
    const viewTop = (vv?.offsetTop ?? 0) + EDGE_GAP;
    const viewBottom = (vv?.offsetTop ?? 0) + (vv?.height ?? window.innerHeight) - EDGE_GAP;
    const rect = el.getBoundingClientRect();

    const scroller = findScrollParent(el);
    const bounds = scroller
      ? Math.max(scroller.getBoundingClientRect().top, viewTop)
      : viewTop;
    const bottom = scroller
      ? Math.min(scroller.getBoundingClientRect().bottom, viewBottom)
      : viewBottom;

    if (rect.bottom > bottom) {
      nudge(scroller || window, rect.bottom - bottom + EDGE_GAP);
    } else if (rect.top < bounds) {
      nudge(scroller || window, rect.top - bounds - EDGE_GAP);
    }
  }, REVEAL_DELAY);
}
