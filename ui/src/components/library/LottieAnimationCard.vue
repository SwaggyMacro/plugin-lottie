<script setup lang="ts">
import LottieCanvas from '../LottieCanvas.vue'

type Animation = { metadata: { name: string }; spec: { displayName: string; format: string; groupName?: string | null; sourceFileName?: string | null; enabled?: boolean | null; tags?: string[] | null; defaults?: Record<string, any> | null } }
const props = defineProps<{ animation: Animation; source: string; groupLabel: string; selectable?: boolean; selected?: boolean }>()
const emit = defineEmits<{ configure: [Animation]; remove: [Animation]; select: [Animation] }>()

function handleCardClick() {
  if (props.selectable) emit('select', props.animation)
}
</script>
<template>
  <article class="animation-card" :class="{ disabled: props.animation.spec.enabled === false, selected: props.selectable && props.selected }" :role="props.selectable ? 'button' : undefined" :tabindex="props.selectable ? 0 : undefined" @click="handleCardClick" @keydown.enter.prevent="handleCardClick" @keydown.space.prevent="handleCardClick">
    <label v-if="props.selectable" class="select-animation" @click.stop><input type="checkbox" :checked="props.selected" @change="emit('select', props.animation)" aria-label="选择动画" /></label>
    <div class="card-preview"><LottieCanvas :src="props.source" :format="props.animation.spec.format" :width="Math.min(Math.max(Number(props.animation.spec.defaults?.width ?? 160), 96), 240)" :height="Math.min(Math.max(Number(props.animation.spec.defaults?.height ?? 160), 96), 240)" :autoplay="false" :loop="Boolean(props.animation.spec.defaults?.loop ?? true)" :speed="Number(props.animation.spec.defaults?.speed ?? 1)" :fit="props.animation.spec.defaults?.fit ?? 'contain'" :align="props.animation.spec.defaults?.align ?? 'center'" :controls="Boolean(props.animation.spec.defaults?.controls ?? false)" :hover-play="true" :freeze-on-offscreen="Boolean(props.animation.spec.defaults?.freezeOnOffscreen ?? true)" :aria-label="props.animation.spec.defaults?.ariaLabel || props.animation.spec.displayName" /><span v-if="props.animation.spec.enabled === false" class="disabled-label">已停用</span></div>
    <div class="card-body"><strong :title="props.animation.spec.displayName">{{ props.animation.spec.displayName }}</strong><span class="card-meta">{{ props.groupLabel }} · {{ props.animation.spec.format }}</span><div v-if="props.animation.spec.tags?.length" class="card-tags"><span v-for="tag in props.animation.spec.tags" :key="tag">{{ tag }}</span></div><span v-if="props.animation.spec.sourceFileName" class="card-source" :title="props.animation.spec.sourceFileName">{{ props.animation.spec.sourceFileName }}</span></div>
    <div class="card-actions"><button type="button" class="quiet-button" @click.stop="emit('configure', props.animation)">配置</button><button type="button" class="danger-button" @click.stop="emit('remove', props.animation)">删除</button></div>
  </article>
</template>

<style scoped>
.animation-card { position: relative; }
.animation-card { display: flex; min-width: 0; flex-direction: column; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; overflow: hidden; }
.animation-card[role='button'] { cursor: pointer; }
.animation-card[role='button']:focus-visible { outline: 2px solid #0f766e; outline-offset: 2px; }
.animation-card.selected { border-color: #0f766e; box-shadow: 0 0 0 2px rgb(15 118 110 / 16%); }
.animation-card.disabled { opacity: .65; }
.card-preview { display: grid; min-height: 190px; position: relative; place-items: center; padding: 16px; background: repeating-conic-gradient(#f8fafc 0 25%, #f1f5f9 0 50%) 50%/16px 16px; }
.disabled-label { position: absolute; top: 9px; right: 9px; padding: 3px 6px; border-radius: 3px; color: #475569; background: #e2e8f0; font-size: 11px; }
.card-body { display: grid; gap: 5px; min-width: 0; padding: 13px 14px 5px; }
.card-body strong, .card-meta, .card-source { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-meta, .card-source { color: #64748b; font-size: 12px; }
.card-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.card-tags span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 2px 5px; border-radius: 3px; color: #0f766e; background: #ecfdf5; font-size: 11px; }
.card-actions { display: flex; gap: 8px; padding: 9px 14px 13px; }
.card-actions button { flex: 1; padding: 6px 8px; font-size: 12px; }
.select-animation { position: absolute; z-index: 2; top: 8px; left: 8px; }
.select-animation input { width: 16px; height: 16px; accent-color: #0f766e; }
.card-preview :deep(.lottie-canvas) { transition: transform .18s ease; }
.animation-card:hover .card-preview :deep(.lottie-canvas) { transform: scale(1.08); }
</style>
