<template>
  <div>
    <label :for="id" class="mb-1 block text-xs text-medical-600">{{ label }}</label>
    <div class="flex gap-2">
      <input type="color" :value="normalizeHex(modelValue) || '#000000'" :aria-label="`选择${label}`" class="h-11 w-11 shrink-0 cursor-pointer border border-medical-200 bg-medical-50 p-1" @input="emit('update:modelValue', $event.target.value)" />
      <input :id="id" :value="modelValue" :aria-invalid="!normalizeHex(modelValue)" type="text" maxlength="7" autocomplete="off" spellcheck="false" class="min-h-11 min-w-0 flex-1 border border-medical-200 bg-medical-50 px-2 font-mono text-sm text-medical-900 outline-none focus:border-accent" @input="emit('update:modelValue', $event.target.value)" />
    </div>
  </div>
</template>
<script setup>
import { useId } from 'vue';
import { normalizeHex } from '../logic/theme.js';
defineProps({ modelValue: { type: String, required: true }, label: { type: String, required: true } });
const emit = defineEmits(['update:modelValue']);
const id = `theme-color-${useId()}`;
</script>
