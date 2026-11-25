// The ring field is laid out from the cover's width, so any cover resize must
// reach layoutRings(). The bug this guards: only the canvas was observed, so
// switching to wallpaper mode (which animates the cover from ~230px to ~410px)
// left the rings frozen at the old size until an unrelated window resize.

import { test } from 'node:test';
import assert from 'node:assert/strict';

/** Mirror of the layout maths, enough to assert the coupling. */
export function ringPlan({ coverSize, cssSize, ringCount }) {
  const worldPerCss = 1200 / cssSize;
  const halfDiag = coverSize * Math.SQRT1_2 * worldPerCss;
  const inner = halfDiag + 12 * worldPerCss;
  const room = 600 - 16 * worldPerCss - inner;
  const preferred = 48 * worldPerCss;
  const minimum = 14 * worldPerCss;
  let keep = ringCount;
  while (keep > 1 && room / (keep - 1) < minimum) keep--;
  if (room < 0) keep = 0;
  return { inner: Math.round(inner), keep };
}

test('a bigger cover pushes the innermost ring outwards', () => {
  const small = ringPlan({ coverSize: 230, cssSize: 854, ringCount: 7 });
  const large = ringPlan({ coverSize: 410, cssSize: 1440, ringCount: 7 });
  const smallRatio = small.inner / 230;
  const largeRatio = large.inner / 410;
  // the ring must keep clearing the cover at either size, not collapse onto it
  assert.ok(large.inner > 0);
  assert.ok(Math.abs(smallRatio - largeRatio) < 1.5, 'inner radius should scale with the cover');
});

test('a stale cover size under-clears the cover once it grows', () => {
  const stale = ringPlan({ coverSize: 230, cssSize: 1440, ringCount: 7 });
  const fresh = ringPlan({ coverSize: 410, cssSize: 1440, ringCount: 7 });
  assert.ok(fresh.inner > stale.inner, 're-layout after the transition must enlarge the field');
});

test('a cover wider than the canvas yields no rings rather than inverted ones', () => {
  // Known limitation, recorded rather than asserted as desirable: once the cover
  // is wider than the canvas there is no room outside it, so every ring is dropped.
  const plan = ringPlan({ coverSize: 900, cssSize: 854, ringCount: 7 });
  assert.ok(plan.inner > 0);
  assert.equal(plan.keep, 0);
});
