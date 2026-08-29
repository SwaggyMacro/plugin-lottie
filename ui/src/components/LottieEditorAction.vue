<script setup lang="ts">
import { IconMotionLine } from '@halo-dev/components'
import { createApp, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import LottiePickerModal from './LottiePickerModal.vue'
import type { LottieInsertAttributes } from '../editor/lottieTypes'
import { LOTTIE_PICKER_OPEN_EVENT, registerLottiePicker, type LottiePickerOpenRequest } from '../editor/lottiePickerBridge'

type EditorLike = {
  chain: () => {
    focus: () => {
      insertLottie: (attributes: LottieInsertAttributes) => { run: () => boolean }
    }
  }
}
type GlobalPickerState = {
  visible: boolean
  initial: LottieInsertAttributes | null
  submit: ((attributes: LottieInsertAttributes) => void) | null
}

const globalPickerState = reactive<GlobalPickerState>({
  visible: false,
  initial: null,
  submit: null,
})

let globalPickerApp: ReturnType<typeof createApp> | null = null

const GlobalPickerHost = defineComponent({
  name: 'LottieGlobalPickerHost',
  setup() {
    const close = (visible: boolean) => {
      globalPickerState.visible = visible
      if (!visible) {
        globalPickerState.initial = null
        globalPickerState.submit = null
      }
    }
    const select = (attributes: LottieInsertAttributes) => {
      globalPickerState.submit?.(attributes)
      close(false)
    }
    return () => h(LottiePickerModal, {
      visible: globalPickerState.visible,
      initial: globalPickerState.initial,
      'onUpdate:visible': close,
      onSelect: select,
    })
  },
})

function openGlobalPicker(initial: LottieInsertAttributes | null, submit: (attributes: LottieInsertAttributes) => void) {
  if (!globalPickerApp) {
    const container = document.createElement('div')
    container.dataset.haloLottiePickerHost = 'true'
    document.body.appendChild(container)
    globalPickerApp = createApp(GlobalPickerHost)
    globalPickerApp.mount(container)
  }
  globalPickerState.initial = initial
  globalPickerState.submit = submit
  globalPickerState.visible = true
}

const props = withDefaults(defineProps<{
  editor: EditorLike
  title?: string
  label?: string
  icon?: unknown
  showLabel?: boolean
  pickerPriority?: number
}>(), {
  title: '插入 Lottie 动画',
  label: 'Lottie 动画',
  showLabel: true,
  pickerPriority: 0,
})

const editingPosition = ref<number | null>(null)

function handleOpen(event: Event) {
  const request = (event as CustomEvent<LottiePickerOpenRequest>).detail
  if (request?.editor === props.editor) {
    editingPosition.value = typeof request.position === 'number' ? request.position : null
    openGlobalPicker(request.initial || null, insert)
  }
}

function open() {
  editingPosition.value = null
  // Open after the menu item's click event has completed. Opening during
  // pointerdown inserts the backdrop under the pointer, so the subsequent
  // click is interpreted as a backdrop click and closes the dialog.
  openGlobalPicker(null, insert)
}

function handleRequest(request: LottiePickerOpenRequest) {
  editingPosition.value = typeof request.position === 'number' ? request.position : null
  openGlobalPicker(request.initial || null, insert)
}

function insert(attributes: LottieInsertAttributes) {
  if (editingPosition.value !== null) {
    const editor = props.editor as unknown as {
      state: { doc: { nodeAt: (position: number) => { type: unknown } | null }; tr: { setNodeMarkup: (position: number, type: unknown, attributes: LottieInsertAttributes) => unknown } }
      view: { dispatch: (transaction: unknown) => void }
    }
    const nodeType = editor.state.doc.nodeAt(editingPosition.value)?.type
    if (nodeType) editor.view.dispatch(editor.state.tr.setNodeMarkup(editingPosition.value, nodeType, attributes))
    editingPosition.value = null
    return
  }
  props.editor.chain().focus().insertLottie(attributes).run()
  editingPosition.value = null
}

let unregister: (() => void) | undefined
onMounted(() => {
  unregister = registerLottiePicker(props.editor as unknown as object, handleRequest, props.pickerPriority)
  window.addEventListener(LOTTIE_PICKER_OPEN_EVENT, handleOpen)
})
onBeforeUnmount(() => {
  unregister?.()
  window.removeEventListener(LOTTIE_PICKER_OPEN_EVENT, handleOpen)
})
</script>

<template>
  <span class="lottie-editor-action">
    <button type="button" class="editor-button" :title="props.title" :aria-label="props.title" @click.prevent.stop="open">
      <component :is="props.icon || IconMotionLine" aria-hidden="true" />
      <span v-if="props.showLabel" class="editor-button__label">{{ props.label }}</span>
    </button>
  </span>
</template>

<style scoped>
.lottie-editor-action { display: block; width: 100%; min-width: 0; }
.editor-button { display: flex; width: 100%; min-width: 0; align-items: center; gap: 8px; min-height: 36px; border: 0; border-radius: 4px; padding: 8px 12px; color: inherit; background: transparent; text-align: left; cursor: pointer; font: inherit; }
.editor-button:hover, .editor-button:focus-visible { background: rgb(15 23 42 / 8%); outline: 0; }
.editor-button :deep(svg) { flex: 0 0 auto; width: 18px; height: 18px; }.editor-button__label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
