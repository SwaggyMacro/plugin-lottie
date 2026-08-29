import { Node } from '@tiptap/core'
import { IconMotionLine } from '@halo-dev/components'
import { markRaw } from 'vue'
import LottieEditorAction from '../components/LottieEditorAction.vue'
import { openLottiePicker } from './lottiePickerBridge'
import type { LottieInsertAttributes } from './lottieTypes'

export type LottieNodeAttributes = LottieInsertAttributes

const numberAttribute = (value: string | null, fallback: number): number => {
  const parsed = Number.parseFloat((value || '').trim())
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

const dimensionAttribute = (value: unknown, fallback: number): string => {
  const parsed = numberAttribute(value == null ? null : String(value), fallback)
  return `${parsed}px`
}

const elementDimension = (element: Element, attribute: string, dataAttribute: string, fallback: number): number => {
  const htmlElement = element as HTMLElement
  // Sanitizers may keep only inline CSS or data-* attributes. Read all three
  // representations so reopening an existing node cannot silently reset it
  // to the 160px default.
  return numberAttribute(
    element.getAttribute(attribute)
      || element.getAttribute(dataAttribute)
      || element.getAttribute(`data-lottie-${attribute}`)
      || htmlElement.style?.getPropertyValue(`--halo-lottie-${attribute}`)
      || htmlElement.style?.getPropertyValue(attribute)
      || null,
    fallback,
  )
}

const booleanAttribute = (value: string | null, fallback: boolean): boolean => {
  if (value === null) return fallback
  return value !== 'false'
}

const renderBoolean = (name: string, value: unknown, defaultValue: boolean, falseValue = false) => {
  const enabled = Boolean(value)
  if (enabled === defaultValue) return {}
  return enabled ? { [name]: 'true' } : (falseValue ? { [name]: 'false' } : {})
}

/** Stable public source URL used by editor nodes and the article renderer. */
export const buildLottieSource = (name: string): string =>
  `/apis/api.lottie.halo.run/v1alpha1/animations/${encodeURIComponent(name)}/content`

export const LottieExtension: any = Node.create({
  name: 'haloLottie',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,

  addAttributes() {
    return {
      name: {
        default: '',
        parseHTML: (element: Element) => element.getAttribute('name') || '',
        renderHTML: (attributes: LottieNodeAttributes) => attributes.name ? { name: attributes.name } : {},
      },
      src: {
        default: '',
        parseHTML: (element: Element) => element.getAttribute('src') || '',
        renderHTML: (attributes: LottieNodeAttributes) => attributes.src ? { src: attributes.src } : {},
      },
      format: {
        default: 'json',
        parseHTML: (element: Element) => element.getAttribute('format') || 'json',
        renderHTML: (attributes: LottieNodeAttributes) => ({ format: attributes.format || 'json' }),
      },
      width: {
        default: 160,
        parseHTML: (element: Element) => elementDimension(element, 'width', 'data-width', 160),
        renderHTML: (attributes: LottieNodeAttributes) => ({ width: dimensionAttribute(attributes.width, 160) }),
      },
      height: {
        default: 160,
        parseHTML: (element: Element) => elementDimension(element, 'height', 'data-height', 160),
        renderHTML: (attributes: LottieNodeAttributes) => ({ height: dimensionAttribute(attributes.height, 160) }),
      },
      autoplay: {
        default: true,
        parseHTML: (element: Element) => booleanAttribute(element.getAttribute('autoplay'), true),
        // Always persist an explicit value so `false` survives an HTML round trip.
        renderHTML: (attributes: LottieNodeAttributes) => ({ autoplay: attributes.autoplay ? 'true' : 'false' }),
      },
      loop: {
        default: true,
        parseHTML: (element: Element) => booleanAttribute(element.getAttribute('loop'), true),
        renderHTML: (attributes: LottieNodeAttributes) => renderBoolean('loop', attributes.loop, true, true),
      },
      speed: {
        default: 1,
        parseHTML: (element: Element) => numberAttribute(element.getAttribute('speed'), 1),
        renderHTML: (attributes: LottieNodeAttributes) => ({ speed: String(numberAttribute(String(attributes.speed), 1)) }),
      },
      fit: {
        default: 'contain',
        parseHTML: (element: Element) => element.getAttribute('fit') || 'contain',
        renderHTML: (attributes: LottieNodeAttributes) => ({ fit: attributes.fit || 'contain' }),
      },
      align: {
        default: 'center',
        parseHTML: (element: Element) => element.getAttribute('align') || 'center',
        renderHTML: (attributes: LottieNodeAttributes) => ({ align: attributes.align || 'center' }),
      },
      controls: {
        default: false,
        parseHTML: (element: Element) => booleanAttribute(element.getAttribute('controls'), false),
        renderHTML: (attributes: LottieNodeAttributes) => renderBoolean('controls', attributes.controls, false),
      },
      hoverPlay: {
        default: false,
        parseHTML: (element: Element) => booleanAttribute(element.getAttribute('hover-play'), false),
        renderHTML: (attributes: LottieNodeAttributes) => renderBoolean('hover-play', attributes.hoverPlay, false),
      },
      freezeOnOffscreen: {
        default: true,
        parseHTML: (element: Element) => booleanAttribute(element.getAttribute('freeze-on-offscreen'), true),
        renderHTML: (attributes: LottieNodeAttributes) => renderBoolean('freeze-on-offscreen', attributes.freezeOnOffscreen, true, true),
      },
      ariaLabel: {
        default: '',
        parseHTML: (element: Element) => element.getAttribute('aria-label') || '',
        renderHTML: (attributes: LottieNodeAttributes) => attributes.ariaLabel ? { 'aria-label': attributes.ariaLabel } : {},
      },
    }
  },

  parseHTML() {
    return [{ tag: 'halo-lottie' }]
  },

  renderHTML({ HTMLAttributes }: { HTMLAttributes: Record<string, unknown> }) {
    const attributes = { ...HTMLAttributes }
    const width = dimensionAttribute(attributes.width, 160)
    const height = dimensionAttribute(attributes.height, 160)
    // Persist dimensions in both attributes and inline CSS. Some theme
    // sanitizers preserve style while dropping unknown custom-element attrs;
    // keeping both makes article rendering deterministic.
    const existingStyle = typeof attributes.style === 'string' ? `${attributes.style};` : ''
    attributes.width = width
    attributes.height = height
    attributes.style = `${existingStyle}width:${width};height:${height};--halo-lottie-width:${width};--halo-lottie-height:${height};`
    attributes['data-width'] = width
    attributes['data-height'] = height
    attributes['data-lottie-width'] = width
    attributes['data-lottie-height'] = height
    return ['halo-lottie', attributes]
  },

  addNodeView() {
    return ({ node, editor, getPos }: any) => {
      const dom = document.createElement('span')
      dom.className = 'halo-lottie-node'
      dom.contentEditable = 'false'
      const animation = document.createElement('halo-lottie')
      animation.style.cursor = 'pointer'
      const toolbar = document.createElement('span')
      toolbar.className = 'halo-lottie-node__toolbar'
      Object.assign(toolbar.style, { position: 'absolute', top: '-34px', left: '50%', transform: 'translateX(-50%)', display: 'none', gap: '4px', padding: '4px', background: '#17202a', borderRadius: '4px', zIndex: '2', boxShadow: '0 4px 12px rgb(15 23 42 / 25%)' })
      Object.assign(dom.style, { position: 'relative', display: 'inline-flex' })
      const edit = document.createElement('button')
      edit.type = 'button'; edit.textContent = '编辑'; edit.title = '编辑动画'
      const remove = document.createElement('button')
      remove.type = 'button'; remove.textContent = '删除'; remove.title = '删除动画'
      const resizeHandle = document.createElement('span')
      resizeHandle.className = 'halo-lottie-node__resize-handle'
      resizeHandle.setAttribute('role', 'presentation')
      resizeHandle.setAttribute('aria-label', '调整动画尺寸（按住 Shift 保持比例）')
      resizeHandle.setAttribute('title', '拖拽调整尺寸；按住 Shift 保持比例')
      for (const button of [edit, remove]) {
        Object.assign(button.style, { border: '0', borderRadius: '3px', padding: '4px 7px', color: '#fff', background: 'transparent', fontSize: '12px', cursor: 'pointer' })
        button.addEventListener('pointerenter', () => { button.style.background = 'rgb(255 255 255 / 18%)' })
        button.addEventListener('pointerleave', () => { button.style.background = 'transparent' })
      }
      toolbar.append(edit, remove)
      dom.append(animation, toolbar, resizeHandle)
      Object.assign(resizeHandle.style, {
        position: 'absolute', right: '-5px', bottom: '-5px', width: '12px', height: '12px',
        border: '2px solid #fff', borderRadius: '2px', background: '#0f766e', cursor: 'nwse-resize',
        display: 'none', zIndex: '3', touchAction: 'none', boxShadow: '0 1px 4px rgb(15 23 42 / 35%)',
      })
      dom.addEventListener('pointerenter', () => { toolbar.style.display = 'inline-flex'; resizeHandle.style.display = 'block' })
      dom.addEventListener('pointerleave', (event) => {
        const next = event.relatedTarget as globalThis.Node | null
        if (!resizing && (!next || !dom.contains(next))) {
          toolbar.style.display = 'none'
          resizeHandle.style.display = 'none'
        }
      })
      const syncAttributes = (attributes: LottieNodeAttributes) => {
        for (const name of ['name', 'src', 'format', 'width', 'height', 'speed', 'fit', 'align', 'aria-label'] as const) {
          const value = name === 'aria-label' ? attributes.ariaLabel : attributes[name]
          if (value === undefined || value === null || value === '') animation.removeAttribute(name)
          else if (name === 'width' || name === 'height') animation.setAttribute(name, dimensionAttribute(value, 160))
          else animation.setAttribute(name, String(value))
        }
        const width = dimensionAttribute(attributes.width, 160)
        const height = dimensionAttribute(attributes.height, 160)
        animation.setAttribute('data-width', width)
        animation.setAttribute('data-height', height)
        animation.setAttribute('data-lottie-width', width)
        animation.setAttribute('data-lottie-height', height)
        animation.style.width = width
        animation.style.height = height
        animation.style.setProperty('--halo-lottie-width', width)
        animation.style.setProperty('--halo-lottie-height', height)
        dom.style.width = width
        dom.style.height = height
        for (const [name, enabled] of [['autoplay', attributes.autoplay], ['loop', attributes.loop], ['controls', attributes.controls], ['hover-play', attributes.hoverPlay], ['freeze-on-offscreen', attributes.freezeOnOffscreen]] as const) {
          animation.setAttribute(name, enabled ? 'true' : 'false')
        }
      }
      syncAttributes(node.attrs)
      const editNode = (event: Event) => {
        event.preventDefault()
        event.stopPropagation()
        const position = getPos()
        openLottiePicker(editor, { ...node.attrs }, typeof position === 'number' ? position : undefined)
      }
      animation.addEventListener('click', editNode)
      edit.addEventListener('click', editNode)
      // Keep resizing local to this node and persist dimensions in the document.
      let resizing = false
      let preserveRatio = false
      let resizeStartX = 0
      let resizeStartY = 0
      let resizeStartWidth = 160
      let resizeStartHeight = 160
      const stopResize = (event?: Event) => {
        if (!resizing) return
        resizing = false
        const pointerEvent = event as PointerEvent | undefined
        if (pointerEvent && resizeHandle.hasPointerCapture?.(pointerEvent.pointerId)) {
          resizeHandle.releasePointerCapture(pointerEvent.pointerId)
        }
        event?.preventDefault()
        event?.stopPropagation()
      }
      resizeHandle.addEventListener('pointerdown', (event: PointerEvent) => {
        event.preventDefault()
        event.stopPropagation()
        resizing = true
        preserveRatio = event.shiftKey
        resizeStartX = event.clientX
        resizeStartY = event.clientY
        resizeStartWidth = numberAttribute(String(node.attrs.width), 160)
        resizeStartHeight = numberAttribute(String(node.attrs.height), 160)
        resizeHandle.setPointerCapture?.(event.pointerId)
      })
      resizeHandle.addEventListener('pointermove', (event: PointerEvent) => {
        if (!resizing) return
        event.preventDefault()
        event.stopPropagation()
        let width = Math.min(4096, Math.max(16, Math.round(resizeStartWidth + event.clientX - resizeStartX)))
        let height = Math.min(4096, Math.max(16, Math.round(resizeStartHeight + event.clientY - resizeStartY)))
        // Freeform resizing is the default. Holding Shift keeps the original
        // aspect ratio while still allowing the user to resize from this corner.
        if (event.shiftKey || preserveRatio) {
          const ratio = resizeStartWidth / Math.max(1, resizeStartHeight)
          const widthDelta = Math.abs(width - resizeStartWidth)
          const heightDelta = Math.abs(height - resizeStartHeight)
          if (widthDelta >= heightDelta) height = Math.round(width / Math.max(0.01, ratio))
          else width = Math.round(height * ratio)
          width = Math.min(4096, Math.max(16, width))
          height = Math.min(4096, Math.max(16, height))
        }
        if (width === resizeStartWidth && height === resizeStartHeight) return
        const position = getPos()
        if (typeof position !== 'number') return
        const current = editor.state.doc.nodeAt(position)
        if (!current) return
        editor.view.dispatch(editor.state.tr.setNodeMarkup(position, current.type, { ...current.attrs, width, height }))
      })
      resizeHandle.addEventListener('pointerup', stopResize)
      resizeHandle.addEventListener('pointercancel', stopResize)
      resizeHandle.addEventListener('lostpointercapture', stopResize)
      window.addEventListener('pointerup', stopResize, true)
      window.addEventListener('pointercancel', stopResize, true)
      remove.addEventListener('click', (event) => {
        event.preventDefault(); event.stopPropagation()
        const position = getPos()
        if (typeof position === 'number') {
          editor.view.dispatch(editor.state.tr.delete(position, position + node.nodeSize))
        }
      })
      return {
        dom,
        update(updatedNode: any) {
          if (updatedNode.type !== node.type) return false
          syncAttributes(updatedNode.attrs)
          node = updatedNode as typeof node
          return true
        },
        selectNode() { dom.setAttribute('data-selected', 'true') },
        deselectNode() { dom.removeAttribute('data-selected') },
        destroy() {
          stopResize()
          window.removeEventListener('pointerup', stopResize, true)
          window.removeEventListener('pointercancel', stopResize, true)
        },
      }
    }
  },

  addCommands() {
    return {
      insertLottie: (attributes: Partial<LottieNodeAttributes>) => ({ commands }: any) =>
        commands.insertContent({ type: this.name, attrs: attributes }),
    } as any
  },

  addOptions() {
    return {
      ...this.parent?.(),
      getToolbarItems: ({ editor }: { editor: unknown }) => ({
        priority: 85,
        component: markRaw(LottieEditorAction),
        props: {
          editor,
          icon: markRaw(IconMotionLine),
          label: 'Lottie 动画',
          showLabel: false,
          title: '插入 Lottie 动画',
          pickerPriority: 100
        },
      }),
      getToolboxItems: ({ editor }: { editor: unknown }) => ({
        priority: 85,
        component: markRaw(LottieEditorAction),
        props: {
          editor,
          icon: markRaw(IconMotionLine),
          label: 'Lottie 动画',
          showLabel: true,
          title: '插入 Lottie 动画',
          pickerPriority: 10
        },
      }),
      getCommandMenuItems: () => ({
        priority: 85,
        icon: markRaw(IconMotionLine),
        title: '插入 Lottie 动画',
        keywords: ['lottie', '动画', '表情', '贴纸'],
        command: ({ editor, range }: { editor: any; range: any }) => {
          editor.chain().focus().deleteRange(range).run()
          openLottiePicker(editor)
        },
      }),
    }
  },
})
