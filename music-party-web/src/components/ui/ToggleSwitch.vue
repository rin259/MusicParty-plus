<template>
  <!-- 44px 命中区包裹 32×16 的视觉轨道：视觉尺寸不变，触摸目标达标 -->
  <button
      type="button"
      role="switch"
      :aria-checked="checked"
      :aria-label="label"
      :disabled="disabled"
      class="flex items-center justify-center w-11 h-11 -m-2 flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed"
      @click="$emit('toggle')"
  >
    <span
        class="relative block rounded-full transition-colors"
        :class="[size === 'md' ? 'w-10 h-5' : 'w-8 h-4', checked ? 'bg-accent' : 'bg-medical-300']"
    >
      <span
          class="absolute top-0.5 left-0.5 bg-surface-1 rounded-full transition-transform duration-300"
          :class="size === 'md' ? 'w-4 h-4' : 'w-3 h-3'"
          :style="{ transform: checked ? (size === 'md' ? 'translateX(20px)' : 'translateX(16px)') : 'translateX(0)' }"
      ></span>
    </span>
  </button>
</template>

<script setup>
defineProps({
  checked: { type: Boolean, default: false },
  label: { type: String, required: true },
  size: { type: String, default: 'sm' },
  disabled: { type: Boolean, default: false }
});
defineEmits(['toggle']);
</script>
