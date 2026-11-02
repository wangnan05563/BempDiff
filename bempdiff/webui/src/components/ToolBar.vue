<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { state, triggerCompare, generateReport, downloadExport, runAiClassify, toast, applyTheme, cycleTheme, loadHlTheme, ingestShellPaths, inferType, startAiAnalysis, aiAnyRunning, isUnpacking, openExports, loadCompareHistory, restoreCompareHistory, removeCompareHistory, clearCompareHistory, loadSavedSessions, saveNamedSession, applyNamedSession, removeNamedSession } from '../store'
import { isElectron, pickPath } from '../lib/desktop-bridge'
import { ACCENTS, loadAccentKey, applyAccent } from '../lib/accents'
import { i18n, LOCALES, setLocale, t } from '../lib/i18n'
import PathBreadcrumb from './PathBreadcrumb.vue'
import Downloads from './Downloads.vue'

// 强调色名取 i18n：有 nameKey 走 t()，否则回退清单里的中文原文
const accentName = (a) => (a && a.nameKey ? t(a.nameKey) : (a ? a.name : ''))
// 侧别标签（老/新 × 包/目录）
const sideLabel = (which) => {
  const folder = state.leftType === 'folder'
  if (which === 'old') return t(folder ? 'tb.side.oldDir' : 'tb.side.oldPkg')
  return t(folder ? 'tb.side.newDir' : 'tb.side.newPkg')
}
const browseTip = (which) => t('tb.browse', { side: sideLabel(which) })

const props = defineProps({
  onOpenConfig: { type: Function, required: true },
  onOpenReport: { type: Function, required: true }
})

const showExport = ref(false)
// T02742：导出粒度（全量 all / 仅变化 jar / 仅变化 class），默认全量，供「导出差异资产」打包时透传后端。
const exportGranularity = ref('all')
// 拖拽高亮状态：'old' | 'new' | null
const dragOver = ref(null)

// 点击下拉框外部自动收缩：监听 document click，若点击目标不在导出按钮容器内则关闭。
const exportWrap = ref(null)
function onDocClick(e) {
  if (showExport.value && exportWrap.value && !exportWrap.value.contains(e.target)) {
    showExport.value = false
  }
  if (showHistory.value && historyWrap.value && !historyWrap.value.contains(e.target)) {
    showHistory.value = false
  }
  if (showAccent.value && accentWrap.value && !accentWrap.value.contains(e.target)) {
    showAccent.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

// ---------------- R5 比对会话历史（二期 T01463） ----------------
const showHistory = ref(false)
const historyWrap = ref(null)
function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value) { loadCompareHistory(); loadSavedSessions() }
}
function histTime(ts) {
  const d = new Date(ts)
  const p = (x) => String(x).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
function histName(p) {
  const s = String(p || '').replace(/\\/g, '/')
  return s.split('/').pop() || s
}
function onRestoreHist(i) {
  if (restoreCompareHistory(i)) {
    showHistory.value = false
    toast('success', t('tb.histRestored'))
  }
}
function onRemoveHist(i) {
  if (window.confirm(t('tb.histRemoveConfirm'))) removeCompareHistory(i)
}
function onClearHist() {
  if (window.confirm(t('tb.histClearConfirm'))) {
    clearCompareHistory()
    showHistory.value = false
  }
}

// ---------------- FR-010 命名保存常用对比任务 ----------------
const sessionName = ref('')
function onSaveSession() {
  if (saveNamedSession(sessionName.value)) {
    toast('success', t('tb.sessionSaved', { name: sessionName.value.trim() }))
    sessionName.value = ''
  } else {
    toast('error', t('tb.sessionSaveNeedName'))
  }
}
function onApplySession(i) {
  if (applyNamedSession(i)) {
    showHistory.value = false
    toast('success', t('tb.sessionApplied'))
  }
}
function onRemoveSession(i) {
  if (window.confirm(t('tb.histRemoveConfirm'))) removeNamedSession(i)
}

// 收敛：工具栏仅保留「生成纯文本/Markdown 报告（不调用 AI）」；AI 报告入口统一在 InfoPanel / ReportPreview
function onReport() { showExport.value = false; generateReport(false) }
function onExport() { showExport.value = false; downloadExport(exportGranularity.value) }
function onExports() { showExport.value = false; openExports() }
// 大包异步导出进行中记录数：在「下载管理」入口显示轻量角标提醒（不自动弹面板——方案 B，避免遮挡导出下拉）
const runningExports = computed(() => state.exportRecords.filter(r => r.status === 'running').length)
function onClassify() { runAiClassify() }
// 工具栏 AI 按钮：直接发起一个「整体风险分析」任务（非阻塞，结果落在下方控制台），不再弹阻塞模态。
function onAiAnalyze() { startAiAnalysis('risk') }

function assignPath(which, val) {
  if (which === 'old') { state.oldPath = val; state.leftType = inferType(val) }
  else state.newPath = val
}
// 面包屑点击/编辑提交后同步输入类型（与 assignPath 口径一致：old 侧按扩展名推断 package/folder）
function onPathUpdate(which, v) {
  if (which === 'old') state.leftType = inferType(v)
}

// ======================================================================
// BCompare 风格路径导航：单侧/两侧「向上一层」+ 后退/前进历史
// ======================================================================
const MAX_HIST = 100
const histStack = ref([])     // [{old, new}] 路径对快照（浏览器式历史）
const histIndex = ref(-1)     // 当前快照在栈中的位置
const histLock = ref(false)   // 恢复历史时禁止再次入栈（防回环）

function snapshot() { return { old: state.oldPath, new: state.newPath } }

/** 把当前路径对记入历史（与栈顶相同则忽略；前进分支上的旧未来被截断）。 */
function pushHist() {
  if (histLock.value) return
  const s = snapshot()
  const top = histStack.value[histIndex.value]
  if (top && top.old === s.old && top.new === s.new) return
  histStack.value = histStack.value.slice(0, histIndex.value + 1)
  histStack.value.push(s)
  if (histStack.value.length > MAX_HIST) histStack.value.shift()
  histIndex.value = histStack.value.length - 1
}

/** 恢复历史快照（不重新入栈）。 */
function applySnapshot(s) {
  histLock.value = true
  try {
    if (s.old !== undefined) { state.oldPath = s.old; state.leftType = inferType(s.old) }
    if (s.new !== undefined) { state.newPath = s.new }
  } finally { histLock.value = false }
}

/** 计算上一层目录路径（Windows / POSIX 通用）；已到根或无可上则原样返回。 */
function upOneLevel(p) {
  if (!p) return p
  const s = String(p).replace(/\\/g, '/').replace(/\/+$/, '')
  const idx = s.lastIndexOf('/')
  if (idx < 0) return p
  const parent = s.slice(0, idx)
  if (!parent) return '/'
  // Windows 盘符根：D: → D:/（保持可继续向上判定的形式）
  if (parent.length === 2 && parent[1] === ':') return parent + '/'
  return parent
}

function toastRoot(which) {
  toast('info', t('tb.atTop', { side: t(which === 'old' ? 'tb.side.old' : 'tb.side.new') }))
}

/** 单侧向上一层（保留输入类型不变，避免静默切换 package/folder）。 */
function goUp(which) {
  if (aiBusy.value) return
  const cur = which === 'old' ? state.oldPath : state.newPath
  const next = upOneLevel(cur)
  if (next === cur) { toastRoot(which); return }
  pushHist()
  if (which === 'old') state.oldPath = next
  else state.newPath = next
}

/** 两侧同时向上一层。 */
function goUpBoth() {
  if (aiBusy.value) return
  const o = upOneLevel(state.oldPath)
  const n = upOneLevel(state.newPath)
  if (o === state.oldPath && n === state.newPath) {
    toast('info', t('tb.bothAtTop'))
    return
  }
  pushHist()
  state.oldPath = o
  state.newPath = n
}

const canBack = computed(() => histIndex.value > 0)
const canForward = computed(() => histIndex.value >= 0 && histIndex.value < histStack.value.length - 1)

/** 后退：回到上一次浏览的路径。 */
function goBack() {
  if (!canBack.value || aiBusy.value) return
  histIndex.value--
  applySnapshot(histStack.value[histIndex.value])
}

/** 前进：回到后退之前的路径。 */
function goForward() {
  if (!canForward.value || aiBusy.value) return
  histIndex.value++
  applySnapshot(histStack.value[histIndex.value])
}

// 主题切换：整合进工具栏（原在 TopBar 内）。FR-004 三态循环：亮 → 暗 → 跟随系统 → 亮。
const THEME_META = {
  light:  { icon: 'bi-sun',         labelKey: 'tb.theme.light' },
  dark:   { icon: 'bi-moon-stars',  labelKey: 'tb.theme.dark' },
  system: { icon: 'bi-circle-half', labelKey: 'tb.theme.system' },
}
const themeIcon = computed(() => (THEME_META[state.theme] || THEME_META.light).icon)
const themeTip = computed(() => t((THEME_META[state.theme] || THEME_META.light).labelKey))

// R10 强调色（二期 T01475）：预设色盘选择，明暗主题正交叠加
state.accent = loadAccentKey()
applyAccent(state.accent) // 启动即恢复上次强调色
loadHlTheme() // FR-014：启动恢复上次语法高亮主题包
const showAccent = ref(false)
const accentWrap = ref(null)
function onPickAccent(key) {
  state.accent = applyAccent(key).key
  showAccent.value = false
}

// AI 分析进行中：禁用工具栏的对比按钮与路径输入框，避免干扰正在生成的对比结果。
// 复用 store.aiAnyRunning 单一判定源，避免双处维护漂移（评审 P2 #15）。
const aiBusy = computed(() => aiAnyRunning())
const unpacking = computed(() => isUnpacking())

// 桌面壳（Electron）：原生对话框直接拿绝对路径（免上传）；浏览器模式：提示手动输入服务器本机绝对路径。
async function browse(which) {
  const sel = await pickPath({ directory: state.leftType === 'folder' })
  if (sel) {
    pushHist() // 记入历史：后退可回到浏览前的路径
    assignPath(which, Array.isArray(sel) ? sel[0] : sel)
    return
  }
  if (!isElectron()) {
    toast('info', t('tb.browserHint'))
  }
}

// ---------- 拖拽即比（仅桌面壳可拿到真实路径；浏览器模式降级提示） ----------
function onDragOver(which, e) {
  if (!isElectron()) return
  e.preventDefault()
  dragOver.value = which
}
function onDragLeave(which) {
  if (dragOver.value === which) dragOver.value = null
}
function onDrop(which, e) {
  dragOver.value = null
  if (!isElectron()) {
    toast('info', t('tb.dropNoElectron'))
    return
  }
  e.preventDefault()
  const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]
  const p = file && file.path
  if (!p) {
    toast('warning', t('tb.dropReadFail'))
    return
  }
  pushHist() // 记入历史：后退可回到拖拽前的路径
  assignPath(which, p)
  // 两个路径齐备即自动开始比对（拖拽即比的「即」）
  if (state.oldPath && state.newPath) triggerCompare()
}

// 工具栏「开始比对」按钮
function doCompare() { triggerCompare() }
</script>

<template>
  <div class="bg-body-tertiary border-bottom px-2 py-1 d-flex flex-wrap align-items-center gap-2">
    <!-- 输入类型切换：图标 btn-group 分段控件 -->
    <div class="btn-group btn-group-sm" role="group" :aria-label="t('tb.inputType')">
      <button type="button" class="btn" :class="state.leftType === 'package' ? 'btn-primary' : 'btn-outline-secondary'"
              @click="state.leftType = 'package'" :disabled="aiBusy" :aria-label="t('tb.typePkg')" :title="t('tb.typePkg')">
        <i class="bi bi-file-earmark-zip"></i>
      </button>
      <button type="button" class="btn" :class="state.leftType === 'folder' ? 'btn-primary' : 'btn-outline-secondary'"
              @click="state.leftType = 'folder'" :disabled="aiBusy" :aria-label="t('tb.typeDir')" :title="t('tb.typeDir')">
        <i class="bi bi-folder"></i>
      </button>
    </div>

    <!-- R5 比对会话历史：跨启动持久化（仅路径元数据），点击恢复路径对，单项可删 -->
    <div class="position-relative" ref="historyWrap">
      <button class="btn btn-outline-secondary btn-sm" @click="toggleHistory" :disabled="aiBusy"
              :aria-label="t('tb.histTitle')" :title="t('tb.histTitle')">
        <i class="bi bi-clock-history"></i>
      </button>
      <ul class="dropdown-menu show py-1" v-if="showHistory"
          style="position:absolute;left:0;top:100%;z-index:1000;min-width:22rem;max-height:24rem;overflow:auto">
        <!-- FR-010 命名会话区（用户显式管理、不自动淘汰，与自动历史分池） -->
        <li class="px-2 py-1 d-flex justify-content-between align-items-center" style="font-size:.72rem;color:var(--bs-secondary-color)">
          <span>{{ t('tb.sessionTitle') }}</span>
        </li>
        <li class="px-2 pb-1 d-flex gap-1">
          <input v-model="sessionName" class="form-control form-control-sm py-0" style="font-size:.72rem"
                 :placeholder="t('tb.sessionNamePh')" maxlength="60"
                 @keydown.enter="onSaveSession" :aria-label="t('tb.sessionNamePh')">
          <button class="btn btn-sm btn-outline-primary py-0 px-2" style="font-size:.72rem" @click="onSaveSession"
                  :disabled="aiBusy" :aria-label="t('tb.sessionSave')" :title="t('tb.sessionSaveTip')">
            <i class="bi bi-plus-lg"></i>
          </button>
        </li>
        <li v-if="!state.savedSessions.length" class="px-2 py-1 text-secondary" style="font-size:.72rem">
          {{ t('tb.sessionEmpty') }}
        </li>
        <li v-for="(s, i) in state.savedSessions" :key="'sv' + i + s.at"
            class="dropdown-item d-flex align-items-center gap-2 text-wrap" style="font-size:.75rem">
          <span role="button" style="flex:1 1 auto;min-width:0" :title="s.name + '：' + s.oldPath + '  ⇄  ' + s.newPath" @click="onApplySession(i)">
            <i class="bi bi-bookmark-star" style="font-size:.7rem"></i>
            <strong style="font-size:.74rem">{{ s.name }}</strong>
            <span class="text-secondary ms-1" style="font-size:.68rem">{{ histTime(s.at) }}</span>
          </span>
          <i class="bi bi-x-lg" role="button" style="font-size:.7rem;opacity:.55" :title="t('tb.histRemove')" @click.stop="onRemoveSession(i)"></i>
        </li>
        <li><hr class="dropdown-divider"></li>
        <!-- R5 自动历史区（LRU 20 条） -->
        <li class="px-2 py-1 d-flex justify-content-between align-items-center" style="font-size:.72rem;color:var(--bs-secondary-color)">
          <span>{{ t('tb.histCount', { n: state.compareHistory.length }) }}</span>
          <a href="#" v-if="state.compareHistory.length" @click.prevent="onClearHist" style="font-size:.7rem">{{ t('tb.clear') }}</a>
        </li>
        <li v-if="!state.compareHistory.length" class="px-2 py-2 text-secondary" style="font-size:.75rem">
          {{ t('tb.histEmpty') }}
        </li>
        <li v-for="(h, i) in state.compareHistory" :key="h.oldPath + '|' + h.newPath + '|' + h.at"
            class="dropdown-item d-flex align-items-center gap-2 text-wrap" style="font-size:.75rem">
          <span role="button" style="flex:1 1 auto;min-width:0" :title="h.oldPath + '  ⇄  ' + h.newPath" @click="onRestoreHist(i)">
            <i class="bi" :class="h.leftType === 'folder' ? 'bi-folder' : 'bi-file-earmark-zip'" style="font-size:.7rem"></i>
            {{ histName(h.oldPath) }} <i class="bi bi-arrow-left-right" style="font-size:.65rem"></i> {{ histName(h.newPath) }}
            <span class="text-secondary ms-1" style="font-size:.68rem">{{ histTime(h.at) }}</span>
          </span>
          <i class="bi bi-x-lg" role="button" style="font-size:.7rem;opacity:.55" :title="t('tb.histRemove')" @click.stop="onRemoveHist(i)"></i>
        </li>
      </ul>
    </div>

    <!-- 老包/老目录：可拖入文件/目录（桌面壳）；面包屑点击层级即导航到该级目录 -->
    <div class="d-flex align-items-center gap-1 drop-zone" :class="{ 'drop-active': dragOver === 'old' }"
         style="max-width:320px"
         @dragover="onDragOver('old', $event)" @dragenter="onDragOver('old', $event)"
         @dragleave="onDragLeave('old')" @drop="onDrop('old', $event)">
      <span class="tb-label" :title="state.leftType === 'folder' ? t('tb.oldDirTip') : t('tb.oldPkgTip')">{{ state.leftType === 'folder' ? t('tb.side.oldDir') : t('tb.side.old') }}</span>
      <PathBreadcrumb v-model="state.oldPath" class="tb-crumb" :label="state.leftType === 'folder' ? t('tb.side.oldDir') : t('tb.side.oldPkg')" :disabled="aiBusy" @update:model-value="(v) => onPathUpdate('old', v)" />
      <button class="btn btn-outline-secondary btn-sm" @click="browse('old')" :disabled="aiBusy" :aria-label="browseTip('old')" :title="browseTip('old')"><i class="bi bi-folder2-open"></i></button>
      <button class="btn btn-outline-secondary btn-sm" @click="goUp('old')" :disabled="aiBusy" :aria-label="t('tb.upOne', { side: t('tb.side.old') })" :title="t('tb.upOne', { side: t('tb.side.old') })"><i class="bi bi-arrow-up-circle"></i></button>
    </div>
    <!-- 新包/新目录：可拖入文件/目录（桌面壳）；面包屑点击层级即导航到该级目录 -->
    <div class="d-flex align-items-center gap-1 drop-zone" :class="{ 'drop-active': dragOver === 'new' }"
         style="max-width:320px"
         @dragover="onDragOver('new', $event)" @dragenter="onDragOver('new', $event)"
         @dragleave="onDragLeave('new')" @drop="onDrop('new', $event)">
      <span class="tb-label" :title="state.leftType === 'folder' ? t('tb.newDirTip') : t('tb.newPkgTip')">{{ state.leftType === 'folder' ? t('tb.side.newDir') : t('tb.side.new') }}</span>
      <PathBreadcrumb v-model="state.newPath" class="tb-crumb" :label="state.leftType === 'folder' ? t('tb.side.newDir') : t('tb.side.newPkg')" :disabled="aiBusy" @update:model-value="(v) => onPathUpdate('new', v)" />
      <button class="btn btn-outline-secondary btn-sm" @click="browse('new')" :disabled="aiBusy" :aria-label="browseTip('new')" :title="browseTip('new')"><i class="bi bi-folder2-open"></i></button>
      <button class="btn btn-outline-secondary btn-sm" @click="goUp('new')" :disabled="aiBusy" :aria-label="t('tb.upOne', { side: t('tb.side.new') })" :title="t('tb.upOne', { side: t('tb.side.new') })"><i class="bi bi-arrow-up-circle"></i></button>
    </div>

    <!-- BCompare 风格路径导航：两侧同时向上 + 后退/前进历史 -->
    <button class="btn btn-outline-secondary btn-sm" @click="goUpBoth" :disabled="aiBusy" :aria-label="t('tb.upBoth')" :title="t('tb.upBoth')">
      <i class="bi bi-arrow-up-square"></i>
    </button>
    <div class="btn-group btn-group-sm" role="group" :aria-label="t('tb.navHist')">
      <button class="btn btn-outline-secondary" @click="goBack" :disabled="!canBack || aiBusy" :aria-label="t('tb.back')" :title="t('tb.back')">
        <i class="bi bi-arrow-left"></i>
      </button>
      <button class="btn btn-outline-secondary" @click="goForward" :disabled="!canForward || aiBusy" :aria-label="t('tb.forward')" :title="t('tb.forward')">
        <i class="bi bi-arrow-right"></i>
      </button>
    </div>

    <button class="btn btn-primary btn-sm" @click="doCompare" :disabled="aiBusy" :aria-label="t('tb.compare')" :title="t('tb.compare')"><i class="bi bi-arrow-left-right"></i></button>

    <div class="ms-auto d-flex gap-2">
      <button class="btn btn-outline-secondary btn-sm" @click="onOpenReport" :disabled="!state.reportMd" :aria-label="t('tb.reportBtn')" :title="t('tb.reportBtn')">
        <i class="bi bi-filetype-md"></i>
      </button>
      <button class="btn btn-outline-secondary btn-sm" @click="onAiAnalyze"
              :disabled="!state.job || aiBusy || unpacking"
              :aria-label="unpacking ? t('tb.aiUnpackBusy') : (aiBusy ? t('tb.aiBusy') : t('tb.aiIdle'))" :title="unpacking ? t('tb.aiUnpackBusy') : (aiBusy ? t('tb.aiBusy') : t('tb.aiIdle'))">
        <i class="bi bi-cpu"></i>
      </button>
      <!-- 与右侧「智能分析」一致：只保留图标，文字「智能分类」隐藏，避免工具栏被文字占宽；功能与悬停提示（title）不变。 -->
      <button class="btn btn-outline-secondary btn-sm" @click="onClassify"
              :disabled="!state.job || state.job.status !== 'DONE' || state.classifying || aiBusy"
              :aria-label="state.classifying ? t('tb.classifying') : (aiBusy ? t('tb.aiWait') : t('tb.classifyDesc'))" :title="state.classifying ? t('tb.classifying') : (aiBusy ? t('tb.aiWait') : t('tb.classifyDesc'))">
        <span v-if="state.classifying" class="spinner-border spinner-border-sm" role="status" :aria-label="t('tb.classifying')"></span>
        <i v-else class="bi bi-tags" role="img" :aria-label="t('term.smartClassify')" :title="t('term.smartClassify')"></i>
      </button>
      <div class="position-relative" ref="exportWrap">
        <button class="btn btn-outline-secondary btn-sm" @click="showExport = !showExport" :disabled="!state.job || state.exporting" :aria-label="state.exporting ? t('tb.exportBusy') : t('tb.export')" :title="state.exporting ? t('tb.exportBusy') : t('tb.export')">
          <i class="bi bi-file-earmark-arrow-down"></i>
        </button>
        <ul class="dropdown-menu dropdown-menu-end show py-1" v-if="showExport"
            style="position:absolute;right:0;top:100%;z-index:1000">
          <li><a class="dropdown-item" href="#" :class="{ disabled: unpacking }" @click.prevent="!unpacking && onReport()" :title="t('tb.genReportTip')"><i class="bi bi-filetype-md"></i> {{ t('info.generate') }}</a></li>
          <!-- AI 分析入口统一收敛到右上角「发起新的 AI 分析」（onAiAnalyze），此处不再重复提供「生成报告(AI)」 -->
          <!-- T02742：导出粒度选择（全量 / JAR / CLASS），默认全量；选中后再点「导出差异资产」按该粒度打包 -->
          <li class="px-3 py-2" style="min-width: 13.5rem">
            <div class="small text-secondary mb-1">{{ t('tb.exportGranularity') }}</div>
            <div class="btn-group btn-group-sm w-100" role="group" aria-label="export granularity">
              <input type="radio" class="btn-check" name="expGran" id="expGranAll" value="all" v-model="exportGranularity">
              <label class="btn btn-outline-secondary" for="expGranAll">{{ t('tb.gAll') }}</label>
              <input type="radio" class="btn-check" name="expGran" id="expGranJar" value="jar" v-model="exportGranularity">
              <label class="btn btn-outline-secondary" for="expGranJar">{{ t('tb.gJar') }}</label>
              <input type="radio" class="btn-check" name="expGran" id="expGranClass" value="class" v-model="exportGranularity">
              <label class="btn btn-outline-secondary" for="expGranClass">{{ t('tb.gClass') }}</label>
            </div>
          </li>
          <li><a class="dropdown-item" href="#" :class="{ disabled: state.exporting || unpacking }" @click.prevent="!state.exporting && !unpacking && onExport()" :title="t('tb.exportZipTip')">
            <span v-if="state.exporting" class="spinner-border spinner-border-sm align-middle me-1" role="status" aria-hidden="true"></span>
            <i v-else class="bi bi-box-seam"></i> {{ state.exporting
              ? (state.exportProgress && state.exportProgress.percent != null ? t('tb.exportingPct', { n: state.exportProgress.percent }) : t('tb.exporting'))
              : t('tb.exportZip') }}</a></li>
          <li><hr class="dropdown-divider"></li>
          <li><a class="dropdown-item" href="#" @click.prevent="onExports()" :title="t('tb.dlManageTip')"><i class="bi bi-download"></i> {{ t('dl.title') }}<span v-if="runningExports" class="badge text-bg-primary ms-1 align-middle">{{ runningExports }}</span></a></li>
          </ul>
      </div>
      <div v-if="state.exportProgress && state.exportProgress.total > 0"
           class="position-relative" style="width:7rem">
        <div class="progress" style="height:8px">
          <div class="progress-bar" role="progressbar"
               :style="{ width: state.exportProgress.percent + '%' }"
               :aria-valuenow="state.exportProgress.percent" aria-valuemin="0" aria-valuemax="100"></div>
        </div>
        <span class="text-secondary" style="font-size:.66rem">{{ state.exportProgress.percent }}% {{ state.exportProgress.etaText }}</span>
      </div>
      <button class="btn btn-outline-secondary btn-sm" @click="cycleTheme"
              :aria-label="themeTip" :title="themeTip">
        <i class="bi" :class="themeIcon"></i>
      </button>
      <!-- R10 强调色选择：色点下拉（明暗主题正交叠加） -->
      <div class="position-relative" ref="accentWrap">
        <button class="btn btn-outline-secondary btn-sm" @click="showAccent = !showAccent"
                :aria-label="t('tb.accent')" :title="t('tb.accentTip')">
          <i class="bi bi-palette"></i>
        </button>
        <ul class="dropdown-menu show py-1" v-if="showAccent"
            style="position:absolute;right:0;top:100%;z-index:1000;min-width:9.5rem" role="menu">
          <li v-for="a in ACCENTS" :key="a.key">
            <a href="#" class="dropdown-item d-flex align-items-center gap-2" style="font-size:.78rem"
               :aria-label="t('tb.accentName', { name: accentName(a) })" @click.prevent="onPickAccent(a.key)">
              <span class="accent-dot" :class="{ active: state.accent === a.key }"
                    :style="{ background: a.color }" :title="accentName(a)"></span>{{ accentName(a) }}
              <i v-if="state.accent === a.key" class="bi bi-check2 ms-auto text-success"></i>
            </a>
          </li>
        </ul>
      </div>
      <!-- R12 语言切换：中/EN（i18n 框架首批覆盖核心界面，渐进迁移） -->
      <button class="btn btn-outline-secondary btn-sm" @click="setLocale(i18n.locale === 'zh-CN' ? 'en-US' : 'zh-CN')"
              :aria-label="i18n.locale === 'zh-CN' ? t('tb.switchToEn') : t('tb.switchToZh')"
              :title="i18n.locale === 'zh-CN' ? t('tb.switchToEn') : t('tb.switchToZh')"
              style="font-size:.72rem;font-weight:600">{{ i18n.locale === 'zh-CN' ? 'EN' : '中' }}</button>
      <button class="btn btn-outline-secondary btn-sm" @click="onOpenConfig" :aria-label="t('tb.config')" :title="t('tb.config')"><i class="bi bi-gear"></i></button>
    </div>
    <!-- 下载管理面板：与导出按钮同级位置，集中展示导出记录（含异步大包导出） -->
    <Downloads v-if="state.exportsOpen" />
  </div>
</template>

<style scoped>
/* 包路径输入区：label + 面包屑 + 操作按钮（flex 布局替代 input-group，容纳可横向滚动的面包屑） */
.tb-label {
  font-size: .72rem;
  font-weight: 600;
  color: var(--bs-secondary-color);
  white-space: nowrap;
  flex: 0 0 auto;
}
.tb-crumb { flex: 1 1 auto; min-width: 0; }
.drop-zone.drop-active { outline: 2px dashed var(--bs-primary); outline-offset: -2px; }
</style>
