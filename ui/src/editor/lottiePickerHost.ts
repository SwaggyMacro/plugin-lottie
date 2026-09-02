import { createApp, defineComponent, h, reactive } from 'vue'
import LottiePickerModal from '../components/LottiePickerModal.vue'
import type { LottieInsertAttributes } from './lottieTypes'

type PickerState = {
  visible: boolean
  initial: LottieInsertAttributes | null
  submit: ((attributes: LottieInsertAttributes) => void) | null
}

const pickerState = reactive<PickerState>({
  visible: false,
  initial: null,
  submit: null,
})

let pickerApp: ReturnType<typeof createApp> | null = null

const GlobalPickerHost = defineComponent({
  name: 'LottieGlobalPickerHost',
  setup() {
    const close = (visible: boolean) => {
      pickerState.visible = visible
      if (!visible) {
        pickerState.initial = null
        pickerState.submit = null
      }
    }
    const select = (attributes: LottieInsertAttributes) => {
      pickerState.submit?.(attributes)
      close(false)
    }
    return () => h(LottiePickerModal, {
      visible: pickerState.visible,
      initial: pickerState.initial,
      'onUpdate:visible': close,
      onSelect: select,
    })
  },
})

/** Mount the picker once so toolbar integrations can use it without a Vue editor action. */
export function ensureLottiePickerHost(): void {
  if (typeof document === 'undefined' || pickerApp) return
  const existing = document.querySelector<HTMLElement>('[data-halo-lottie-picker-host]')
  if (existing) {
    pickerApp = createApp(GlobalPickerHost)
    pickerApp.mount(existing)
    return
  }
  const container = document.createElement('div')
  container.dataset.haloLottiePickerHost = 'true'
  document.body.appendChild(container)
  pickerApp = createApp(GlobalPickerHost)
  pickerApp.mount(container)
}

export function openLottiePickerHost(
  initial: LottieInsertAttributes | null,
  submit: (attributes: LottieInsertAttributes) => void,
): void {
  if (typeof document === 'undefined') return
  ensureLottiePickerHost()
  pickerState.initial = initial
  pickerState.submit = submit
  pickerState.visible = true
}
