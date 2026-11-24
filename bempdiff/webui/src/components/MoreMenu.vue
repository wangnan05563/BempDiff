<script setup>
// T06219 更多菜单：把报告/导出/AI/主题/会话/帮助/配置等低频动作收进 Compare Bar。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  state, generateReport, downloadExport, downloadDiffCsv, openExports, runAiClassify, startAiAnalysis,
  aiAnyRunning, isRunning, isUnpacking, cycleTheme, toast,
  loadCompareHistory, restoreCompareHistory, removeCompareHistory, clearCompareHistory,
  loadSavedSessions, saveNamedSession, applyNamedSession, removeNamedSession, setReviewCockpit,
  openLaunchpad
} from '../store'
import { ACCENTS, loadAccentKey, applyAccent } from '../lib/accents'
import { i18n, setLocale, t } from '../lib/i18n'

const props = defineProps({
  onOpenConfig: { type: Function, required: true },
  onOpenReport: { type: Function, required: true },
  onOpenHelp: { type: Function, default: null }
})

const show = ref(false)
const wrap = ref(null)
const historyOpen = ref(false)
const sessionName = ref('')
const aiBusy = computed(() => aiAnyRunning())
const running = computed(() => isRunning())
const unpacking = computed(() => isUnpacking())
const jarChanged = computed(() => (state.job && state.job.stats ? Number(state.job.stats.jarChanged || 0) : 0))
const jarDisabled = computed(() => !!state.job && jarChanged.value === 0)
const runningExports = computed(() => state.exportRecords.filter(r => r.status === 'running').length)
const accentName = (a) => (a && a.nameKey ? t(a.nameKey) : (a ? a.name : ''))

state.accent = loadAccentKey()
applyAccent(state.accent)

function onDocClick(e) {
  if (show.value && wrap.value && !wrap.value.contains(e.target)) show.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

function close() { show.value = false }
function after(fn) { close(); fn && fn() }
async function genReport() {
  close()
  await generateReport(false)
  if (state.reportMd) props.onOpenReport()
}
function doExport(granularity) {
  if (granularity === 'jar' && jarDisabled.value) { toast('warning', t('tb.gJarEmpty')); return false }
  close()
  downloadExport(granularity)
}
function openHistory() {
  close()
  loadCompareHistory()
  loadSavedSessions()
  historyOpen.value = true
}
function histTime(ts) {
  const d = new Date(ts)
  const p = x => String(x).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
function histName(p) {
  const s = String(p || '').replace(/\\/g, '/')
  return s.split('/').pop() || s
}
function onRestore(i) {
  if (restoreCompareHistory(i)) { historyOpen.value = false; toast('success', t('tb.histRestored')) }
}
function onRemoveHist(i) {
  if (window.confirm(t('tb.histRemoveConfirm'))) removeCompareHistory(i)
}
function onClearHist() {
  if (window.confirm(t('tb.histClearConfirm'))) clearCompareHistory()
}
function onSaveSession() {
  if (saveNamedSession(sessionName.value)) {
    toast('success', t('tb.sessionSaved', { name: sessionName.value.trim() }))
    sessionName.value = ''
  } else toast('error', t('tb.sessionSaveNeedName'))
}
function onApplySession(i) {
  if (applyNamedSession(i)) { historyOpen.value = false; toast('success', t('tb.sessionApplied')) }
}
function onRemoveSession(i) {
  if (window.confirm(t('tb.histRemoveConfirm'))) removeNamedSession(i)
}
function switchLegacy() {
  close()
  setReviewCockpit(false)
  toast('info', t('cb.legacyOn'))
}
function goLaunchpad() {
  close()
  openLaunchpad()
}
</script>

<template>
  <div class="position-relative mm-wrap" ref="wrap">
    <button type="button" class="btn btn-outline-secondary btn-sm cb-more" :class="{ active: show }"
            :aria-label="t('mm.aria')" :aria-expanded="show" :title="t('mm.aria')" @click="show = !show">
      <i class="bi bi-three-dots"></i>
    </button>

    <ul v-if="show" class="dropdown-menu dropdown-menu-end show mm-menu" role="menu" :aria-label="t('mm.aria')">
      <li class="mm-head">{{ t('mm.output') }}</li>
      <li><button class="dropdown-item" :disabled="!state.reportMd" @click="after(props.onOpenReport)">
        <i class="bi bi-filetype-md"></i> {{ t('mm.preview') }}</button></li>
      <li><button class="dropdown-item" :disabled="!state.job || state.reporting" @click="genReport">
        <i class="bi bi-file-earmark-text"></i> {{ t('mm.genReport') }}</button></li>
      <li><button class="dropdown-item" :disabled="!state.job || state.exporting || unpacking" @click="doExport('all')">
        <i class="bi bi-box-seam"></i> {{ t('mm.exportAll') }}</button></li>
      <li class="mm-inline">
        <button class="btn btn-sm btn-outline-secondary" :disabled="state.exporting || unpacking" @click="doExport('all')">{{ t('tb.gAll') }}</button>
        <button class="btn btn-sm btn-outline-secondary" :disabled="state.exporting || unpacking || jarDisabled" :title="jarDisabled ? t('tb.gJarEmpty') : ''" @click="doExport('jar')">{{ t('tb.gJar') }}</button>
        <button class="btn btn-sm btn-outline-secondary" :disabled="state.exporting || unpacking" @click="doExport('class')">{{ t('tb.gClass') }}</button>
      </li>
      <li><button class="dropdown-item" :disabled="!state.job || !state.job.tree || !state.job.tree.length" @click="after(downloadDiffCsv)">
        <i class="bi bi-filetype-csv"></i> {{ t('mm.exportCsv') }}</button></li>
      <li><button class="dropdown-item" @click="after(openExports)">
        <i class="bi bi-download"></i> {{ t('dl.title') }}
        <span v-if="runningExports" class="badge text-bg-primary ms-1">{{ runningExports }}</span></button></li>

      <li><hr class="dropdown-divider"></li>
      <li class="mm-head">{{ t('mm.ai') }}</li>
      <li><button class="dropdown-item" :disabled="!state.job || aiBusy || unpacking" @click="after(() => startAiAnalysis('risk'))">
        <i class="bi bi-cpu"></i> {{ t('mm.aiAnalyze') }}</button></li>
      <li><button class="dropdown-item" :disabled="!state.job || state.job.status !== 'DONE' || state.classifying || aiBusy" @click="after(runAiClassify)">
        <i class="bi bi-tags"></i> {{ state.classifying ? t('tb.classifying') : t('mm.aiClassify') }}</button></li>

      <li><hr class="dropdown-divider"></li>
      <li class="mm-head">{{ t('mm.view') }}</li>
      <li><button class="dropdown-item" @click="after(cycleTheme)">
        <i class="bi bi-circle-half"></i> {{ t('mm.theme') }}：{{ t('tb.theme.' + state.theme) }}</button></li>
      <li class="mm-accent">
        <span class="small text-secondary">{{ t('tb.accent') }}</span>
        <div class="d-flex gap-2 mt-1">
          <button v-for="a in ACCENTS" :key="a.key" type="button" class="accent-dot mm-dot"
                  :class="{ active: state.accent === a.key }" :style="{ background: a.color }"
                  :aria-label="t('tb.accentName', { name: accentName(a) })" :title="accentName(a)"
                  @click="state.accent = applyAccent(a.key).key"></button>
        </div>
      </li>
      <li><button class="dropdown-item" @click="setLocale(i18n.locale === 'zh-CN' ? 'en-US' : 'zh-CN')">
        <i class="bi bi-translate"></i> {{ i18n.locale === 'zh-CN' ? 'English' : '中文' }}</button></li>

      <li><hr class="dropdown-divider"></li>
      <li class="mm-head">{{ t('mm.session') }}</li>
      <li><button class="dropdown-item" @click="goLaunchpad">
        <i class="bi bi-house"></i> {{ t('mm.launchpad') }}</button></li>
      <li><button class="dropdown-item" @click="openHistory">
        <i class="bi bi-clock-history"></i> {{ t('mm.history') }}
        <span class="text-secondary ms-1">{{ state.compareHistory.length }}</span></button></li>

      <li><hr class="dropdown-divider"></li>
      <li><button v-if="props.onOpenHelp" class="dropdown-item" @click="after(props.onOpenHelp)">
        <i class="bi bi-question-lg"></i> {{ t('tb.help') }}</button></li>
      <li><button class="dropdown-item" @click="after(props.onOpenConfig)">
        <i class="bi bi-gear"></i> {{ t('tb.config') }}</button></li>
      <li><button class="dropdown-item" @click="switchLegacy">
        <i class="bi bi-layout-sidebar"></i> {{ t('mm.legacy') }}</button></li>
    </ul>

    <!-- 历史/命名会话：从更多菜单进入，保留旧工具栏的全部路径元数据能力 -->
    <div v-if="historyOpen" class="modal d-block mm-modal" tabindex="-1" role="dialog" :aria-label="t('mm.history')" @click.self="historyOpen = false">
      <div class="modal-dialog modal-dialog-centered modal-lg">
        <div class="modal-content">
          <div class="modal-header py-2">
            <h5 class="modal-title fs-6"><i class="bi bi-clock-history"></i> {{ t('mm.history') }}</h5>
            <button type="button" class="btn-close" :aria-label="t('common.close')" @click="historyOpen = false"></button>
          </div>
          <div class="modal-body" style="max-height:70vh;overflow:auto">
            <div class="d-flex gap-2 mb-2">
              <input v-model="sessionName" class="form-control form-control-sm" maxlength="60"
                     :placeholder="t('tb.sessionNamePh')" :aria-label="t('tb.sessionNamePh')" @keydown.enter="onSaveSession">
              <button class="btn btn-sm btn-outline-primary" :disabled="aiBusy" @click="onSaveSession">
                <i class="bi bi-plus-lg"></i> {{ t('tb.sessionSave') }}</button>
            </div>
            <div v-if="state.savedSessions.length" class="mb-3">
              <div class="small text-secondary mb-1">{{ t('tb.sessionTitle') }}</div>
              <div v-for="(s, i) in state.savedSessions" :key="'sv' + i + s.at" class="mm-row">
                <button class="btn btn-link btn-sm text-start flex-1 text-decoration-none" @click="onApplySession(i)">
                  <i class="bi bi-bookmark-star"></i> <b>{{ s.name }}</b>
                  <span class="text-secondary ms-1">{{ histTime(s.at) }}</span>
                  <span class="text-secondary d-block small text-truncate">{{ s.oldPath }} ⇄ {{ s.newPath }}</span>
                </button>
                <button class="btn btn-sm btn-outline-secondary border-0" :aria-label="t('tb.histRemove')" @click="onRemoveSession(i)"><i class="bi bi-x-lg"></i></button>
              </div>
            </div>
            <div class="d-flex align-items-center mb-1">
              <span class="small text-secondary">{{ t('tb.histCount', { n: state.compareHistory.length }) }}</span>
              <a v-if="state.compareHistory.length" href="#" class="ms-auto small" @click.prevent="onClearHist">{{ t('tb.clear') }}</a>
            </div>
            <div v-if="!state.compareHistory.length" class="text-secondary small py-2">{{ t('tb.histEmpty') }}</div>
            <div v-for="(h, i) in state.compareHistory" :key="h.oldPath + '|' + h.newPath + '|' + h.at" class="mm-row">
              <button class="btn btn-link btn-sm text-start flex-1 text-decoration-none" @click="onRestore(i)">
                <i class="bi" :class="h.leftType === 'folder' ? 'bi-folder' : 'bi-file-earmark-zip'"></i>
                {{ histName(h.oldPath) }} <i class="bi bi-arrow-left-right"></i> {{ histName(h.newPath) }}
                <span class="text-secondary ms-1">{{ histTime(h.at) }}</span>
              </button>
              <button class="btn btn-sm btn-outline-secondary border-0" :aria-label="t('tb.histRemove')" @click="onRemoveHist(i)"><i class="bi bi-x-lg"></i></button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mm-wrap { flex: 0 0 auto; }
.cb-more { flex: 0 0 auto; }
.mm-menu {
  position: absolute; right: 0; top: calc(100% + 4px); z-index: 1200;
  width: 16rem; max-height: min(70vh, 34rem); overflow: auto; padding: .25rem; font-size: .78rem;
}
.mm-head { padding: .3rem .6rem .15rem; font-size: .68rem; letter-spacing: .04em; color: var(--bs-secondary-color); text-transform: uppercase; }
.mm-menu .dropdown-item { display: flex; align-items: center; gap: .45rem; padding: .34rem .6rem; border-radius: .25rem; }
.mm-menu .dropdown-item i { width: 1rem; text-align: center; color: var(--bs-secondary-color); }
.mm-inline { display: flex; gap: .25rem; padding: .15rem .6rem .3rem; }
.mm-inline .btn { flex: 1 1 0; padding-left: .2rem; padding-right: .2rem; }
.mm-accent { padding: .15rem .6rem .35rem; }
.mm-dot { width: .95rem; height: .95rem; cursor: pointer; padding: 0; }
.mm-row { display: flex; align-items: center; gap: .25rem; border-bottom: 1px solid var(--bs-border-color); }
.mm-row:last-child { border-bottom: 0; }
.mm-modal { background: rgba(0, 0, 0, .45); }
@media (max-width: 1279px) {
  .mm-menu { width: 14rem; }
}
</style>
