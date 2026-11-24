<script setup>
import { computed } from 'vue'
import { THEMES, THEME_NAMES, staticTokens, resolveTokenTheme } from '../design/tokenRegistry'
import { state, applyTheme, toast } from '../store'
import { t } from '../lib/i18n'

// TH-02 六主题矩阵：点击卡片立即切换并持久化；System 按 OS 亮暗实时解析。
// 预览色全部来自 Theme Registry，组件自身不写死任何色值。
const staticTok = staticTokens()
const THEME_LABEL = {
  light: 'theme.light', dark: 'theme.dark', midnight: 'theme.midnight',
  paper: 'theme.paper', contrast: 'theme.contrast', system: 'theme.system'
}
const THEME_NOTE = {
  light: 'theme.note.light', dark: 'theme.note.dark', midnight: 'theme.note.midnight',
  paper: 'theme.note.paper', contrast: 'theme.note.contrast', system: 'theme.note.system'
}
const themes = computed(() => THEME_NAMES.map((name) => {
  const tokens = name === 'system' ? THEMES.light : THEMES[name]
  return { name, tokens }
}))
const current = computed(() => state.theme || 'system')
const systemDark = () => !!(typeof window !== 'undefined' && window.matchMedia &&
  window.matchMedia('(prefers-color-scheme: dark)').matches)
const resolved = computed(() => resolveTokenTheme(state.theme || 'system', systemDark()))
const resolvedLabel = computed(() => t(THEME_LABEL[resolved.value]))
const timingText = computed(() => {
  const ms = Number(state.lastThemeSwitchMs || 0)
  return ms > 0 ? `${ms} ms` : '—'
})
function dots(tokens) {
  return [tokens.canvas, tokens.surfaceRaised, tokens.text, tokens.accent, tokens.diffAddFg, tokens.diffDelFg]
}
function pickTheme(name) {
  if (name === current.value) return
  applyTheme(name)
  toast('success', t('theme.studio.applied', { name: t(THEME_LABEL[name]) }))
}
</script>

<template>
  <div class="theme-studio" :aria-label="t('theme.studio.aria')">
    <div class="d-flex align-items-center gap-2 flex-wrap mb-2">
      <span class="fw-semibold text-nowrap"><i class="bi bi-palette2"></i>{{ t('theme.studio') }}</span>
      <span class="text-secondary">{{ t('theme.studio.desc') }}</span>
    </div>
    <div class="ts-grid">
      <button v-for="item in themes" :key="item.name" type="button" class="ts-card"
              :class="{ active: current === item.name }"
              :style="{ background: item.tokens.canvas, color: item.tokens.text, borderColor: item.tokens.border }"
              :aria-pressed="current === item.name"
              :title="t(THEME_NOTE[item.name])" @click="pickTheme(item.name)">
        <span class="d-flex align-items-center justify-content-between gap-1">
          <span class="ts-name">{{ t(THEME_LABEL[item.name]) }}</span>
          <i v-if="current === item.name" class="bi bi-check2-circle" aria-hidden="true"></i>
        </span>
        <span class="ts-dots" aria-hidden="true">
          <i v-for="(c, i) in dots(item.tokens)" :key="i" :style="{ background: c }"></i>
        </span>
        <span class="ts-meta" :style="{ color: item.tokens.textMuted }">
          {{ t('theme.studio.meta', { radius: staticTok.radius.md, font: staticTok.font.sizeMd }) }}
        </span>
      </button>
    </div>
    <div class="ts-current mt-2">
      <span>{{ t('theme.studio.current', { name: t(THEME_LABEL[current]), resolved: resolvedLabel }) }}</span>
      <span class="text-secondary ms-2">{{ t('theme.studio.timing', { ms: timingText }) }}</span>
    </div>
    <div class="form-text mt-1" style="font-size:.72rem">{{ t('theme.studio.hint') }}</div>
  </div>
</template>

<style scoped>
.ts-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--bdf-space-sm); }
@media (max-width: 720px) { .ts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.ts-card {
  display: block; width: 100%; text-align: left; font: inherit; cursor: pointer;
  border: 1px solid var(--bdf-border); border-radius: var(--bdf-radius-md);
  padding: var(--bdf-space-sm) var(--bdf-space-md); min-width: 0;
}
.ts-card.active { outline: 2px solid var(--bdf-accent); outline-offset: 1px; }
.ts-name { font-size: var(--bdf-font-size-sm); font-weight: var(--bdf-font-weight-strong); }
.ts-dots { display: flex; gap: 3px; margin: var(--bdf-space-xs) 0; }
.ts-dots i { width: .7rem; height: .7rem; border-radius: var(--bdf-radius-pill); display: inline-block; }
.ts-meta { display: block; font-size: var(--bdf-font-size-xs); font-family: var(--bdf-font-family); }
.ts-current { font-size: var(--bdf-font-size-sm); }
</style>
