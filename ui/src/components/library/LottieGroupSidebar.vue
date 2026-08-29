<script setup lang="ts">
type Group = { metadata: { name: string }; spec: { displayName: string } }
type Animation = { spec: { groupName?: string | null } }
const props = defineProps<{ groups: Group[]; animations: Animation[]; selected: string }>()
defineEmits<{ 'update:selected': [string]; create: []; edit: [Group]; remove: [Group] }>()
const count = (name?: string) => props.animations.filter((item) => name === '__ungrouped__' ? !item.spec.groupName : item.spec.groupName === name).length
</script>
<template>
  <aside class="sidebar" aria-label="动画分组"><div class="sidebar-heading"><h2>分组</h2><button class="icon-button" type="button" title="新建分组" aria-label="新建分组" @click="$emit('create')">＋</button></div><nav class="group-list"><button type="button" :class="{ active: !props.selected }" @click="$emit('update:selected', '')">全部动画 <span>{{ props.animations.length }}</span></button><button type="button" :class="{ active: props.selected === '__ungrouped__' }" @click="$emit('update:selected', '__ungrouped__')">未分组 <span>{{ count('__ungrouped__') }}</span></button><div v-for="group in props.groups" :key="group.metadata.name" class="group-item" :class="{ active: props.selected === group.metadata.name }"><button type="button" class="group-select" @click="$emit('update:selected', group.metadata.name)"><span>{{ group.spec.displayName }}</span><span>{{ count(group.metadata.name) }}</span></button><span class="group-actions"><button type="button" title="编辑分组" @click.stop="$emit('edit', group)">编辑</button><button type="button" title="删除分组" @click.stop="$emit('remove', group)">删除</button></span></div></nav></aside>
</template>

<style scoped>
.sidebar { padding: 16px 12px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.sidebar-heading { display: flex; align-items: center; justify-content: space-between; padding: 0 4px 12px; border-bottom: 1px solid #e2e8f0; }
.sidebar-heading h2 { margin: 0; font-size: 15px; }
.icon-button { width: 30px; height: 30px; border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #334155; cursor: pointer; }
.group-list { display: grid; gap: 3px; padding-top: 8px; }
.group-list > button, .group-select { display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; border-radius: 4px; padding: 8px 9px; color: #475569; background: transparent; text-align: left; font-size: 13px; cursor: pointer; }
.group-list > button:hover, .group-item:hover { background: #f8fafc; }
.group-list > button.active, .group-item.active { color: #0f766e; background: #f0fdfa; }
.group-list button span { color: #94a3b8; font-size: 12px; }
.group-item { display: flex; align-items: center; border-radius: 4px; }
.group-item .group-select { flex: 1; }
.group-actions { display: none; gap: 2px; padding-right: 5px; }
.group-item:hover .group-actions, .group-item.active .group-actions { display: flex; }
.group-actions button { border: 0; padding: 3px; color: #64748b; background: transparent; font-size: 11px; cursor: pointer; }
</style>
