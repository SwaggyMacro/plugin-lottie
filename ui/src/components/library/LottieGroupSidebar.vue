<script setup lang="ts">
import { ref } from 'vue'
type Group = { metadata: { name: string }; spec: { displayName: string; sort?: number | null } }
type Animation = { spec: { groupName?: string | null } }
const props = defineProps<{ groups: Group[]; animations: Animation[]; selected: string; busy?: boolean }>()
const emit = defineEmits<{ 'update:selected': [string]; create: []; edit: [Group]; remove: [Group]; reorder: [string[]] }>()
const draggingName = ref('')
const dragOverName = ref('')
const count = (name?: string) => props.animations.filter((item) => name === '__ungrouped__' ? !item.spec.groupName : item.spec.groupName === name).length

function handleDragStart(group: Group, event: DragEvent) {
  if (props.busy) return
  draggingName.value = group.metadata.name
  event.dataTransfer?.setData('text/plain', group.metadata.name)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function handleDragOver(group: Group, event: DragEvent) {
  if (props.busy || !draggingName.value || draggingName.value === group.metadata.name) return
  event.preventDefault()
  dragOverName.value = group.metadata.name
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

function handleDrop(group: Group, event: DragEvent) {
  event.preventDefault()
  const sourceName = draggingName.value || event.dataTransfer?.getData('text/plain')
  draggingName.value = ''
  dragOverName.value = ''
  if (props.busy || !sourceName || sourceName === group.metadata.name) return
  const ordered = props.groups.map((item) => item.metadata.name)
  const sourceIndex = ordered.indexOf(sourceName)
  const targetIndex = ordered.indexOf(group.metadata.name)
  if (sourceIndex < 0 || targetIndex < 0) return
  ordered.splice(sourceIndex, 1)
  ordered.splice(ordered.indexOf(group.metadata.name), 0, sourceName)
  emit('reorder', ordered)
}

function handleDragEnd() {
  draggingName.value = ''
  dragOverName.value = ''
}
</script>
<template>
  <aside class="sidebar" aria-label="动画分组"><div class="sidebar-heading"><h2>分组</h2><button class="icon-button" type="button" title="新建分组" aria-label="新建分组" :disabled="props.busy" @click="emit('create')">＋</button></div><nav class="group-list"><button type="button" :class="{ active: !props.selected }" @click="emit('update:selected', '')">全部动画 <span>{{ props.animations.length }}</span></button><button type="button" :class="{ active: props.selected === '__ungrouped__' }" @click="emit('update:selected', '__ungrouped__')">未分组 <span>{{ count('__ungrouped__') }}</span></button><div v-for="group in props.groups" :key="group.metadata.name" class="group-item" :class="{ active: props.selected === group.metadata.name, dragging: draggingName === group.metadata.name, 'drag-over': dragOverName === group.metadata.name }" draggable="true" @dragstart="handleDragStart(group, $event)" @dragover="handleDragOver(group, $event)" @drop="handleDrop(group, $event)" @dragend="handleDragEnd"><button type="button" class="group-select" @click="emit('update:selected', group.metadata.name)"><span>{{ group.spec.displayName }}</span><span>{{ count(group.metadata.name) }}</span></button><span class="group-actions"><button type="button" title="编辑分组" @click.stop="emit('edit', group)">编辑</button><button type="button" title="删除分组" @click.stop="emit('remove', group)">删除</button></span></div></nav></aside>
</template>

<style scoped>
.sidebar { display: flex; min-height: 0; flex-direction: column; padding: 16px 12px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.sidebar-heading { display: flex; align-items: center; justify-content: space-between; padding: 0 4px 12px; border-bottom: 1px solid #e2e8f0; }
.sidebar-heading h2 { margin: 0; font-size: 15px; }
.icon-button { width: 30px; height: 30px; border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #334155; cursor: pointer; }
.group-list { display: grid; flex: 1 1 auto; min-height: 0; gap: 3px; padding-top: 8px; overflow-y: auto; }
.group-list > button, .group-select { display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; border-radius: 4px; padding: 8px 9px; color: #475569; background: transparent; text-align: left; font-size: 13px; cursor: pointer; }
.group-list > button:hover, .group-item:hover { background: #f8fafc; }
.group-list > button.active, .group-item.active { color: #0f766e; background: #f0fdfa; }
.group-list button span { color: #94a3b8; font-size: 12px; }
.group-item { display: flex; align-items: center; border-radius: 4px; }
.group-item { cursor: grab; }
.group-item.dragging { opacity: .55; }
.group-item.drag-over { outline: 2px solid #99f6e4; outline-offset: -2px; }
.group-item .group-select { flex: 1; }
.group-actions { display: none; gap: 2px; padding-right: 5px; }
.group-item:hover .group-actions, .group-item.active .group-actions { display: flex; }
.group-actions button { border: 0; padding: 3px; color: #64748b; background: transparent; font-size: 11px; cursor: pointer; }
@media (max-width: 820px) {
  .sidebar { display: block; height: auto; padding: 12px; }
  .group-list { display: flex; flex-wrap: wrap; max-height: none; overflow: visible; }
  .group-list > button, .group-item { width: auto; }
  .group-item .group-select { width: auto; }
  .group-actions { display: flex; }
}
</style>
