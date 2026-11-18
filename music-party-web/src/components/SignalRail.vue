<template>
  <span class="signal-rail" :class="`signal-rail--${variant}`" aria-hidden="true">
    <span v-if="ratio !== null" class="signal-rail__fill" :style="{ transform: `scaleX(${ratio})` }" />
    <span v-else-if="active && variant === 'cells'" class="signal-rail__cells"><i /><i /><i /></span>
    <span v-else-if="active" class="signal-rail__sweep" />
    <span class="signal-rail__ticks" />
  </span>
</template>

<script setup>
defineProps({
  active: Boolean,
  /** Real completion ratio (0–1). When null the rail reports activity, never fake percentage. */
  ratio: { type: Number, default: null },
  /** cells: compact 3-lamp chase. sweep: one wide pass for full-width rails. */
  variant: { type: String, default: 'cells' }
});
</script>

<style scoped>
.signal-rail {
  position: relative;
  display: block;
  height: 3px;
  overflow: hidden;
  background: rgb(var(--medical-200));
}
.signal-rail__fill, .signal-rail__sweep, .signal-rail__ticks { position: absolute; inset: 0; }
.signal-rail__fill {
  background: rgb(var(--accent) / 0.85);
  transform-origin: left center;
  transition: transform 220ms cubic-bezier(.22, 1, .36, 1);
}
/* Three lamps step once each, twice, then rest. Activity, not a fake percentage. */
.signal-rail--cells {
  width: 18px;
  height: 6px;
  overflow: visible;
  background: transparent;
}
/* No background or tick overlay here: on a 26px rail the comb pattern reads
   as a squeezed block instead of three lamps. */
.signal-rail--cells .signal-rail__ticks { display: none; }
.signal-rail--cells .signal-rail__cells {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  gap: 3px;
}
.signal-rail--cells .signal-rail__cells i {
  width: 4px;
  height: 4px;
  background: rgb(var(--accent));
  opacity: 0.18;
  animation: signal-cell 520ms ease-in-out 2 both;
}
.signal-rail__cells i:nth-child(2) { animation-delay: 110ms; }
.signal-rail__cells i:nth-child(3) { animation-delay: 220ms; }
.signal-rail__sweep {
  width: 22%;
  background: rgb(var(--accent) / 0.72);
  animation: signal-sweep 820ms 120ms cubic-bezier(.22, 1, .36, 1) 2 both;
}
.signal-rail__ticks {
  background: repeating-linear-gradient(90deg, transparent 0 3px, rgb(var(--surface-1)) 3px 4px);
  pointer-events: none;
}
@keyframes signal-cell {
  0%, 100% { opacity: 0.2; }
  45% { opacity: 1; }
}
@keyframes signal-sweep {
  0% { transform: translateX(-100%); opacity: 0; }
  20%, 72% { opacity: 1; }
  100% { transform: translateX(460%); opacity: 0; }
}
</style>