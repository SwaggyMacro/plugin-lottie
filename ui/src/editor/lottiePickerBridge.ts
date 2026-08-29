export const LOTTIE_PICKER_OPEN_EVENT = 'halo-lottie:open-picker'

export interface LottiePickerOpenRequest {
  editor: unknown
  initial?: import('./lottieTypes').LottieInsertAttributes | null
  position?: number
}

type PickerHandler = (request: LottiePickerOpenRequest) => void
type PickerRegistration = { handler: PickerHandler; priority: number }
// Multiple editor surfaces (the fixed toolbar and the "+" toolbox) can
// register for one editor. Keep registrations ordered by priority so a
// persistent host remains available when a temporary menu unmounts.
const handlers = new WeakMap<object, PickerRegistration[]>()

/** Registers one picker host for an editor instance and returns its cleanup. */
export function registerLottiePicker(editor: object, handler: PickerHandler, priority = 0): () => void {
  const registered = handlers.get(editor) || []
  const registration = { handler, priority: Number.isFinite(priority) ? priority : 0 }
  registered.push(registration)
  registered.sort((left, right) => right.priority - left.priority)
  handlers.set(editor, registered)
  return () => {
    const current = handlers.get(editor)
    if (!current) return
    const index = current.lastIndexOf(registration)
    if (index >= 0) current.splice(index, 1)
    if (!current.length) handlers.delete(editor)
  }
}

/**
 * Opens the picker for one editor instance. The event is intentionally tiny so
 * toolbar, toolbox and command-menu integrations can share the same action.
 */
export function openLottiePicker(editor: unknown, initial?: LottiePickerOpenRequest['initial'], position?: number): void {
  if (typeof window === 'undefined') return
  const request = { editor, initial, position }
  if (editor && (typeof editor === 'object' || typeof editor === 'function')) {
    const registered = handlers.get(editor as object)
    const handler = registered?.[0]?.handler
    if (handler) {
      handler(request)
      return
    }
  }
  // Keep the event fallback for hosts that cannot register a Vue action.
  window.dispatchEvent(new CustomEvent<LottiePickerOpenRequest>(LOTTIE_PICKER_OPEN_EVENT, { detail: request }))
}
