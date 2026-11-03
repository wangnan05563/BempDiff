<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { state, init, closeReportPreview, triggerCompare, checkUpdateSilently, dismissUpdateHint } from './store'
import ToolBar from './components/ToolBar.vue'
import DiffTree from './components/DiffTree.vue'
import DiffView from './components/DiffView.vue'
import InfoPanel from './components/InfoPanel.vue'
import StatusBar from './components/StatusBar.vue'
import ConfigDialog from './components/ConfigDialog.vue'
import CompareOverlay from './components/CompareOverlay.vue'
import ReportPreview from './components/ReportPreview.vue'
import CostGateDialog from './components/CostGateDialog.vue'
import GuideOverlay from './components/GuideOverlay.vue'
import ShortcutHelp from './components/ShortcutHelp.vue'
import DragDropOverlay from './components/DragDropOverlay.vue'
import { isEditableTarget } from './lib/shortcuts'
import { t } from './lib/i18n'

const showConfig = ref(false)
const showReport = ref(false)
onMounted(() => {
  init()
  // R9（二期 T01471）：启动 3s 后异步静默检查更新（24h 节流），发现新版本 → 右下角提示条（不自动安装）
  setTimeout(() => { checkUpdateSilently() }, 3000)
})

// ===== R6 全局快捷键（二期 T01465）：Ctrl+Enter 比对 / Ctrl+K 聚焦过滤框 / ? 速查面板 =====
// 冲突原则：输入框内不劫持；弹窗（.modal-backdrop，含速查面板自身）打开时仅 ?/Esc 生效（面板内部处理）。
const showShortcuts = ref(false)
function onGlobalKey(e) {
  if (isEditableTarget(e)) return
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    triggerCompare()
    return
  }
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    state.treeFilterFocusTick++
    return
  }
  if (e.key === '?' || (e.shiftKey && e.key === '/')) {
    // 已有其它模态弹窗时不叠加速查面板，避免焦点混乱
    if (document.querySelector('.modal-backdrop')) return
    e.preventDefault()
    showShortcuts.value = !showShortcuts.value
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey))

// ===== 左右栏拖拽调宽（分隔条） =====
// 两栏宽度由此统一管理：树栏随拖拽实时改宽；分析栏「仅在用户拖拽过」后才固定为内联宽度，
// 否则保持 CSS + media query 的窄屏自动压缩（避免用户从未拖拽时被意外覆盖）。
// 注意：宽度必须经 prop 传入组件，而非 :style 透传——DiffTree 渲染为 fragment（含右键菜单/属性弹窗
// 多个顶层根节点），style 无法 fallthrough 到唯一根元素（曾导致拖拽无效的 bug）。
const treeW = ref(300)
const aiW = ref(380)
const aiResized = ref(false)

let dragCtx = null
function startResize(which, e) {
  // 已收起的栏不响应拖拽（分隔条此时已 v-show 隐藏，此处为双保险）
  if (which === 'tree' && state.treePanelCollapsed) return
  if (which === 'ai' && state.aiPanelCollapsed) return
  // 分隔条相邻的实际栏元素：树在分隔条左侧、分析栏在右侧，读其当前宽度作为拖拽基准
  const bar = e.currentTarget
  const peer = which === 'tree' ? bar.previousElementSibling : bar.nextElementSibling
  const baseW = peer ? peer.getBoundingClientRect().width : (which === 'tree' ? 300 : 380)
  dragCtx = { which, startX: e.clientX, baseW }
  // 拖拽期间全局呈现 col-resize 光标并禁用文本选中，避免穿过对比区时闪烁
  document.documentElement.classList.add('resizing-col')
  window.addEventListener('mousemove', onResize)
  window.addEventListener('mouseup', endResize)
  e.preventDefault()
}
function onResize(e) {
  if (!dragCtx) return
  const d = e.clientX - dragCtx.startX
  // 树向右拖变大；分析栏向左拖变大（方向相反）
  const raw = dragCtx.which === 'tree' ? dragCtx.baseW + d : dragCtx.baseW - d
  const MIN = 120
  const MAX = Math.max(MIN + 1, window.innerWidth * 0.6)
  const w = Math.min(MAX, Math.max(MIN, raw))
  if (dragCtx.which === 'tree') treeW.value = w
  else { aiW.value = w; aiResized.value = true }
}
function endResize() {
  if (!dragCtx) return
  dragCtx = null
  document.documentElement.classList.remove('resizing-col')
  window.removeEventListener('mousemove', onResize)
  window.removeEventListener('mouseup', endResize)
}

const toastClass = computed(() => {
  const t = state.toast && state.toast.type
  if (t === 'danger') return 'text-bg-danger'
  if (t === 'success') return 'text-bg-success'
  if (t === 'warning') return 'text-bg-warning'
  // default / info：用跟随主题的 body 背景，避免 dark 模式下 secondary 变亮导致 toast 闪白。
  return 'text-bg-body'
})

// 报告预览关闭：区分来源——任务预览（previewOpen）优先关闭，避免连带关闭先前打开的全局报告（评审 P2 #13）
function onPreviewClose() {
  if (state.previewOpen) closeReportPreview()
  else showReport.value = false
}
</script>

<template>
  <ToolBar :on-open-config="() => (showConfig = true)" :on-open-report="() => (showReport = true)" />

  <!-- 专注模式：隐藏左右栏，中间 diff 占满视野（由 state.focusMode 控制） -->
  <div class="app-main" :class="{ 'focus-mode': state.focusMode }">
    <DiffTree :panel-width="treeW" />
    <div v-show="!state.treePanelCollapsed" class="vt-handle" :title="t('app.resizeTree')"
         @mousedown="startResize('tree', $event)"></div>
    <DiffView />
    <div v-show="!state.aiPanelCollapsed" class="vt-handle" :title="t('app.resizeAi')"
         @mousedown="startResize('ai', $event)"></div>
    <InfoPanel :panel-width="aiResized ? aiW : undefined" />
  </div>

  <StatusBar />

  <ConfigDialog :visible="showConfig" @close="showConfig = false" />
  <!-- 报告预览：既能看全局 reportMd，也能看 AI 控制台里某次任务的预览报告（:md 优先） -->
  <ReportPreview :visible="showReport || state.previewOpen" :md="state.previewOpen ? state.previewMd : null" @close="onPreviewClose" />
  <CostGateDialog />
  <CompareOverlay />
  <!-- R6 快捷键速查面板：「?」唤起 -->
  <ShortcutHelp v-if="showShortcuts" @close="showShortcuts = false" />

  <!-- R9 更新提示条（右下角）：仅提示与跳转下载页，不自动下载/安装 -->
  <div class="update-hint shadow" v-if="state.updateAvailable" role="status"
       :aria-label="t('app.updateAria')">
    <i class="bi bi-arrow-up-circle text-primary"></i>
    <span class="uh-text">{{ t('app.updateFound') }} <b>v{{ state.updateAvailable.tag }}</b></span>
    <a v-if="state.updateAvailable.url" class="btn btn-sm btn-primary py-0 px-2" style="font-size:.72rem"
       :href="state.updateAvailable.url" target="_blank" rel="noopener"
       :title="t('app.updateGoTitle')">{{ t('app.updateGo') }}</a>
    <button type="button" class="btn-close btn-sm" :aria-label="t('app.closeUpdate')"
            @click="dismissUpdateHint"></button>
  </div>
  <!-- 功能引导：首次自动弹出一次，之后经左下角常驻「引导」按钮唤起 -->
  <GuideOverlay />
  <DragDropOverlay />

  <div class="toast-container position-fixed top-0 end-0 p-3" style="z-index:3000">
    <div class="toast align-items-center border-0 show" v-if="state.toast" :class="toastClass" role="alert">
      <div class="d-flex">
        <div class="toast-body">{{ state.toast.text }}</div>
      </div>
    </div>
  </div>
</template>

<style>
/* R9 更新提示条（右下角浮层）：轻量不遮挡主界面，点击下载页新开标签 */
.update-hint {
  position: fixed; right: 1rem; bottom: 3.2rem; z-index: 1500;
  display: flex; align-items: center; gap: .5rem;
  padding: .45rem .7rem; border-radius: .5rem;
  background: var(--bs-body-bg); border: 1px solid var(--bs-border-color);
  font-size: .78rem;
}
.update-hint .uh-text { white-space: nowrap; }

/* ===== R10 主题强调色（T01475）：html[data-accent] 重映射 Bootstrap 强调元素 =====
   Bootstrap 5.3 的 .btn-primary 等背景为编译期硬编码，须显式重映射；色值经
   color-mix 生成 hover/active 层次，亮暗主题通用（跟随 --bs-body-bg）。 */
[data-accent] {
  --acc: #0d6efd;
  --acc-rgb: 13, 110, 253;
}
[data-accent] .btn-primary {
  --bs-btn-bg: var(--acc);
  --bs-btn-border-color: var(--acc);
  --bs-btn-hover-bg: color-mix(in srgb, var(--acc) 85%, black);
  --bs-btn-hover-border-color: color-mix(in srgb, var(--acc) 85%, black);
  --bs-btn-active-bg: color-mix(in srgb, var(--acc) 75%, black);
  --bs-btn-active-border-color: color-mix(in srgb, var(--acc) 75%, black);
  --bs-btn-disabled-bg: var(--acc);
  --bs-btn-disabled-border-color: var(--acc);
}
[data-accent] .btn-outline-primary {
  --bs-btn-color: var(--acc);
  --bs-btn-border-color: var(--acc);
  --bs-btn-hover-bg: var(--acc);
  --bs-btn-hover-border-color: var(--acc);
  --bs-btn-active-bg: var(--acc);
  --bs-btn-active-border-color: var(--acc);
}
[data-accent] .progress-bar { background-color: var(--acc); }
[data-accent] .text-primary { color: var(--acc) !important; }
[data-accent] .bg-primary { background-color: var(--acc) !important; }
[data-accent] .link-primary { color: var(--acc) !important; }
[data-accent] .form-check-input:checked { background-color: var(--acc); border-color: var(--acc); }
[data-accent] .form-control:focus, [data-accent] .form-select:focus {
  border-color: color-mix(in srgb, var(--acc) 60%, white);
  box-shadow: 0 0 0 .25rem rgba(var(--acc-rgb), .25);
}
[data-accent] .nav-tabs .nav-link.active { color: var(--acc); }
[data-accent] .list-group-item.active { background-color: var(--acc); border-color: var(--acc); }
[data-accent] .spinner-border.text-primary { color: var(--acc) !important; }
.accent-dot {
  width: .85rem; height: .85rem; border-radius: 50%;
  display: inline-block; border: 2px solid transparent; flex: 0 0 auto;
}
.accent-dot.active { border-color: var(--bs-body-color); box-shadow: 0 0 0 2px var(--bs-body-bg) inset; }
</style>
