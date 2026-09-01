import { DotLottie } from './lottie/dotlottie-web.js';

// Assets are served by Halo under /plugins/{plugin-name}/assets/{path}.
// Resolve the runtime base from this script URL so the plugin keeps working
// when installed under a different metadata.name or reverse-proxy prefix.
const runtimeScript = document.currentScript || document.querySelector('script[data-halo-lottie-runtime="true"]');
const runtimeBase = (() => {
  const script = runtimeScript;
  if (!script || !script.src) return '/plugins/lottie/assets';
  return new URL('.', script.src).pathname.replace(/\/$/, '');
})();
const skeletonEnabled = runtimeScript?.dataset.haloLottieSkeletonEnabled !== 'false';

DotLottie.setWasmUrl(`${runtimeBase}/lottie/dotlottie-player.wasm`);

const ALIGNMENTS = { center: [0.5, 0.5], top: [0.5, 0], bottom: [0.5, 1], left: [0, 0.5], right: [1, 0.5] };
const FITS = ['contain', 'cover', 'fill', 'none', 'fit-width', 'fit-height'];
const sourceBytesCache = new Map();
const enqueue = typeof queueMicrotask === 'function' ? queueMicrotask : (callback) => Promise.resolve().then(callback);
const bool = (el, name, fallback) => { const value = el.getAttribute(name); return value === null ? fallback : value !== 'false'; };
const size = (value, fallback) => {
  const candidate = value && value.trim();
  if (!candidate) return `${fallback}px`;
  // Tiptap/Halo may serialize numeric HTML attributes without a unit
  // (for example width="512"). Treat those values as CSS pixels instead of
  // silently falling back to the legacy 160px default.
  if (/^\d+(?:\.\d+)?$/.test(candidate)) return `${candidate}px`;
  return /^(?:\d+(?:\.\d+)?)(?:px|%|rem|em|vw|vh)$/.test(candidate) ? candidate : `${fallback}px`;
};

function bytesToDataUri(bytes, mime = 'application/json') {
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return `data:${mime};base64,${btoa(binary)}`;
}

function sourceFormat(src, format) {
  const explicit = String(format || '').trim().toLowerCase();
  if (explicit) return explicit;
  try { return new URL(src, document.baseURI).pathname.split('.').pop()?.toLowerCase() || ''; } catch { return ''; }
}

function sameOriginUrl(src) {
  if (src.startsWith('data:')) return undefined;
  try {
    const url = new URL(src, document.baseURI);
    if (!['http:', 'https:'].includes(url.protocol) || url.origin !== window.location.origin) return undefined;
    return url.href;
  } catch { return undefined; }
}

function loadSourceBytes(url) {
  const cached = sourceBytesCache.get(url);
  if (cached) return cached;
  const request = fetch(url)
    .then(async (response) => {
      if (!response.ok) throw new Error(`Unable to load Lottie animation (${response.status})`);
      return new Uint8Array(await response.arrayBuffer());
    })
    .catch((error) => { sourceBytesCache.delete(url); throw error; });
  sourceBytesCache.set(url, request);
  return request;
}

async function fetchSourceBytes(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Unable to load Lottie animation (${response.status})`);
  return new Uint8Array(await response.arrayBuffer());
}

function decodeDataUri(src) {
  const comma = src.indexOf(',');
  if (comma < 0) throw new Error('Invalid animation data URI');
  const payload = src.slice(comma + 1);
  const binary = src.slice(0, comma).toLowerCase().includes(';base64') ? atob(payload) : decodeURIComponent(payload);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function gzipToJson(compressed) {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('This browser does not support TGS gzip decompression');
  }
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip'));
  const json = new TextDecoder().decode(await new Response(stream).arrayBuffer()).trim();
  try {
    const document = JSON.parse(json);
    if (!document || typeof document !== 'object' || Array.isArray(document)) throw new Error('Invalid Lottie JSON');
  } catch (error) {
    throw new Error(error instanceof Error && error.message === 'Invalid Lottie JSON' ? error.message : 'Invalid TGS animation');
  }
  // TGS is decompressed to a JSON string and passed through DotLottie's
  // `data` option. Passing a data URI through `src` makes some player builds
  // parse the URI itself as JSON and report an invalid Lottie string.
  return json;
}

async function resolveSource(src, format) {
  const kind = sourceFormat(src, format);
  const isTgs = kind === 'tgs';
  const sameOrigin = sameOriginUrl(src);
  if (sameOrigin) {
    const bytes = await loadSourceBytes(sameOrigin);
    if (isTgs) return gzipToJson(bytes);
    return bytesToDataUri(bytes, kind === 'lottie' ? 'application/octet-stream' : 'application/json');
  }
  if (!isTgs) return src;
  const compressed = src.startsWith('data:') ? decodeDataUri(src) : await fetchSourceBytes(src);
  return gzipToJson(compressed);
}

class HaloLottie extends HTMLElement {
  static get observedAttributes() { return ['src', 'format', 'width', 'height', 'data-width', 'data-height', 'data-lottie-width', 'data-lottie-height', 'autoplay', 'loop', 'speed', 'fit', 'align', 'controls', 'hover-play', 'freeze-on-offscreen', 'aria-label']; }
  hoverIntent = false;
  renderVersion = 0;
  renderScheduled = false;
  connectedCallback() { this.mountSkeleton(); this.scheduleRender(); }
  disconnectedCallback() { this.removeEventListener('pointerenter', this.onEnter); this.removeEventListener('pointerleave', this.onLeave); this.hideSkeleton(); this.destroyPlayer(); }
  attributeChangedCallback() { if (this.isConnected) { this.mountSkeleton(); this.scheduleRender(); } }
  scheduleRender() {
    if (this.renderScheduled) return;
    this.renderScheduled = true;
    enqueue(() => { this.renderScheduled = false; if (this.isConnected) this.render(); });
  }
  destroyPlayer() { if (this.player) { this.player.destroy(); this.player = undefined; } }
  mountSkeleton() {
    if (!skeletonEnabled || !this.getAttribute('src')) return;
    const root = this.getRootNode();
    if (root instanceof ShadowRoot && !root.querySelector('[data-halo-lottie-skeleton-style]')) {
      const style = document.createElement('style');
      style.setAttribute('data-halo-lottie-skeleton-style', 'true');
      style.textContent = `@keyframes halo-lottie-loader-spin{to{transform:translate(-50%,-50%) rotate(360deg)}}@keyframes halo-lottie-loader-breathe{from{opacity:.5}to{opacity:1}}halo-lottie[data-halo-lottie-loading='true']{display:inline-flex;position:relative;width:var(--halo-lottie-width,160px);height:var(--halo-lottie-height,160px);overflow:hidden;vertical-align:middle;border-radius:var(--halo-lottie-skeleton-radius,8px);background:var(--halo-lottie-skeleton-background,#f3f4f6);box-shadow:inset 0 0 0 1px var(--halo-lottie-skeleton-frame-border,#d7dbe0)}halo-lottie[data-halo-lottie-loading='true']::before{position:absolute;z-index:1;top:50%;left:50%;width:30%;min-width:24px;max-width:56px;aspect-ratio:1;border:2px solid var(--halo-lottie-skeleton-border,#d7dbe0);border-top-color:var(--halo-lottie-skeleton-accent,#6b7280);border-radius:50%;box-shadow:0 0 0 5px var(--halo-lottie-skeleton-accent-shadow,rgb(107 114 128 / 10%));content:'';display:var(--halo-lottie-skeleton-loader-display,block);pointer-events:none;transform:translate(-50%,-50%);animation:halo-lottie-loader-spin 1.05s linear infinite}halo-lottie[data-halo-lottie-loading='true']::after{position:absolute;z-index:1;inset:0;border:1px solid var(--halo-lottie-skeleton-frame-border,#d7dbe0);border-radius:inherit;box-shadow:var(--halo-lottie-skeleton-breathe-shadow,inset 0 0 24px rgb(107 114 128 / 10%));content:'';pointer-events:none;animation:halo-lottie-loader-breathe 1.35s ease-in-out infinite alternate}@media (prefers-reduced-motion:reduce){halo-lottie[data-halo-lottie-loading='true']::before,halo-lottie[data-halo-lottie-loading='true']::after{animation:none}}`;
      root.append(style);
    }
    this.setAttribute('data-halo-lottie-loading', 'true');
  }
  hideSkeleton() { this.removeAttribute('data-halo-lottie-loading'); }
  layout() { const fit = this.getAttribute('fit'); const align = this.getAttribute('align'); return { fit: fit && FITS.includes(fit) ? fit : 'contain', align: align && ALIGNMENTS[align] ? ALIGNMENTS[align] : ALIGNMENTS.center }; }
  updateToggle() { const button = this.controls && this.controls.querySelector('[data-toggle]'); if (!button) return; const playing = Boolean(this.player && this.player.isPlaying); button.textContent = playing ? 'Pause' : 'Play'; button.title = playing ? 'Pause animation' : 'Play animation'; button.setAttribute('aria-label', button.title); }
  async render() {
    const version = ++this.renderVersion;
    this.removeEventListener('pointerenter', this.onEnter); this.removeEventListener('pointerleave', this.onLeave); this.destroyPlayer(); this.innerHTML = ''; this.controls = undefined;
    // Prefer serialized attributes, then data-* fallbacks, then an inline
    // style left by the editor.  The latter keeps dimensions working through
    // HTML sanitizers that remove custom attributes.
    const width = size(this.getAttribute('width') || this.getAttribute('data-width') || this.getAttribute('data-lottie-width') || this.style.getPropertyValue('--halo-lottie-width') || this.style.width, 160);
    const height = size(this.getAttribute('height') || this.getAttribute('data-height') || this.getAttribute('data-lottie-height') || this.style.getPropertyValue('--halo-lottie-height') || this.style.height, 160);
    this.style.setProperty('display', 'inline-flex');
    this.style.setProperty('position', 'relative');
    this.style.setProperty('vertical-align', 'middle');
    this.style.setProperty('overflow', 'hidden');
    this.style.setProperty('width', width, 'important');
    this.style.setProperty('height', height, 'important');
    const canvas = document.createElement('canvas'); canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', this.getAttribute('aria-label') || 'Lottie animation'); canvas.style.cssText = 'display:block;width:100%;height:100%;'; this.append(canvas);
    if (bool(this, 'controls', false)) this.mountControls();
    const src = this.getAttribute('src'); if (!src) { this.hideSkeleton(); return; }
    this.mountSkeleton();
    const hoverPlay = bool(this, 'hover-play', false); const speed = Number(this.getAttribute('speed') || 1);
    try {
      const source = await resolveSource(src, this.getAttribute('format'));
      if (version !== this.renderVersion || !this.isConnected) return;
      const kind = sourceFormat(src, this.getAttribute('format'));
      const sourceConfig = kind === 'tgs' ? { data: source } : { src: source };
      const player = new DotLottie({ canvas, ...sourceConfig, autoplay: bool(this, 'autoplay', true) && !hoverPlay, loop: bool(this, 'loop', true), speed: Number.isFinite(speed) && speed > 0 ? speed : 1, layout: this.layout(), renderConfig: { autoResize: true, freezeOnOffscreen: bool(this, 'freeze-on-offscreen', true) } });
      this.player = player;
      ['play', 'pause', 'stop', 'complete'].forEach((event) => player.addEventListener(event, () => this.updateToggle()));
      const handleLoad = () => { if (this.player !== player || version !== this.renderVersion) return; this.hideSkeleton(); player.setFrame(0); if (this.hoverIntent && bool(this, 'hover-play', false)) player.play(); this.updateToggle(); };
      player.addEventListener('load', handleLoad);
      if (player.isLoaded) handleLoad();
      const handleError = (error) => { if (this.player === player && version === this.renderVersion) this.showError(error); };
      player.addEventListener('loadError', (event) => handleError(event.error)); player.addEventListener('renderError', (event) => handleError(event.error));
      this.addEventListener('pointerenter', this.onEnter); this.addEventListener('pointerleave', this.onLeave);
    } catch (error) { if (version === this.renderVersion && this.isConnected) this.showError(error); }
  }
  showError(error) { this.hideSkeleton(); const node = document.createElement('span'); node.setAttribute('role', 'status'); node.textContent = error instanceof Error ? error.message : 'Lottie animation failed to load'; node.style.cssText = 'position:absolute;inset:0;display:grid;place-items:center;padding:8px;color:#9f1239;background:#fff1f2;font:12px sans-serif;text-align:center;'; this.append(node); }
  mountControls() {
    const controls = document.createElement('span'); controls.setAttribute('role', 'group'); controls.setAttribute('aria-label', 'Animation controls'); controls.style.cssText = 'position:absolute;right:4px;bottom:4px;display:inline-flex;gap:3px;padding:3px;border-radius:4px;background:rgb(15 23 42 / 72%);';
    const toggle = document.createElement('button'); toggle.type = 'button'; toggle.dataset.toggle = 'true'; toggle.textContent = 'Play'; toggle.title = 'Play animation'; toggle.setAttribute('aria-label', toggle.title);
    const stop = document.createElement('button'); stop.type = 'button'; stop.textContent = 'Stop'; stop.title = 'Stop animation'; stop.setAttribute('aria-label', stop.title);
    [toggle, stop].forEach((button) => { button.style.cssText = 'min-width:36px;min-height:24px;border:0;border-radius:3px;color:#fff;background:transparent;font:11px sans-serif;cursor:pointer;'; button.addEventListener('pointerdown', (event) => event.stopPropagation()); });
    toggle.addEventListener('click', () => { if (this.player && this.player.isPlaying) this.player.pause(); else if (this.player) this.player.play(); this.updateToggle(); }); stop.addEventListener('click', () => { if (this.player) this.player.stop(); this.updateToggle(); });
    controls.append(toggle, stop); this.controls = controls; this.append(controls);
  }
  onEnter = () => { this.hoverIntent = true; if (bool(this, 'hover-play', false) && this.player) this.player.play(); };
  onLeave = () => { this.hoverIntent = false; if (bool(this, 'hover-play', false) && this.player) this.player.pause(); };
}
if (!customElements.get('halo-lottie')) customElements.define('halo-lottie', HaloLottie);
