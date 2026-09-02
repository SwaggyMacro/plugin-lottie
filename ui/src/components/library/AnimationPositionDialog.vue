<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import LottieCanvas from '../LottieCanvas.vue'

type Animation = {
  metadata: { name: string }
  spec: {
    displayName: string
    format: string
    sourceFileName?: string | null
    defaults?: { fit?: string; align?: string; ariaLabel?: string | null } | null
  }
}

export type AnimationPositionOption = { animation: Animation; source: string }

const props = withDefaults(
  defineProps<{
    open: boolean
    options: AnimationPositionOption[]
    selectedCount: number
    busy?: boolean
  }>(),
  { busy: false },
)

const emit = defineEmits<{
  'update:open': [boolean]
  move: [string]
}>()

const expanded = ref(false)
const selectedName = ref('')
const selectedOption = computed(
  () =>
    props.options.find((option) => option.animation.metadata.name === selectedName.value) ?? null,
)

watch(
  () => props.open,
  (open) => {
    if (open) {
      selectedName.value = props.options[0]?.animation.metadata.name ?? ''
      expanded.value = false
    }
  },
)
watch(
  () => props.options,
  (options) => {
    if (!options.some((option) => option.animation.metadata.name === selectedName.value)) {
      selectedName.value = options[0]?.animation.metadata.name ?? ''
    }
  },
  { deep: true },
)

function close() {
  if (!props.busy) {
    expanded.value = false
    emit('update:open', false)
  }
}

function choose(name: string) {
  selectedName.value = name
  expanded.value = false
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    expanded.value = false
    return
  }
  if (expanded.value && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault()
    const options = props.options
    if (!options.length) return
    const currentIndex = Math.max(
      0,
      options.findIndex((option) => option.animation.metadata.name === selectedName.value),
    )
    const offset = event.key === 'ArrowDown' ? 1 : -1
    const nextIndex = Math.min(options.length - 1, Math.max(0, currentIndex + offset))
    const nextOption = options[nextIndex]
    if (nextOption) {
      selectedName.value = nextOption.animation.metadata.name
    }
  }
}

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <Teleport to="body">
    <div v-if="props.open" class="position-backdrop" @click.self="close">
      <section
        class="position-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="position-dialog-title"
      >
        <header class="position-header">
          <div>
            <p class="position-eyebrow">批量管理</p>
            <h2 id="position-dialog-title">移动动画位置</h2>
          </div>
          <button type="button" class="icon-button" aria-label="关闭" @click="close">×</button>
        </header>
        <p class="position-description">
          已选择 {{ props.selectedCount }} 个动画。请选择它们要移动到哪个动画之前。
        </p>

        <div class="position-field">
          <span class="field-label">目标动画</span>
          <div class="position-combobox">
            <button
              type="button"
              class="combobox-trigger"
              role="combobox"
              aria-haspopup="listbox"
              :aria-expanded="expanded"
              :disabled="props.busy || !props.options.length"
              @click="expanded = !expanded"
            >
              <span v-if="selectedOption" class="option-content">
                <LottieCanvas
                  :src="selectedOption.source"
                  :format="selectedOption.animation.spec.format"
                  :width="42"
                  :height="42"
                  :autoplay="false"
                  :loop="false"
                  :fit="selectedOption.animation.spec.defaults?.fit || 'contain'"
                  :align="selectedOption.animation.spec.defaults?.align || 'center'"
                  :aria-label="`${selectedOption.animation.spec.displayName} 的首帧预览`"
                />
                <span class="option-text"
                  ><strong>{{ selectedOption.animation.spec.displayName }}</strong
                  ><small>{{
                    selectedOption.animation.spec.sourceFileName ||
                    selectedOption.animation.spec.format
                  }}</small></span
                >
              </span>
              <span v-else class="placeholder">没有可用的目标动画</span>
              <span class="combobox-chevron" aria-hidden="true">⌄</span>
            </button>
            <div v-if="expanded" class="combobox-list" role="listbox">
              <button
                v-for="option in props.options"
                :key="option.animation.metadata.name"
                type="button"
                class="combobox-option"
                role="option"
                :aria-selected="option.animation.metadata.name === selectedName"
                @click="choose(option.animation.metadata.name)"
              >
                <LottieCanvas
                  :src="option.source"
                  :format="option.animation.spec.format"
                  :width="42"
                  :height="42"
                  :autoplay="false"
                  :loop="false"
                  :fit="option.animation.spec.defaults?.fit || 'contain'"
                  :align="option.animation.spec.defaults?.align || 'center'"
                  :aria-label="`${option.animation.spec.displayName} 的首帧预览`"
                />
                <span class="option-text"
                  ><strong>{{ option.animation.spec.displayName }}</strong
                  ><small>{{
                    option.animation.spec.sourceFileName || option.animation.spec.format
                  }}</small></span
                >
              </button>
            </div>
          </div>
        </div>

        <footer class="position-actions">
          <button type="button" class="quiet-button" :disabled="props.busy" @click="close">
            取消
          </button>
          <button
            type="button"
            class="primary-button"
            :disabled="props.busy || !selectedName"
            @click="emit('move', selectedName)"
          >
            {{ props.busy ? '保存中…' : '移动' }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
:global(*) {
  box-sizing: border-box;
}
.position-backdrop {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(15 23 42 / 45%);
}
.position-dialog {
  width: min(520px, 100%);
  max-height: calc(100vh - 48px);
  overflow: visible;
  padding: 22px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  color: #17202a;
  background: #fff;
  box-shadow: 0 20px 50px rgb(15 23 42 / 20%);
}
.position-header,
.position-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.position-header {
  align-items: flex-start;
  margin-bottom: 12px;
}
.position-eyebrow {
  margin: 0 0 4px;
  color: #0f766e;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
h2 {
  margin: 0;
  font-size: 20px;
}
.icon-button {
  width: 30px;
  height: 30px;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  color: #334155;
  background: #fff;
  font: inherit;
  font-size: 18px;
  cursor: pointer;
}
.position-description {
  margin: 0 0 18px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}
.position-field {
  display: grid;
  gap: 7px;
}
.field-label {
  color: #475569;
  font-size: 13px;
}
.position-combobox {
  position: relative;
}
.combobox-trigger {
  display: flex;
  width: 100%;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  padding: 7px 10px;
  color: #17202a;
  background: #fff;
  text-align: left;
  cursor: pointer;
}
.combobox-trigger:focus-visible {
  outline: 2px solid #0f766e;
  outline-offset: 2px;
}
.option-content {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}
.option-text {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.option-text strong,
.option-text small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.option-text strong {
  font-size: 13px;
}
.option-text small {
  color: #64748b;
  font-size: 11px;
}
.placeholder {
  color: #94a3b8;
  font-size: 13px;
}
.combobox-chevron {
  color: #64748b;
  font-size: 17px;
}
.combobox-list {
  position: absolute;
  z-index: 2;
  top: calc(100% + 4px);
  right: 0;
  left: 0;
  max-height: 280px;
  overflow: auto;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  padding: 4px;
  background: #fff;
  box-shadow: 0 12px 28px rgb(15 23 42 / 15%);
}
.combobox-option {
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 4px;
  padding: 6px;
  color: #17202a;
  background: #fff;
  text-align: left;
  cursor: pointer;
}
.combobox-option:hover,
.combobox-option[aria-selected='true'] {
  background: #f0fdfa;
}
.position-actions {
  justify-content: flex-end;
  margin-top: 22px;
}
.quiet-button,
.primary-button {
  min-height: 34px;
  border-radius: 4px;
  padding: 7px 13px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.quiet-button {
  border: 1px solid #cbd5e1;
  color: #334155;
  background: #fff;
}
.primary-button {
  border: 1px solid #0f766e;
  color: #fff;
  background: #0f766e;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
</style>
