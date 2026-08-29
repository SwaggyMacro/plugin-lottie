<script setup lang="ts">
import { DotLottie, type Config, type Fit, type Layout } from '@lottiefiles/dotlottie-web'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

type Align = 'center' | 'top' | 'bottom' | 'left' | 'right'

const props = withDefaults(defineProps<{
  src: string
  format?: string
  width?: number | string
  height?: number | string
  autoplay?: boolean
  loop?: boolean
  speed?: number
  fit?: string
  align?: string
  controls?: boolean
  hoverPlay?: boolean
  freezeOnOffscreen?: boolean
  ariaLabel?: string
}>(), {
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
  format: 'json',
})

const canvas = ref<HTMLCanvasElement | null>(null)
const error = ref('')
const isPlaying = ref(false)
let player: DotLottie | undefined
let lifecycle = 0
let hoverIntent = false

const alignMap: Record<Align, [number, number]> = {
  center: [0.5, 0.5], top: [0.5, 0], bottom: [0.5, 1], left: [0, 0.5], right: [1, 0.5],
}
const fitValues: Fit[] = ['contain', 'cover', 'fill', 'none', 'fit-width', 'fit-height']

function getLayout(): Layout {
  const fit = fitValues.includes(props.fit as Fit) ? props.fit as Fit : 'contain'
  const align = props.align in alignMap ? props.align as Align : 'center'
  return { fit, align: alignMap[align] }
}

function syncPlaying() { isPlaying.value = Boolean(player?.isPlaying) }

function destroyPlayer() {
  lifecycle += 1
  player?.destroy()
  player = undefined
  isPlaying.value = false
}

function dimension(value: number | string | undefined, fallback: number) {
  const raw = String(value ?? '').trim()
  if (/^\d+(?:\.\d+)?$/.test(raw)) return `${raw}px`
  if (/^\d+(?:\.\d+)?(?:px|%|rem|em|vw|vh)$/.test(raw)) return raw
  return `${fallback}px`
}

async function resolveSource(src: string): Promise<string> {
  if (props.format?.toLowerCase() !== 'tgs') return src
  if (typeof DecompressionStream === 'undefined') throw new Error('当前浏览器不支持 TGS 解压')
  const response = await fetch(src)
  if (!response.ok) throw new Error(`无法加载 TGS 动画 (${response.status})`)
  const compressed = await response.arrayBuffer()
  const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip'))
  const json = await new Response(stream).text()
  return json
}

async function mountPlayer() {
  const currentLifecycle = ++lifecycle
  player?.destroy()
  player = undefined
  isPlaying.value = false
  error.value = ''
  if (!canvas.value || !props.src) return
  let source = props.src
  try { source = await resolveSource(props.src) } catch (reason) {
    if (currentLifecycle === lifecycle) error.value = reason instanceof Error ? reason.message : 'Lottie 动画加载失败'
    return
  }
  if (currentLifecycle !== lifecycle || !canvas.value) return
  const config: Config = {
    canvas: canvas.value,
    ...(props.format?.toLowerCase() === 'tgs' ? { data: source } : { src: source }),
    autoplay: props.autoplay && !props.hoverPlay,
    loop: props.loop,
    speed: Number.isFinite(props.speed) && props.speed > 0 ? props.speed : 1,
    layout: getLayout(),
    renderConfig: { autoResize: true, freezeOnOffscreen: props.freezeOnOffscreen },
  }
  try {
    player = new DotLottie(config)
    for (const event of ['play', 'pause', 'stop', 'complete'] as const) player.addEventListener(event, syncPlaying)
    // dotlottie does not advance a non-autoplaying player, and some renderers
    // leave its canvas transparent until the first explicit frame render.
    // Draw frame zero so previews remain visible while idle.
    player.addEventListener('load', () => {
      if (currentLifecycle !== lifecycle || !player) return
      player.setFrame(0)
      if (hoverIntent && props.hoverPlay) player.play()
      syncPlaying()
    })
    player.addEventListener('loadError', (event) => { error.value = event.error?.message || 'Lottie 动画加载失败' })
    player.addEventListener('renderError', (event) => { error.value = event.error?.message || 'Lottie 动画渲染失败' })
    syncPlaying()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Lottie 动画加载失败'
  }
}

function play() { player?.play(); syncPlaying() }
function pause() { player?.pause(); syncPlaying() }
function stop() { player?.stop(); syncPlaying() }
function togglePlay() { isPlaying.value ? pause() : play() }
function handlePointerEnter() { hoverIntent = true; if (props.hoverPlay) play() }
function handlePointerLeave() { hoverIntent = false; if (props.hoverPlay) pause() }

onMounted(mountPlayer)
watch(() => [props.src, props.format, props.width, props.height, props.autoplay, props.loop, props.speed, props.fit, props.align, props.controls, props.hoverPlay, props.freezeOnOffscreen, props.ariaLabel], mountPlayer)
onBeforeUnmount(destroyPlayer)
</script>

<template>
  <span class="lottie-canvas" :style="{ width: dimension(props.width, 160), height: dimension(props.height, 160) }" :aria-label="props.ariaLabel || 'Lottie 动画'" @pointerenter="handlePointerEnter" @pointerleave="handlePointerLeave">
    <canvas ref="canvas" role="img" :aria-label="props.ariaLabel || 'Lottie 动画'" />
    <span v-if="props.controls && !error" class="lottie-canvas__controls" role="group" aria-label="动画控制">
      <button type="button" :aria-label="isPlaying ? '暂停动画' : '播放动画'" :title="isPlaying ? '暂停' : '播放'" @click.stop="togglePlay">{{ isPlaying ? '暂停' : '播放' }}</button>
      <button type="button" aria-label="停止动画" title="停止" @click.stop="stop">停止</button>
    </span>
    <span v-if="error" class="lottie-canvas__error" role="status">{{ error }}</span>
  </span>
</template>

<style scoped>
.lottie-canvas { display: inline-flex; position: relative; vertical-align: middle; overflow: hidden; user-select: none; }
.lottie-canvas canvas { display: block; width: 100%; height: 100%; }
.lottie-canvas__controls { position: absolute; right: 5px; bottom: 5px; display: inline-flex; gap: 3px; padding: 3px; border-radius: 4px; background: rgb(15 23 42 / 72%); }
.lottie-canvas__controls button { display: grid; min-width: 36px; height: 24px; place-items: center; border: 0; border-radius: 3px; color: #fff; background: transparent; font-size: 11px; cursor: pointer; }
.lottie-canvas__controls button:hover, .lottie-canvas__controls button:focus-visible { background: rgb(255 255 255 / 22%); outline: 0; }
.lottie-canvas__error { position: absolute; inset: 0; display: grid; place-items: center; padding: 8px; color: #9f1239; font-size: 12px; text-align: center; background: #fff1f2; }
</style>
