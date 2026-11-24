<script setup>
// T06219 新版工作区 Compare Bar：单行全局层 + 可展开路径编辑；低频动作收进 MoreMenu。
// 旧 ToolBar 仍保留在 App.vue 的 feature flag 回退分支，二者共用同一 store（切换不丢数据）。
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  state, triggerCompare, cancelCompare, isRunning, isUnpacking, aiAnyRunning,
  inferType, setTreePanelCollapsed, loadHlTheme, toast, toggleMobileTree, toggleMobileAi
} from '../store'
import { isElectron, pickPath } from '../lib/desktop-bridge'
import { loadAccentKey, applyAccent } from '../lib/accents'
import { t } from '../lib/i18n'
import PathBreadcrumb from './PathBreadcrumb.vue'
import MoreMenu from './MoreMenu.vue'

const props = defineProps({
  onOpenConfig: { type: Function, required: true },
  onOpenReport: { type: Function, required: true },
  onOpenHelp: { type: Function, default: null }
})

const editorOpen = ref(false)
const editorSide = ref('old')
const dragOver = ref(null)
const searchRef = ref(null)
const aiBusy = computed(() => aiAnyRunning())
const unpacking = computed(() => isUnpacking())
const running = computed(() => isRunning())
const compareDisabled = computed(() => aiBusy.value || state.busy || unpacking.value)
// 窄屏抽屉：选中文件后自动收起，避免覆盖层挡住 diff。
watch(() => state.activeKey, () => { if (state.mobileTreeOpen) state.mobileTreeOpen = false })
const sideLabel = (which) => {
  const folder = state.leftType === 'folder'
  if (which === 'old') return t(folder ? 'tb.side.oldDir' : 'tb.side.old')
  return t(folder ? 'tb.side.newDir' : 'tb.side.new')
}
const browseTip = (which) => t('tb.browse', { side: sideLabel(which) })

// 路径摘要：首行单行展示，超长时保留尾段（文件名/版本），展开后才显示完整面包屑与编辑入口。
function pathSummary(p) {
  if (!p) return t('cb.unset')
  const s = String(p).replace(/\\/g, '/').replace(/\/+$/, '')
  if (s.length <= 46) return s
  const tail = s.split('/').slice(-2).join('/')
  return tail.length <= 42 ? '…/' + tail : '…' + s.slice(-44)
}

const searchText = computed({
  get: () => (state.config && state.config.filterSearch) || '',
  set: (v) => {
    if (!state.config) return
    state.config.filterSearch = v
    if (v) setTreePanelCollapsed(false)
  }
})
function clearSearch() { searchText.value = ''; searchRef.value?.focus() }
function focusTreeFilter() { setTreePanelCollapsed(false); state.treeFilterFocusTick++ }

function assignPath(which, val) {
  if (which === 'old') { state.oldPath = val; state.leftType = inferType(val) }
  else state.newPath = val
}
function onPathUpdate(which, v) { if (which === 'old') state.leftType = inferType(v) }

// ===== 路径导航历史（与旧 ToolBar 同语义，供展开编辑态使用） =====
const MAX_HIST = 100
const histStack = ref([])
const histIndex = ref(-1)
const histLock = ref(false)
function snapshot() { return { old: state.oldPath, new: state.newPath } }
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
function applySnapshot(s) {
  histLock.value = true
  try {
    if (s.old !== undefined) { state.oldPath = s.old; state.leftType = inferType(s.old) }
    if (s.new !== undefined) state.newPath = s.new
  } finally { histLock.value = false }
}
function upOneLevel(p) {
  if (!p) return p
  const s = String(p).replace(/\\/g, '/').replace(/\/+$/, '')
  const idx = s.lastIndexOf('/')
  if (idx < 0) return p
  const parent = s.slice(0, idx)
  if (!parent) return '/'
  if (parent.length === 2 && parent[1] === ':') return parent + '/'
  return parent
}
function goUp(which) {
  if (aiBusy.value || running.value) return
  const cur = which === 'old' ? state.oldPath : state.newPath
  const next = upOneLevel(cur)
  if (next === cur) { toast('info', t('tb.atTop', { side: t(which === 'old' ? 'tb.side.old' : 'tb.side.new') })); return }
  pushHist()
  if (which === 'old') state.oldPath = next
  else state.newPath = next
}
function goUpBoth() {
  if (aiBusy.value || running.value) return
  const o = upOneLevel(state.oldPath)
  const n = upOneLevel(state.newPath)
  if (o === state.oldPath && n === state.newPath) { toast('info', t('tb.bothAtTop')); return }
  pushHist()
  state.oldPath = o
  state.newPath = n
}
const canBack = computed(() => histIndex.value > 0)
const canForward = computed(() => histIndex.value >= 0 && histIndex.value < histStack.value.length - 1)
function goBack() {
  if (!canBack.value || aiBusy.value || running.value) return
  histIndex.value--
  applySnapshot(histStack.value[histIndex.value])
}
function goForward() {
  if (!canForward.value || aiBusy.value || running.value) return
  histIndex.value++
  applySnapshot(histStack.value[histIndex.value])
}

async function browse(which) {
  const sel = await pickPath({ directory: state.leftType === 'folder' })
  if (sel) {
    pushHist()
    assignPath(which, Array.isArray(sel) ? sel[0] : sel)
    return
  }
  if (!isElectron()) toast('info', t('tb.browserHint'))
}
function onDragOver(which, e) {
  if (!isElectron()) return
  e.preventDefault()
  dragOver.value = which
}
function onDragLeave(which) { if (dragOver.value === which) dragOver.value = null }
function onDrop(which, e) {
  dragOver.value = null
  if (!isElectron()) { toast('info', t('tb.dropNoElectron')); return }
  e.preventDefault()
  const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]
  const p = file && file.path
  if (!p) { toast('warning', t('tb.dropReadFail')); return }
  pushHist()
  assignPath(which, p)
  if (state.oldPath && state.newPath) triggerCompare()
}
function openEditor(side) {
  editorSide.value = side
  editorOpen.value = true
  nextTick(() => {
    const el = document.querySelector(`.cb-edit-side[data-side="${side}"] .pb-edit`)
    if (el) el.focus()
  })
}
function toggleEditor() { editorOpen.value = !editorOpen.value }
function doCompare() {
  if (running.value) { cancelCompare(); return }
  triggerCompare()
}

// 新版 Compare Bar 挂载即恢复强调色与语法高亮主题（旧 ToolBar 未渲染时也不会丢设置）。
onMounted(() => {
  state.accent = loadAccentKey()
  applyAccent(state.accent)
  loadHlTheme()
  if (!state.oldPath || !state.newPath) editorOpen.value = true
})

// 焦点离开搜索框时保留关键词；Ctrl+K 仍由 App 全局快捷键聚焦树内过滤框。
watch(() => state.reviewCockpit, (v) => { if (!v) editorOpen.value = false })
</script>

<template>
  <header class="compare-bar" :class="{ 'cb-expanded': editorOpen }" data-testid="compare-bar">
    <div class="cb-main">
      <div class="cb-brand" :title="'BempDiff'">
        <span class="brand-mark"><i class="bi bi-file-earmark-diff"></i></span>
        <span class="cb-brand-text">BempDiff</span>
      </div>

      <div class="btn-group btn-group-sm cb-type" role="group" :aria-label="t('tb.inputType')">
        <button type="button" class="btn" :class="state.leftType === 'package' ? 'btn-primary' : 'btn-outline-secondary'"
                @click="state.leftType = 'package'" :disabled="aiBusy || running"
                :aria-label="t('tb.typePkg')" :title="t('tb.typePkg')"><i class="bi bi-file-earmark-zip"></i></button>
        <button type="button" class="btn" :class="state.leftType === 'folder' ? 'btn-primary' : 'btn-outline-secondary'"
                @click="state.leftType = 'folder'" :disabled="aiBusy || running"
                :aria-label="t('tb.typeDir')" :title="t('tb.typeDir')"><i class="bi bi-folder"></i></button>
      </div>

      <!-- <1080 单栏+抽屉：左右栏不再占宽度，用这两个入口按需覆盖打开 -->
      <div class="btn-group btn-group-sm cb-mobile-nav">
        <button type="button" class="btn" :class="state.mobileTreeOpen ? 'btn-primary' : 'btn-outline-secondary'"
                :aria-pressed="state.mobileTreeOpen" :aria-label="t('cb.treeDrawer')" :title="t('cb.treeDrawer')"
                @click="toggleMobileTree"><i class="bi bi-list-nested"></i></button>
        <button type="button" class="btn" :class="!state.aiPanelCollapsed ? 'btn-primary' : 'btn-outline-secondary'"
                :aria-pressed="!state.aiPanelCollapsed" :aria-label="t('cb.aiDrawer')" :title="t('cb.aiDrawer')"
                @click="toggleMobileAi"><i class="bi bi-layout-sidebar-inset-reverse"></i></button>
      </div>

      <div class="cb-paths">
        <div class="drop-zone cb-drop" :class="{ 'drop-active': dragOver === 'old' }"
             @dragover="onDragOver('old', $event)" @dragenter="onDragOver('old', $event)"
             @dragleave="onDragLeave('old')" @drop="onDrop('old', $event)">
          <button type="button" class="cb-path" :class="{ active: editorOpen && editorSide === 'old' }"
                  :title="state.oldPath ? state.oldPath + '\n' + t('cb.pathExpand') : t('cb.pathExpand')"
                  :aria-label="t('cb.pathExpand')" @click="openEditor('old')">
            <span class="cb-path-label">{{ sideLabel('old') }}</span>
            <span class="cb-path-text">{{ pathSummary(state.oldPath) }}</span>
            <i class="bi bi-pencil-square cb-path-edit"></i>
          </button>
        </div>
        <i class="bi bi-arrow-left-right cb-arrow" :title="t('cb.compareFlow')" aria-hidden="true"></i>
        <div class="drop-zone cb-drop" :class="{ 'drop-active': dragOver === 'new' }"
             @dragover="onDragOver('new', $event)" @dragenter="onDragOver('new', $event)"
             @dragleave="onDragLeave('new')" @drop="onDrop('new', $event)">
          <button type="button" class="cb-path" :class="{ active: editorOpen && editorSide === 'new' }"
                  :title="state.newPath ? state.newPath + '\n' + t('cb.pathExpand') : t('cb.pathExpand')"
                  :aria-label="t('cb.pathExpand')" @click="openEditor('new')">
            <span class="cb-path-label">{{ sideLabel('new') }}</span>
            <span class="cb-path-text">{{ pathSummary(state.newPath) }}</span>
            <i class="bi bi-pencil-square cb-path-edit"></i>
          </button>
        </div>
        <button type="button" class="btn btn-outline-secondary btn-sm cb-chevron" :aria-expanded="editorOpen"
                :aria-label="editorOpen ? t('cb.pathCollapse') : t('cb.pathExpand')"
                :title="editorOpen ? t('cb.pathCollapse') : t('cb.pathExpand')" @click="toggleEditor">
          <i class="bi" :class="editorOpen ? 'bi-chevron-up' : 'bi-chevron-down'"></i>
        </button>
      </div>

      <button v-if="!running" type="button" class="btn btn-primary btn-sm cb-run"
              :disabled="compareDisabled || !state.oldPath || !state.newPath"
              :aria-label="t('cb.compareAria')" :title="t('cb.compareAria')" @click="doCompare">
        <i class="bi bi-arrow-left-right"></i><span class="cb-run-label">{{ t('cb.compare') }}</span>
      </button>
      <button v-else type="button" class="btn btn-danger btn-sm cb-run"
              :aria-label="t('cb.stopAria')" :title="t('cb.stopAria')" @click="doCompare">
        <i class="bi bi-stop-fill"></i><span class="cb-run-label">{{ t('cb.stop') }}</span>
      </button>

      <label class="cb-search" :class="{ filled: !!searchText }" :title="t('cb.searchAria')">
        <i class="bi bi-search" aria-hidden="true"></i>
        <input ref="searchRef" v-model="searchText" type="search" :placeholder="t('cb.searchPlaceholder')"
               :aria-label="t('cb.searchAria')" autocomplete="off" spellcheck="false">
        <button v-if="searchText" type="button" class="cb-search-clear" :aria-label="t('cb.clearSearch')"
                :title="t('cb.clearSearch')" @click.prevent="clearSearch"><i class="bi bi-x-lg"></i></button>
        <button type="button" class="cb-search-jump" :aria-label="t('cb.focusTree')" :title="t('cb.focusTree')"
                @click.prevent="focusTreeFilter"><i class="bi bi-arrow-return-left"></i></button>
      </label>

      <MoreMenu :on-open-config="props.onOpenConfig" :on-open-report="props.onOpenReport" :on-open-help="props.onOpenHelp" />
    </div>

    <!-- 展开编辑：完整路径面包屑 + 浏览/上一层；全局导航（两侧上一层/后退/前进/收起） -->
    <div v-if="editorOpen" class="cb-editor">
      <div class="cb-edit-grid">
        <div v-for="(which, idx) in ['old', 'new']" :key="which" class="cb-edit-side" :data-side="which">
          <span class="cb-edit-label">{{ sideLabel(which) }}</span>
          <PathBreadcrumb v-if="which === 'old'" v-model="state.oldPath" :label="sideLabel('old')" :disabled="aiBusy || running"
                          @update:model-value="(v) => onPathUpdate('old', v)" />
          <PathBreadcrumb v-else v-model="state.newPath" :label="sideLabel('new')" :disabled="aiBusy || running"
                          @update:model-value="(v) => onPathUpdate('new', v)" />
          <button type="button" class="btn btn-outline-secondary btn-sm cb-edit-btn" :disabled="aiBusy || running"
                  :aria-label="browseTip(which)" :title="browseTip(which)" @click="browse(which)"><i class="bi bi-folder2-open"></i></button>
          <button type="button" class="btn btn-outline-secondary btn-sm cb-edit-btn" :disabled="aiBusy || running"
                  :aria-label="t('tb.upOne', { side: sideLabel(which) })" :title="t('tb.upOne', { side: sideLabel(which) })"
                  @click="goUp(which)"><i class="bi bi-arrow-up-circle"></i></button>
          <span v-if="idx === 0" class="cb-edit-hint" aria-hidden="true"></span>
        </div>
      </div>
      <div class="cb-editor-tools">
        <button type="button" class="btn btn-outline-secondary btn-sm" :disabled="aiBusy || running"
                :aria-label="t('tb.upBoth')" :title="t('tb.upBoth')" @click="goUpBoth"><i class="bi bi-arrow-up-square"></i></button>
        <div class="btn-group btn-group-sm" role="group" :aria-label="t('tb.navHist')">
          <button type="button" class="btn btn-outline-secondary" :disabled="!canBack || aiBusy || running"
                  :aria-label="t('tb.back')" :title="t('tb.back')" @click="goBack"><i class="bi bi-arrow-left"></i></button>
          <button type="button" class="btn btn-outline-secondary" :disabled="!canForward || aiBusy || running"
                  :aria-label="t('tb.forward')" :title="t('tb.forward')" @click="goForward"><i class="bi bi-arrow-right"></i></button>
        </div>
        <span class="text-secondary cb-editor-tip">{{ t('cb.editHint') }}</span>
        <button type="button" class="btn btn-outline-secondary btn-sm ms-auto" :aria-label="t('cb.pathCollapse')"
                :title="t('cb.pathCollapse')" @click="editorOpen = false"><i class="bi bi-x-lg"></i></button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.compare-bar {
  flex: 0 0 auto;
  background: var(--bs-tertiary-bg);
  border-bottom: 1px solid var(--bs-border-color);
  min-width: 0;
}
.cb-main {
  display: flex; align-items: center; gap: .38rem;
  min-width: 0; padding: .3rem .5rem; white-space: nowrap;
}
.cb-brand { display: flex; align-items: center; gap: .4rem; flex: 0 0 auto; min-width: 0; }
.cb-brand .brand-mark { width: 1.6rem; height: 1.6rem; border-radius: .35rem; font-size: .85rem; }
.cb-brand-text { font-weight: 700; letter-spacing: .01em; color: var(--bs-body-color); font-size: .8rem; }
.cb-type { flex: 0 0 auto; }
.cb-mobile-nav { display: none; flex: 0 0 auto; }
.cb-paths { display: flex; align-items: center; gap: .3rem; flex: 1 1 auto; min-width: 0; }
.cb-drop { min-width: 0; flex: 1 1 0; border-radius: .375rem; }
.cb-drop.drop-active { outline: 2px dashed var(--bs-primary); outline-offset: -1px; }
.cb-path {
  width: 100%; display: flex; align-items: center; gap: .35rem;
  height: 1.85rem; min-width: 0; padding: .1rem .4rem;
  border: 1px solid var(--bs-border-color); border-radius: .375rem;
  background: var(--bs-body-bg); color: var(--bs-body-color); text-align: left;
  transition: border-color .15s ease, box-shadow .15s ease;
}
.cb-path:hover, .cb-path.active { border-color: var(--bs-primary); }
.cb-path:focus-visible { border-color: var(--bs-primary); box-shadow: 0 0 0 .15rem rgba(var(--bs-primary-rgb), .18); outline: none; }
.cb-path-label { flex: 0 0 auto; font-size: .68rem; font-weight: 600; color: var(--bs-secondary-color); }
.cb-path-text { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--bs-font-monospace); font-size: .73rem; }
.cb-path-edit { flex: 0 0 auto; font-size: .65rem; color: var(--bs-secondary-color); opacity: .75; }
.cb-arrow { flex: 0 0 auto; font-size: .7rem; color: var(--bs-secondary-color); }
.cb-chevron { flex: 0 0 auto; padding-left: .3rem; padding-right: .3rem; }
.cb-run { flex: 0 0 auto; display: inline-flex; align-items: center; gap: .25rem; white-space: nowrap; }
.cb-run-label { font-size: .74rem; }
.cb-search {
  flex: 0 1 13rem; min-width: 6.5rem; display: flex; align-items: center; gap: .3rem;
  height: 1.85rem; padding: .1rem .35rem; border: 1px solid var(--bs-border-color);
  border-radius: .375rem; background: var(--bs-body-bg); color: var(--bs-secondary-color);
}
.cb-search:focus-within { border-color: var(--bs-primary); box-shadow: 0 0 0 .15rem rgba(var(--bs-primary-rgb), .16); }
.cb-search input { flex: 1 1 auto; min-width: 0; border: 0; outline: none; background: transparent; color: var(--bs-body-color); font-size: .74rem; }
.cb-search input::placeholder { color: var(--bs-secondary-color); opacity: .85; }
.cb-search-clear, .cb-search-jump { flex: 0 0 auto; border: 0; background: transparent; color: var(--bs-secondary-color); padding: 0 .18rem; line-height: 1; }
.cb-search-clear:hover, .cb-search-jump:hover { color: var(--bs-primary); }
.cb-search-jump { opacity: .6; }

.cb-editor { border-top: 1px solid var(--bs-border-color); background: var(--bs-body-bg); padding: .35rem .5rem .3rem; min-width: 0; }
.cb-edit-grid { display: grid; grid-template-columns: 1fr 1fr; gap: .4rem; min-width: 0; }
.cb-edit-side { display: flex; align-items: center; gap: .3rem; min-width: 0; }
.cb-edit-label { flex: 0 0 auto; font-size: .7rem; font-weight: 600; color: var(--bs-secondary-color); }
.cb-edit-side :deep(.pbc) { flex: 1 1 auto; min-width: 0; }
.cb-edit-btn { flex: 0 0 auto; padding-left: .35rem; padding-right: .35rem; }
.cb-editor-tools { display: flex; align-items: center; gap: .35rem; margin-top: .3rem; min-width: 0; }
.cb-editor-tip { font-size: .68rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1279px) {
  .cb-brand-text { display: none; }
  .cb-search { flex-basis: 10rem; }
  .cb-edit-grid { grid-template-columns: 1fr; }
  .cb-editor-tip { display: none; }
}
@media (max-width: 1079px) {
  .cb-brand { display: none; }
  .cb-mobile-nav { display: inline-flex; }
  .cb-search { flex-basis: 8rem; }
  .cb-run-label { display: none; }
}
@media (max-width: 900px) {
  .cb-search { flex-basis: 6.5rem; min-width: 5rem; }
  .cb-path-label { display: none; }
  .cb-paths { gap: .15rem; }
}
</style>
