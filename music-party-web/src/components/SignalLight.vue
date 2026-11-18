<template>
  <span class="signal" :class="{ 'signal--active': active, 'signal--tick': active && tick, 'signal--fault': fault, 'signal--off': disabled }" aria-hidden="true" />
</template>

<script setup>
defineProps({ active: Boolean, tick: Boolean, fault: Boolean, disabled: Boolean });
</script>

<style scoped>
.signal { display: inline-block; width: 5px; height: 5px; flex: none; background: rgb(var(--medical-400)); }
.signal--active { background: rgb(var(--accent)); }
.signal--tick { animation: signal-acquire 680ms ease-out both; }
.signal--fault { background: rgb(var(--danger)); }
.signal--off { background: transparent; box-shadow: inset 0 0 0 1px rgb(var(--medical-300)); }
@keyframes signal-acquire {
  0%, 52% { opacity: 0.35; }
  26%, 100% { opacity: 1; }
}
</style>
