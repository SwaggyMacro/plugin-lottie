<script setup lang="ts">
import LottieCanvas from '../LottieCanvas.vue'

export type LottieDefaults = { width: number; height: number; autoplay: boolean; loop: boolean; speed: number; fit: string; align: string; controls: boolean; hoverPlay: boolean; freezeOnOffscreen: boolean; ariaLabel: string }
export type AnimationDraft = { name: string; displayName: string; groupName: string; format: string; mediaType: string; attachmentUrl?: string | null; attachmentName?: string | null; sha256?: string | null; tags: string[]; sourceFileName: string; enabled: boolean; defaults: LottieDefaults }
export type Group = { metadata: { name: string }; spec: { displayName: string } }

const props = defineProps<{ open: boolean; draft: AnimationDraft | null; groups: Group[]; source: string; busy?: boolean }>()
const emit = defineEmits<{ 'update:open': [boolean]; save: [] }>()
</script>

<template>
  <div v-if="props.open && props.draft" class="modal-backdrop" @click.self="emit('update:open', false)">
    <section class="modal animation-modal" role="dialog" aria-modal="true" aria-labelledby="animation-dialog-title">
      <div class="modal-header"><div><p class="eyebrow">{{ props.draft.format }}</p><h2 id="animation-dialog-title">动画配置</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="emit('update:open', false)">×</button></div>
      <div class="dialog-preview"><LottieCanvas :src="props.source" :format="props.draft.format" :width="Math.min(Math.max(props.draft.defaults.width, 96), 280)" :height="Math.min(Math.max(props.draft.defaults.height, 96), 280)" :autoplay="props.draft.defaults.autoplay" :loop="props.draft.defaults.loop" :speed="props.draft.defaults.speed" :fit="props.draft.defaults.fit" :align="props.draft.defaults.align" :controls="props.draft.defaults.controls" :hover-play="props.draft.defaults.hoverPlay" :freeze-on-offscreen="props.draft.defaults.freezeOnOffscreen" :aria-label="props.draft.defaults.ariaLabel || props.draft.displayName" /></div>
      <form class="form" @submit.prevent="emit('save')">
        <label>动画名称<input v-model="props.draft.displayName" required /></label>
        <label>分组<select v-model="props.draft.groupName"><option value="">未分组</option><option v-for="group in props.groups" :key="group.metadata.name" :value="group.metadata.name">{{ group.spec.displayName }}</option></select></label>
        <label>表情标签<input :value="props.draft.tags.join(', ')" placeholder="例如 笑哭, 开心, 贴纸" @input="props.draft.tags = ($event.target as HTMLInputElement).value.split(',').map((item) => item.trim()).filter(Boolean)" /></label>
        <label class="checkbox-label"><input v-model="props.draft.enabled" type="checkbox" />启用此动画</label>
        <fieldset><legend>默认显示配置</legend><div class="form-grid"><label>宽度（px）<input v-model.number="props.draft.defaults.width" type="number" min="1" max="4096" /></label><label>高度（px）<input v-model.number="props.draft.defaults.height" type="number" min="1" max="4096" /></label><label>播放速度<input v-model.number="props.draft.defaults.speed" type="number" min="0.1" max="10" step="0.1" /></label><label>适配方式<select v-model="props.draft.defaults.fit"><option value="contain">contain</option><option value="cover">cover</option><option value="fill">fill</option><option value="fit-width">fit-width</option><option value="fit-height">fit-height</option></select></label><label>对齐方式<select v-model="props.draft.defaults.align"><option value="center">居中</option><option value="top">顶部</option><option value="bottom">底部</option><option value="left">左侧</option><option value="right">右侧</option></select></label></div><div class="toggle-grid"><label class="checkbox-label"><input v-model="props.draft.defaults.autoplay" type="checkbox" />自动播放</label><label class="checkbox-label"><input v-model="props.draft.defaults.loop" type="checkbox" />循环播放</label><label class="checkbox-label"><input v-model="props.draft.defaults.controls" type="checkbox" />显示控件</label><label class="checkbox-label"><input v-model="props.draft.defaults.hoverPlay" type="checkbox" />悬停播放</label><label class="checkbox-label"><input v-model="props.draft.defaults.freezeOnOffscreen" type="checkbox" />离屏暂停</label></div><label>无障碍标签<input v-model="props.draft.defaults.ariaLabel" placeholder="例如 开心笑脸动画" /></label></fieldset>
        <details><summary>资源信息</summary><p class="resource-meta">标识：{{ props.draft.name }}<br />来源文件：{{ props.draft.sourceFileName || '未记录' }}<br />媒体类型：{{ props.draft.mediaType }}</p></details>
        <div class="modal-actions"><button type="button" class="quiet-button" @click="emit('update:open', false)">取消</button><button type="submit" class="primary-button" :disabled="props.busy">保存配置</button></div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 24px; background: rgb(15 23 42 / 45%); }
.modal { width: min(520px, 100%); max-height: calc(100vh - 48px); overflow: auto; padding: 22px; border-radius: 7px; background: #fff; box-shadow: 0 20px 50px rgb(15 23 42 / 20%); }
.animation-modal { width: min(700px, 100%); }
.modal-header, .modal-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.modal-header { align-items: flex-start; margin-bottom: 16px; }
.modal-header h2 { margin: 0; font-size: 20px; }
.eyebrow { margin: 0 0 6px; color: #0f766e; font-size: 12px; font-weight: 700; text-transform: uppercase; }
.dialog-preview { display: grid; min-height: 180px; margin-bottom: 18px; place-items: center; overflow: hidden; border: 1px dashed #cbd5e1; border-radius: 5px; background: #f8fafc; }
.icon-button, .quiet-button, .primary-button { border-radius: 5px; padding: 8px 13px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
.icon-button { width: 30px; height: 30px; padding: 0; border: 1px solid #cbd5e1; background: #fff; color: #334155; }
.form { display: grid; gap: 16px; }
.form > label, fieldset > label { display: grid; gap: 6px; color: #475569; font-size: 13px; }
input, select { min-height: 34px; padding: 7px 9px; }
input, select, textarea { border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #17202a; font: inherit; }
fieldset { display: grid; gap: 14px; margin: 0; padding: 14px; border: 1px solid #e2e8f0; border-radius: 5px; }
legend { padding: 0 5px; font-size: 13px; font-weight: 700; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.form-grid label { display: grid; gap: 5px; color: #64748b; font-size: 12px; }
.toggle-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 9px; }
.checkbox-label { display: flex !important; align-items: center; gap: 7px; }
.checkbox-label input { min-height: auto; accent-color: #0f766e; }
.quiet-button { border: 1px solid #cbd5e1; color: #334155; background: #fff; }
.primary-button { border: 1px solid #0f766e; color: #fff; background: #0f766e; }
.modal-actions { justify-content: flex-end; padding-top: 4px; }
details { color: #64748b; font-size: 12px; }
summary { cursor: pointer; color: #475569; font-weight: 600; }
.resource-meta { margin: 8px 0 0; line-height: 1.7; overflow-wrap: anywhere; }
button:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 560px) { .modal-backdrop { padding: 10px; } .form-grid { grid-template-columns: 1fr 1fr; } }
</style>
