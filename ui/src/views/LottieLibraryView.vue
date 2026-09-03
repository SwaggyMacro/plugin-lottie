<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LottieCanvas from '../components/LottieCanvas.vue'
import LottieAnimationCard from '../components/library/LottieAnimationCard.vue'
import LottieAnimationDialog from '../components/library/LottieAnimationDialog.vue'
import AnimationPositionDialog, { type AnimationPositionOption } from '../components/library/AnimationPositionDialog.vue'
import LottieGroupDialog from '../components/library/LottieGroupDialog.vue'
import LottieGroupSidebar from '../components/library/LottieGroupSidebar.vue'
import AttachmentPickerModal, { type AttachmentLike } from '../components/library/AttachmentPickerModal.vue'
import ActionConfirmDialog from '../components/library/ActionConfirmDialog.vue'
import { readLottieDimensions } from '../utils/lottieDimensions'
import {
  importPreviewKey,
  previewDimensions,
  resolveImportPreviewSources,
  type ImportPreviewSource,
} from '../utils/importPreviewSources'

const API_BASE = '/apis/console.api.lottie.halo.run/v1alpha1'
const UNGROUPED = '__ungrouped__'

type LottieDefaults = {
  width: number
  height: number
  autoplay: boolean
  loop: boolean
  speed: number
  fit: string
  align: string
  controls: boolean
  hoverPlay: boolean
  freezeOnOffscreen: boolean
  ariaLabel: string
}

type Animation = {
  metadata: { name: string }
  spec: {
    displayName: string
    format: string
    sort?: number | null
    mediaType?: string
    attachmentUrl?: string | null
    attachmentName?: string | null
    tags?: string[] | null
    groupName?: string | null
    sourceFileName?: string | null
    sha256?: string | null
    enabled?: boolean | null
    defaults?: Partial<LottieDefaults> | null
  }
}

type Group = {
  metadata: { name: string }
  spec: { displayName: string; parentName?: string | null; description?: string | null; sort?: number | null }
}
type AttachmentGroup = { name: string; displayName: string; totalAttachments: number }
type Attachment = AttachmentLike
type AttachmentPolicy = { name: string; displayName?: string | null }
type PluginSettings = {
  readAnimationDimensions: boolean
  defaultWidth: number
  defaultHeight: number
}

type ImportCandidate = {
  sourceFileName: string
  displayName: string
  groupName?: string | null
  format: string
  mediaType: string
  attachmentUrl?: string | null
  tags?: string[]
  sha256: string
  width?: number
  height?: number
}

type GroupDraft = {
  name: string
  displayName: string
  parentName: string
  description: string
  sort: number
}

type AnimationDraft = {
  name: string
  displayName: string
  groupName: string
  format: string
  mediaType: string
  attachmentUrl?: string | null
  attachmentName?: string | null
  sha256?: string | null
  tags: string[]
  sourceFileName: string
  enabled: boolean
  sort: number
  defaults: LottieDefaults
}

const defaultConfig = (): LottieDefaults => ({
  width: 160,
  height: 160,
  autoplay: true,
  loop: true,
  speed: 1,
  fit: 'contain',
  align: 'center',
  controls: false,
  hoverPlay: false,
  freezeOnOffscreen: true,
  ariaLabel: '',
})

const animations = ref<Animation[]>([])
const groups = ref<Group[]>([])
const attachmentGroups = ref<AttachmentGroup[]>([])
const attachmentPolicies = ref<AttachmentPolicy[]>([])
const pluginSettings = ref<PluginSettings>({
  // Intrinsic animation dimensions are the safest default for stickers. The
  // configured values remain a fallback when a source cannot be inspected.
  readAnimationDimensions: true,
  defaultWidth: 160,
  defaultHeight: 160,
})
const search = ref('')
const tagFilter = ref('')
const enabledFilter = ref<'all' | 'enabled' | 'disabled'>('all')
const selectedGroup = ref('')
const loading = ref(false)
const busy = ref(false)
const message = ref('')
const errorMessage = ref('')
const messageTime = ref<Date | null>(null)
const errorMessageTime = ref<Date | null>(null)
const selectedNames = ref<string[]>([])
const selectionMode = ref(false)
const draggingNames = ref<string[]>([])
const dragOverName = ref('')
const pointerDrag = ref<{ animation: Animation; pointerId: number; startX: number; startY: number; active: boolean } | null>(null)
const dragPointer = ref({ x: 0, y: 0 })
const suppressNextCardClick = ref(false)
const movePositionDialogOpen = ref(false)
const moveGroup = ref('')
const page = ref(1)
const pageSize = ref(24)

const fileInput = ref<HTMLInputElement | null>(null)
const pendingFiles = ref<File[]>([])
const importPreview = ref<ImportCandidate[]>([])
const importPreviewSources = ref<Record<string, ImportPreviewSource>>({})
const activeImportPreviewKey = ref('')
const importPreviewPosition = ref({ left: 0, top: 0 })
const duplicateMode = ref<'skip' | 'overwrite' | 'duplicate' | 'rename'>('skip')
const attachmentGroupName = ref('')
const attachmentPolicyName = ref('')
const targetGroupName = ref('')
const attachmentPickerOpen = ref(false)

const groupDialogOpen = ref(false)
const groupDraft = ref<GroupDraft>({ name: '', displayName: '', parentName: '', description: '', sort: 0 })
const editingGroupName = ref('')

const animationDialogOpen = ref(false)
const animationDraft = ref<AnimationDraft | null>(null)

type ConfirmAction = (deleteAttachment: boolean) => Promise<void>
const confirmOpen = ref(false)
const confirmTitle = ref('确认操作')
const confirmMessage = ref('')
const confirmLabel = ref('确认删除')
const confirmAttachmentOption = ref(false)
const confirmAttachmentChecked = ref(false)
const confirmAction = ref<ConfirmAction | null>(null)
let revokeImportPreviewSources = () => {}

function compareAnimations(left: Animation, right: Animation): number {
  const bySort = (left.spec.sort ?? 0) - (right.spec.sort ?? 0)
  return bySort || left.metadata.name.localeCompare(right.metadata.name, undefined, { sensitivity: 'base' })
}

const visibleAnimations = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  const result = animations.value.filter((animation) => {
    const belongsToGroup = selectedGroup.value === UNGROUPED
      ? !animation.spec.groupName
      : !selectedGroup.value || animation.spec.groupName === selectedGroup.value
    if (!belongsToGroup) return false
    if (enabledFilter.value === 'enabled' && animation.spec.enabled === false) return false
    if (enabledFilter.value === 'disabled' && animation.spec.enabled !== false) return false
    if (tagFilter.value && !(animation.spec.tags ?? []).some((tag) => tag.toLocaleLowerCase().includes(tagFilter.value.toLocaleLowerCase()))) return false
    if (!keyword) return true
    const haystack = [
      animation.metadata.name,
      animation.spec.displayName,
      animation.spec.sourceFileName ?? '',
      animation.spec.format,
      ...(animation.spec.tags ?? []),
    ].join(' ').toLocaleLowerCase()
    return haystack.includes(keyword)
  })
  return selectedGroup.value ? result.sort(compareAnimations) : result
})
const pageCount = computed(() => Math.max(1, Math.ceil(visibleAnimations.value.length / pageSize.value)))
const pagedAnimations = computed(() => visibleAnimations.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const groupAnimations = computed(() => animations.value
  .filter((animation) => selectedGroup.value === UNGROUPED
    ? !animation.spec.groupName
    : Boolean(selectedGroup.value) && animation.spec.groupName === selectedGroup.value)
  .slice()
  .sort(compareAnimations))
const displayedAnimations = pagedAnimations
const selectedGroupNames = computed(() => new Set(groupAnimations.value.map((item) => item.metadata.name)))
const movableSelection = computed(() => selectedNames.value.filter((name) => selectedGroupNames.value.has(name)))
const positionOptions = computed<AnimationPositionOption[]>(() => groupAnimations.value
  .filter((animation) => !selectedNames.value.includes(animation.metadata.name))
  .map((animation) => ({ animation, source: source(animation) })))
const sortableEnabled = computed(() => Boolean(selectedGroup.value) && !busy.value)
const sortableGroups = computed(() => groups.value.slice().sort((left, right) => {
  const bySort = (left.spec.sort ?? 0) - (right.spec.sort ?? 0)
  return bySort || left.spec.displayName.localeCompare(right.spec.displayName, undefined, { sensitivity: 'base' })
}))

const selectedGroupLabel = computed(() => {
  if (selectedGroup.value === UNGROUPED) return '未分组'
  if (!selectedGroup.value) return '全部动画'
  return groups.value.find((group) => group.metadata.name === selectedGroup.value)?.spec.displayName ?? selectedGroup.value
})
const enabledCount = computed(() => animations.value.filter((item) => item.spec.enabled !== false).length)
const taggedCount = computed(() => animations.value.filter((item) => (item.spec.tags?.length ?? 0) > 0).length)
const activeImportPreview = computed(() => {
  const candidate = importPreview.value.find((item) => importPreviewKey(item) === activeImportPreviewKey.value)
  if (!candidate) return null
  const source = importPreviewSources.value[importPreviewKey(candidate)]
  return source ? { candidate, source, dimensions: previewDimensions(candidate.width, candidate.height, 256) } : null
})
const dragPreviewAnimation = computed(() => animations.value.find((animation) => animation.metadata.name === draggingNames.value[0]) ?? null)

function cloneDefaults(value?: Partial<LottieDefaults> | null): LottieDefaults {
  return { ...defaultConfig(), ...value }
}

function source(animation: Animation): string {
  return animation.spec.attachmentUrl || `/apis/api.lottie.halo.run/v1alpha1/animations/${encodeURIComponent(animation.metadata.name)}/content`
}

const animationDialogSource = computed(() => {
  const draft = animationDraft.value
  if (!draft) return ''
  return draft.attachmentUrl || `/apis/api.lottie.halo.run/v1alpha1/animations/${encodeURIComponent(draft.name)}/content`
})

function displayGroup(groupName?: string | null): string {
  if (!groupName) return '未分组'
  return groups.value.find((group) => group.metadata.name === groupName)?.spec.displayName ?? groupName
}

function attachmentUrl(item: Attachment): string | null {
  return item.status?.permalink || item.status?.url || item.spec.url || null
}

function errorText(error: unknown): string {
  const candidate = error as {
    response?: { data?: { message?: string } | string }
    message?: string
  }
  const data = candidate?.response?.data
  if (typeof data === 'object' && data?.message) return data.message
  if (typeof data === 'string' && data) return data
  return candidate?.message || '请求失败，请稍后重试'
}

const noticeTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

function formatNoticeTime(value: Date | null): string {
  return value ? noticeTimeFormatter.format(value) : ''
}

function showMessage(value: string) {
  message.value = value
  messageTime.value = new Date()
}

function showError(value: string) {
  errorMessage.value = value
  errorMessageTime.value = new Date()
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [animationResponse, groupResponse, attachmentGroupResponse, attachmentPolicyResponse, settingsResponse] = await Promise.all([
      axiosInstance.get<Animation[]>(`${API_BASE}/animations`),
      axiosInstance.get<Group[]>(`${API_BASE}/groups`),
      axiosInstance.get<AttachmentGroup[]>(`${API_BASE}/attachment-groups`).catch(() => null),
      axiosInstance.get<AttachmentPolicy[]>(`${API_BASE}/attachment-policies`).catch(() => null),
      axiosInstance.get<PluginSettings>(`${API_BASE}/settings`).catch(() => null),
    ])
    animations.value = animationResponse.data ?? []
    groups.value = groupResponse.data ?? []
    attachmentGroups.value = (attachmentGroupResponse?.data || []) as AttachmentGroup[]
    attachmentPolicies.value = (attachmentPolicyResponse?.data || []) as AttachmentPolicy[]
    if (settingsResponse?.data) {
      pluginSettings.value = {
        // Older installations may not have the setting persisted yet. Treat
        // an absent value as enabled so newly added animations use their
        // intrinsic Lottie dimensions by default.
        readAnimationDimensions: settingsResponse.data.readAnimationDimensions == null
          ? true
          : Boolean(settingsResponse.data.readAnimationDimensions),
        defaultWidth: Number(settingsResponse.data.defaultWidth) > 0 ? Number(settingsResponse.data.defaultWidth) : 160,
        defaultHeight: Number(settingsResponse.data.defaultHeight) > 0 ? Number(settingsResponse.data.defaultHeight) : 160,
      }
    }
  } catch (error) {
    showError(errorText(error))
  } finally {
    loading.value = false
  }
}

function resetImport() {
  revokeImportPreviewSources()
  revokeImportPreviewSources = () => {}
  pendingFiles.value = []
  importPreview.value = []
  importPreviewSources.value = {}
  activeImportPreviewKey.value = ''
  targetGroupName.value = ''
  attachmentGroupName.value = ''
  attachmentPolicyName.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (!files.length) return
  pendingFiles.value = files
  importPreview.value = []
  message.value = ''
  errorMessage.value = ''
  busy.value = true
  try {
    const form = new FormData()
    for (const file of files) form.append('file', file)
    let response
    try {
      response = await axiosInstance.post<ImportCandidate[]>(`${API_BASE}/animations/import/preview`, form)
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status !== 404) throw error
      const fallback = new FormData()
      for (const file of files) fallback.append('file', file)
      response = await axiosInstance.post<ImportCandidate[]>(`${API_BASE}/import/preview`, fallback)
    }
    const candidates = response.data ?? []
    const resolvedSources = await resolveImportPreviewSources(files, candidates)
    revokeImportPreviewSources()
    revokeImportPreviewSources = resolvedSources.revoke
    importPreviewSources.value = resolvedSources.sources
    importPreview.value = candidates
    showMessage(`已识别 ${importPreview.value.length} 个动画，请确认导入`)
  } catch (error) {
    showError(errorText(error))
    resetImport()
  } finally {
    busy.value = false
  }
}

function sourceForImportPreview(candidate: ImportCandidate): ImportPreviewSource | undefined {
  return importPreviewSources.value[importPreviewKey(candidate)]
}

function previewSize(candidate: ImportCandidate, maximumSize: number) {
  return previewDimensions(candidate.width, candidate.height, maximumSize)
}

function showImportPreview(candidate: ImportCandidate, target: HTMLElement) {
  if (!sourceForImportPreview(candidate)) return
  const rect = target.getBoundingClientRect()
  const popoverSize = 256
  const gap = 12
  const left = rect.right + gap + popoverSize <= window.innerWidth
    ? rect.right + gap
    : Math.max(gap, rect.left - gap - popoverSize)
  importPreviewPosition.value = {
    left,
    top: Math.min(Math.max(gap, rect.top), Math.max(gap, window.innerHeight - popoverSize - gap)),
  }
  activeImportPreviewKey.value = importPreviewKey(candidate)
}

function hideImportPreview(candidate: ImportCandidate) {
  if (activeImportPreviewKey.value === importPreviewKey(candidate)) activeImportPreviewKey.value = ''
}

async function confirmImport() {
  if (!pendingFiles.value.length) return
  busy.value = true
  errorMessage.value = ''
  try {
    const form = new FormData()
    for (const file of pendingFiles.value) form.append('file', file)
    const request = {
      params: {
        duplicateMode: duplicateMode.value,
        groupName: targetGroupName.value || undefined,
        attachmentGroup: attachmentGroupName.value || undefined,
        attachmentPolicy: attachmentPolicyName.value || undefined,
      },
    }
    try {
      await axiosInstance.post(`${API_BASE}/animations/import`, form, request)
    } catch (error) {
      // A running pre-alias plugin may still expose only /import. Retry only
      // on a missing route; validation and upload errors must reach the user.
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status !== 404) throw error
      const fallback = new FormData()
      for (const file of pendingFiles.value) fallback.append('file', file)
      await axiosInstance.post(`${API_BASE}/import`, fallback, request)
    }
    showMessage(`导入完成，共处理 ${importPreview.value.length} 个动画`)
    resetImport()
    await load()
  } catch (error) {
    showError(errorText(error))
  } finally {
    busy.value = false
  }
}

function formatForAttachment(item: Attachment): { format: string; mediaType: string } {
  const filename = item.spec.displayName || item.metadata.name
  const lower = filename.toLowerCase()
  if (lower.endsWith('.tgs') || item.spec.mediaType === 'application/gzip') return { format: 'tgs', mediaType: item.spec.mediaType || 'application/gzip' }
  if (lower.endsWith('.lottie') || item.spec.mediaType === 'application/octet-stream') return { format: 'lottie', mediaType: item.spec.mediaType || 'application/octet-stream' }
  return { format: 'json', mediaType: item.spec.mediaType || 'application/json' }
}

async function addAttachments(items: Attachment[]) {
  if (!items.length) return
  busy.value = true
  errorMessage.value = ''
  try {
    const groupName = selectedGroup.value && selectedGroup.value !== UNGROUPED ? selectedGroup.value : null
    for (const item of items) {
      const format = formatForAttachment(item)
      // Prefer dimensions declared by the animation itself. A failed fetch or
      // unsupported payload falls back to the configured defaults below.
      const intrinsic = await readLottieDimensions(attachmentUrl(item), format.format)
      const configuredDefaults = {
        ...defaultConfig(),
        width: pluginSettings.value.defaultWidth,
        height: pluginSettings.value.defaultHeight,
        ...(intrinsic || {}),
      }
      await axiosInstance.post(`${API_BASE}/animations`, {
        name: item.metadata.name,
        displayName: item.spec.displayName || item.metadata.name,
        groupName,
        format: format.format,
        mediaType: format.mediaType,
        attachmentName: item.metadata.name,
        attachmentUrl: attachmentUrl(item),
        sourceFileName: item.spec.displayName || item.metadata.name,
        enabled: true,
        defaults: configuredDefaults,
        tags: [],
      })
    }
    showMessage(`已从附件库添加 ${items.length} 个动画`)
    await load()
  } catch (error) { showError(errorText(error)) } finally { busy.value = false }
}

function openCreateGroup() {
  editingGroupName.value = ''
  groupDraft.value = { name: '', displayName: '', parentName: '', description: '', sort: 0 }
  groupDialogOpen.value = true
}

function askForConfirmation(options: {
  title: string
  message: string
  confirmLabel?: string
  attachmentOption?: boolean
}, action: ConfirmAction) {
  confirmTitle.value = options.title
  confirmMessage.value = options.message
  confirmLabel.value = options.confirmLabel || '确认删除'
  confirmAttachmentOption.value = Boolean(options.attachmentOption)
  confirmAttachmentChecked.value = false
  confirmAction.value = action
  confirmOpen.value = true
}

async function runConfirmedAction() {
  const action = confirmAction.value
  if (!action) return
  confirmOpen.value = false
  confirmAction.value = null
  await action(confirmAttachmentChecked.value)
}

function openEditGroup(group: Group) {
  editingGroupName.value = group.metadata.name
  groupDraft.value = {
    name: group.metadata.name,
    displayName: group.spec.displayName,
    parentName: group.spec.parentName ?? '',
    description: group.spec.description ?? '',
    sort: group.spec.sort ?? 0,
  }
  groupDialogOpen.value = true
}

async function saveGroup() {
  const name = groupDraft.value.name.trim()
  const displayName = groupDraft.value.displayName.trim()
  if (!name || !displayName) {
    showError('分组名称和显示名称不能为空')
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    const response = await axiosInstance.post<Group>(`${API_BASE}/groups`, {
      name: editingGroupName.value || name,
      displayName,
      parentName: groupDraft.value.parentName.trim() || null,
      description: groupDraft.value.description.trim() || null,
      sort: Math.max(0, Number(groupDraft.value.sort) || 0),
    })
    const saved = response.data
    const index = groups.value.findIndex((group) => group.metadata.name === saved.metadata.name)
    if (index >= 0) groups.value[index] = saved
    else groups.value.push(saved)
    groupDialogOpen.value = false
    showMessage(editingGroupName.value ? '分组已更新' : '分组已创建')
  } catch (error) {
    showError(errorText(error))
  } finally {
    busy.value = false
  }
}

async function persistGroupOrder(ordered: string[]) {
  const orderByName = new Map(ordered.map((name, index) => [name, index]))
  groups.value = groups.value.map((group) => {
    const nextSort = orderByName.get(group.metadata.name)
    return nextSort == null ? group : { ...group, spec: { ...group.spec, sort: nextSort } }
  })
  busy.value = true
  errorMessage.value = ''
  try {
    await axiosInstance.post(`${API_BASE}/groups/reorder`, { names: ordered })
    showMessage('分组排序已保存')
  } catch (error) {
    showError(errorText(error))
    await load()
  } finally {
    busy.value = false
  }
}

async function removeGroup(group: Group) {
  askForConfirmation({
    title: '删除动画分组',
    message: `确定删除分组“${group.spec.displayName}”吗？分组内动画会保留并移到未分组。`,
    confirmLabel: '删除分组',
  }, async () => {
    busy.value = true
    errorMessage.value = ''
    try {
      await axiosInstance.delete(`${API_BASE}/groups/${encodeURIComponent(group.metadata.name)}`)
      groups.value = groups.value.filter((item) => item.metadata.name !== group.metadata.name)
      if (selectedGroup.value === group.metadata.name) selectedGroup.value = ''
      showMessage('分组已删除')
    } catch (error) {
      showError(errorText(error))
    } finally {
      busy.value = false
    }
  })
}

function openAnimation(animation: Animation) {
  animationDraft.value = {
    name: animation.metadata.name,
    displayName: animation.spec.displayName,
    groupName: animation.spec.groupName ?? '',
    format: animation.spec.format,
    mediaType: animation.spec.mediaType ?? 'application/json',
    attachmentUrl: animation.spec.attachmentUrl ?? null,
    attachmentName: (animation.spec as any).attachmentName ?? null,
    sha256: animation.spec.sha256 ?? null,
    tags: [...(animation.spec.tags ?? [])],
    sourceFileName: animation.spec.sourceFileName ?? '',
    enabled: animation.spec.enabled !== false,
    sort: animation.spec.sort ?? 0,
    defaults: cloneDefaults(animation.spec.defaults),
  }
  animationDialogOpen.value = true
}

async function saveAnimation() {
  const draft = animationDraft.value
  if (!draft) return
  const displayName = draft.displayName.trim()
  if (!displayName) {
    showError('动画名称不能为空')
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    const response = await axiosInstance.post<Animation>(`${API_BASE}/animations`, {
      name: draft.name,
      displayName,
      groupName: draft.groupName || null,
      format: draft.format,
      mediaType: draft.mediaType,
      attachmentUrl: draft.attachmentUrl,
      attachmentName: draft.attachmentName,
      sha256: draft.sha256,
      tags: draft.tags,
      sourceFileName: draft.sourceFileName || null,
      defaults: draft.defaults,
      enabled: draft.enabled,
      sort: draft.sort,
    })
    const index = animations.value.findIndex((item) => item.metadata.name === response.data.metadata.name)
    if (index >= 0) animations.value[index] = response.data
    else animations.value.push(response.data)
    animationDialogOpen.value = false
    showMessage('动画配置已保存')
  } catch (error) {
    showError(errorText(error))
  } finally {
    busy.value = false
  }
}

async function removeAnimation(animation: Animation) {
  askForConfirmation({
    title: '删除动画',
    message: `确定删除动画“${animation.spec.displayName}”吗？此操作会移除动画配置。`,
    attachmentOption: Boolean(animation.spec.attachmentName),
  }, async (deleteAttachment) => {
    busy.value = true
    errorMessage.value = ''
    try {
      await axiosInstance.delete(`${API_BASE}/animations/${encodeURIComponent(animation.metadata.name)}`, {
        params: { deleteAttachment: deleteAttachment && Boolean(animation.spec.attachmentName) },
      })
      animations.value = animations.value.filter((item) => item.metadata.name !== animation.metadata.name)
      selectedNames.value = selectedNames.value.filter((name) => name !== animation.metadata.name)
      showMessage('动画已删除')
    } catch (error) {
      showError(errorText(error))
    } finally {
      busy.value = false
    }
  })
}

function toggleSelection(animation: Animation) {
  if (suppressNextCardClick.value) {
    suppressNextCardClick.value = false
    return
  }
  const name = animation.metadata.name
  selectedNames.value = selectedNames.value.includes(name)
    ? selectedNames.value.filter((item) => item !== name)
    : [...selectedNames.value, name]
}

function toggleSelectVisible() {
  const visibleNames = pagedAnimations.value.map((item) => item.metadata.name)
  const allSelected = visibleNames.length > 0 && visibleNames.every((name) => selectedNames.value.includes(name))
  selectedNames.value = allSelected
    ? selectedNames.value.filter((name) => !visibleNames.includes(name))
    : Array.from(new Set([...selectedNames.value, ...visibleNames]))
}

function clearSelection() {
  selectedNames.value = []
  moveGroup.value = ''
  movePositionDialogOpen.value = false
}

function toggleSelectionMode() {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) {
    clearSelection()
  }
}

function beginDrag(animation: Animation) {
  if (!sortableEnabled.value) return
  const ordered = groupAnimations.value.map((item) => item.metadata.name)
  const sourceName = animation.metadata.name
  const selected = selectedNames.value.includes(sourceName)
    ? new Set(movableSelection.value)
    : new Set([sourceName])
  draggingNames.value = ordered.filter((name) => selected.has(name))
}

function handlePointerDown(animation: Animation, event: PointerEvent) {
  if (!sortableEnabled.value || event.button !== 0) return
  suppressNextCardClick.value = false
  const target = event.target as HTMLElement | null
  if (target?.closest('button, input, select, textarea, a, label')) return
  dragPointer.value = { x: event.clientX, y: event.clientY }
  pointerDrag.value = { animation, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, active: false }
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', handlePointerUp, { once: true })
  window.addEventListener('pointercancel', handlePointerCancel, { once: true })
}

function handlePointerMove(event: PointerEvent) {
  const drag = pointerDrag.value
  if (!drag || event.pointerId !== drag.pointerId) return
  dragPointer.value = { x: event.clientX, y: event.clientY }
  if (!drag.active) {
    const distance = Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY)
    if (distance < 5) return
    drag.active = true
    suppressNextCardClick.value = true
    beginDrag(drag.animation)
  }
  event.preventDefault()
  handleDragAutoScroll(event.clientY)
  updateDragTarget(event.clientX, event.clientY)
}

async function handlePointerUp(event: PointerEvent) {
  const drag = pointerDrag.value
  pointerDrag.value = null
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointercancel', handlePointerCancel)
  if (!drag?.active || event.pointerId !== drag.pointerId) {
    suppressNextCardClick.value = false
    return
  }
  const targetName = dragOverName.value
  const draggedNames = draggingNames.value.slice()
  draggingNames.value = []
  dragOverName.value = ''
  window.setTimeout(() => { suppressNextCardClick.value = false }, 0)
  if (!targetName) return
  await persistDrop(targetName, draggedNames)
}

function handlePointerCancel(event: PointerEvent) {
  void handlePointerUp(event)
}

async function persistDrop(targetName: string, draggedNames: string[]) {
  if (!draggedNames.length || draggedNames.includes(targetName)) return
  const ordered = groupAnimations.value.map((item) => item.metadata.name)
  const validDraggedNames = draggedNames.filter((name) => ordered.includes(name))
  if (!validDraggedNames.length || !ordered.includes(targetName)) return
  const sourceIndex = Math.min(...validDraggedNames.map((name) => ordered.indexOf(name)))
  const targetIndex = ordered.indexOf(targetName)
  const remaining = ordered.filter((name) => !validDraggedNames.includes(name))
  const insertionIndex = sourceIndex < targetIndex
    ? remaining.indexOf(targetName) + 1
    : remaining.indexOf(targetName)
  remaining.splice(Math.max(0, insertionIndex), 0, ...validDraggedNames)
  await persistOrder(remaining)
}

function handleDragAutoScroll(clientY: number) {
  if (!draggingNames.value.length) return
  const edge = 90
  const speed = 18
  if (clientY < edge) window.scrollBy({ top: -speed, behavior: 'auto' })
  else if (clientY > window.innerHeight - edge) window.scrollBy({ top: speed, behavior: 'auto' })
}

function updateDragTarget(clientX: number, clientY: number) {
  const target = document.elementFromPoint(clientX, clientY)?.closest<HTMLElement>('[data-animation-name]')
  const targetName = target?.dataset.animationName ?? ''
  dragOverName.value = targetName && !draggingNames.value.includes(targetName) ? targetName : ''
}

function handleDragWheel(event: WheelEvent) {
  if (!draggingNames.value.length || event.deltaY === 0) return
  event.preventDefault()
  window.scrollBy({ top: event.deltaY, left: event.deltaX, behavior: 'auto' })
  updateDragTarget(dragPointer.value.x, dragPointer.value.y)
}

function handleDragKeydown(event: KeyboardEvent) {
  if (!draggingNames.value.length) return
  let offset: number | null = null
  const pageStep = Math.max(120, window.innerHeight * 0.9)
  if (event.key === 'PageDown') offset = pageStep
  else if (event.key === 'PageUp') offset = -pageStep
  else if (event.key === ' ') offset = event.shiftKey ? -pageStep : pageStep
  else if (event.key === 'Home') {
    event.preventDefault()
    window.scrollTo({ top: 0, behavior: 'auto' })
    return
  } else if (event.key === 'End') {
    event.preventDefault()
    const root = document.documentElement
    window.scrollTo({ top: root.scrollHeight, behavior: 'auto' })
    return
  }
  if (offset == null) return
  event.preventDefault()
  window.scrollBy({ top: offset, behavior: 'auto' })
}

async function persistOrder(ordered: string[]): Promise<boolean> {
  if (!selectedGroup.value || !ordered.length) return false
  const orderByName = new Map(ordered.map((name, index) => [name, index]))
  animations.value = animations.value.map((item) => {
    const nextSort = orderByName.get(item.metadata.name)
    return nextSort == null ? item : { ...item, spec: { ...item.spec, sort: nextSort } }
  })
  busy.value = true
  errorMessage.value = ''
  try {
    await axiosInstance.post(`${API_BASE}/animations/reorder`, {
      groupName: selectedGroup.value === UNGROUPED ? null : selectedGroup.value,
      names: ordered,
    })
    showMessage('动画排序已保存')
  } catch (error) {
    showError(errorText(error))
    await load()
    return false
  } finally {
    busy.value = false
  }
  return true
}

function handleDragEnd() {
  draggingNames.value = []
  dragOverName.value = ''
}

function openMovePosition() {
  if (!movableSelection.value.length) {
    showError('请先选择当前分组内的动画')
    return
  }
  if (!positionOptions.value.length) {
    showError('当前分组没有可放置在其前的动画')
    return
  }
  movePositionDialogOpen.value = true
}

async function moveSelectedBefore(targetName: string) {
  const selected = new Set(movableSelection.value)
  if (!selected.size || selected.has(targetName)) return
  const ordered = groupAnimations.value.map((item) => item.metadata.name)
  const moving = ordered.filter((name) => selected.has(name))
  const remaining = ordered.filter((name) => !selected.has(name))
  const targetIndex = remaining.indexOf(targetName)
  if (!moving.length || targetIndex < 0) return
  remaining.splice(targetIndex, 0, ...moving)
  const saved = await persistOrder(remaining)
  if (saved) {
    movePositionDialogOpen.value = false
    selectedNames.value = []
    selectionMode.value = false
  }
}

async function bulkDelete() {
  if (!selectedNames.value.length) return
  const selectedAnimations = animations.value.filter((item) => selectedNames.value.includes(item.metadata.name))
  const hasAttachments = selectedAnimations.some((item) => Boolean(item.spec.attachmentName))
  askForConfirmation({
    title: '批量删除动画',
    message: `确定删除选中的 ${selectedNames.value.length} 个动画吗？此操作会移除动画配置。`,
    attachmentOption: hasAttachments,
  }, async (deleteAttachment) => {
    busy.value = true
    errorMessage.value = ''
    try {
      await axiosInstance.post(`${API_BASE}/animations/bulk-delete`, {
        names: selectedNames.value,
        deleteAttachment: deleteAttachment && hasAttachments,
      })
      animations.value = animations.value.filter((item) => !selectedNames.value.includes(item.metadata.name))
      selectedNames.value = []
      selectionMode.value = false
      showMessage('已批量删除动画')
    } catch (error) { showError(errorText(error)) } finally { busy.value = false }
  })
}

async function bulkMove() {
  if (!selectedNames.value.length) return
  busy.value = true
  try {
    await axiosInstance.post(`${API_BASE}/animations/bulk-move`, { names: selectedNames.value, groupName: moveGroup.value || null })
    await load()
    selectedNames.value = []
    selectionMode.value = false
    showMessage('已移动动画分组')
  } catch (error) { showError(errorText(error)) } finally { busy.value = false }
}

onMounted(() => {
  load()
  window.addEventListener('wheel', handleDragWheel, { capture: true, passive: false })
  window.addEventListener('keydown', handleDragKeydown, true)
})
onBeforeUnmount(() => {
  revokeImportPreviewSources()
  pointerDrag.value = null
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', handlePointerUp)
  window.removeEventListener('pointercancel', handlePointerCancel)
  window.removeEventListener('wheel', handleDragWheel, true)
  window.removeEventListener('keydown', handleDragKeydown, true)
})
watch([search, selectedGroup], () => {
  page.value = 1
  draggingNames.value = []
  dragOverName.value = ''
  movePositionDialogOpen.value = false
})
watch(selectedGroup, () => {
  selectedNames.value = []
  moveGroup.value = ''
})
watch([tagFilter, enabledFilter, pageSize], () => { page.value = 1 })
watch(pageCount, (count) => { if (page.value > count) page.value = count })
</script>

<template>
  <div class="library">
    <header class="page-header">
      <div>
        <p class="eyebrow">Lottie 动画库</p>
        <h1>Lottie 动画库</h1>
        <p class="subtitle">集中管理可插入文章的 JSON、dotLottie 与 TGS 动画资源。</p>
      </div>
      <div class="header-actions">
      <button class="secondary-button" type="button" :disabled="busy" @click="attachmentPickerOpen = true">从附件库添加</button>
      <label class="upload-button" :class="{ disabled: busy }">
        <input ref="fileInput" type="file" accept=".json,.lottie,.tgs,.zip" multiple :disabled="busy" @change="handleFileChange" />
        {{ busy ? '处理中…' : '导入动画' }}
      </label>
      </div>
    </header>

    <section class="stats-row" aria-label="动画库概览">
      <div class="stat"><span>动画总数</span><strong>{{ animations.length }}</strong></div>
      <div class="stat"><span>已启用</span><strong>{{ enabledCount }}</strong></div>
      <div class="stat"><span>分组</span><strong>{{ groups.length }}</strong></div>
      <div class="stat"><span>已标记</span><strong>{{ taggedCount }}</strong></div>
    </section>

    <section v-if="importPreview.length" class="import-panel" aria-labelledby="import-title">
      <div class="panel-heading">
        <div>
          <h2 id="import-title">导入预览</h2>
          <p>{{ importPreview.length }} 个动画已准备就绪，可批量确认导入。</p>
        </div>
        <button class="quiet-button" type="button" @click="resetImport">取消</button>
      </div>
      <div class="import-options">
        <label>重复文件处理
          <select v-model="duplicateMode">
            <option value="skip">跳过重复项</option>
            <option value="overwrite">覆盖已有元数据</option>
            <option value="duplicate">创建副本</option>
            <option value="rename">自动重命名</option>
          </select>
        </label>
        <label>目标附件分组
          <select v-model="attachmentGroupName"><option value="">不指定分组</option><option v-for="group in attachmentGroups" :key="group.name" :value="group.name">{{ group.displayName }}（{{ group.totalAttachments }}）</option></select>
        </label>
        <label>存储策略
          <select v-model="attachmentPolicyName">
            <option value="">使用默认策略</option>
            <option v-for="policy in attachmentPolicies" :key="policy.name" :value="policy.name">{{ policy.displayName || policy.name }}</option>
          </select>
        </label>
        <label>目标 Lottie 分组
          <select v-model="targetGroupName"><option value="">不指定分组</option><option v-for="group in groups" :key="group.metadata.name" :value="group.metadata.name">{{ group.spec.displayName }}</option></select>
        </label>
        <button class="primary-button" type="button" :disabled="busy" @click="confirmImport">确认导入</button>
      </div>
      <div class="preview-list">
        <div v-for="candidate in importPreview" :key="`${candidate.sourceFileName}-${candidate.sha256}`" class="preview-row">
          <div
            class="preview-media"
            :class="{ unavailable: !sourceForImportPreview(candidate) }"
            tabindex="0"
            :aria-label="`${candidate.displayName} 的动画预览`"
            @pointerenter="showImportPreview(candidate, $event.currentTarget as HTMLElement)"
            @pointerleave="hideImportPreview(candidate)"
            @focusin="showImportPreview(candidate, $event.currentTarget as HTMLElement)"
            @focusout="hideImportPreview(candidate)"
            @keydown.esc.prevent="hideImportPreview(candidate)"
          >
            <LottieCanvas
              v-if="sourceForImportPreview(candidate)"
              :src="sourceForImportPreview(candidate)!.src"
              :format="sourceForImportPreview(candidate)!.format"
              :width="previewSize(candidate, 42).width"
              :height="previewSize(candidate, 42).height"
              :autoplay="false"
              :loop="false"
              :hover-play="false"
              :freeze-on-offscreen="true"
              :aria-label="`${candidate.displayName} 的首帧预览`"
            />
            <span v-else aria-hidden="true">-</span>
          </div>
          <span class="format-badge">{{ candidate.format }}</span>
          <span class="preview-name">{{ candidate.displayName }}</span>
          <span class="preview-source">{{ candidate.sourceFileName }}</span>
          <span class="preview-group">{{ candidate.groupName || '未分组' }}</span>
        </div>
      </div>
    </section>

    <section class="toolbar" aria-label="动画筛选">
      <label class="search-field">
        <span aria-hidden="true">⌕</span>
        <input v-model="search" type="search" placeholder="搜索动画名称、文件名或格式" />
      </label>
      <input v-model="tagFilter" class="filter-field" type="search" placeholder="按表情标签筛选" aria-label="按表情标签筛选" />
      <select v-model="enabledFilter" class="filter-select" aria-label="启用状态筛选">
        <option value="all">全部状态</option>
        <option value="enabled">仅启用</option>
        <option value="disabled">仅停用</option>
      </select>
      <span class="result-count">{{ visibleAnimations.length }} / {{ animations.length }} 个动画</span>
    </section>

    <section class="library-layout">
      <LottieGroupSidebar :groups="sortableGroups" :animations="animations" :selected="selectedGroup" :busy="busy" @update:selected="selectedGroup = $event" @create="openCreateGroup" @edit="openEditGroup" @remove="removeGroup" @reorder="persistGroupOrder" />

      <section class="content-panel">
        <div class="content-heading">
          <div class="content-heading-row">
            <div>
              <p class="eyebrow">当前视图</p>
              <h2>{{ selectedGroupLabel }}</h2>
            </div>
            <span v-if="loading" class="loading-label">加载中…</span>
            <div class="content-actions"><label class="page-size-control">每页<select v-model.number="pageSize" aria-label="每页显示数量"><option :value="12">12</option><option :value="24">24</option><option :value="48">48</option><option :value="96">96</option><option :value="192">192</option><option :value="384">384</option></select></label><button class="quiet-button" type="button" :disabled="loading" @click="load">刷新</button><button class="quiet-button" type="button" :disabled="busy" @click="toggleSelectionMode">{{ selectionMode ? '完成选择' : '批量管理' }}</button><button v-if="selectionMode && pagedAnimations.length" class="quiet-button" type="button" @click="toggleSelectVisible">{{ pagedAnimations.every((item) => selectedNames.includes(item.metadata.name)) ? '取消全选' : '全选当前页' }}</button></div>
          </div>
          <div v-if="message || errorMessage" class="notices">
            <div v-if="message" class="notice success" role="status"><span>{{ message }}</span><time :datetime="messageTime?.toISOString()">{{ formatNoticeTime(messageTime) }}</time></div>
            <div v-if="errorMessage" class="notice error" role="alert"><span>{{ errorMessage }}</span><time :datetime="errorMessageTime?.toISOString()">{{ formatNoticeTime(errorMessageTime) }}</time></div>
          </div>
          <div v-if="selectionMode && selectedNames.length" class="bulk-bar"><span>已选择 {{ selectedNames.length }} 项</span><select v-model="moveGroup"><option value="">移出分组</option><option v-for="group in groups" :key="group.metadata.name" :value="group.metadata.name">移动到 {{ group.spec.displayName }}</option></select><button class="quiet-button" type="button" :disabled="busy || !movableSelection.length || !positionOptions.length" @click="openMovePosition">调整位置</button><button class="quiet-button" type="button" :disabled="busy" @click="bulkMove">移动</button><button class="danger-button" type="button" :disabled="busy" @click="bulkDelete">删除</button><button class="quiet-button" type="button" :disabled="busy" @click="clearSelection">取消</button></div>
        </div>
        <div v-if="displayedAnimations.length" class="grid" :class="{ sorting: sortableEnabled }"><LottieAnimationCard v-for="animation in displayedAnimations" :key="animation.metadata.name" :animation="animation" :source="source(animation)" :group-label="displayGroup(animation.spec.groupName)" :selectable="selectionMode" :selected="selectedNames.includes(animation.metadata.name)" :sortable="sortableEnabled" :dragging="draggingNames.includes(animation.metadata.name)" :drag-over="dragOverName === animation.metadata.name" @configure="openAnimation" @remove="removeAnimation" @select="toggleSelection" @pointerdown="handlePointerDown" /></div>
        <nav v-if="visibleAnimations.length" class="library-pagination" aria-label="动画分页"><button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button><span>第 {{ page }} / {{ pageCount }} 页 · 每页 {{ pageSize }} · 共 {{ visibleAnimations.length }} 个</span><button type="button" :disabled="page >= pageCount" @click="page += 1">下一页</button></nav>
        <p v-else-if="!displayedAnimations.length" class="empty">{{ search ? '没有匹配的动画。' : '暂无动画，请导入 JSON、dotLottie、TGS 或 ZIP 文件。' }}</p>
      </section>
    </section>

    <LottieGroupDialog v-model:open="groupDialogOpen" :draft="groupDraft" :groups="groups" :editing-name="editingGroupName" :busy="busy" @save="saveGroup" />
    <LottieAnimationDialog v-model:open="animationDialogOpen" :draft="animationDraft" :groups="groups" :source="animationDialogSource" :busy="busy" @save="saveAnimation" />
    <AnimationPositionDialog v-model:open="movePositionDialogOpen" :options="positionOptions" :selected-count="movableSelection.length" :busy="busy" @move="moveSelectedBefore" />
    <AttachmentPickerModal v-model:open="attachmentPickerOpen" :busy="busy" @select="addAttachments" />
    <ActionConfirmDialog
      v-model:open="confirmOpen"
      :title="confirmTitle"
      :message="confirmMessage"
      :confirm-label="confirmLabel"
      :attachment-option="confirmAttachmentOption"
      :attachment-checked="confirmAttachmentChecked"
      :busy="busy"
      @update:attachment-checked="confirmAttachmentChecked = $event"
      @confirm="runConfirmedAction"
    />
    <Teleport to="body">
      <div
        v-if="activeImportPreview"
        class="import-preview-popover"
        role="tooltip"
        :aria-label="`${activeImportPreview.candidate.displayName} 的 256 像素预览`"
        :style="{ left: `${importPreviewPosition.left}px`, top: `${importPreviewPosition.top}px` }"
      >
        <LottieCanvas
          :src="activeImportPreview.source.src"
          :format="activeImportPreview.source.format"
          :width="activeImportPreview.dimensions.width"
          :height="activeImportPreview.dimensions.height"
          :autoplay="true"
          :loop="true"
          :hover-play="false"
          :freeze-on-offscreen="true"
          :aria-label="`${activeImportPreview.candidate.displayName} 的首帧预览`"
        />
      </div>
    </Teleport>
    <Teleport to="body">
      <div
        v-if="draggingNames.length && dragPreviewAnimation"
        class="drag-ghost"
        :style="{ left: `${dragPointer.x}px`, top: `${dragPointer.y}px` }"
        aria-hidden="true"
      >
        <LottieCanvas
          :src="source(dragPreviewAnimation)"
          :format="dragPreviewAnimation.spec.format"
          :width="64"
          :height="64"
          :autoplay="false"
          :loop="false"
          :hover-play="false"
          :freeze-on-offscreen="true"
        />
        <span class="drag-ghost-name">{{ dragPreviewAnimation.spec.displayName }}</span>
        <span v-if="draggingNames.length > 1" class="drag-ghost-count">{{ draggingNames.length }}</span>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
.library { display: block; width: 100%; min-width: 0; min-height: 100%; max-width: none; margin: 0; padding: 28px clamp(20px, 3vw, 48px) 56px; color: #17202a; background: #f8fafc; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 24px; padding-bottom: 24px; border-bottom: 1px solid #e2e8f0; }
.header-actions { display: inline-flex; align-items: center; gap: 10px; flex: 0 0 auto; }
.eyebrow { margin: 0 0 6px; color: #0f766e; font-size: 12px; font-weight: 700; letter-spacing: .08em;  }
h1, h2, p { margin-top: 0; } h1 { margin-bottom: 8px; font-size: 30px; line-height: 1.2; } h2 { margin-bottom: 0; font-size: 20px; } .subtitle { margin-bottom: 0; color: #64748b; }
button, input, select, textarea { font: inherit; } button { cursor: pointer; } button:disabled { cursor: not-allowed; opacity: .55; }
.upload-button, .secondary-button, .primary-button, .quiet-button, .danger-button, .icon-button { display: inline-flex; align-items: center; justify-content: center; border: 1px solid transparent; border-radius: 5px; padding: 8px 13px; font-size: 13px; font-weight: 600; transition: background .15s, border-color .15s; }
.secondary-button { border-color: #99f6e4; color: #0f766e; background: #f0fdfa; }
.secondary-button:hover { background: #ccfbf1; }
.upload-button { border-color: #0f766e; color: #0f766e; background: #fff; white-space: nowrap; } .upload-button:hover { background: #ecfdf5; } .upload-button.disabled { opacity: .55; pointer-events: none; } .upload-button input { display: none; }
.primary-button { color: #fff; background: #0f766e; } .primary-button:hover { background: #115e59; } .quiet-button { border-color: #cbd5e1; color: #334155; background: #fff; } .quiet-button:hover { background: #f1f5f9; } .danger-button { border-color: #fecdd3; color: #be123c; background: #fff; } .danger-button:hover { background: #fff1f2; }
.icon-button { width: 30px; height: 30px; padding: 0; border-color: #cbd5e1; color: #334155; background: #fff; } .icon-button:hover { background: #f1f5f9; }
.notices { display: grid; gap: 8px; } .notice { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; padding: 10px 14px; border-radius: 5px; font-size: 13px; } .notice time { flex: 0 0 auto; color: #64748b; font-size: 12px; white-space: nowrap; } .notice.success { color: #166534; background: #f0fdf4; border: 1px solid #bbf7d0; } .notice.error { color: #9f1239; background: #fff1f2; border: 1px solid #fecdd3; }
.stats-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 20px; }
.stat { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 14px 16px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.stat span { color: #64748b; font-size: 12px; }.stat strong { color: #0f766e; font-size: 22px; line-height: 1; }
.import-panel { margin-top: 24px; padding: 20px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; } .panel-heading, .import-options, .content-heading, .modal-header, .modal-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; } .panel-heading p { margin: 5px 0 0; color: #64748b; font-size: 13px; }
.import-options { justify-content: flex-start; margin: 18px 0; } .import-options label { display: flex; align-items: center; gap: 8px; color: #475569; font-size: 13px; } select, input, textarea { border: 1px solid #cbd5e1; border-radius: 4px; color: #17202a; background: #fff; } select, input { min-height: 34px; padding: 6px 9px; } textarea { padding: 8px 9px; resize: vertical; } select:focus, input:focus, textarea:focus { outline: 2px solid #99f6e4; outline-offset: 1px; border-color: #0f766e; }
.preview-list { display: grid; gap: 6px; max-height: 240px; overflow: auto; } .preview-row { display: grid; grid-template-columns: 52px 48px minmax(120px, 1.2fr) minmax(140px, 2fr) minmax(80px, 1fr); gap: 10px; align-items: center; min-height: 58px; padding: 8px 10px; color: #475569; font-size: 13px; background: #f8fafc; } .preview-media { display: grid; width: 48px; height: 48px; place-items: center; overflow: hidden; border: 1px solid #cbd5e1; border-radius: 4px; background: repeating-conic-gradient(#f8fafc 0 25%, #eef2f7 0 50%) 50% / 12px 12px; cursor: zoom-in; } .preview-media:focus-visible { outline: 2px solid #0f766e; outline-offset: 2px; } .preview-media.unavailable { color: #94a3b8; cursor: default; } .format-badge { color: #0f766e; font-size: 11px; font-weight: 700; } .preview-name { color: #17202a; font-weight: 600; } .preview-source, .preview-group { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .import-preview-popover { position: fixed; z-index: 60; display: grid; width: 256px; height: 256px; place-items: center; overflow: hidden; border: 1px solid #94a3b8; border-radius: 5px; background: repeating-conic-gradient(#fff 0 25%, #f1f5f9 0 50%) 50% / 16px 16px; box-shadow: 0 16px 32px rgb(15 23 42 / 20%); pointer-events: none; }
.drag-ghost { position: fixed; z-index: 100; display: grid; width: 170px; min-height: 94px; grid-template-columns: 64px minmax(0, 1fr); align-items: center; gap: 10px; padding: 10px; overflow: hidden; border: 1px solid #0f766e; border-radius: 6px; color: #17202a; background: #fff; box-shadow: 0 12px 28px rgb(15 23 42 / 24%); pointer-events: none; transform: translate(-18px, -18px); }
.drag-ghost-name { overflow: hidden; font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.drag-ghost-count { position: absolute; top: -7px; right: -7px; display: grid; width: 22px; height: 22px; place-items: center; border-radius: 50%; color: #fff; background: #0f766e; font-size: 11px; font-weight: 700; }
.toolbar { display: flex; align-items: center; gap: 10px; margin: 24px 0 18px; } .search-field { display: flex; align-items: center; gap: 8px; flex: 1 1 320px; max-width: 520px; padding: 0 10px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; color: #64748b; } .search-field input { width: 100%; min-height: 38px; padding: 7px 0; border: 0; outline: 0; } .filter-field, .filter-select { flex: 0 1 190px; min-height: 38px; padding: 7px 10px; } .result-count, .loading-label { color: #64748b; font-size: 13px; white-space: nowrap; }
.library-layout { display: grid; grid-template-columns: minmax(260px, 300px) minmax(0, 1fr); gap: 28px; align-items: stretch; min-width: 0; } .library-layout > :deep(.sidebar) { position: sticky; top: 0; align-self: start; height: calc(100vh - 24px); } .content-panel { min-width: 0; } .content-heading { position: sticky; top: 0; z-index: 10; display: grid; grid-template-columns: minmax(0, 1fr); gap: 10px; min-width: 0; margin-bottom: 16px; padding: 12px 0; background: #f8fafc; } .content-heading-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-width: 0; flex-wrap: wrap; } .content-heading-row > div:first-child { min-width: 0; } .content-heading > .notices { width: 100%; } .content-heading h2 { font-size: 22px; } .bulk-bar, .grid { position: relative; z-index: 0; } .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 14px; } .grid.sorting { user-select: none; } .animation-card { display: flex; min-width: 0; flex-direction: column; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; overflow: hidden; } .animation-card.disabled { opacity: .65; } .card-preview { display: grid; min-height: 190px; position: relative; place-items: center; padding: 16px; background: linear-gradient(135deg, #f8fafc 25%, #f1f5f9 25%, #f1f5f9 50%, #f8fafc 50%, #f8fafc 75%, #f1f5f9 75%); background-size: 16px 16px; } .disabled-label { position: absolute; top: 9px; right: 9px; padding: 3px 6px; border-radius: 3px; color: #475569; background: #e2e8f0; font-size: 11px; } .card-body { display: grid; gap: 5px; min-width: 0; padding: 13px 14px 5px; } .card-body strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .card-meta, .card-source { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #64748b; font-size: 12px; } .card-actions { display: flex; gap: 8px; padding: 9px 14px 13px; } .card-actions button { flex: 1; padding: 6px 8px; font-size: 12px; }
.content-actions { display: inline-flex; align-items: center; gap: 8px; } .page-size-control { display: inline-flex; align-items: center; gap: 6px; color: #64748b; font-size: 13px; white-space: nowrap; } .page-size-control select { min-height: 34px; padding: 5px 7px; }
.library-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 22px 0 4px; color: #64748b; font-size: 13px; }
.library-pagination button { min-height: 34px; border: 1px solid #cbd5e1; border-radius: 4px; padding: 7px 13px; color: #334155; background: #fff; font-size: 13px; }
.library-pagination button:hover:not(:disabled) { border-color: #0f766e; color: #0f766e; background: #f0fdfa; }
.bulk-bar { display: flex; align-items: center; gap: 8px; width: 100%; margin-bottom: 12px; padding: 10px 12px; border: 1px solid #99f6e4; border-radius: 5px; color: #115e59; background: #f0fdfa; font-size: 13px; flex-wrap: wrap; }
.content-heading > .bulk-bar { margin-bottom: 0; }
.empty { padding: 70px 20px; color: #64748b; text-align: center; border: 1px dashed #cbd5e1; border-radius: 6px; background: #fff; }
.modal-backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 24px; background: rgb(15 23 42 / 45%); } .modal { width: min(520px, 100%); max-height: calc(100vh - 48px); overflow: auto; padding: 22px; border-radius: 7px; background: #fff; box-shadow: 0 20px 50px rgb(15 23 42 / 20%); } .animation-modal { width: min(700px, 100%); } .modal-header { align-items: flex-start; margin-bottom: 20px; } .form { display: grid; gap: 16px; } .form > label, fieldset > label { display: grid; gap: 6px; color: #475569; font-size: 13px; } fieldset { display: grid; gap: 14px; margin: 0; padding: 14px; border: 1px solid #e2e8f0; border-radius: 5px; } legend { padding: 0 5px; color: #17202a; font-size: 13px; font-weight: 700; } .form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; } .form-grid label { display: grid; gap: 5px; color: #64748b; font-size: 12px; } .toggle-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 9px; } .checkbox-label { display: flex !important; align-items: center; gap: 7px; color: #475569 !important; } .checkbox-label input { min-height: auto; accent-color: #0f766e; } .modal-actions { justify-content: flex-end; padding-top: 4px; } details { color: #64748b; font-size: 12px; } summary { cursor: pointer; color: #475569; font-weight: 600; } .resource-meta { margin: 8px 0 0; line-height: 1.7; overflow-wrap: anywhere; }
@media (max-width: 820px) { .library { min-height: 100vh; padding: 24px 20px 40px; } .library-layout { grid-template-columns: 1fr; } .library-layout > :deep(.sidebar) { position: static; height: auto; } .sidebar { padding: 12px; } .group-list { display: flex; flex-wrap: wrap; max-height: none; overflow: visible; } .group-list > button, .group-item { width: auto; } .group-item .group-select { width: auto; } .group-actions { display: flex; } .content-heading { position: static; padding: 0; } }
@media (max-width: 560px) { .page-header, .toolbar, .import-options { align-items: stretch; flex-direction: column; } .header-actions { width: 100%; } .header-actions > * { flex: 1; } .upload-button { width: 100%; } .search-field, .filter-field, .filter-select { width: 100%; max-width: none; flex-basis: auto; } .preview-row { grid-template-columns: 52px minmax(0, 1fr); gap: 5px 10px; } .preview-media { grid-row: span 4; } .format-badge, .preview-name, .preview-source, .preview-group { grid-column: 2; } .form-grid { grid-template-columns: 1fr 1fr; } .stats-row { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>

