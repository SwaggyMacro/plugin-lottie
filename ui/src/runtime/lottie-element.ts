import { DotLottie, type Fit, type Layout } from '@lottiefiles/dotlottie-web'

type Align = 'center' | 'top' | 'bottom' | 'left' | 'right'
const ALIGNMENTS: Record<Align, [number, number]> = {
  center: [0.5, 0.5], top: [0.5, 0], bottom: [0.5, 1], left: [0, 0.5], right: [1, 0.5],
}
const FITS: Fit[] = ['contain', 'cover', 'fill', 'none', 'fit-width', 'fit-height']
const sourceBytesCache = new Map<string, Promise<Uint8Array>>()

function bool(element: HTMLElement, name: string, fallback: boolean) {
  const value = element.getAttribute(name)
  return value === null ? fallback : value !== 'false'
}

function size(value: string | null, fallback: number) {
  const candidate = value?.trim() || ''
  if (/^\d+(?:\.\d+)?$/.test(candidate)) return `${candidate}px`
  return /^(?:\d+(?:\.\d+)?)(?:px|%|rem|em|vw|vh)$/.test(candidate) ? candidate : `${fallback}px`
}

function bytesToDataUri(bytes: Uint8Array, mime = 'application/json') {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  }
  return `data:${mime};base64,${btoa(binary)}`
}

function sourceFormat(src: string, format: string | null) {
  const explicit = (format || '').trim().toLowerCase()
  if (explicit) return explicit
  try {
    return new URL(src, document.baseURI).pathname.split('.').pop()?.toLowerCase() || ''
  } catch {
    return ''
  }
}

function sameOriginUrl(src: string) {
  if (src.startsWith('data:')) return undefined
  try {
    const url = new URL(src, document.baseURI)
    if (!['http:', 'https:'].includes(url.protocol) || url.origin !== window.location.origin) return undefined
    return url.href
  } catch {
    return undefined
  }
}

function loadSourceBytes(url: string) {
  const cached = sourceBytesCache.get(url)
  if (cached) return cached
  const request = fetch(url)
    .then(async (response) => {
      if (!response.ok) throw new Error(`无法加载 Lottie 动画 (${response.status})`)
      return new Uint8Array(await response.arrayBuffer())
    })
    .catch((error) => {
      sourceBytesCache.delete(url)
      throw error
    })
  sourceBytesCache.set(url, request)
  return request
}

async function fetchSourceBytes(url: string) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`无法加载 Lottie 动画 (${response.status})`)
  return new Uint8Array(await response.arrayBuffer())
}

function decodeDataUri(src: string) {
  const comma = src.indexOf(',')
  if (comma < 0) throw new Error('动画数据地址无效')
  const payload = src.slice(comma + 1)
  const binary = src.slice(0, comma).toLowerCase().includes(';base64')
    ? atob(payload)
    : decodeURIComponent(payload)
  return Uint8Array.from(binary, (character) => character.charCodeAt(0))
}

async function gzipToJson(compressed: Uint8Array) {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('当前浏览器不支持 TGS 解压')
  }
  const stream = new Blob([compressed as unknown as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'))
  const json = new TextDecoder().decode(await new Response(stream).arrayBuffer()).trim()
  try {
    const document = JSON.parse(json)
    if (!document || typeof document !== 'object' || Array.isArray(document)) throw new Error('Lottie JSON 无效')
  } catch (error) {
    throw new Error(error instanceof Error && error.message === 'Lottie JSON 无效' ? error.message : 'TGS 动画无效')
  }
  // DotLottie accepts parsed animation data through `data`. Passing a data URI
  // through `src` makes the WASM parser treat the URI itself as JSON on some
  // versions, which produces "Lottie JSON 无效字符串" for TGS files.
  return json
}

async function resolveSource(src: string, format: string | null) {
  const kind = sourceFormat(src, format)
  const isTgs = kind === 'tgs'
  const sameOrigin = sameOriginUrl(src)
  if (sameOrigin) {
    const bytes = await loadSourceBytes(sameOrigin)
    if (isTgs) return gzipToJson(bytes)
    const mime = kind === 'lottie' ? 'application/octet-stream' : 'application/json'
    return bytesToDataUri(bytes, mime)
  }
  if (!isTgs) return src
  const compressed = src.startsWith('data:') ? decodeDataUri(src) : await fetchSourceBytes(src)
  return gzipToJson(compressed)
}

class HaloLottieElement extends HTMLElement {
  private player?: DotLottie
  private controls?: HTMLSpanElement
  private hoverIntent = false
  private renderVersion = 0
  private renderScheduled = false

  static get observedAttributes() {
    return ['src', 'format', 'width', 'height', 'data-width', 'data-height', 'data-lottie-width', 'data-lottie-height', 'autoplay', 'loop', 'speed', 'fit', 'align', 'controls', 'hover-play', 'freeze-on-offscreen', 'aria-label']
  }

  connectedCallback() { this.scheduleRender() }
  disconnectedCallback() { this.removeEventListener('pointerenter', this.onEnter); this.removeEventListener('pointerleave', this.onLeave); this.destroyPlayer() }
  attributeChangedCallback() { if (this.isConnected) this.scheduleRender() }

  private scheduleRender() {
    if (this.renderScheduled) return
    this.renderScheduled = true
    queueMicrotask(() => {
      this.renderScheduled = false
      if (this.isConnected) void this.render()
    })
  }

  private destroyPlayer() {
    this.player?.destroy()
    this.player = undefined
  }

  private layout(): Layout {
    const fitValue = this.getAttribute('fit') as Fit | null
    const alignValue = this.getAttribute('align') as Align | null
    return {
      fit: fitValue && FITS.includes(fitValue) ? fitValue : 'contain',
      align: alignValue && alignValue in ALIGNMENTS ? ALIGNMENTS[alignValue] : ALIGNMENTS.center,
    }
  }

  private updateToggle() {
    const button = this.controls?.querySelector<HTMLButtonElement>('[data-toggle]')
    if (!button) return
    const playing = Boolean(this.player?.isPlaying)
    button.textContent = playing ? '暂停' : '播放'
    button.title = playing ? '暂停动画' : '播放动画'
    button.setAttribute('aria-label', button.title)
  }

  private async render() {
    const version = ++this.renderVersion
    this.removeEventListener('pointerenter', this.onEnter)
    this.removeEventListener('pointerleave', this.onLeave)
    this.destroyPlayer()
    this.innerHTML = ''
    this.controls = undefined
    const width = size(this.getAttribute('width') || this.getAttribute('data-width') || this.getAttribute('data-lottie-width') || this.style.getPropertyValue('--halo-lottie-width') || this.style.width, 160)
    const height = size(this.getAttribute('height') || this.getAttribute('data-height') || this.getAttribute('data-lottie-height') || this.style.getPropertyValue('--halo-lottie-height') || this.style.height, 160)
    // Keep dimensions on the host and canvas wrapper. This survives theme CSS
    // that targets custom elements and prevents a silent fallback to 160px.
    this.style.setProperty('display', 'inline-flex')
    this.style.setProperty('position', 'relative')
    this.style.setProperty('vertical-align', 'middle')
    this.style.setProperty('overflow', 'hidden')
    this.style.setProperty('width', width, 'important')
    this.style.setProperty('height', height, 'important')
    this.style.setProperty('--halo-lottie-width', width)
    this.style.setProperty('--halo-lottie-height', height)

    const canvas = document.createElement('canvas')
    canvas.setAttribute('role', 'img')
    canvas.setAttribute('aria-label', this.getAttribute('aria-label') || 'Lottie 动画')
    canvas.style.cssText = 'display:block;width:100%;height:100%;'
    this.append(canvas)
    if (bool(this, 'controls', false)) this.mountControls()

    const src = this.getAttribute('src')
    if (!src) return
    const hoverPlay = bool(this, 'hover-play', false)
    const speed = Number(this.getAttribute('speed') || 1)
    try {
      const source = await resolveSource(src, this.getAttribute('format'))
      if (version !== this.renderVersion || !this.isConnected) return
      const kind = sourceFormat(src, this.getAttribute('format'))
      const sourceConfig = kind === 'tgs' ? { data: source } : { src: source }
      this.player = new DotLottie({
        canvas, ...sourceConfig, autoplay: bool(this, 'autoplay', true) && !hoverPlay,
        loop: bool(this, 'loop', true), speed: Number.isFinite(speed) && speed > 0 ? speed : 1,
        layout: this.layout(), renderConfig: { autoResize: true, freezeOnOffscreen: bool(this, 'freeze-on-offscreen', true) },
      })
      for (const event of ['play', 'pause', 'stop', 'complete'] as const) this.player.addEventListener(event, () => this.updateToggle())
      this.player.addEventListener('load', () => {
        if (!this.player) return
        this.player.setFrame(0)
        if (this.hoverIntent && bool(this, 'hover-play', false)) this.player.play()
        this.updateToggle()
      })
      this.player.addEventListener('loadError', (event) => this.showError(event.error))
      this.player.addEventListener('renderError', (event) => this.showError(event.error))
      this.addEventListener('pointerenter', this.onEnter)
      this.addEventListener('pointerleave', this.onLeave)
    } catch (error) { this.showError(error) }
  }

  private showError(error: unknown) {
    const node = document.createElement('span')
    node.setAttribute('role', 'status')
    node.textContent = error instanceof Error ? error.message : 'Lottie 动画加载失败'
    node.style.cssText = 'position:absolute;inset:0;display:grid;place-items:center;padding:8px;color:#9f1239;background:#fff1f2;font:12px sans-serif;text-align:center;'
    this.append(node)
  }

  private mountControls() {
    const controls = document.createElement('span')
    controls.setAttribute('role', 'group')
    controls.setAttribute('aria-label', '动画控制')
    controls.style.cssText = 'position:absolute;right:4px;bottom:4px;display:inline-flex;gap:3px;padding:3px;border-radius:4px;background:rgb(15 23 42 / 72%);'
    const toggle = document.createElement('button')
    toggle.type = 'button'; toggle.dataset.toggle = 'true'; toggle.textContent = '播放'; toggle.title = '播放动画'; toggle.setAttribute('aria-label', toggle.title)
    const stop = document.createElement('button')
    stop.type = 'button'; stop.textContent = '停止'; stop.title = '停止动画'; stop.setAttribute('aria-label', stop.title)
    for (const button of [toggle, stop]) { button.style.cssText = 'min-width:36px;min-height:24px;border:0;border-radius:3px;color:#fff;background:transparent;font:11px sans-serif;cursor:pointer;'; button.addEventListener('pointerdown', (event) => event.stopPropagation()) }
    toggle.addEventListener('click', () => { if (this.player?.isPlaying) this.player.pause(); else this.player?.play(); this.updateToggle() })
    stop.addEventListener('click', () => { this.player?.stop(); this.updateToggle() })
    controls.append(toggle, stop); this.controls = controls; this.append(controls)
  }

  private onEnter = () => { this.hoverIntent = true; if (bool(this, 'hover-play', false)) this.player?.play() }
  private onLeave = () => { this.hoverIntent = false; if (bool(this, 'hover-play', false)) this.player?.pause() }
}

if (!customElements.get('halo-lottie')) customElements.define('halo-lottie', HaloLottieElement)
