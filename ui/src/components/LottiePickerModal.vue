<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { computed, ref, watch } from 'vue'
import LottieCanvas from './LottieCanvas.vue'
import LottieConfigModal from './LottieConfigModal.vue'
import type { LottieConfig, LottieInsertAttributes } from '../editor/lottieTypes'

export interface LottieAnimation {
  metadata: { name: string }
  spec: {
    displayName: string
    format: string
    attachmentUrl?: string | null
    groupName?: string | null
    sourceFileName?: string | null
    enabled?: boolean | null
    defaults?: Partial<LottieConfig> | null
  }
}
export interface LottieGroup { metadata: { name: string }; spec: { displayName: string; parentName?: string | null } }

const props = withDefaults(defineProps<{ visible: boolean; initial?: LottieInsertAttributes | null }>(), { initial: null })
const emit = defineEmits<{ 'update:visible': [boolean]; select: [LottieInsertAttributes]; close: [] }>()
const API_BASE = '/apis/console.api.lottie.halo.run/v1alpha1'
const PUBLIC_BASE = '/apis/api.lottie.halo.run/v1alpha1/animations'
const defaults = (): LottieConfig => ({ width: 160, height: 160, autoplay: true, loop: true, speed: 1, fit: 'contain', align: 'center', controls: false, hoverPlay: false, freezeOnOffscreen: true, ariaLabel: '' })
const animations = ref<LottieAnimation[]>([])
const groups = ref<LottieGroup[]>([])
const search = ref('')
const activeGroup = ref('')
const selected = ref<LottieAnimation | null>(null)
const configVisible = ref(false)
const page = ref(1)
const pageSize = ref(12)
const loading = ref(false)
const error = ref('')
const isEditing = computed(() => Boolean(props.initial?.name))
const filtered = computed(() => {
  const query = search.value.trim().toLowerCase()
  return animations.value.filter((animation) => {
    const groupMatch = !activeGroup.value || (activeGroup.value === '__ungrouped__' ? !animation.spec.groupName : animation.spec.groupName === activeGroup.value)
    const text = [animation.metadata.name, animation.spec.displayName, animation.spec.sourceFileName || ''].join(' ').toLowerCase()
    return animation.spec.enabled !== false && groupMatch && (!query || text.includes(query))
  })
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const source = computed(() => selected.value ? (selected.value.spec.attachmentUrl || `${PUBLIC_BASE}/${encodeURIComponent(selected.value.metadata.name)}/content`) : '')
const groupLabel = computed(() => activeGroup.value === '__ungrouped__' ? '未分组' : groups.value.find((group) => group.metadata.name === activeGroup.value)?.spec.displayName || '全部动画')
function animationSource(animation: LottieAnimation) { return animation.spec.attachmentUrl || `${PUBLIC_BASE}/${encodeURIComponent(animation.metadata.name)}/content` }
function errorText(reason: unknown) {
  const value = reason as { response?: { data?: { message?: string } | string }; message?: string }
  const data = value?.response?.data
  return typeof data === 'object' && data?.message ? data.message : typeof data === 'string' && data ? data : value?.message || '动画库加载失败，请稍后重试。'
}
function selectAnimation(animation: LottieAnimation) { selected.value = animation; configVisible.value = true }
function applyInitial() {
  if (!props.initial) { selected.value = null; configVisible.value = false; return }
  selected.value = animations.value.find((animation) => animation.metadata.name === props.initial?.name) || null
  configVisible.value = Boolean(selected.value)
}
function close() { configVisible.value = false; emit('update:visible', false); emit('close') }
function confirm(config: LottieConfig) {
  if (!selected.value) return
  emit('select', { ...config, name: selected.value.metadata.name, src: source.value, format: selected.value.spec.format })
  close()
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const [animationResponse, groupResponse] = await Promise.all([
      axiosInstance.get<LottieAnimation[]>(`${API_BASE}/animations`),
      axiosInstance.get<LottieGroup[]>(`${API_BASE}/groups`),
    ])
    animations.value = animationResponse.data || []
    groups.value = groupResponse.data || []
    applyInitial(); page.value = 1
  } catch (reason) { error.value = errorText(reason) } finally { loading.value = false }
}
watch(() => props.visible, (visible) => { if (visible) void load() })
watch(() => props.initial, () => { if (props.visible && animations.value.length) applyInitial() }, { deep: true })
watch([search, activeGroup, pageSize], () => { page.value = 1 })
watch(pageCount, (count) => { if (page.value > count) page.value = count })
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="picker-backdrop" @click.self="close">
      <section class="picker" role="dialog" aria-modal="true" aria-labelledby="lottie-picker-title">
        <header class="picker-header"><div><p class="eyebrow">Lottie 动画库</p><h2 id="lottie-picker-title">{{ isEditing ? '编辑动画' : '插入动画' }}</h2></div><button type="button" class="icon-button" aria-label="关闭" title="关闭" @click="close">×</button></header>
        <div v-if="error" class="error" role="alert">{{ error }}</div>
        <div class="picker-toolbar"><input v-model="search" type="search" placeholder="搜索动画名称或文件名" aria-label="搜索动画" /><span class="toolbar-count">共 {{ filtered.length }} 个动画</span><label class="page-size-control">每页显示<select v-model.number="pageSize" aria-label="每页显示数量"><option :value="8">8</option><option :value="12">12</option><option :value="24">24</option><option :value="48">48</option></select></label></div>
        <div class="picker-body">
          <aside class="group-tabs" aria-label="动画分组"><button type="button" :class="{ active: activeGroup === '' }" @click="activeGroup = ''"><span>全部动画</span><span>{{ animations.length }}</span></button><button type="button" :class="{ active: activeGroup === '__ungrouped__' }" @click="activeGroup = '__ungrouped__'"><span>未分组</span><span>{{ animations.filter((animation) => !animation.spec.groupName).length }}</span></button><button v-for="group in groups" :key="group.metadata.name" type="button" :class="{ active: activeGroup === group.metadata.name }" @click="activeGroup = group.metadata.name"><span>{{ group.spec.displayName }}</span><span>{{ animations.filter((animation) => animation.spec.groupName === group.metadata.name).length }}</span></button></aside>
          <section class="results-pane"><div class="results-heading"><h3>{{ groupLabel }}</h3><span v-if="loading" class="toolbar-count">正在加载...</span></div><div v-if="loading || !paged.length" class="empty">{{ loading ? '正在加载动画...' : '暂无匹配的动画。' }}</div><div v-else class="animation-grid"><button v-for="animation in paged" :key="animation.metadata.name" type="button" class="animation-card" :class="{ selected: selected?.metadata.name === animation.metadata.name }" @click="selectAnimation(animation)"><span class="card-preview"><LottieCanvas :src="animationSource(animation)" :format="animation.spec.format" :width="112" :height="112" :autoplay="false" :loop="true" :speed="1" :fit="animation.spec.defaults?.fit || 'contain'" :align="animation.spec.defaults?.align || 'center'" :hover-play="true" :aria-label="animation.spec.displayName" /></span><span class="card-name" :title="animation.spec.displayName">{{ animation.spec.displayName }}</span><span class="card-meta">{{ animation.spec.format }}</span></button></div><nav v-if="pageCount > 1" class="pagination" aria-label="动画分页"><button type="button" :disabled="page === 1" aria-label="上一页" @click="page--">‹</button><span>第 {{ page }} / {{ pageCount }} 页</span><button type="button" :disabled="page === pageCount" aria-label="下一页" @click="page++">›</button></nav></section>
        </div>
        <footer class="picker-footer"><button type="button" class="quiet-button" @click="close">取消</button><button type="button" class="primary-button" :disabled="!selected" @click="configVisible = true">配置并{{ isEditing ? '更新' : '插入' }}</button></footer>
      </section>
    </div>
  </Teleport>
  <LottieConfigModal :visible="configVisible" :animation="selected" :source="source" :initial="isEditing ? props.initial : null" :title="isEditing ? '编辑动画参数' : '配置动画参数'" @update:visible="configVisible = $event" @confirm="confirm" />
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
.picker-backdrop { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 24px; background: rgb(15 23 42 / 48%); }
.picker { display: flex; width: min(1080px, 100%); max-height: calc(100vh - 48px); flex-direction: column; overflow: hidden; border-radius: 8px; background: #fff; color: #17202a; box-shadow: 0 24px 70px rgb(15 23 42 / 25%); }
.picker-header, .picker-toolbar, .picker-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 18px 22px; border-bottom: 1px solid #e2e8f0; }
.picker-header { align-items: flex-start; }.picker-header h2 { margin: 0; font-size: 20px; }.eyebrow { margin: 0 0 5px; color: #0f766e; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.icon-button { width: 30px; height: 30px; border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #334155; cursor: pointer; font-size: 20px; }
.picker-toolbar { justify-content: flex-start; padding-block: 12px; }.picker-toolbar input { flex: 1; min-width: 180px; }.page-size-control { display: inline-flex; align-items: center; gap: 6px; color: #64748b; font-size: 12px; white-space: nowrap; }.toolbar-count { color: #64748b; font-size: 12px; } input, select { min-height: 34px; border: 1px solid #cbd5e1; border-radius: 4px; padding: 6px 9px; background: #fff; color: #17202a; font: inherit; }
.error { margin: 14px 22px 0; padding: 9px 12px; border: 1px solid #fecdd3; border-radius: 4px; color: #9f1239; background: #fff1f2; font-size: 13px; }.picker-body { display: grid; min-height: 0; grid-template-columns: 210px minmax(0, 1fr); overflow: auto; }.group-tabs { min-height: 300px; max-height: 70vh; overflow-y: auto; padding: 12px; border-right: 1px solid #e2e8f0; }.group-tabs button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 8px; border: 0; border-radius: 5px; padding: 10px; color: #475569; background: transparent; text-align: left; cursor: pointer; font-size: 13px; }.group-tabs button:hover { background: #f8fafc; }.group-tabs button.active { color: #0f766e; background: #ccfbf1; font-weight: 600; }.group-tabs button span:last-child { color: #94a3b8; font-size: 12px; }.results-pane { min-width: 0; padding: 16px 20px 20px; }.results-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }.results-heading h3 { margin: 0; font-size: 15px; }.animation-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(145px, 1fr)); gap: 12px; }.animation-card { display: grid; gap: 6px; min-width: 0; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px; background: #fff; color: #334155; text-align: left; cursor: pointer; transition: transform .15s, border-color .15s, box-shadow .15s; }.animation-card:hover { transform: translateY(-2px); border-color: #5eead4; box-shadow: 0 8px 20px rgb(15 118 110 / 12%); }.animation-card.selected { border-color: #0f766e; box-shadow: 0 0 0 2px rgb(15 118 110 / 15%); }.card-preview { display: grid; min-height: 125px; place-items: center; overflow: hidden; border-radius: 4px; background: repeating-conic-gradient(#f8fafc 0 25%, #f1f5f9 0 50%) 50%/14px 14px; }.card-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }.card-meta { color: #64748b; font-size: 11px; text-transform: uppercase; }.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 16px; color: #64748b; font-size: 12px; }.pagination button { min-width: 28px; height: 28px; border: 1px solid #cbd5e1; border-radius: 4px; background: #fff; color: #334155; cursor: pointer; font-size: 18px; }.empty { min-height: 180px; display: grid; place-items: center; color: #64748b; font-size: 13px; }.picker-footer { justify-content: flex-end; border-top: 1px solid #e2e8f0; border-bottom: 0; }.quiet-button, .primary-button { min-height: 34px; border-radius: 4px; padding: 7px 12px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }.quiet-button { border: 1px solid #cbd5e1; background: #fff; color: #334155; }.primary-button { border: 1px solid #0f766e; background: #0f766e; color: #fff; }button:disabled { cursor: not-allowed; opacity: .5; }.animation-card :deep(.lottie-canvas) { transition: transform .18s ease; }.animation-card:hover :deep(.lottie-canvas) { transform: scale(1.08); }
@media (max-width: 760px) { .picker-toolbar { flex-wrap: wrap; }.picker-toolbar input { flex-basis: 100%; }.picker-backdrop { padding: 10px; }.picker-body { grid-template-columns: 1fr; }.group-tabs { display: flex; gap: 5px; max-height: 90px; min-height: 0; overflow-x: auto; overflow-y: hidden; border-right: 0; border-bottom: 1px solid #e2e8f0; }.group-tabs button { width: auto; flex: 0 0 auto; white-space: nowrap; } }
</style>