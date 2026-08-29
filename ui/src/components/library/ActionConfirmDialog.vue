<script setup lang="ts">
const props = withDefaults(defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  attachmentOption?: boolean
  attachmentChecked?: boolean
  busy?: boolean
}>(), { confirmLabel: '确认删除', attachmentOption: false, attachmentChecked: false, busy: false })
const emit = defineEmits<{
  'update:open': [boolean]
  'update:attachmentChecked': [boolean]
  confirm: []
}>()
function close() { if (!props.busy) emit('update:open', false) }
</script>

<template>
  <Teleport to="body">
    <div v-if="props.open" class="confirm-backdrop" @click.self="close">
      <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
        <header class="confirm-header">
          <div class="confirm-icon" aria-hidden="true">!</div>
          <div><h2 id="confirm-title">{{ props.title }}</h2><p>{{ props.message }}</p></div>
        </header>
        <label v-if="props.attachmentOption" class="attachment-option">
          <input type="checkbox" :checked="props.attachmentChecked" :disabled="props.busy" @change="emit('update:attachmentChecked', ($event.target as HTMLInputElement).checked)" />
          <span>同时从 Halo 附件库删除对应文件</span>
        </label>
        <p v-if="props.attachmentOption" class="confirm-hint">如果文件被其他动画引用，插件会自动保留它。</p>
        <footer class="confirm-actions">
          <button type="button" class="quiet-button" :disabled="props.busy" @click="close">取消</button>
          <button type="button" class="danger-button" :disabled="props.busy" @click="emit('confirm')">{{ props.busy ? '处理中…' : props.confirmLabel }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
.confirm-backdrop { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; padding: 20px; background: rgb(15 23 42 / 48%); }
.confirm-dialog { width: min(440px, 100%); border: 1px solid #e2e8f0; border-radius: 8px; padding: 22px; color: #17202a; background: #fff; box-shadow: 0 24px 70px rgb(15 23 42 / 25%); }
.confirm-header { display: flex; align-items: flex-start; gap: 12px; }
.confirm-icon { display: grid; width: 30px; height: 30px; flex: 0 0 auto; place-items: center; border-radius: 50%; color: #9f1239; background: #ffe4e6; font-weight: 800; }
h2 { margin: 2px 0 7px; font-size: 18px; }
.confirm-header p { margin: 0; color: #475569; font-size: 13px; line-height: 1.6; }
.attachment-option { display: flex; align-items: center; gap: 8px; margin-top: 18px; color: #334155; font-size: 13px; }
.attachment-option input { accent-color: #be123c; }
.confirm-hint { margin: 7px 0 0 22px; color: #94a3b8; font-size: 11px; line-height: 1.5; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 22px; }
.quiet-button, .danger-button { min-height: 34px; border-radius: 4px; padding: 7px 13px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
.quiet-button { border: 1px solid #cbd5e1; color: #334155; background: #fff; }
.danger-button { border: 1px solid #be123c; color: #fff; background: #be123c; }
button:disabled { cursor: not-allowed; opacity: .55; }
</style>
