<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { state, activeTab, setInsightTab, loadInsights, jumpToHunk, setAiPanelCollapsed, applyAiPanelResponsive, reviewNotesOf, reviewBookmarksOf, toast, fmtBytes } from '../store'
import { t } from '../lib/i18n'
import AiConsole from './AiConsole.vue'

const props = defineProps({ panelWidth: { type: Number, default: null } })
const tab = computed({
  get: () => state.insightTab || 'overview',
  set: (v) => setInsightTab(v)
})
function readFilter(key, fallback) {
  try { return localStorage.getItem(key) || fallback } catch (_) { return fallback }
}
function saveFilter(key, value) {
  try { localStorage.setItem(key, value) } catch (_) { /* 静默 */ }
}
const compatQuery = ref(readFilter('bempdiff.insightCompatQuery', ''))
const compatFilter = ref(readFilter('bempdiff.insightCompatFilter', 'all'))
const impactQuery = ref(readFilter('bempdiff.insightImpactQuery', ''))
const impactFilter = ref(readFilter('bempdiff.insightImpactFilter', 'all'))
function setCompatQuery(v) { compatQuery.value = v; saveFilter('bempdiff.insightCompatQuery', v) }
function setCompatFilter(v) { compatFilter.value = v; saveFilter('bempdiff.insightCompatFilter', v) }
function setImpactQuery(v) { impactQuery.value = v; saveFilter('bempdiff.insightImpactQuery', v) }
function setImpactFilter(v) { impactFilter.value = v; saveFilter('bempdiff.insightImpactFilter', v) }
const ins = computed(() => state.insights || null)
const job = computed(() => state.job || null)
const rv = computed(() => (ins.value && ins.value.reviewSummary) || state.reviewSummary || {})
const compat = computed(() => (ins.value && ins.value.apiCompat) || { items: [], total: 0, truncated: false })
const impact = computed(() => (ins.value && ins.value.impact) || { items: [], total: 0, truncated: false })
const ast = computed(() => (ins.value && ins.value.astSummary) || {})
const aiOn = computed(() => !!(state.config && state.config.aiEnabled))
const selected = computed(() => activeTab() || null)
const selectedKey = computed(() => (selected.value && selected.value.key) || '')
const selectedNode = computed(() => (selected.value && selected.value.node) || null)
const selectedNotes = computed(() => selectedKey.value ? reviewNotesOf(selectedKey.value) : [])
const selectedBookmarks = computed(() => selectedKey.value ? reviewBookmarksOf(selectedKey.value) : [])

const RISK_WEIGHT = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, BREAKING: 4, ADDITIVE: 2, NONE: 0, REMOVED_API: 4, SIGNATURE_CHANGED: 3, ADDED_API: 1 }
const RISK_CLS = { CRITICAL: 'risk-critical', HIGH: 'risk-high', MEDIUM: 'risk-medium', LOW: 'risk-low', BREAKING: 'risk-critical', ADDITIVE: 'risk-low', NONE: 'risk-none' }
function compatCls(level) {
  if (level === 'REMOVED_API') return 'risk-critical'
  if (level === 'SIGNATURE_CHANGED') return 'risk-high'
  return 'risk-low'
}

onMounted(() => {
  applyAiPanelResponsive()
  window.addEventListener('resize', applyAiPanelResponsive)
  if (state.job && !state.insights) loadInsights(state.job.jobId).catch(() => {})
})
onUnmounted(() => window.removeEventListener('resize', applyAiPanelResponsive))

function refresh() {
  if (!state.job) return
  loadInsights(state.job.jobId, { limit: 500 }).catch(() => {})
}

function riskLabel(r) {
  return t('in.risk.' + (r || 'LOW'))
}
function changeLabel(k) {
  return t('in.change.' + k)
}
function pct(n) {
  return Math.max(0, Math.min(100, Number(n) || 0))
}
function num(n) {
  return (Number(n) || 0).toLocaleString()
}
function evidenceText(it) {
  const list = (it && it.evidence) || []
  if (!list.length) return t('in.inferred')
  return String(list[0]).replace(/^javac-signature:/, '')
}
function confidenceLabel(it) {
  const c = (it && it.confidence) || 'inferred'
  return t('in.confidence.' + c) === 'in.confidence.' + c ? t('in.inferred') : t('in.confidence.' + c)
}
function fileBase(key) {
  if (!key) return t('in.noFile')
  const s = String(key).replace(/\\/g, '/')
  const parts = s.split('/')
  return parts[parts.length - 1] || s
}
function resolveFileKey(item) {
  if (!item || !job.value) return ''
  if (item.fileKey) return item.fileKey
  const cls = item.className || ''
  if (!cls) return ''
  const base = cls.replace(/\./g, '/')
  const suffixJava = base + '.java'
  const suffixClass = base + '.class'
  const tree = (job.value.tree || []).map(n => n && n.key).filter(Boolean)
  const hit = tree.find(k => k === suffixJava || k.endsWith('/' + suffixJava)
    || k === suffixClass || k.endsWith('/' + suffixClass))
  return hit || ''
}
function openItem(item) {
  const key = resolveFileKey(item)
  if (!key) {
    toast('warning', t('in.jumpFail'))
    return
  }
  jumpToHunk(key, item && item.hunkIndex ? item.hunkIndex : 0)
}

// Top 5：高优先级影响面 + 不兼容 API，按风险权重排序（同分按文件名稳定排序）。
const topRisks = computed(() => {
  const out = []
  for (const item of (impact.value.items || [])) {
    if (!item || !item.risk) continue
    out.push({
      id: item.id, risk: item.risk, title: item.className, detail: item.reason || item.status,
      fileKey: item.fileKey || '', kind: 'impact'
    })
  }
  for (const item of (compat.value.items || [])) {
    if (!item || item.level !== 'REMOVED_API' && item.level !== 'SIGNATURE_CHANGED') continue
    out.push({
      id: item.id, risk: item.level === 'REMOVED_API' ? 'CRITICAL' : 'HIGH',
      title: item.className, detail: `${changeLabel(item.level)} · ${item.api || item.member || ''}`,
      fileKey: item.fileKey || '', kind: 'compat'
    })
  }
  return out.sort((a, b) => (RISK_WEIGHT[b.risk] || 0) - (RISK_WEIGHT[a.risk] || 0) || a.title.localeCompare(b.title)).slice(0, 5)
})

const suggested = computed(() => {
  const out = []
  for (const item of topRisks.value) {
    if (item.risk !== 'HIGH' && item.risk !== 'CRITICAL') continue
    out.push({ ...item, why: item.kind === 'compat' ? t('in.compat.title') : t('in.impact.title') })
  }
  return out.slice(0, 4)
})

const typeDist = computed(() => {
  const s = (job.value && job.value.stats) || {}
  const total = (Number(s.added) || 0) + (Number(s.deleted) || 0) + (Number(s.modified) || 0)
  return [
    { key: 'ADDED', n: Number(s.added) || 0, cls: 'bar-added' },
    { key: 'DELETED', n: Number(s.deleted) || 0, cls: 'bar-deleted' },
    { key: 'MODIFIED', n: Number(s.modified) || 0, cls: 'bar-modified' },
    { key: 'FORMAT', n: Number(ast.value.ignoredChanges) || 0, cls: 'bar-format' }
  ].map(x => ({ ...x, percent: total > 0 ? Math.round(x.n * 100 / total) : 0 }))
})
const classDist = computed(() => {
  const counts = { CONFIG: 0, JAR: 0, CLASS: 0, OTHER: 0 }
  for (const n of (job.value && job.value.tree) || []) {
    const fc = (n && n.fileClass) || 'OTHER'
    if (fc === 'CONFIG') counts.CONFIG++
    else if (fc === 'JAR') counts.JAR++
    else if (fc === 'CLASS') counts.CLASS++
    else counts.OTHER++
  }
  const total = Math.max(1, counts.CONFIG + counts.JAR + counts.CLASS + counts.OTHER)
  return [
    { key: 'CONFIG', label: '配置', n: counts.CONFIG, percent: Math.round(counts.CONFIG * 100 / total) },
    { key: 'JAR', label: '依赖', n: counts.JAR, percent: Math.round(counts.JAR * 100 / total) },
    { key: 'CLASS', label: '类', n: counts.CLASS, percent: Math.round(counts.CLASS * 100 / total) },
    { key: 'OTHER', label: '其它', n: counts.OTHER, percent: Math.round(counts.OTHER * 100 / total) }
  ]
})

const breakingCount = computed(() => Number((ins.value && ins.value.summary && ins.value.summary.breaking) || 0))
const highRiskCount = computed(() => {
  const im = impact.value
  return (Number(im.critical) || 0) + (Number(im.high) || 0)
})
const changedCount = computed(() => {
  const s = (job.value && job.value.stats) || {}
  return (Number(s.added) || 0) + (Number(s.deleted) || 0) + (Number(s.modified) || 0)
})
const hasAny = computed(() => !!(job.value && job.value.status === 'DONE'))
const compatRows = computed(() => {
  const q = compatQuery.value.trim().toLowerCase()
  return (compat.value.items || [])
    .filter(it => {
      if (compatFilter.value === 'breaking' && it.level !== 'REMOVED_API' && it.level !== 'SIGNATURE_CHANGED') return false
      if (compatFilter.value === 'additive' && it.level !== 'ADDED_API') return false
      if (!q) return true
      return [it.className, it.member, it.signature, it.api].join(' ').toLowerCase().includes(q)
    })
    .slice()
    .sort((a, b) => (RISK_WEIGHT[b.level] || 0) - (RISK_WEIGHT[a.level] || 0))
})
const impactRows = computed(() => {
  const q = impactQuery.value.trim().toLowerCase()
  return (impact.value.items || [])
    .filter(it => {
      if (impactFilter.value === 'high' && it.risk !== 'HIGH' && it.risk !== 'CRITICAL') return false
      if (impactFilter.value === 'medium' && it.risk !== 'MEDIUM') return false
      if (impactFilter.value === 'low' && it.risk !== 'LOW') return false
      if (!q) return true
      return [it.className, it.reason, ...(it.callers || []).map(c => c.className)].join(' ').toLowerCase().includes(q)
    })
    .slice()
    .sort((a, b) => (RISK_WEIGHT[b.risk] || 0) - (RISK_WEIGHT[a.risk] || 0))
})
const notesPreview = computed(() => selectedNotes.value.slice(0, 6))
const bookmarksPreview = computed(() => selectedBookmarks.value.slice(0, 6))
</script>

<template>
  <aside class="col-ai insight-panel" :class="{ collapsed: state.aiPanelCollapsed }"
         :style="props.panelWidth != null && !state.aiPanelCollapsed ? { width: props.panelWidth + 'px' } : undefined"
         aria-label="洞察面板">
    <div v-if="!state.aiPanelCollapsed" class="pane-head">
      <i class="bi bi-lightbulb" role="img" :aria-label="t('in.title')"></i> {{ t('in.title') }}
      <button class="btn btn-sm btn-outline-secondary border-0 ms-auto px-1 py-0" :aria-label="t('in.refresh')" :title="t('in.refresh')"
              @click="refresh"><i class="bi bi-arrow-clockwise"></i></button>
      <button class="btn btn-sm btn-outline-secondary border-0 px-1 py-0" :aria-label="t('info.collapse')" :title="t('info.collapse')"
              @click="setAiPanelCollapsed(true)"><i class="bi bi-layout-sidebar-inset-reverse"></i></button>
    </div>
    <div v-else class="ai-collapsed-bar" :title="t('info.expand')" @click="setAiPanelCollapsed(false)">
      <i class="bi bi-chevron-left"></i>
      <i class="bi bi-lightbulb" role="img" :aria-label="t('in.title')"></i>
    </div>

    <template v-if="!state.aiPanelCollapsed">
      <ul class="nav nav-tabs insight-tabs px-2 pt-2" role="tablist">
        <li class="nav-item" v-for="tb in ['overview','compat','impact','evidence','console']" :key="tb">
          <button type="button" class="nav-link py-1" :class="{ active: tab === tb }"
                  role="tab" :aria-selected="tab === tb" @click="tab = tb">
            {{ t('in.tab.' + tb) }}
            <span v-if="tb === 'compat' && breakingCount" class="in-tab-badge">{{ breakingCount }}</span>
            <span v-else-if="tb === 'impact' && highRiskCount" class="in-tab-badge danger">{{ highRiskCount }}</span>
          </button>
        </li>
      </ul>

      <div v-if="tab !== 'console'" class="in-body">
        <div v-if="!hasAny" class="in-empty">
          <i class="bi bi-graph-up"></i>
          <p>{{ t('in.empty') }}</p>
        </div>
        <div v-else-if="tab !== 'overview' && state.insightsLoading && !ins" class="in-empty">
          <span class="spinner-border spinner-border-sm"></span>
          <p>{{ t('in.loading') }}</p>
        </div>
        <div v-else-if="state.insightsError" class="in-error">
          <i class="bi bi-exclamation-triangle"></i>
          <p>{{ t('in.error', { err: state.insightsError }) }}</p>
          <button type="button" class="btn btn-sm btn-outline-primary" @click="refresh">{{ t('in.refresh') }}</button>
        </div>

        <!-- 总览 -->
        <div v-else-if="tab === 'overview'" class="in-overview">
          <div class="in-kpis">
            <div class="in-kpi"><span>{{ t('in.kpi.files') }}</span><b>{{ num(changedCount) }}</b><small class="in-kpi-sub">{{ num(ast.normalizedFiles || 0) }} 个归一化</small></div>
            <div class="in-kpi"><span>{{ t('in.kpi.breaking') }}</span><b class="text-danger">{{ num(breakingCount) }}</b><small class="in-kpi-sub">{{ num((ins && ins.summary && ins.summary.removed) || 0) }} 移除 / {{ num((ins && ins.summary && ins.summary.signatureChanged) || 0) }} 签名</small></div>
            <div class="in-kpi"><span>{{ t('in.kpi.highRisk') }}</span><b class="text-warning">{{ num(highRiskCount) }}</b><small class="in-kpi-sub">{{ num((impact && impact.critical) || 0) }} 严重</small></div>
            <div class="in-kpi"><span>{{ t('in.kpi.review') }}</span><b class="text-success">{{ pct(rv.filePercent) }}%</b><small class="in-kpi-sub">{{ num(rv.reviewed || 0) }}/{{ num(rv.total || 0) }} 文件</small></div>
          </div>

          <div class="in-section">
            <div class="in-section-head"><h4><i class="bi bi-exclamation-diamond"></i> {{ t('in.riskTop') }}</h4></div>
            <div v-if="!topRisks.length" class="in-muted">{{ t('in.noRisk') }}</div>
            <ul v-else class="in-risk-list">
              <li v-for="it in topRisks" :key="it.kind + it.id">
                <button type="button" class="in-risk-row" @click="openItem(it)">
                  <span class="in-risk-badge" :class="RISK_CLS[it.risk]">{{ riskLabel(it.risk) }}</span>
                  <span class="in-risk-text"><b>{{ it.title }}</b><small>{{ it.detail }}</small></span>
                  <i class="bi bi-chevron-right"></i>
                </button>
              </li>
            </ul>
          </div>

          <div class="in-section">
            <div class="in-section-head"><h4><i class="bi bi-pie-chart"></i> {{ t('in.typeDist') }}</h4></div>
            <div class="in-bars">
              <div v-for="b in typeDist" :key="b.key" class="in-bar-row">
                <span>{{ b.key === 'FORMAT' ? t('in.evidence.ignored') : t('info.status.' + b.key) }}</span>
                <div class="in-bar"><i :class="b.cls" :style="{ width: b.percent + '%' }"></i></div>
                <b>{{ num(b.n) }}</b>
              </div>
            </div>
            <div class="in-bars in-bars-thin">
              <div v-for="b in classDist" :key="b.key" class="in-bar-row">
                <span>{{ b.label }}</span>
                <div class="in-bar"><i class="bar-type" :style="{ width: b.percent + '%' }"></i></div>
                <b>{{ num(b.n) }}</b>
              </div>
            </div>
          </div>

          <div class="in-section">
            <div class="in-section-head"><h4><i class="bi bi-check2-square"></i> {{ t('in.suggested') }}</h4></div>
            <div v-if="!suggested.length" class="in-muted">{{ t('in.noSuggest') }}</div>
            <ul v-else class="in-suggest-list">
              <li v-for="it in suggested" :key="'s' + it.id">
                <button type="button" @click="openItem(it)"><i class="bi bi-arrow-return-right"></i> {{ it.title }} <small>{{ it.why }}</small></button>
              </li>
            </ul>
          </div>

          <div v-if="selectedNode" class="in-section in-file-card">
            <div class="in-section-head"><h4><i class="bi bi-file-earmark-code"></i> {{ t('in.selectedFile') }}</h4></div>
            <div class="in-file-row"><b>{{ fileBase(selectedKey) }}</b><span>{{ selectedNode.status }} · {{ selectedNode.fileClass }} · {{ fmtBytes(selectedNode.size) }}</span></div>
          </div>
        </div>

        <!-- 兼容 -->
        <div v-else-if="tab === 'compat'" class="in-list-page">
          <div class="in-page-head"><h3>{{ t('in.compat.title') }}</h3><p>{{ t('in.compat.desc') }}</p></div>
          <div class="in-tools">
            <div class="in-seg">
              <button type="button" :class="{ active: compatFilter === 'all' }" @click="setCompatFilter('all')">{{ t('in.filter.all') }}</button>
              <button type="button" :class="{ active: compatFilter === 'breaking' }" @click="setCompatFilter('breaking')">{{ t('in.filter.breaking') }}</button>
              <button type="button" :class="{ active: compatFilter === 'additive' }" @click="setCompatFilter('additive')">{{ t('in.filter.additive') }}</button>
            </div>
            <input :value="compatQuery" type="search" class="in-search" data-test="insight-compat-search"
                   :placeholder="t('in.search')" @input="setCompatQuery($event.target.value)" />
          </div>
          <div v-if="compat.truncated" class="in-truncated">{{ t('in.truncated', { n: compat.items.length, total: compat.total }) }}</div>
          <div v-if="!compatRows.length" class="in-empty in-empty-small"><i class="bi bi-check2-circle"></i><p>{{ t('in.noCompat') }}</p></div>
          <div v-else class="in-table-wrap">
            <table class="in-table">
              <thead><tr><th>{{ t('in.level') }}</th><th>{{ t('in.className') }}</th><th>{{ t('in.api') }}</th><th>{{ t('in.confidence') }}</th><th>{{ t('in.evidence') }}</th><th></th></tr></thead>
              <tbody>
                <tr v-for="it in compatRows" :key="it.id">
                  <td><span class="in-risk-badge" :class="compatCls(it.level)">{{ changeLabel(it.level) }}</span></td>
                  <td class="in-mono in-ellipsis" :title="it.className">{{ it.className }}</td>
                  <td class="in-mono in-ellipsis" :title="it.signature || it.api">{{ it.signature || it.member || it.api }}</td>
                  <td class="in-confidence">{{ confidenceLabel(it) }}</td>
                  <td class="in-evidence in-ellipsis" :title="evidenceText(it)">{{ evidenceText(it) }}</td>
                  <td><button type="button" class="in-link" @click="openItem(it)">{{ t('in.jump') }}</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 影响 -->
        <div v-else-if="tab === 'impact'" class="in-list-page">
          <div class="in-page-head"><h3>{{ t('in.impact.title') }}</h3><p>{{ t('in.impact.desc') }}</p></div>
          <div class="in-tools">
            <div class="in-seg">
              <button type="button" :class="{ active: impactFilter === 'all' }" @click="setImpactFilter('all')">{{ t('in.filter.all') }}</button>
              <button type="button" :class="{ active: impactFilter === 'high' }" @click="setImpactFilter('high')">{{ t('in.risk.HIGH') }}</button>
              <button type="button" :class="{ active: impactFilter === 'medium' }" @click="setImpactFilter('medium')">{{ t('in.risk.MEDIUM') }}</button>
            </div>
            <input :value="impactQuery" type="search" class="in-search" data-test="insight-impact-search"
                   :placeholder="t('in.search')" @input="setImpactQuery($event.target.value)" />
          </div>
          <div v-if="impact.truncated" class="in-truncated">{{ t('in.truncated', { n: impact.items.length, total: impact.total }) }}</div>
          <div v-if="!impactRows.length" class="in-empty in-empty-small"><i class="bi bi-diagram-3"></i><p>{{ t('in.noImpact') }}</p></div>
          <div v-else class="in-impact-list">
            <article v-for="it in impactRows" :key="it.id" class="in-impact-card">
              <div class="in-impact-head">
                <span class="in-risk-badge" :class="RISK_CLS[it.risk]">{{ riskLabel(it.risk) }}</span>
                <b class="in-ellipsis" :title="it.className">{{ it.className }}</b>
                <span class="in-status-pill">{{ it.status }}</span>
              </div>
              <p class="in-impact-reason">{{ it.reason }}</p>
              <div v-if="it.callers && it.callers.length" class="in-callers">
                <i class="bi bi-arrow-left-right"></i> {{ t('in.callers') }}:
                <span v-for="c in it.callers.slice(0, 3)" :key="c.fileKey" class="in-caller">{{ c.className }}</span>
                <span v-if="it.callers.length > 3">+{{ it.callers.length - 3 }}</span>
              </div>
              <div class="in-impact-foot">
                <span><i class="bi bi-shield-check"></i> {{ confidenceLabel(it) }} · {{ evidenceText(it) }}</span>
                <span class="in-not-provided">{{ t('in.entries') }} / {{ t('in.tests') }}：{{ t('in.notProvided') }}</span>
                <button type="button" class="in-link" @click="openItem(it)">{{ t('in.jump') }}</button>
              </div>
            </article>
          </div>
        </div>

        <!-- 证据 -->
        <div v-else-if="tab === 'evidence'" class="in-list-page">
          <div class="in-page-head"><h3>{{ t('in.evidence.title') }}</h3><p>{{ t('in.evidence.desc') }}</p></div>
          <div class="in-evidence-grid">
            <div class="in-ev-card"><span>{{ t('in.evidence.reviewed') }}</span><b>{{ num(rv.reviewed || 0) }}/{{ num(rv.total || 0) }}</b><div class="in-progress"><i :style="{ width: pct(rv.filePercent) + '%' }"></i></div></div>
            <div class="in-ev-card"><span>{{ t('in.evidence.notes') }}</span><b>{{ num(rv.notes || 0) }}</b><small>{{ t('in.evidence.bookmarks') }} {{ num(rv.bookmarks || 0) }}</small></div>
            <div class="in-ev-card"><span>{{ t('in.evidence.ast') }}</span><b>{{ num(ast.normalizedFiles || 0) }}</b><small>{{ t('in.evidence.fallback') }} {{ num(ast.fallbackFiles || 0) }}</small></div>
            <div class="in-ev-card"><span>{{ t('in.evidence.ignored') }}</span><b>{{ num(ast.ignoredChanges || 0) }}</b><small>{{ t('in.evidence.normalized') }} {{ num(ast.normalizedFiles || 0) }}</small></div>
          </div>
          <div v-if="selectedKey" class="in-section in-evidence-file">
            <div class="in-section-head"><h4><i class="bi bi-file-earmark-text"></i> {{ fileBase(selectedKey) }}</h4></div>
            <div class="in-file-row"><span>{{ selectedNode ? selectedNode.status : '' }} · {{ selectedNode ? selectedNode.fileClass : '' }}</span></div>
            <div class="in-evidence-lists">
              <div><h5>{{ t('in.evidence.notes') }}</h5><ul v-if="notesPreview.length"><li v-for="n in notesPreview" :key="n.id">{{ n.text }}</li></ul><div v-else class="in-muted">—</div></div>
              <div><h5>{{ t('in.evidence.bookmarks') }}</h5><ul v-if="bookmarksPreview.length"><li v-for="b in bookmarksPreview" :key="b.targetKey">{{ b.scope }} {{ b.hunkId || b.line || '' }}</li></ul><div v-else class="in-muted">—</div></div>
            </div>
          </div>
          <div class="in-tip"><i class="bi bi-info-circle"></i> {{ t('in.reviewHint') }}</div>
        </div>
      </div>

      <AiConsole v-show="tab === 'console'" />
    </template>
  </aside>
</template>

<style scoped>
.insight-panel { display: flex; flex-direction: column; min-width: 0; }
.insight-tabs { flex-wrap: wrap; row-gap: 2px; }
.insight-tabs .nav-link { white-space: nowrap; padding-left: .6rem; padding-right: .6rem; position: relative; }
.in-tab-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 15px; height: 15px; border-radius: 999px; padding: 0 4px; font-size: .6rem; background: var(--bs-warning); color: #111; margin-left: 4px; vertical-align: middle; }
.in-tab-badge.danger { background: var(--bs-danger); color: #fff; }
.in-body { flex: 1; min-height: 0; overflow: auto; padding: 12px; }
.in-empty { text-align: center; padding: 46px 12px; color: var(--bs-secondary-color); font-size: .8rem; }
.in-empty i { font-size: 1.65rem; display: block; margin-bottom: 8px; color: var(--bs-primary); }
.in-empty-small { padding: 22px 8px; }
.in-error { border: 1px solid color-mix(in srgb, var(--bs-danger) 35%, transparent); background: color-mix(in srgb, var(--bs-danger) 8%, transparent); border-radius: 10px; padding: 12px; font-size: .78rem; }
.in-error p { margin: 4px 0 8px; }
.in-overview { display: flex; flex-direction: column; gap: 12px; }
.in-kpis { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.in-kpi { border: 1px solid var(--bs-border-color); border-radius: 11px; padding: 9px 10px; background: color-mix(in srgb, var(--bs-primary) 3%, var(--bs-body-bg)); min-width: 0; }
.in-kpi span { display: block; font-size: .68rem; color: var(--bs-secondary-color); }
.in-kpi b { display: block; font-size: 1.3rem; line-height: 1.1; margin-top: 2px; font-variant-numeric: tabular-nums; }
.in-kpi-sub { display: block; font-size: .62rem; color: var(--bs-secondary-color); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-section { border-top: 1px solid var(--bs-border-color); padding-top: 11px; }
.in-section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; }
.in-section-head h4 { margin: 0; font-size: .78rem; display: flex; align-items: center; gap: 6px; }
.in-section-head h4 i { color: var(--bs-primary); }
.in-muted { color: var(--bs-secondary-color); font-size: .72rem; padding: 5px 0; }
.in-risk-list { list-style: none; margin: 0; padding: 0; }
.in-risk-row { width: 100%; display: flex; align-items: center; gap: 8px; border: 0; background: transparent; color: inherit; text-align: left; padding: 6px 0; border-bottom: 1px dashed var(--bs-border-color); }
.in-risk-row:hover { color: var(--bs-primary); }
.in-risk-text { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; }
.in-risk-text b { font-size: .74rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-risk-text small { font-size: .64rem; color: var(--bs-secondary-color); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-risk-badge { display: inline-flex; align-items: center; justify-content: center; border-radius: 999px; padding: 2px 7px; font-size: .62rem; font-weight: 700; white-space: nowrap; }
.risk-critical { background: color-mix(in srgb, var(--bs-danger) 18%, transparent); color: var(--bs-danger); border: 1px solid color-mix(in srgb, var(--bs-danger) 40%, transparent); }
.risk-high { background: color-mix(in srgb, #f59e0b 18%, transparent); color: #b45309; border: 1px solid color-mix(in srgb, #f59e0b 42%, transparent); }
.risk-medium { background: color-mix(in srgb, #eab308 15%, transparent); color: #854d0e; border: 1px solid color-mix(in srgb, #eab308 34%, transparent); }
.risk-low { background: color-mix(in srgb, var(--bs-success) 12%, transparent); color: var(--bs-success); border: 1px solid color-mix(in srgb, var(--bs-success) 30%, transparent); }
.risk-none { background: var(--bs-tertiary-bg); color: var(--bs-secondary-color); border: 1px solid var(--bs-border-color); }
.in-bars { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.in-bar-row { display: grid; grid-template-columns: 64px minmax(0, 1fr) 34px; align-items: center; gap: 6px; font-size: .66rem; }
.in-bar-row span { color: var(--bs-secondary-color); }
.in-bar-row b { text-align: right; font-variant-numeric: tabular-nums; }
.in-bar { height: 7px; border-radius: 4px; overflow: hidden; background: var(--bs-tertiary-bg); }
.in-bar i { display: block; height: 100%; border-radius: 4px; }
.bar-added { background: var(--bs-success); }
.bar-deleted { background: var(--bs-danger); }
.bar-modified { background: var(--bs-warning); }
.bar-format, .bar-type { background: color-mix(in srgb, var(--bs-primary) 70%, var(--bs-secondary-color)); }
.in-bars-thin { opacity: .85; }
.in-suggest-list { margin: 0; padding: 0; list-style: none; }
.in-suggest-list button { border: 0; background: transparent; color: inherit; text-align: left; padding: 4px 0; font-size: .7rem; }
.in-suggest-list button:hover { color: var(--bs-primary); }
.in-suggest-list small { color: var(--bs-secondary-color); margin-left: 5px; }
.in-file-card .in-file-row { display: flex; flex-direction: column; gap: 2px; font-size: .7rem; }
.in-file-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-file-row span { color: var(--bs-secondary-color); }
.in-page-head h3 { margin: 0; font-size: .9rem; }
.in-page-head p { margin: 3px 0 10px; font-size: .68rem; color: var(--bs-secondary-color); }
.in-tools { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.in-seg { display: flex; border: 1px solid var(--bs-border-color); border-radius: 7px; overflow: hidden; }
.in-seg button { border: 0; background: transparent; color: var(--bs-secondary-color); font-size: .64rem; padding: 3px 7px; }
.in-seg button.active { background: var(--bs-primary); color: #fff; }
.in-search { flex: 1; min-width: 90px; border: 1px solid var(--bs-border-color); border-radius: 7px; background: var(--bs-body-bg); color: inherit; font-size: .68rem; padding: 3px 7px; }
.in-confidence { font-size: .62rem; white-space: nowrap; color: var(--bs-secondary-color); }
.in-evidence { font-size: .6rem; max-width: 120px; color: var(--bs-secondary-color); }
.in-not-provided { color: var(--bs-secondary-color); }
.in-truncated { border: 1px dashed var(--bs-border-color); border-radius: 8px; padding: 6px 8px; font-size: .66rem; color: var(--bs-secondary-color); margin-bottom: 8px; }
.in-table-wrap { overflow: auto; border: 1px solid var(--bs-border-color); border-radius: 9px; }
.in-table { width: 100%; border-collapse: collapse; font-size: .68rem; }
.in-table th { position: sticky; top: 0; background: var(--bs-tertiary-bg); text-align: left; padding: 6px; font-weight: 600; white-space: nowrap; }
.in-table td { padding: 6px; border-top: 1px solid var(--bs-border-color); vertical-align: top; }
.in-mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .64rem; max-width: 150px; }
.in-ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-link { border: 0; background: transparent; color: var(--bs-primary); font-size: .66rem; padding: 2px 4px; white-space: nowrap; }
.in-impact-list { display: flex; flex-direction: column; gap: 8px; }
.in-impact-card { border: 1px solid var(--bs-border-color); border-radius: 10px; padding: 9px; background: color-mix(in srgb, var(--bs-body-bg) 95%, var(--bs-primary) 5%); }
.in-impact-head { display: flex; align-items: center; gap: 6px; }
.in-impact-head b { flex: 1; min-width: 0; font-size: .72rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.in-status-pill { font-size: .58rem; color: var(--bs-secondary-color); border: 1px solid var(--bs-border-color); border-radius: 4px; padding: 1px 4px; }
.in-impact-reason { margin: 6px 0 5px; font-size: .66rem; color: var(--bs-secondary-color); line-height: 1.35; }
.in-callers { font-size: .64rem; color: var(--bs-secondary-color); display: flex; gap: 4px; flex-wrap: wrap; align-items: center; }
.in-caller { border: 1px solid var(--bs-border-color); border-radius: 4px; padding: 0 4px; }
.in-impact-foot { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 3px 6px; margin-top: 6px; font-size: .62rem; color: var(--bs-secondary-color); }
.in-evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 10px; }
.in-ev-card { border: 1px solid var(--bs-border-color); border-radius: 10px; padding: 9px; min-width: 0; }
.in-ev-card span { font-size: .64rem; color: var(--bs-secondary-color); display: block; }
.in-ev-card b { font-size: 1.05rem; display: block; margin: 2px 0; }
.in-ev-card small { font-size: .62rem; color: var(--bs-secondary-color); }
.in-progress { height: 5px; border-radius: 3px; background: var(--bs-tertiary-bg); overflow: hidden; margin-top: 4px; }
.in-progress i { display: block; height: 100%; background: var(--bs-success); }
.in-evidence-file { margin-top: 2px; }
.in-evidence-lists { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 7px; }
.in-evidence-lists h5 { font-size: .66rem; margin: 0 0 4px; color: var(--bs-secondary-color); }
.in-evidence-lists ul { margin: 0; padding-left: 14px; font-size: .64rem; }
.in-evidence-lists li { margin-bottom: 2px; }
.in-tip { margin-top: 10px; font-size: .64rem; color: var(--bs-secondary-color); border-top: 1px dashed var(--bs-border-color); padding-top: 7px; }
@media (max-width: 720px) {
  .in-kpis, .in-evidence-grid { grid-template-columns: 1fr; }
  .in-evidence-lists { grid-template-columns: 1fr; }
}
</style>
