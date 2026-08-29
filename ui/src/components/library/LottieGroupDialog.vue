<script setup lang="ts">
export type GroupDraft = { name: string; displayName: string; parentName: string; description: string; sort: number }
export type Group = { metadata: { name: string }; spec: { displayName: string; parentName?: string | null; description?: string | null } }

const props = defineProps<{
  open: boolean
  draft: GroupDraft
  groups: Group[]
  editingName: string
  busy?: boolean
}>()
const emit = defineEmits<{ 'update:open': [boolean]; save: [] }>()
</script>

<template>
  <div v-if="props.open" class="modal-backdrop" @click.self="emit('update:open', false)">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="group-dialog-title">
      <div class="modal-header"><h2 id="group-dialog-title">{{ props.editingName ? '编辑分组' : '新建分组' }}</h2><button type="button" class="icon-button" aria-label="关闭" @click="emit('update:open', false)">×</button></div>
      <form class="form" @submit.prevent="emit('save')">
        <label>分组标识<input v-model="props.draft.name" :disabled="Boolean(props.editingName)" required placeholder="例如 stickers" /></label>
        <label>显示名称<input v-model="props.draft.displayName" required placeholder="例如 表情包" /></label>
        <label>父分组（可选）<select v-model="props.draft.parentName"><option value="">无</option><option v-for="group in props.groups.filter((item) => item.metadata.name !== props.editingName)" :key="group.metadata.name" :value="group.metadata.name">{{ group.spec.displayName }}</option></select></label>
        <label>排序<input v-model.number="props.draft.sort" type="number" min="0" max="100000" /></label>
        <label>描述<textarea v-model="props.draft.description" rows="3" placeholder="用于说明此分组的用途" /></label>
        <div class="modal-actions"><button type="button" class="quiet-button" @click="emit('update:open', false)">取消</button><button type="submit" class="primary-button" :disabled="props.busy">保存分组</button></div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 24px; background: rgb(15 23 42 / 45%); }
.modal { width: min(520px, 100%); max-height: calc(100vh - 48px); overflow: auto; padding: 22px; border-radius: 7px; background: #fff; box-shadow: 0 20px 50px rgb(15 23 42 / 20%); }
.modal-header, .modal-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.modal-header { align-items: flex-start; margin-bottom: 20px; }
.modal-header h2 { margin: 0; font-size: 20px; }
.icon-button, .quiet-button, .primary-button { border-radius: 5px; padding: 8px 13px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
.icon-button { width: 30px; height: 30px; padding: 0; border: 1px solid #cbd5e1; background: #fff; color: #334155; }
.form { display: grid; gap: 16px; }
.form > label { display: grid; gap: 6px; color: #475569; font-size: 13px; }
input, select, textarea { border: 1px solid #cbd5e1; border-radius: 4px; padding: 7px 9px; background: #fff; color: #17202a; font: inherit; }
input, select { min-height: 34px; }
.quiet-button { border: 1px solid #cbd5e1; color: #334155; background: #fff; }
.primary-button { border: 1px solid #0f766e; color: #fff; background: #0f766e; }
.modal-actions { justify-content: flex-end; padding-top: 4px; }
button:disabled { cursor: not-allowed; opacity: .55; }
</style>
