<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { ref, watch } from 'vue'

/** The subset of Halo's Attachment extension consumed by the library. */
export type AttachmentLike = {
  metadata: { name: string }
  spec: {
    displayName?: string
    mediaType?: string
    size?: number
    groupName?: string
    url?: string
  }
  status?: { permalink?: string; url?: string }
}

type AttachmentValue = AttachmentLike | string | {
  value?: string
  url?: string
  name?: string
  metadata?: { name?: string }
  spec?: AttachmentLike['spec']
  status?: AttachmentLike['status']
}

const props = withDefaults(defineProps<{ open: boolean; busy?: boolean }>(), { busy: false })
const emit = defineEmits<{
  'update:open': [boolean]
  select: [AttachmentLike[]]
}>()

const API_BASE = '/apis/console.api.lottie.halo.run/v1alpha1'
const accepts = [
  'application/json',
  'application/zip',
  'application/gzip',
  'application/x-gzip',
  'application/octet-stream',
]
const selected = ref<unknown[]>([])
const resolving = ref(false)

watch(() => props.open, (open) => {
  if (open) selected.value = []
})

function close() {
  if (!props.busy && !resolving.value) emit('update:open', false)
}

function valuesOf(value: unknown): AttachmentValue[] {
  const values = Array.isArray(value) ? value : value == null || value === '' ? [] : [value]
  return values as AttachmentValue[]
}

function asAttachment(value: AttachmentValue): AttachmentLike | null {
  if (!value || typeof value === 'string') return null
  if (!value.metadata?.name) return null
  return value as AttachmentLike
}

function referencesOf(value: AttachmentValue): string[] {
  if (typeof value === 'string') return [value]
  // FormKit's attachment input has returned both primitive references and
  // small wrapper objects across Halo Console versions. Normalize through a
  // structural view so the canonical AttachmentLike shape remains strict.
  const reference = value as {
    value?: unknown
    url?: unknown
    name?: unknown
    metadata?: { name?: unknown }
  }
  return [reference.value, reference.url, reference.name, reference.metadata?.name]
    .filter((item): item is string => typeof item === 'string' && Boolean(item.trim()))
}

function attachmentUrl(item: AttachmentLike): string | null {
  return item.status?.permalink || item.status?.url || item.spec?.url || null
}

function isAnimationAttachment(item: AttachmentLike): boolean {
  const filename = (item.spec?.displayName || item.metadata?.name || '').toLowerCase()
  const mediaType = (item.spec?.mediaType || '').toLowerCase()
  return /\.(json|lottie|tgs)$/.test(filename)
    || mediaType === 'application/json'
    || mediaType === 'application/gzip'
    || mediaType === 'application/x-gzip'
    || (mediaType === 'application/octet-stream' && filename.endsWith('.lottie'))
}

async function confirmSelection() {
  if (props.busy || resolving.value) return
  const values = valuesOf(selected.value)
  if (!values.length) {
    Toast.warning('请选择至少一个 Lottie 附件')
    return
  }
  resolving.value = true
  try {
    const direct = values
      .map(asAttachment)
      .filter((item): item is AttachmentLike => Boolean(item && isAnimationAttachment(item)))
    const references = values.flatMap(referencesOf)
    const resolved = references.length
      ? (await axiosInstance.post<AttachmentLike[]>(`${API_BASE}/attachments/resolve`, { references })).data
      : []
    const merged = [...direct, ...resolved.filter(isAnimationAttachment)]
    const unique = Array.from(new Map(merged.map((item) => [item.metadata.name, item])).values())
      .filter((item) => Boolean(attachmentUrl(item) || item.metadata.name))
    if (!unique.length) {
      throw new Error('未找到可用的 Lottie 附件，请重新选择 JSON、Lottie 或 TGS 文件')
    }
    emit('select', unique)
    emit('update:open', false)
  } catch (error) {
    const reason = error as { response?: { data?: { message?: string } | string }; message?: string }
    const data = reason?.response?.data
    const message = typeof data === 'object' && data?.message
      ? data.message
      : typeof data === 'string' && data ? data : reason?.message || '附件解析失败，请稍后重试'
    Toast.error(message)
  } finally {
    resolving.value = false
  }
}
</script>

<template>
  <VModal
    v-if="props.open"
    title="从附件库添加 Lottie"
    :width="620"
    :centered="false"
    :mount-to-body="true"
    @close="close"
  >
    <div class="picker-intro">
      <strong>选择动画附件</strong>
      <p>使用 Halo 原生附件选择器，可批量选择 JSON、dotLottie 或 TGS 文件。附件仍由 Halo 附件库统一管理。</p>
    </div>
    <FormKit
      v-model="selected"
      type="attachment"
      name="lottieAttachments"
      label="Lottie 附件"
      help="支持多选；仅会导入 Lottie 相关格式。"
      multiple
      :accepts="accepts"
      :disabled="props.busy || resolving"
    />
    <template #footer>
      <VSpace>
        <VButton :disabled="props.busy || resolving" @click="close">取消</VButton>
        <VButton type="secondary" :loading="resolving" @click="confirmSelection">添加到当前分组</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.picker-intro { margin-bottom: 12px; }
.picker-intro strong { color: #17202a; font-size: 15px; }
.picker-intro p { margin: 6px 0 0; color: #64748b; font-size: 13px; line-height: 1.6; }
</style>
