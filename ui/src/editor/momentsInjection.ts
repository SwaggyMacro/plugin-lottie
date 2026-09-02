import { IconMotionLine } from '@halo-dev/components'
import { createVNode, render } from 'vue'
import { LottieExtension } from './LottieExtension'
import { openLottiePickerHost, ensureLottiePickerHost } from './lottiePickerHost'
import type { LottieInsertAttributes } from './lottieTypes'

export const MOMENTS_ROUTE = '/console/moments'
export const MOMENTS_LOTTIE_BUTTON_ATTRIBUTE = 'haloLottieMomentsButton'
const MOMENTS_LOTTIE_BUTTON_SELECTOR = '[data-halo-lottie-moments-button]'
const DEBUG_PREFIX = '[halo-lottie:moments]'
const LOTTIE_MARKUP_PATTERN = /<halo-lottie(?:\s|>)/i

type MomentEditor = any
type EditorElement = HTMLElement & { editor?: MomentEditor }

const ROUTE_CHANGE_EVENT = 'halo-lottie:moments-route-change'

function debugLog(message: string, details?: unknown): void {
  if (details === undefined) console.info(DEBUG_PREFIX, message)
  else console.info(DEBUG_PREFIX, message, details)
}

export function isMomentsRoute(pathname: string): boolean {
  const normalized = pathname.replace(/\/+$/, '') || '/'
  return normalized === MOMENTS_ROUTE
}

function buttonText(button: HTMLButtonElement): string {
  return [
    button.getAttribute('aria-label'),
    button.getAttribute('title'),
    button.textContent,
  ].filter(Boolean).join(' ')
}

function debugButton(button: HTMLButtonElement): Record<string, unknown> {
  return {
    text: button.textContent?.trim().slice(0, 80) || '',
    ariaLabel: button.getAttribute('aria-label'),
    title: button.getAttribute('title'),
    role: button.getAttribute('role'),
    ariaControls: button.getAttribute('aria-controls'),
    toolbarControl: button.getAttribute('data-editor-toolbar-control'),
    disabled: button.disabled,
  }
}

function debugLayout(button: HTMLElement): Record<string, unknown> {
  const chain: Array<Record<string, unknown>> = []
  let current: HTMLElement | null = button
  let depth = 0
  while (current && depth < 6) {
    const rect = current.getBoundingClientRect()
    const style = window.getComputedStyle(current)
    chain.push({
      depth,
      tag: current.tagName.toLowerCase(),
      id: current.id || null,
      class: current.className || null,
      rect: { width: rect.width, height: rect.height, top: rect.top, left: rect.left },
      display: style.display,
      position: style.position,
      visibility: style.visibility,
      opacity: style.opacity,
      overflow: style.overflow,
      flex: style.flex,
      flexBasis: style.flexBasis,
      transform: style.transform,
      contain: style.contain,
    })
    current = current.parentElement
    depth += 1
  }
  return { chain }
}

function hasRenderableBox(element: HTMLElement): boolean {
  if (!element.isConnected) return false
  const style = window.getComputedStyle(element)
  if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false
  if (Number.parseFloat(style.opacity) === 0) return false
  const rect = element.getBoundingClientRect()
  return rect.width > 0 && rect.height > 0
}

function isImageButton(button: HTMLButtonElement): boolean {
  return /图片|图像|照片|\bimage\b/i.test(buttonText(button))
}

function isInsertMenuButton(button: HTMLButtonElement): boolean {
  const text = buttonText(button)
  const controls = button.getAttribute('aria-controls') || ''
  return (
    /插入内容|添加内容|添加|insert content|add content/i.test(text)
    || (controls.startsWith('editor-toolbox-') && button.getAttribute('aria-haspopup') === 'menu')
  )
}

/** Finds an image action in a rendered editor fragment. */
export function findMomentImageButton(root: ParentNode): HTMLButtonElement | null {
  const buttons = root.querySelectorAll<HTMLButtonElement>(
    'button[data-editor-toolbar-control], button[role="menuitem"]',
  )
  return Array.from(buttons).find((button) => !button.disabled && isImageButton(button)) || null
}

/**
 * RichTextEditor does not promise an editor property on the ProseMirror DOM
 * node. In Vue builds, the instance is available through the component
 * context attached to one of the rendered elements, so walk that context as
 * a fallback. This keeps the bridge independent of a particular editor DOM
 * wrapper version.
 */
export function editorFromRoot(root: HTMLElement): MomentEditor | null {
  const elements = [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))]
  const seen = new Set<unknown>()
  for (const element of elements) {
    const direct = (element as EditorElement).editor
    if (direct?.view && direct?.state) return direct
    let instance = (element as HTMLElement & { __vueParentComponent?: any }).__vueParentComponent
    while (instance && !seen.has(instance)) {
      seen.add(instance)
      const candidates = [instance.props?.editor, instance.setupState?.editor, instance.ctx?.editor]
      const editor = candidates.find((candidate) => candidate?.view && candidate?.state)
      if (editor) return editor
      instance = instance.parent
    }
  }
  return null
}

function findMomentInsertButtonCandidates(root: ParentNode): HTMLButtonElement[] {
  const buttons = root.querySelectorAll<HTMLButtonElement>('button')
  return Array.from(buttons).filter((button) => !button.disabled && isInsertMenuButton(button))
}

function findMomentInsertButton(root: ParentNode): HTMLButtonElement | null {
  const candidates = findMomentInsertButtonCandidates(root)
  return candidates.find(hasRenderableBox) || null
}

/**
 * Plugin Moments hides Halo's normal rich-text toolbar and exposes its own
 * attachment action below the editor. The action is icon-only, so its SVG is
 * the stable identifier across the current hashed UnoCSS class names.
 */
function isMomentAttachmentImageButton(button: HTMLButtonElement): boolean {
  const pathData = Array.from(button.querySelectorAll('svg path'))
    .map((path) => path.getAttribute('d') || '')
    .join(' ')
  return pathData.includes('M15 8')
    && pathData.includes('M3 6')
    && pathData.includes('m3 16')
}

function containsLottieMarkup(content: unknown): content is string {
  return typeof content === 'string' && LOTTIE_MARKUP_PATTERN.test(content)
}

function findMomentAttachmentImageButton(root: HTMLElement): HTMLButtonElement | null {
  // The moment composer and its attachment row share a `.card` ancestor.
  // Scope the search there so list-item image menus on the rest of the page
  // cannot become an injection target.
  const scope = root.closest<HTMLElement>('.card') || root.parentElement
  if (!scope) return null
  return Array.from(scope.querySelectorAll<HTMLButtonElement>('button')).find((button) => (
    !button.disabled
    && !button.matches(MOMENTS_LOTTIE_BUTTON_SELECTOR)
    && hasRenderableBox(button)
    && isMomentAttachmentImageButton(button)
  )) || null
}

function momentItemContainer(element: HTMLElement): HTMLElement | null {
  // MomentItem keeps its outer preview card mounted while replacing the
  // nested MomentEdit card. Use that outer card as the stable cache key.
  return element.closest<HTMLElement>('.card.preview')
    || element.closest<HTMLElement>('.card')
}

function findTeleportedImageButton(root: HTMLElement, editor?: MomentEditor): HTMLButtonElement | null {
  const controls = root.querySelectorAll<HTMLElement>('[aria-controls]')
  const controlledMenuId = Array.from(controls)
    .map((control) => control.getAttribute('aria-controls'))
    .find((id) => id?.startsWith('editor-toolbox-'))
  const instanceMenuId = editor?.instanceId ? `editor-toolbox-${editor.instanceId}` : ''
  const menus = document.querySelectorAll<HTMLElement>('[role="menu"]')
  for (const menu of Array.from(menus)) {
    if (controlledMenuId && menu.id !== controlledMenuId) continue
    if (!controlledMenuId && instanceMenuId && menu.id !== instanceMenuId) continue
    if (menu.getAttribute('aria-hidden') === 'true' || menu.dataset.state === 'closed') continue
    const button = findMomentImageButton(menu)
    if (button && hasRenderableBox(button)) return button
  }
  return null
}

function replaceButtonIcon(button: HTMLButtonElement): void {
  const isMenuItem = button.getAttribute('role') === 'menuitem'
  const iconContainer = isMenuItem ? button.firstElementChild : null
  const mount = document.createElement('span')
  mount.dataset.haloLottieIcon = 'true'
  Object.assign(mount.style, {
    display: 'inline-flex',
    width: '1.2em',
    height: '1.2em',
    alignItems: 'center',
    justifyContent: 'center',
  })
  if (iconContainer) {
    iconContainer.replaceChildren(mount)
    render(createVNode(IconMotionLine, { 'aria-hidden': 'true', class: 'size-full' }), mount)
    const label = Array.from(button.children)
      .map((element) => element as HTMLElement)
      .find((element) => element !== iconContainer && Boolean(element.textContent?.trim()))
    if (label) {
      label.textContent = 'Lottie 动画'
      label.setAttribute('title', 'Lottie 动画')
    } else {
      const text = document.createElement('span')
      text.textContent = 'Lottie 动画'
      button.appendChild(text)
    }
    return
  }
  button.replaceChildren(mount)
  render(createVNode(IconMotionLine, { 'aria-hidden': 'true' }), mount)
}

/** Clone the host action so the editor's own toolbar styling remains intact. */
export function injectButton(
  imageButton: HTMLButtonElement,
  onClick: (event: MouseEvent) => void,
): HTMLButtonElement | null {
  const hostParent = imageButton.parentElement
  if (!hostParent) return null
  // Floating-Vue wraps toolbar triggers in `.v-popper`. A second trigger
  // inside that wrapper can inherit a zero-size trigger layout, so place the
  // clone beside the wrapper while keeping menu items in their original list.
  const isPopperWrapper = hostParent.classList.contains('v-popper')
  const isMomentAttachment = isMomentAttachmentImageButton(imageButton)
  const attachmentRow = isMomentAttachment ? hostParent.parentElement : null
  const insertionParent = isPopperWrapper
    ? hostParent.parentElement
    : attachmentRow || hostParent
  if (!insertionParent || insertionParent.querySelector(`button${MOMENTS_LOTTIE_BUTTON_SELECTOR}`)) return null
  const button = imageButton.cloneNode(true) as HTMLButtonElement
  button.dataset[MOMENTS_LOTTIE_BUTTON_ATTRIBUTE] = 'true'
  button.type = 'button'
  button.disabled = false
  button.removeAttribute('aria-haspopup')
  button.removeAttribute('aria-controls')
  button.removeAttribute('aria-expanded')
  button.removeAttribute('aria-pressed')
  button.removeAttribute('data-state')
  // Do not let RichTextEditor's toolbar keyboard manager treat this clone as
  // one of its own controls. It only needs the visual toolbar classes.
  button.removeAttribute('data-editor-toolbar-control')
  button.setAttribute('aria-label', 'Lottie 动画')
  button.setAttribute('title', 'Lottie 动画')
  button.tabIndex = -1
  replaceButtonIcon(button)
  // Keep the clone at the host's real size. This also protects the fallback
  // toolbar trigger from its zero-basis layout rule in some Console versions.
  const hostRect = imageButton.getBoundingClientRect()
  const width = hostRect.width > 0 ? `${hostRect.width}px` : '2rem'
  const height = hostRect.height > 0 ? `${hostRect.height}px` : '2rem'
  button.style.setProperty('min-width', width, 'important')
  button.style.setProperty('min-height', height, 'important')
  button.style.setProperty('width', width, 'important')
  button.style.setProperty('height', height, 'important')
  button.style.setProperty('flex', `0 0 ${width}`, 'important')
  button.style.setProperty('display', 'inline-flex', 'important')
  button.style.setProperty('align-items', 'center', 'important')
  button.style.setProperty('justify-content', 'center', 'important')
  button.style.setProperty('visibility', 'visible', 'important')
  button.style.setProperty('opacity', '1', 'important')
  button.addEventListener('pointerdown', (event) => event.preventDefault())
  button.addEventListener('click', onClick)
  if (isMomentAttachment && attachmentRow) {
    // The attachment row distributes its children with space-between. Keep
    // the Lottie action in the image action's wrapper so the two controls are
    // adjacent instead of being pushed to opposite sides of the row.
    hostParent.style.setProperty('display', 'inline-flex', 'important')
    hostParent.style.setProperty('align-items', 'center', 'important')
    hostParent.style.setProperty('gap', '0', 'important')
    hostParent.insertBefore(button, imageButton)
  }
  else if (isPopperWrapper) hostParent.insertAdjacentElement('afterend', button)
  else imageButton.insertAdjacentElement('afterend', button)
  return button
}

function attrsSpec(): Record<string, { default?: unknown }> {
  const configured = LottieExtension.config.addAttributes.call(LottieExtension) as Record<string, { default?: unknown }>
  return Object.fromEntries(Object.entries(configured).map(([name, config]) => [
    name,
    Object.prototype.hasOwnProperty.call(config, 'default') ? { default: config.default } : {},
  ]))
}

function createLottieNodeSpec() {
  const configured = LottieExtension.config.addAttributes.call(LottieExtension) as Record<string, {
    default?: unknown
    parseHTML?: (element: Element) => unknown
  }>
  return {
    inline: true,
    group: 'inline',
    atom: true,
    selectable: true,
    content: '',
    attrs: attrsSpec(),
    parseDOM: [{
      tag: 'halo-lottie',
      getAttrs(element: Element) {
        return Object.fromEntries(Object.entries(configured).map(([name, config]) => [
          name,
          config.parseHTML ? config.parseHTML(element) : config.default,
        ]))
      },
    }],
    toDOM(node: { attrs: LottieInsertAttributes }) {
      return LottieExtension.config.renderHTML.call(LottieExtension, { HTMLAttributes: { ...node.attrs } })
    },
  }
}

function refreshContentMatches(schema: any): void {
  const nodeTypes = Object.values(schema.nodes) as any[]
  const matchConstructor = nodeTypes.find((type) => type.contentMatch)?.contentMatch?.constructor
  if (!matchConstructor?.parse) return
  for (const type of nodeTypes) {
    type.contentMatch = matchConstructor.parse(type.spec.content || '', schema.nodes)
    type.inlineContent = type.contentMatch.inlineContent
  }
  schema.cached && (schema.cached.domParser = null, schema.cached.domSerializer = null, schema.cached.wrappings = Object.create(null))
}

function installNodeView(editor: MomentEditor): void {
  const view = editor?.view
  if (!view?.setProps || !view.nodeViews || view.nodeViews.haloLottie) return
  const createNodeView = LottieExtension.config.addNodeView?.call(LottieExtension)
  if (typeof createNodeView !== 'function') return
  view.setProps({
    nodeViews: {
      ...view.nodeViews,
      haloLottie: (node: any, _view: unknown, getPos: () => number | undefined) =>
        createNodeView({ node, editor, getPos }),
    },
  })
}

const hydratedEditors = new WeakSet<object>()

function hydrateEditorContent(editor: MomentEditor, content: unknown): void {
  if (!editor || typeof editor !== 'object' || hydratedEditors.has(editor) || !containsLottieMarkup(content)) return
  try {
    const currentHtml = typeof editor.getHTML === 'function' ? editor.getHTML() : ''
    if (containsLottieMarkup(currentHtml)) return
    editor.commands?.setContent?.(content)
    hydratedEditors.add(editor)
    debugLog('editor content rehydrated', {
      sourceLength: content.length,
      currentHtmlLength: currentHtml.length,
    })
  } catch (error) {
    debugLog('editor content rehydrate failed', { error: String(error) })
  }
}

function cacheMomentPreviewContent(cache: WeakMap<HTMLElement, string>): void {
  const previews = document.querySelectorAll<HTMLElement>('.moment-preview-html')
  for (const preview of Array.from(previews)) {
    const content = preview.innerHTML
    if (!containsLottieMarkup(content)) continue
    const container = momentItemContainer(preview)
    if (container) cache.set(container, content)
  }
}

/** Add the Lottie node to a moments editor schema without replacing its editor instance. */
export function ensureLottieNode(editor: MomentEditor): boolean {
  const schema = editor?.schema || editor?.state?.schema
  if (!schema?.nodes) return false
  if (!schema.nodes.haloLottie) {
    const existing = schema.nodes.image || schema.nodes.hardBreak || Object.values(schema.nodes).find((type: any) => type.isInline)
    if (!existing?.constructor || !existing?.contentMatch) return false
    const NodeType = existing.constructor as new (name: string, schema: any, spec: any) => any
    const nodeType = new NodeType('haloLottie', schema, createLottieNodeSpec())
    nodeType.contentMatch = existing.contentMatch.constructor.empty || existing.contentMatch
    nodeType.inlineContent = false
    nodeType.markSet = []
    schema.nodes.haloLottie = nodeType
    refreshContentMatches(schema)
  }
  installNodeView(editor)
  return true
}

function selectionBookmark(editor: MomentEditor): any {
  try {
    return editor.state.selection.getBookmark?.()
  } catch {
    return null
  }
}

export function insertLottieIntoEditor(
  editor: MomentEditor,
  bookmark: any,
  attributes: LottieInsertAttributes,
): boolean {
  if (!ensureLottieNode(editor) || !editor?.view?.dispatch) return false
  const nodeType = editor.schema.nodes.haloLottie
  const node = nodeType.create(attributes)
  let transaction = editor.state.tr
  try {
    const selection = bookmark?.resolve?.(editor.state.doc)
    if (selection) transaction = transaction.setSelection(selection)
  } catch {
    // A document change while the picker was open invalidates the old bookmark.
  }
  transaction = transaction.replaceSelectionWith(node).scrollIntoView()
  editor.view.dispatch(transaction)
  editor.view.focus?.()
  return true
}

function removeInjectedButtons(buttons: Set<HTMLButtonElement>): void {
  for (const button of buttons) {
    const iconMount = button.querySelector<HTMLElement>('[data-halo-lottie-icon]')
    if (iconMount) render(null, iconMount)
    button.remove()
  }
  buttons.clear()
}

function cleanupDetachedButtons(buttons: Set<HTMLButtonElement>): void {
  for (const button of buttons) {
    if (!button.isConnected) buttons.delete(button)
  }
}

/** Start the route-aware DOM bridge used by the moments editor. */
export function startMomentsLottieInjection(): () => void {
  if (typeof window === 'undefined' || typeof document === 'undefined') return () => undefined
  debugLog('bridge created', {
    pathname: window.location.pathname,
    readyState: document.readyState,
    hasBody: Boolean(document.body),
  })
  const injectedButtons = new Set<HTMLButtonElement>()
  let observer: MutationObserver | null = null
  let layoutObserver: ResizeObserver | null = null
  const observedLayoutElements = new Set<HTMLElement>()
  let retryTimer: number | null = null
  let retryCount = 0
  let stopped = false
  let initialized = false
  let lastScanSignature = ''
  const previewContentCache = new WeakMap<HTMLElement, string>()

  const scheduleLayoutRetry = () => {
    if (retryTimer !== null || stopped || !isMomentsRoute(window.location.pathname)) return
    if (retryCount >= 40) return
    retryCount += 1
    retryTimer = window.setTimeout(() => {
      retryTimer = null
      scan()
    }, 150)
  }

  const handleClick = (root: HTMLElement, event: MouseEvent) => {
    event.preventDefault()
    event.stopPropagation()
    const editor = editorFromRoot(root)
    debugLog('button clicked', {
      hasEditor: Boolean(editor),
      pathname: window.location.pathname,
    })
    if (!editor) {
      debugLog('cannot open picker: editor instance was not found')
      return
    }
    const bookmark = selectionBookmark(editor)
    if (!ensureLottieNode(editor)) {
      debugLog('cannot open picker: editor schema could not be extended')
      return
    }
    ensureLottiePickerHost()
    openLottiePickerHost(null, (attributes) => insertLottieIntoEditor(editor, bookmark, attributes))
  }

  const scan = () => {
    if (stopped || !initialized) return
    cleanupDetachedButtons(injectedButtons)
    if (!isMomentsRoute(window.location.pathname)) {
      removeInjectedButtons(injectedButtons)
      layoutObserver?.disconnect()
      observedLayoutElements.clear()
      retryCount = 0
      if (retryTimer !== null) {
        window.clearTimeout(retryTimer)
        retryTimer = null
      }
      const signature = `route:${window.location.pathname}`
      if (signature !== lastScanSignature) {
        lastScanSignature = signature
        debugLog('scan skipped: not moments route', { pathname: window.location.pathname })
      }
      return
    }
    // MomentEdit's editor is created with the raw HTML before our bridge can
    // extend its schema. Cache the rendered source while the moment is still
    // in preview mode so an unknown <halo-lottie> node can be restored when
    // the user enters edit mode.
    cacheMomentPreviewContent(previewContentCache)
    const roots = Array.from(document.querySelectorAll<HTMLElement>('.halo-moment-editor'))
    const observeLayout = (element: HTMLElement | null) => {
      if (!element || !layoutObserver || observedLayoutElements.has(element)) return
      observedLayoutElements.add(element)
      layoutObserver.observe(element)
    }
    const details = roots.map((root) => {
      observeLayout(root)
      root.querySelectorAll<HTMLElement>('.halo-rich-text-editor, .editor-header, [role="toolbar"]').forEach(observeLayout)
      const editor = editorFromRoot(root)
      const imageButtonCandidate = findMomentImageButton(root)
      const imageButton = imageButtonCandidate && hasRenderableBox(imageButtonCandidate)
        ? imageButtonCandidate
        : null
      const teleportedImageButton = imageButton ? null : findTeleportedImageButton(root, editor)
      const attachmentImageButton = findMomentAttachmentImageButton(root)
      const insertButtonCandidate = findMomentInsertButtonCandidates(root)[0] || null
      observeLayout(imageButtonCandidate)
      observeLayout(attachmentImageButton)
      observeLayout(insertButtonCandidate)
      const insertButton = findMomentInsertButton(root)
      const hostButton = attachmentImageButton || imageButton || teleportedImageButton || insertButton
      return {
        buttons: root.querySelectorAll('button').length,
        buttonDetails: Array.from(root.querySelectorAll<HTMLButtonElement>('button')).slice(0, 16).map(debugButton),
        proseMirror: Boolean(root.querySelector('.ProseMirror')),
        hasEditor: Boolean(editor),
        imageButtonCandidate: imageButtonCandidate ? debugButton(imageButtonCandidate) : null,
        imageButton: Boolean(imageButton),
        teleportedImageButton: Boolean(teleportedImageButton),
        attachmentImageButton: attachmentImageButton ? debugButton(attachmentImageButton) : null,
        insertButtonCandidate: insertButtonCandidate ? debugButton(insertButtonCandidate) : null,
        insertButton: Boolean(insertButton),
        host: hostButton ? buttonText(hostButton) : null,
        hostLayout: hostButton
          ? debugLayout(hostButton)
          : insertButtonCandidate
            ? debugLayout(insertButtonCandidate)
            : imageButtonCandidate
              ? debugLayout(imageButtonCandidate)
              : null,
      }
    })
    const menus = Array.from(document.querySelectorAll<HTMLElement>('[role="menu"]')).map((menu) => ({
      id: menu.id || null,
      state: menu.dataset.state || null,
      hidden: menu.getAttribute('aria-hidden'),
      buttons: Array.from(menu.querySelectorAll<HTMLButtonElement>('button')).slice(0, 12).map(debugButton),
    }))
    const signature = JSON.stringify({ pathname: window.location.pathname, roots: details, menus })
    if (signature !== lastScanSignature) {
      lastScanSignature = signature
      debugLog('scan', { pathname: window.location.pathname, roots: details, menus })
    }
    for (const root of roots) {
      const editor = editorFromRoot(root)
      const container = momentItemContainer(root)
      const cachedContent = container ? previewContentCache.get(container) : undefined
      if (editor && containsLottieMarkup(cachedContent)) {
        ensureLottieNode(editor)
        hydrateEditorContent(editor, cachedContent)
      }
      const imageButtonCandidate = findMomentImageButton(root)
      const attachmentImageButton = findMomentAttachmentImageButton(root)
      const insertButtonCandidate = findMomentInsertButtonCandidates(root)[0] || null
      observeLayout(root)
      observeLayout(imageButtonCandidate)
      observeLayout(attachmentImageButton)
      observeLayout(insertButtonCandidate)
      const imageButton = imageButtonCandidate && hasRenderableBox(imageButtonCandidate)
        ? imageButtonCandidate
        : findTeleportedImageButton(root, editor)
      const hostButton = attachmentImageButton || imageButton || findMomentInsertButton(root)
      if (!hostButton) {
        if (insertButtonCandidate || imageButtonCandidate || root.closest('.card')) {
          debugLog('host skipped: no renderable moment action', {
            imageButton: imageButtonCandidate ? debugButton(imageButtonCandidate) : null,
            attachmentImageButton: attachmentImageButton ? debugButton(attachmentImageButton) : null,
            insertButton: insertButtonCandidate ? debugButton(insertButtonCandidate) : null,
            layout: insertButtonCandidate
              ? debugLayout(insertButtonCandidate)
              : imageButtonCandidate
                ? debugLayout(imageButtonCandidate)
                : null,
          })
        }
        scheduleLayoutRetry()
        continue
      }
      retryCount = 0
      const button = injectButton(hostButton, (event) => handleClick(root, event))
      if (button) {
        injectedButtons.add(button)
        const rect = button.getBoundingClientRect()
        const style = window.getComputedStyle(button)
        debugLog('button injected', {
          menuItem: hostButton.getAttribute('role') === 'menuitem',
          hostType: hostButton === attachmentImageButton ? 'moment-attachment-image' : 'editor-toolbar',
          hostText: buttonText(hostButton),
          html: button.outerHTML.slice(0, 600),
          parent: button.parentElement?.outerHTML.slice(0, 600),
          visible: {
            connected: button.isConnected,
            display: style.display,
            visibility: style.visibility,
            opacity: style.opacity,
            width: rect.width,
            height: rect.height,
            top: rect.top,
            left: rect.left,
          },
          layout: debugLayout(button),
        })
      }
    }
  }

  const routeChanged = () => window.dispatchEvent(new Event(ROUTE_CHANGE_EVENT))
  const history = window.history
  type HistoryMethod = (data: any, unused: string, url?: string | URL | null) => void
  const originalPushState = history.pushState.bind(history) as HistoryMethod
  const originalReplaceState = history.replaceState.bind(history) as HistoryMethod
  const wrappedPushState = function (this: History, data: any, unused: string, url?: string | URL | null) {
    const result = originalPushState.call(this, data, unused, url)
    routeChanged()
    return result
  }
  const wrappedReplaceState = function (this: History, data: any, unused: string, url?: string | URL | null) {
    const result = originalReplaceState.call(this, data, unused, url)
    routeChanged()
    return result
  }
  const ready = () => {
    if (stopped || initialized || !document.body) return
    initialized = true
    debugLog('bridge initialized', { pathname: window.location.pathname })
    history.pushState = wrappedPushState
    history.replaceState = wrappedReplaceState
    window.addEventListener('popstate', routeChanged)
    window.addEventListener(ROUTE_CHANGE_EVENT, scan)
    observer = new MutationObserver(scan)
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      // The moments editor can become visible by toggling classes/attributes
      // without adding DOM nodes. Catch that transition so a toolbar that was
      // initially measured at 0x0 gets another injection attempt.
      attributes: true,
      attributeFilter: ['class', 'style', 'hidden', 'aria-hidden', 'data-state'],
    })
    if (typeof ResizeObserver !== 'undefined') {
      layoutObserver = new ResizeObserver(() => scan())
    }
    scan()
  }
  document.addEventListener('DOMContentLoaded', ready, { once: true })
  ready()

  return () => {
    stopped = true
    debugLog('bridge stopped')
    document.removeEventListener('DOMContentLoaded', ready)
    observer?.disconnect()
    observer = null
    if (retryTimer !== null) window.clearTimeout(retryTimer)
    retryTimer = null
    retryCount = 0
    layoutObserver?.disconnect()
    layoutObserver = null
    observedLayoutElements.clear()
    removeInjectedButtons(injectedButtons)
    window.removeEventListener('popstate', routeChanged)
    window.removeEventListener(ROUTE_CHANGE_EVENT, scan)
    if (initialized && history.pushState === wrappedPushState) history.pushState = originalPushState
    if (initialized && history.replaceState === wrappedReplaceState) history.replaceState = originalReplaceState
  }
}
