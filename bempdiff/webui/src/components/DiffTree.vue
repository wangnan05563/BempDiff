<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import {
  state, selectEntry, toggleArchive, STATUS_META, statusLabel, toast,
  excludeEntry, restoreAllExcluded, addIgnoreRule, removeIgnoreRule, clearIgnoreRules,
  setBaseFolder, showProperties, hideProperties, startFileAiSummary,
  setTreePanelCollapsed, reviewStatusOf, hasReviewBadge
} from '../store'
import { reviewStatusMeta } from '../lib/review'
import {
  normalizeReviewTreeFilter, reviewTreeStats, passesReviewTreeFilter, nextUnreviewedNode, isReviewableFile,
  countFileNotes, countFileBookmarks
} from '../lib/review_tree'
import { isIgnored, IGNORE_TYPES } from '../lib/ignore'
import { menuItemsFor, buildCopyText } from '../lib/tree_actions'
import { t } from '../lib/i18n'
import { buildDirTree, flattenDirTree, dirLayersOf, flattenArchiveChildren } from '../lib/dir_tree'
import { api } from '../api/client'
import ContextMenu from './ContextMenu.vue'
import TipButton from './TipButton.vue'
// 宽度经 prop 传入（App 拖拽调宽），根节点显式绑定——本组件为 fragment（含右键菜单/属性弹窗
// 多个顶层根），无法靠 :style fallthrough 把宽度透传到唯一根元素。
const props = defineProps({ panelWidth: { type: Number, default: null } })
// STATUS_META 已迁到 store.js，与 DiffView/InfoPanel 共享
const LAYER_LABEL = { L0: 'dt.layer.L0', L1: 'dt.layer.L1', L2: 'dt.layer.L2', '?': 'dt.layer.?' }
const LAYER_TITLE = {
  L0: 'dt.layerTitle.L0',
  L1: 'dt.layerTitle.L1',
  L2: 'dt.layerTitle.L2',
  '?': 'dt.layerTitle.?'
}

const tree = computed(() => (state.job && state.job.tree) || [])

// ===================== T06220 审阅头部筛选 =====================
const REVIEW_FILTER_KEY = 'bempdiff.treeReviewFilter'
const AUTO_NEXT_KEY = 'bempdiff.autoNextUnreviewed'
function readJsonStored(key) {
  try { return JSON.parse(localStorage.getItem(key) || 'null') } catch (_) { return null }
}
const reviewFilter = ref(normalizeReviewTreeFilter(readJsonStored(REVIEW_FILTER_KEY)))
const autoNextUnreviewed = ref(localStorage.getItem(AUTO_NEXT_KEY) !== '0')
watch(reviewFilter, (v) => {
  try { localStorage.setItem(REVIEW_FILTER_KEY, JSON.stringify(normalizeReviewTreeFilter(v))) } catch (_) {}
}, { deep: true })
const reviewStats = computed(() => reviewTreeStats(tree.value, state.review, state.aiClassify))
const formattedOnlyCount = computed(() => tree.value.filter(n => isReviewableFile(n) && n.formattedOnly === true).length)
const hasAiClassification = computed(() => Object.keys(state.aiClassify || {}).length > 0)
const REVIEW_CHIP_DEFS = [
  { key: 'all', labelKey: 'dt.chip.all', countKey: 'totalFiles' },
  { key: 'unreviewed', labelKey: 'rv.status.unreviewed', countKey: 'unreviewed' },
  { key: 'in_progress', labelKey: 'dt.chip.inProgress', countKey: 'inProgress' },
  { key: 'needs_review', labelKey: 'rv.status.needs_review', countKey: 'needsReview' },
  { key: 'reviewed', labelKey: 'rv.status.reviewed', countKey: 'reviewed' }
]
const reviewChips = computed(() => REVIEW_CHIP_DEFS.map(c => ({
  ...c, count: Number(reviewStats.value[c.countKey]) || 0
})))

function setReviewStatus(key) {
  const cur = reviewFilter.value
  if (key === 'all') {
    reviewFilter.value = { ...cur, status: 'all', highRisk: false, onlyFormatted: false, onlyBookmarks: false }
  } else {
    reviewFilter.value = { ...cur, status: cur.status === key ? 'all' : key }
  }
}
function toggleHighRisk() {
  if (!hasAiClassification.value) {
    toast('info', t('dt.chip.highRiskNeedAi'))
    return
  }
  // 新 chip 与原风险按钮互斥：chip 只允许 HIGH；启用时把原风险栏恢复全选，避免交集为空。
  const cfg = state.config || {}
  cfg.filterRisk = ['HIGH', 'MEDIUM', 'LOW']
  reviewFilter.value = { ...reviewFilter.value, highRisk: !reviewFilter.value.highRisk }
}
function toggleFormattedOnly() {
  reviewFilter.value = { ...reviewFilter.value, onlyFormatted: !reviewFilter.value.onlyFormatted }
}
function toggleBookmarksOnly() {
  reviewFilter.value = { ...reviewFilter.value, onlyBookmarks: !reviewFilter.value.onlyBookmarks }
}
function toggleAutoNext() {
  autoNextUnreviewed.value = !autoNextUnreviewed.value
  try { localStorage.setItem(AUTO_NEXT_KEY, autoNextUnreviewed.value ? '1' : '0') } catch (_) {}
}
function clearReviewFilters() {
  reviewFilter.value = { status: 'all', highRisk: false, onlyFormatted: false, onlyBookmarks: false }
}

// T06218 树内审阅状态：只有 in_progress/reviewed/needs_review/手动覆盖才显示，避免 1000 行徽标噪声。
function reviewLabelOf(key) {
  return t(reviewStatusMeta(reviewStatusOf(key)).labelKey)
}
function reviewIconOf(key) {
  return reviewStatusMeta(reviewStatusOf(key)).icon
}
function reviewColorOf(key) {
  const s = reviewStatusOf(key)
  if (s === 'reviewed') return 'var(--bs-success)'
  if (s === 'in_progress') return 'var(--bs-warning)'
  if (s === 'needs_review') return 'var(--bs-danger)'
  return 'var(--bs-secondary-color)'
}
function noteCountOf(key) {
  const f = state.review && state.review.files ? state.review.files[key] : null
  return countFileNotes(f)
}
function bookmarkCountOf(key) {
  const f = state.review && state.review.files ? state.review.files[key] : null
  return countFileBookmarks(f)
}

// 树栏「过滤项计数」：直接用后端全量统计（state.job.stats，已含解包后的嵌套归档内部），
// 与顶部全局汇总栏同一数据源，天然一致且准确。
const counts = computed(() => {
  const c = { ADDED: 0, DELETED: 0, MODIFIED: 0, UNCHANGED: 0 }
  const s = state.job && state.job.stats
  if (s) {
    c.ADDED = s.added ?? 0
    c.DELETED = s.deleted ?? 0
    c.MODIFIED = s.modified ?? 0
    c.UNCHANGED = s.unchanged ?? 0
    return c
  }
  // 兜底：后端未回传全量统计时退化为顶层树计数（仅保证不崩，不做异步增量）
  for (const n of tree.value) if (c[n.status] !== undefined) c[n.status]++
  return c
})
// 单节点过滤谓词：状态 + 搜索 + 风险过滤三项。主树与归档展开子节点共用，
// 修复「未勾选『未变』时仍显示 jar 解包后的灰色条目」（解包子节点原先绕过该过滤）。
// 状态判定用 `=== true`（而非 `!== false`）：当某节点 status 缺失或不在四个已知状态
// （MODIFIED/ADDED/DELETED/UNCHANGED）时，showStatus[n.status] 为 undefined，会被全部过滤掉。
// 之所以必须如此：这类「无状态/未知状态」节点多为目录/包级占位（list/tree 中间层），
// 既不属于任何勾选状态，又往往是「4 个过滤全未勾选时树仍残留、且点击无反应」的元凶；
// 用 `!== false` 会让它们漏网（undefined !== false === true）而一直在树里显示。
// FOLDER 节点不再特例放行：folder 对比模式后端会给「含文件目录」下发显式 FOLDER 节点，其 sha 恒为
// 目录哨兵值，故目录自身在中始终是 UNCHANGED（除非整体新建/删除）。过去对 FOLDER 无条件 return true
// 会让「未勾选『未变』时纯无差异文件夹仍显示」——现改为与其他节点一样按状态过滤。目录骨架不会因此
// 塌缩：buildDirTree 会从「通过过滤的文件」的路径前缀自动重建目录行（dir_tree.buildDirTree 收集文件
// 全部路径段），故含差异文件的目录仍显示、纯无差异目录才被隐藏，二者兼得。
function passStatusFilter(n) {
  const cfg = state.config || {}
  const showStatus = {
    MODIFIED: cfg.filterShowModified === true,
    ADDED: cfg.filterShowAdded === true,
    DELETED: cfg.filterShowDeleted === true,
    UNCHANGED: cfg.filterShowUnchanged === true
  }
  return showStatus[n.status] === true
}
function passSearchFilter(n) {
  const cfg = state.config || {}
  const q = (cfg.filterSearch || '').trim()
  if (!q) return true
  try {
    if (cfg.filterRegex) return new RegExp(q, 'i').test(n.key)
    return n.key.toLowerCase().includes(q.toLowerCase())
  } catch (_) { return true /* 非法正则时忽略搜索过滤，避免误伤 */ }
}
function passRiskFilter(n) {
  const cfg = state.config || {}
  const rf = cfg.filterRisk
  if (!rf || !rf.length) return true // 空选 = 不过滤
  const aiMap = state.aiClassify || {}
  if (!Object.keys(aiMap).length) return true // 还没做智能分类 = 风险过滤不生效
  const c = aiMap[n.key]
  return !!(c && rf.includes(c.risk))
}
// 右键菜单视图过滤：排除（临时隐藏，可恢复）+ 忽略（持久化规则）均不参与树渲染
function passViewFilter(n) {
  if (state.excludedKeys[n.key]) return false
  if (isIgnored(n.key, state.ignoreRules)) return false
  return true
}
function passReviewFilter(n) {
  return passesReviewTreeFilter(n, reviewFilter.value, state.review, state.aiClassify)
}
// 当前文件刚变已审时仍需留在候选序列中，才能找到它后面的下一个未审。
function passesBaseFilters(n) {
  return passStatusFilter(n) && passSearchFilter(n) && passRiskFilter(n) && passViewFilter(n)
}
function passesFilters(n) { return passesBaseFilters(n) && passReviewFilter(n) }
// 自动跳下一未审时，status 条件固定为“未审”由 nextUnreviewedNode 负责；其余高风险/仅格式仍需生效。
function passesAutoNextFilters(n) {
  return passesBaseFilters(n) &&
    passesReviewTreeFilter(n, { ...reviewFilter.value, status: 'all' }, state.review, state.aiClassify)
}

/** 一键清空所有过滤（搜索/4 个状态/风险/审阅 chips）：用户被「勾选过滤后整树消失」卡住时一键恢复。
 * 仅清运行时态（state.config），不修改持久化配置——避免覆盖用户此前在配置中心保存的偏好。
 * T06220 起审阅筛选独立持久化在 localStorage，一并重置为「全部」。 */
function clearAllFilters() {
  const cfg = state.config
  if (!cfg) return
  cfg.filterSearch = ''
  cfg.filterRegex = false
  cfg.filterShowModified = true
  cfg.filterShowAdded = true
  cfg.filterShowDeleted = true
  cfg.filterShowUnchanged = true
  cfg.filterRisk = ['HIGH', 'MEDIUM', 'LOW']
  clearReviewFilters()
  toast('info', t('dt.filtersCleared'))
}

const filtered = computed(() => {
  return tree.value.filter(passesFilters)
})

// P2-5：判断本次比对是否真的存在差异（以全量 stats 为准，不受当前过滤条件影响）。
// 目录/包完全一致时 tree 仍可能含“未变”条目，过滤掉未变后 filtered=[]；
// 若只按 filtered 判空会误显示「过滤后无匹配」，掩盖「两侧一致」的结论。
const jobHasDiff = computed(() => {
  const s = state.job && state.job.stats
  if (s) {
    return (Number(s.added) || 0) + (Number(s.deleted) || 0) + (Number(s.modified) || 0) > 0
  }
  // stats 缺失（旧任务/测试桩）时回退原始树状态，避免把“有差异但未统计”误判成两侧一致，
  // 从而抢在“过滤后无匹配”之前显示 identical 空态、藏掉清除过滤入口。
  const raw = state.job && state.job.tree
  if (!Array.isArray(raw)) return false
  return raw.some(n => n && ['ADDED', 'DELETED', 'MODIFIED'].includes(String(n.status)))
})

const groups = computed(() => {
  const order = ['L0', 'L1', 'L2', '?']
  const map = new Map()
  for (const n of filtered.value) {
    const L = n.layer || '?'
    if (!map.has(L)) map.set(L, [])
    map.get(L).push(n)
  }
  const out = []
  const cfgG = state.config || {}
  const aiMapG = state.aiClassify || {}
  const pushGroup = (L) => {
    if (!map.has(L)) return
    const nodes = map.get(L)
    if (cfgG.sortByRisk) {
      nodes.sort((a, b) =>
        riskRank(aiMapG[b.key] && aiMapG[b.key].risk) - riskRank(aiMapG[a.key] && aiMapG[a.key].risk)
        || a.key.localeCompare(b.key))
    } else {
      nodes.sort((a, b) => a.key.localeCompare(b.key))
    }
    out.push({ layer: L, label: LAYER_LABEL[L] ? t(LAYER_LABEL[L]) : L, nodes })
  }
  order.forEach(pushGroup)
  for (const L of map.keys()) if (!order.includes(L)) pushGroup(L)
  return out
})

const aiClassify = computed(() => state.aiClassify || {})
const RISK_META = {
  HIGH: { dot: 'var(--bs-danger)', labelKey: 'dt.risk.HIGH', cls: 'text-bg-danger' },
  MEDIUM: { dot: 'var(--bs-warning)', labelKey: 'dt.risk.MEDIUM', cls: 'text-bg-warning' },
  LOW: { dot: 'var(--bs-success)', labelKey: 'dt.risk.LOW', cls: 'text-bg-success' }
}
function riskMeta(r) { return RISK_META[r] || null }
// 风险等级显示名（RISK_META 存 labelKey，渲染期求值支持切语言）
function riskLabel(r) { const m = RISK_META[r]; return m ? t(m.labelKey) : '' }
function riskRank(r) { return r === 'HIGH' ? 3 : r === 'MEDIUM' ? 2 : r === 'LOW' ? 1 : 0 }

// 状态排序优先级（数值小=更靠前）：MODIFIED 最先审阅，其次 DELETED/ADDED，最后 UNCHANGED
const STATUS_ORDER = { MODIFIED: 0, DELETED: 1, ADDED: 2, UNCHANGED: 3 }
function statusOrder(s) { return STATUS_ORDER[s] != null ? STATUS_ORDER[s] : 9 }
function basenameOf(k) { const i = (k || '').lastIndexOf('/'); return i >= 0 ? k.substring(i + 1) : (k || '') }

/** 文件类型图标（VS Code 风格：目录用 folder，文件按 fileClass 区分类型）。 */
function fileIcon(fc) {
  switch (fc) {
    case 'CLASS': return 'bi-filetype-java'
    case 'JAVA': return 'bi-filetype-java'
    case 'JAR': return 'bi-box-seam'
    case 'JS': return 'bi-filetype-js'
    case 'HTML': return 'bi-filetype-html'
    case 'CSS': return 'bi-filetype-css'
    case 'JSP': return 'bi-file-code'
    case 'CONFIG': return 'bi-file-earmark-text'
    case 'STATIC': return 'bi-file-earmark-image'
    case 'ARCHIVE': return 'bi-file-earmark-zip'
    case 'OFFICE': return 'bi-file-earmark-richtext'
    case 'FOLDER': return 'bi-folder2-open'
    default: return 'bi-file-earmark'
  }
}

// ===================== 视图模式 & 排序（任务 3） =====================
// 视图模式：'tree'(目录树：按目录层级递归展开，节点仅显示当前层名称) | 'list'(全平摊，显示完整路径)。
// T06220 增加 'queue'（审阅队列：按 AI 风险 + 审阅紧急度排序的平铺队列）。
const viewMode = computed(() => (state.config && state.config.treeViewMode) || 'tree')
const effectiveSortMode = computed(() => viewMode.value === 'tree' ? 'path' : ((state.config && state.config.treeSortMode) || 'path'))
const sortByRisk = computed(() => !!(state.config && state.config.sortByRisk))
function reviewQueueRank(key) {
  const s = reviewStatusOf(key)
  if (s === 'needs_review') return 0
  if (s === 'unreviewed') return 1
  if (s === 'in_progress') return 2
  return 3
}
function compareNodes(a, b) {
  // 审阅队列：AI 高风险优先，其次需复核/未审/进行中/已审，最后按路径稳定排序。
  if (viewMode.value === 'queue') {
    const ra = riskRank(aiClassify.value[a.key] && aiClassify.value[a.key].risk)
    const rb = riskRank(aiClassify.value[b.key] && aiClassify.value[b.key].risk)
    if (ra !== rb) return rb - ra
    const qa = reviewQueueRank(a.key)
    const qb = reviewQueueRank(b.key)
    if (qa !== qb) return qa - qb
    return a.key.localeCompare(b.key)
  }
  // 风险等级置顶（高→低），打破平手时回落到 sortMode
  if (sortByRisk.value) {
    const ra = riskRank(aiClassify.value[a.key] && aiClassify.value[a.key].risk)
    const rb = riskRank(aiClassify.value[b.key] && aiClassify.value[b.key].risk)
    if (ra !== rb) return rb - ra
  }
  const m = effectiveSortMode.value
  if (m === 'name') {
    const an = (a.name || basenameOf(a.key))
    const bn = (b.name || basenameOf(b.key))
    const c = an.localeCompare(bn)
    return c !== 0 ? c : (a.key.localeCompare(b.key))
  }
  if (m === 'status') {
    const r = statusOrder(a.status) - statusOrder(b.status)
    return r !== 0 ? r : a.key.localeCompare(b.key)
  }
  return a.key.localeCompare(b.key)
}
function setViewMode(m) {
  if (!state.config) return
  state.config.treeViewMode = m
  // 进入树视图：若当前排序不是 path，重置为 path（树状下其他排序无意义）
  if (m === 'tree') state.config.treeSortMode = 'path'
}
function setSortMode(m) {
  if (!state.config) return
  if (viewMode.value === 'tree') return // 树视图下排序固定，不允许切换
  state.config.treeSortMode = m
}

// ===================== 目录树展开/折叠（树视图，VS Code 风格） =====================
// 展开状态键 = `${layer}:${dirKey}`：同一目录路径会跨 L0/L1/L2 分区各自成树，
// 若只用 dirKey 作状态键，点开 L0 的 WEB-INF/ 会连带 L1 的同名目录一起展开（联动）。
// 带上 layer 后各分区各自记忆展开状态，点一个区不影响另一个区，交互更符合直觉。
const zoneDirKey = (layer, dirKey) => layer + ':' + dirKey
// expandedDirs[zoneKey] === false 表示折叠；缺省/true 表示展开（默认全展开，便于一览结构）。
const expandedDirs = ref({})
function toggleDir(layer, dirKey) {
  const k = zoneDirKey(layer, dirKey)
  expandedDirs.value = { ...expandedDirs.value, [k]: expandedDirs.value[k] === false ? true : false }
}

// ---- 「全部展开/全部折叠」逐层交错动画 ----
// 展开：目录按深度从顶层逐层展开；折叠：按深度从最深层逐层收起，视觉自然流畅、无生硬跳变。
// token 令牌：动画进行中再次点击（含切换方向）即中断上一轮，从当前状态实时续跑，支持实时切换。
const ANIM_STEP_MS = 60 // 每层动画间隔（毫秒）
let dirAnimToken = 0
let dirAnimTimer = null

/** 收集全部目录 key 按深度分层（跨 Layer 分组合并），layers[0]=顶层。 */
function collectDirLayers() {
  const layers = []
  for (const g of groups.value) {
    const gl = dirLayersOf(g.nodes)
    for (let d = 0; d < gl.length; d++) {
      if (!layers[d]) layers[d] = []
      for (const k of gl[d]) layers[d].push(zoneDirKey(g.layer, k)) // 与展开状态键保持一致（按区隔离）
    }
  }
  return layers
}

/** 全部展开（逐层动画）：仅展开当前仍折叠的目录（已展开的不动），从顶层逐层展开。 */
function expandAllDirs() {
  const cur = expandedDirs.value
  const todo = collectDirLayers().map(L => L.filter(k => cur[k] === false)).filter(L => L.length)
  if (!todo.length) return
  runDirAnim(todo, (patch, k) => { patch[k] = true })
}

/** 全部折叠（逐层动画）：仅折叠当前仍展开的目录，从最深层逐层收起。 */
function collapseAllDirs() {
  const cur = expandedDirs.value
  const todo = collectDirLayers().map(L => L.filter(k => cur[k] !== false)).filter(L => L.length).reverse()
  if (!todo.length) return
  runDirAnim(todo, (patch, k) => { patch[k] = false })
}

/** 逐层应用 patch 的动画执行体；新一轮调用通过 token 中断上一轮。 */
function runDirAnim(layers, apply) {
  dirAnimToken++
  const token = dirAnimToken
  clearTimeout(dirAnimTimer)
  let i = 0
  const step = () => {
    if (token !== dirAnimToken) return // 已被新的展开/折叠指令打断
    const patch = {}
    for (const k of layers[i]) apply(patch, k)
    expandedDirs.value = { ...expandedDirs.value, ...patch }
    i++
    if (i < layers.length) dirAnimTimer = setTimeout(step, ANIM_STEP_MS)
  }
  step()
}

// 工具栏按钮的悬浮提示文案：树视图下为功能说明；列表视图下提示需先切换（按钮禁用）。
const expandTooltip = computed(() => (viewMode.value === 'tree' ? t('dt.expandAll') : t('dt.treeViewOnly')))
const collapseTooltip = computed(() => (viewMode.value === 'tree' ? t('dt.collapseAll') : t('dt.treeViewOnly')))
// 「...」菜单：菜单项高亮与排序选项同步（树视图下 effectiveSortMode = path）
const sortMenuKey = computed(() => effectiveSortMode.value)
const showViewMenu = ref(false)
const viewMenuWrap = ref(null)
function onDocClick(e) {
  if (showViewMenu.value && viewMenuWrap.value && !viewMenuWrap.value.contains(e.target)) {
    showViewMenu.value = false
  }
}
function toggleRisk(r) {
  const cfg = state.config || {}
  if (!Array.isArray(cfg.filterRisk)) cfg.filterRisk = []
  const i = cfg.filterRisk.indexOf(r)
  if (i >= 0) cfg.filterRisk.splice(i, 1); else cfg.filterRisk.push(r)
  // 手动调整原风险栏时退出“高风险”chip，避免两个入口互相矛盾。
  if (reviewFilter.value.highRisk) reviewFilter.value = { ...reviewFilter.value, highRisk: false }
}
function statusMeta(s) { return STATUS_META[s] || STATUS_META.UNCHANGED }

// ===================== R1 过滤栏显隐与命中计数（二期 T01451） =====================
// 过滤栏（搜索框 + 状态勾选区）可整体收起：「...」视图菜单开关，localStorage 双保险记忆
// （config 仅在配置中心保存时落盘，treeViewMode 同款限制；收起偏好要求重启即记忆，故走 localStorage，
//  与 setAiPanelCollapsed 的 AI_COLLAPSE_KEY 模式一致）。
const FILTER_BAR_KEY = 'bempdiff.treeFilterBarVisible'
function readInitialFilterBarVisible() {
  try {
    const v = localStorage.getItem(FILTER_BAR_KEY)
    if (v !== null) return v === 'true'
  } catch (_) { /* 非安全上下文等异常时回落 config 默认 */ }
  return (state.config && state.config.treeFilterBarVisible) !== false
}
const filterBarVisible = ref(readInitialFilterBarVisible())
function setFilterBarVisible(v) {
  filterBarVisible.value = !!v
  try { localStorage.setItem(FILTER_BAR_KEY, String(filterBarVisible.value)) } catch (_) {}
}
// 风险过滤「实际生效」判定：已完成智能分类且未全选三档（全选 = 无过滤效果）。
const riskEffective = computed(() => {
  const cfg = state.config || {}
  const rf = cfg.filterRisk
  return !!(Object.keys(state.aiClassify || {}).length && Array.isArray(rf) && rf.length && rf.length < 3)
})
// 任一过滤生效（供「命中 / 总数」计数切换展示）：搜索非空 / 任一状态未勾选 / 风险过滤实际生效。
const filtersActive = computed(() => {
  const cfg = state.config || {}
  if ((cfg.filterSearch || '').trim()) return true
  if (cfg.filterShowModified !== true || cfg.filterShowAdded !== true ||
      cfg.filterShowDeleted !== true || cfg.filterShowUnchanged !== true) return true
  if (riskEffective.value) return true
  if (reviewFilter.value.status !== 'all' || reviewFilter.value.highRisk || reviewFilter.value.onlyFormatted || reviewFilter.value.onlyBookmarks) return true
  return false
})
const hitCount = computed(() => filtered.value.length)
// R6（T01465）：全局 Ctrl+K 聚焦过滤框——store 自增信号驱动，输入框不存在（过滤栏收起）时先展开再聚焦
const filterInputRef = ref(null)
watch(() => state.treeFilterFocusTick, () => {
  if (!filterBarVisible.value) setFilterBarVisible(true)
  nextTick(() => {
    const el = filterInputRef.value
    if (el) { el.focus(); el.select() }
  })
})

// ===================== 虚拟化滚动（D4） =====================
// 把 groups 拍平为「分组头 + 节点」的线性 rows，固定行高，仅渲染可视区切片。
const ROW_H = 30 // 每行固定像素高度（分组头与节点同高，保证对齐）
const rowStyle = { height: ROW_H + 'px', boxSizing: 'border-box' }
const INDENT_STEP = 20 // 目录树每级缩进像素（加大步长让层次更鲜明）
/** 节点行内联样式：按目录深度逐级缩进（分组头保持无缩进）。
 * 关键：内联 paddingLeft 加 !important，胜过模板上 Bootstrap px-2 utility 的 !important，
 * 否则「差异文件树」所有节点左内边距被锁 8px，无缩进差异（参见 2026-08-21 修复）。 */
function rowStyleFor(row) {
  const depth = (row && row.depth) || 0
  return { height: ROW_H + 'px', boxSizing: 'border-box', paddingLeft: (8 + depth * INDENT_STEP) + 'px !important' }
}

// 归档展开子节点：抽取为 helper，让 rows 在 tree/list 两种视图分支下都能复用。
// 子节点复用主过滤谓词；按出现顺序插入父级之后（archiveChildren 由 ArchiveTree 按 name 排好）。
// jar 内条目由 flattenArchiveChildren 按内部路径分层（目录行可折叠、文件按层级缩进）——
// 修复「展开 jar 后全部平铺同一缩进、无层次感」（2026-08-21）。
function pushArchiveChildren(out, parent, jarDepth) {
  const cache = state.archiveChildren[parent.key]
  const kids = (cache && cache.children) || []
  if (cache && cache.loading && !kids.length) {
    out.push({ kind: 'archiveLoading', parentKey: parent.key, depth: jarDepth + 1 })
    return
  }
  const folded = new Set()
  for (const k in expandedArchiveDirs.value) if (expandedArchiveDirs.value[k] === false) folded.add(k)
  for (const r of flattenArchiveChildren(kids, folded)) {
    if (!passesFilters(r.node)) continue
    out.push({ kind: r.kind, node: r.node, key: r.key, parentKey: parent.key,
               depth: jarDepth + r.depth, name: r.name, innerPath: r.innerPath })
    // 嵌套归档（如 zip 内部再展开 jar）：若该 archiveChild 处于展开态且子节点已缓存，
    // 需递归再展开其内部条目——否则 jar 显示已展开却无内部行（2026-08-23 修复）。
    const fc = r.node && r.node.fileClass
    if ((fc === 'ARCHIVE' || fc === 'JAR') && state.expandedArchives[r.node.key]) {
      pushArchiveChildren(out, r.node, jarDepth + r.depth)
    }
  }
}
/** jar 内目录折叠状态（与主树 expandedDirs 独立，键为完整复合键 outer!/dir/）。 */
const expandedArchiveDirs = ref({})
function toggleArchiveDir(dirKey) {
  expandedArchiveDirs.value = { ...expandedArchiveDirs.value, [dirKey]: expandedArchiveDirs.value[dirKey] === false ? true : false }
}

const rows = computed(() => {
  const out = []
  if (viewMode.value === 'tree') {
    // 树视图：Layer 分组头 + 目录树。目录/文件按层级递归展开（VS Code 风格），
    // 节点仅显示当前层级的目录名/文件名；目录行可展开/折叠，文件行点击打开内容比对。
    const fileCompare = (a, b) => {
      // AI 风险置顶（目录内文件按风险排序，风险相同按名称）
      if (sortByRisk.value) {
        const ra = riskRank(aiClassify.value[a.node.key] && aiClassify.value[a.node.key].risk)
        const rb = riskRank(aiClassify.value[b.node.key] && aiClassify.value[b.node.key].risk)
        if (ra !== rb) return rb - ra
      }
      return (a.name || '').localeCompare(b.name || '')
    }
    for (const g of groups.value) {
      // isExpanded 必须在 for 循环体内定义：引用当次迭代的 g.layer 才能匹配当前分组。
      // 若定义在循环体外，闭包词法作用域里没有 `g`（`for...of` 的 const 变量是块级作用域），
      // flattenDirTree 递归调用它时会抛 ReferenceError: g is not defined → 整棵 rows 崩溃 → 树视图空白。
      // 这正是「列表视图正常、树视图空白」的根因：列表分支不定义/不调用 isExpanded。
      const isExpanded = (k) => expandedDirs.value[zoneDirKey(g.layer, k)] !== false
      out.push({ kind: 'header', layer: g.layer, label: g.label, count: g.nodes.length })
      const { root } = buildDirTree(g.nodes, { fileCompare })
      for (const r of flattenDirTree(root, isExpanded)) {
        if (r.isDir) {
          // layer 随行写入：同一目录路径可能跨 L0/L1/L2 分组各自成为一棵子树的根，
          // :key 需带 layer 区分，否则虚拟滚动切片里同名目录撞 key，Vue 错误复用 DOM → 顶部多出文件夹/点击错乱
          out.push({ kind: 'dir', key: r.key, dirKey: r.key, name: r.name, depth: r.depth, node: r.node, layer: g.layer })
        } else {
          out.push({ kind: 'node', key: r.node.key, node: r.node, depth: r.depth, name: r.name })
          if ((r.node.fileClass === 'ARCHIVE' || r.node.fileClass === 'JAR') && state.expandedArchives[r.node.key]) {
            pushArchiveChildren(out, r.node, r.depth)
          }
        }
      }
    }
  } else {
    // 列表视图：丢掉分组头，全局按 sortMode（含风险置顶）排序；archiveChild 紧随父级之后展开。
    // 审阅队列：复用平铺渲染，但只保留可审阅文件，排序由 compareNodes 的 queue 分支给出。
    const source = viewMode.value === 'queue' ? filtered.value.filter(isReviewableFile) : filtered.value
    const sorted = source.slice().sort(compareNodes)
    for (const n of sorted) {
      out.push({ kind: 'node', key: n.key, node: n, depth: 0, name: n.key })
      if ((n.fileClass === 'ARCHIVE' || n.fileClass === 'JAR') && state.expandedArchives[n.key]) {
        pushArchiveChildren(out, n, 0)
      }
    }
  }
  return out
})
const totalH = computed(() => rows.value.length * ROW_H)

const scrollEl = ref(null)
const scrollTop = ref(0)
const viewportH = ref(600) // 初始估值，挂载后由容器实测覆盖
const OVERSCAN = 6
const start = computed(() => Math.max(0, Math.min(Math.floor(scrollTop.value / ROW_H) - OVERSCAN, rows.value.length)))
const visibleCount = computed(() => Math.ceil(viewportH.value / ROW_H) + OVERSCAN * 2)
const slice = computed(() => rows.value.slice(start.value, start.value + visibleCount.value))

function onScroll(e) { scrollTop.value = e.target.scrollTop }
function measure() {
  if (scrollEl.value) viewportH.value = scrollEl.value.clientHeight || 600
}

let ro = null
onMounted(() => {
  nextTick(measure)
  if (typeof ResizeObserver !== 'undefined' && scrollEl.value) {
    ro = new ResizeObserver(() => measure())
    ro.observe(scrollEl.value)
  }
  // 「...」下拉菜单外部点击关闭（与 ToolBar 工具栏的下拉共用模式）
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  document.removeEventListener('click', onDocClick)
  if (locateTimer) { clearTimeout(locateTimer); locateTimer = null }
  clearTimeout(dirAnimTimer)
})

// 新比对 / 差异树变化：回到顶部；目录展开状态重置为全展开（便于一览结构）。
watch(tree, () => {
  scrollTop.value = 0
  expandedDirs.value = {}
  if (scrollEl.value) scrollEl.value.scrollTop = 0
})
// 切换视图模式（树/列表）时把滚动重置到顶部：两种视图的 rows 行数差异巨大
// （列表全平摊→行数多；树视图目录折叠合并→行数骤减）。若沿用旧 scrollTop，
// 虚拟滚动的 start 会远超新 rows 长度导致 slice 为空 → 整屏空白（用户切换后"树看不见"）。
watch(viewMode, () => {
  scrollTop.value = 0
  if (scrollEl.value) { scrollEl.value.scrollTop = 0 }
})

// ===================== 路径定位（PathBar 面包屑 / 编辑框触发） =====================
// watch state.treeLocate.seq：找到第一个 key 匹配前缀的可见行 → 滚动居中 + flash 高亮。
const locateFlash = ref('') // 当前 flash 高亮的节点 key（动画结束后复位）
let locateTimer = null
watch(() => (state.treeLocate && state.treeLocate.seq), () => {
  const req = state.treeLocate
  const p = req && req.prefix
  const rs = rows.value
  let idx = -1
  if (!p) {
    idx = 0 // 根节点 → 树顶部
  } else {
    // 精确 key 优先（文件/目录），否则找第一个此前缀开头（含该前缀自身）的节点
    const hit = rs.findIndex(r => (r.kind === 'node' || r.kind === 'dir') && (r.key === p || r.key.startsWith(p + '/')))
    idx = hit
  }
  if (idx < 0) { toast('info', t('dt.locateMiss')); return }
  const el = scrollEl.value
  if (!el) return
  el.scrollTop = Math.max(0, idx * ROW_H - (viewportH.value - ROW_H) / 2) // 目标行居中
  nextTick(() => {
    const key = rs[idx] && rs[idx].key ? rs[idx].key : ''
    if (!key) return
    locateFlash.value = key
    if (locateTimer) clearTimeout(locateTimer)
    locateTimer = setTimeout(() => { locateFlash.value = '' }, 1400)
  })
})

// ===================== T06220 标记后自动跳下一未审 =====================
/** 目标在折叠目录内时，先展开其所有父目录（按节点所在 layer 生成 zoneKey）。 */
function expandParentsOf(node) {
  if (viewMode.value !== 'tree' || !node || !node.key) return
  const layer = node.layer || '?'
  const segs = node.key.split('/')
  segs.pop() // 去掉文件名
  if (!segs.length) return
  const patch = { ...expandedDirs.value }
  let prefix = ''
  for (const s of segs) {
    prefix = prefix ? prefix + '/' + s : s
    patch[zoneDirKey(layer, prefix + '/')] = true // dir_tree 的目录 key 统一带尾斜杠
  }
  expandedDirs.value = patch
}
watch(() => state.reviewAdvanceTick, () => {
  if (!autoNextUnreviewed.value) return
  if (reviewFilter.value.status !== 'unreviewed') return
  const next = nextUnreviewedNode(tree.value, state.activeKey, state.review, passesAutoNextFilters)
  if (!next) { toast('info', t('dt.noNextUnreviewed')); return }
  expandParentsOf(next)
  selectEntry(next.key)
  nextTick(() => {
    state.treeLocate = { prefix: next.key, seq: ((state.treeLocate && state.treeLocate.seq) || 0) + 1 }
  })
  toast('success', t('dt.nextUnreviewed').replace('{key}', next.key))
})

// ===================== 右键菜单（BCompare 风格） =====================
const ctxMenu = ref({ visible: false, x: 0, y: 0, node: null })
const excludedCount = computed(() => Object.keys(state.excludedKeys || {}).length)
const hasShell = computed(() => !!(typeof window !== 'undefined' && window.bempdiff &&
  (typeof window.bempdiff.openPath === 'function' || typeof window.bempdiff.showInFolder === 'function')))
// 注意：磁盘根目录的选择请用 diskRootOf(node)（按差异状态选侧），不要新增固定左根的 rootPath（已删）。

const menuItems = computed(() => {
  const node = ctxMenu.value.node
  if (!node) return []
  const ctx = {
    mode: (state.job && state.job.mode) || 'package',
    status: node.status,
    baseFolder: state.baseFolder,
    shell: hasShell.value
  }
  return menuItemsFor(node, ctx)
})

function openCtxMenu(node, e) {
  ctxMenu.value = { visible: true, x: e.clientX, y: e.clientY, node }
}
function closeCtxMenu() {
  ctxMenu.value = { ...ctxMenu.value, visible: false }
}

async function copyText(text, tip) {
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text)
    } else {
      // 非安全上下文兜底
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    toast('success', t('dt.copyToast', { tip, text: text ? (text.length > 60 ? text.slice(0, 60) + '…' : text) : '' }))
  } catch (_) {
    toast('danger', t('dt.copyFail'))
  }
}

/**
 * 解析节点对应的磁盘根目录（folder 模式；包模式返回 ''）。
 * 关键：后端 handleFile 会按差异状态动态选侧——ADDED（仅右侧存在）→ 右根，
 * 其余（MODIFIED/UNCHANGED/DELETED）→ 左根。前端磁盘路径必须与之对齐，
 * 否则 ADDED 条目的「打开 / 在资源管理器中显示 / 复制路径」会指向不存在的左侧路径。
 */
function diskRootOf(node) {
  if (!state.job || state.job.mode !== 'folder') return ''
  const isAdded = (node && node.status) === 'ADDED'
  return isAdded ? (state.newPath || '') : (state.oldPath || '')
}

/** 解析节点对应的磁盘物理路径（folder 模式；包模式返回 null）。 */
function diskPathOf(node) {
  if (!state.job || state.job.mode !== 'folder') return null
  const root = diskRootOf(node)
  if (!root) return null
  const key = (node.key || '').replace(/^\/+/, '')
  return root.replace(/[\\/]+$/, '') + '/' + key
}

async function runAction(item) {
  // 防御性 try/catch：任何分支异常都 toast 反馈而非静默失败
  // （历史：menuItemsFor 嵌套结构导致 item.id=undefined → switch 全落空 → 用户看到"点击无反应"；
  //  现已扁平化修复；本兜底应对未来数据上下文/闭包变量异常等极端场景）
  try {
    const node = ctxMenu.value.node
    if (!node) return
    if (!item) { toast('danger', t('dt.menuInvalid')); return }
    if (!item.id) { toast('danger', t('dt.menuMissingId')); return }
    const jobId = state.job && state.job.jobId
    const mode = (state.job && state.job.mode) || 'package'
    const disk = diskPathOf(node)
  switch (item.id) {
    case 'open': {
      if (mode === 'folder' && hasShell.value && disk) {
        // 桌面壳：系统默认程序打开文件 / 打开文件夹
        try {
          const r = await window.bempdiff.openPath(disk)
          if (r !== undefined && r !== null && r !== '') toast('danger', t('dt.openFail', { msg: r }))
        } catch (e) { toast('danger', t('dt.openFail', { msg: e.message })) }
      } else if (mode === 'folder') {
        toast('info', t('dt.openBrowserFallback'))
        if (node.fileClass !== 'FOLDER') selectEntry(node.key)
      } else {
        if (node.fileClass === 'FOLDER') toast('info', t('dt.folderNoCompare'))
        else selectEntry(node.key) // 包模式：打开反编译内容比对
      }
      break
    }
    case 'reveal': {
      if (hasShell.value && disk) {
        try { await window.bempdiff.showInFolder(disk) } catch (e) { toast('danger', t('dt.revealFail', { msg: e.message })) }
      } else {
        toast('warning', t('dt.revealShellOnly'))
      }
      break
    }
    case 'setBase': {
      if (node.fileClass === 'FOLDER') setBaseFolder(node.key)
      else toast('warning', t('dt.setBaseFolderOnly'))
      break
    }
    case 'rename': {
      const cur = (node.name || (node.key.split('/').pop())) || ''
      const newName = window.prompt(t('dt.renamePrompt'), cur)
      if (newName === null || newName === '') return
      if (!jobId) return
      try {
        const r = await apiFileOps(jobId, 'rename', node.key, { newName })
        if (!r.ok) { toast('warning', r.message || t('dt.renameFailShort')); break }
        toast('success', r.message)
        toast('info', t('dt.diskChanged'))
      } catch (e) { toast('danger', t('dt.renameFail', { msg: e.message })) }
      break
    }
    case 'delete': {
      const isDir = node.fileClass === 'FOLDER'
      const ok = window.confirm(t('dt.deleteConfirm', { key: node.key, dir: isDir ? t('dt.deleteDirSuffix') : '' }))
      if (!ok) return
      if (!jobId) return
      try {
        const r = await apiFileOps(jobId, 'delete', node.key)
        if (!r.ok) { toast('warning', r.message || t('dt.deleteFailShort')); break }
        toast('success', r.message)
        toast('info', '已修改磁盘文件，如需刷新差异请重新比对')
      } catch (e) { toast('danger', t('dt.deleteFail', { msg: e.message })) }
      break
    }
    case 'copyFile': {
      if (mode !== 'folder') { toast('warning', t('dt.copyFileNoDisk')); break }
      if (!jobId) return
      try {
        const r = await apiFileOps(jobId, 'copy', node.key)
        if (!r.ok) { toast('warning', r.message || t('dt.copyFileFailShort')); break }
        toast('success', r.message)
        toast('info', t('dt.copiedToOther'))
      } catch (e) { toast('danger', t('dt.copyFileFail', { msg: e.message })) }
      break
    }
    case 'exclude': {
      const n = excludeEntry(node.key)
      toast('info', t('dt.excluded', { key: node.key, more: n > 1 ? t('dt.excludedMore', { n }) : '' }))
      break
    }
    case 'ignore': {
      const type = node.fileClass === 'FOLDER' ? 'prefix' : 'name'
      addIgnoreRule(type, node.fileClass === 'FOLDER' ? node.key : (node.key.split('/').pop() || node.key))
      break
    }
    case 'copyName': {
      const t = buildCopyText(node, { mode })
      await copyText(t.name, t('dt.copiedName'))
      break
    }
    case 'copyPath': {
      // 用 diskRootOf（按状态选侧）而非固定的 rootPath，保证 ADDED 条目复制的是真实存在的右侧路径
      const t = buildCopyText(node, { mode, rootPath: diskRootOf(node) })
      if (mode !== 'folder') toast('info', t('dt.copiedPkgPath'))
      await copyText(t.absPath, t('dt.copiedPath'))
      break
    }
    case 'copyRelPath': {
      const t = buildCopyText(node, { mode })
      await copyText(t.relPath, t('dt.copiedRelPath'))
      break
    }
    case 'props': {
      showProperties(node)
      break
    }
    case 'aiSummary': {
      startFileAiSummary(node)
      break
    }
    default:
      toast('warning', t('dt.menuUnknown', { id: item.id || '(no id)' }))
  }
  } catch (e) {
    console.error('[runAction]', e)
    toast('danger', t('dt.menuActionFail', { msg: (e && e.message) || e }))
  }
}

/** 后端文件操作（统一处理 200+ok:false 的业务拒绝）。 */
async function apiFileOps(jobId, op, key, extra = {}) {
  return api.fileOps({ jobId, op, key, ...extra })
}

// 点击节点：文件夹不支持内容比对（提示而非报错）
function onNodeClick(node) {
  if (node.fileClass === 'FOLDER') {
    toast('info', t('dt.folderNoComparePick'))
    return
  }
  selectEntry(node.key)
}

// ---------- 属性弹窗 helpers ----------
const propDiskPath = computed(() => {
  const n = state.propertyNode
  if (!n || !state.job || state.job.mode !== 'folder') return ''
  return diskPathOf(n) || ''
})
function fmtSize(s) {
  const v = Number(s) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  if (v < 1024 * 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  return (v / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}
function ruleTypeLabel(t) {
  const found = IGNORE_TYPES.find(x => x.key === t)
  return found ? found.label : t
}
</script>

<template>
  <div class="col-tree" :class="{ collapsed: state.treePanelCollapsed }"
       :style="props.panelWidth != null && !state.treePanelCollapsed ? { width: props.panelWidth + 'px' } : undefined">
    <div v-if="!state.treePanelCollapsed" class="pane-head" :title="t('dt.paneTitle')">
      <i class="bi bi-diagram-3"></i> {{ t('dt.treeTitle') }}
      <span class="fw-normal" style="font-size:.75rem;color:var(--bs-secondary-color)"
            :title="state.error ? t('dt.failedCountTitle') : (filtersActive ? t('dt.hitTitle') : t('dt.totalTitle'))">
        <template v-if="state.error">{{ t('dt.noResult') }}</template>
        <template v-else-if="filtersActive">{{ t('dt.hitCount', { hit: hitCount, total: tree.length }) }}</template>
        <template v-else>{{ t('dt.totalCount', { total: tree.length }) }}</template>
      </span>
      <button class="btn btn-sm btn-outline-secondary border-0 ms-auto px-1 py-0"
              type="button" :title="t('dt.collapsePane')" @click="setTreePanelCollapsed(true)">
        <i class="bi bi-layout-sidebar-inset-reverse"></i>
      </button>
    </div>
    <!-- 收起态窄条：仅留竖排标题与展开入口，把横向空间让给比对窗口（与右侧智能分析栏对称） -->
    <div v-else class="tree-collapsed-bar" :title="t('dt.expandPane')" @click="setTreePanelCollapsed(false)">
      <i class="bi bi-chevron-right"></i>
      <span class="tree-collapsed-label">{{ t('dt.treeTitle') }}</span>
    </div>

    <!-- T06220 审阅统计头：已审/高风险/备注/书签 + 审阅状态 chips（PRD A4 / AC-07） -->
    <div class="dt-review-head px-2 py-1" v-if="state.config">
      <div class="dt-review-stats" :title="t('dt.reviewStatsTitle')">
        <span class="dt-stat dt-stat-reviewed" :title="t('dt.stat.reviewedTip', { done: reviewStats.reviewed, total: reviewStats.totalFiles })">
          <i class="bi bi-check2-circle"></i> {{ t('dt.stat.reviewed') }}
          <b>{{ reviewStats.reviewed }}/{{ reviewStats.totalFiles }}</b>
          <small>{{ reviewStats.filePercent }}%</small>
        </span>
        <span class="dt-stat" :class="{'text-danger': reviewStats.highRisk > 0}"
              :title="hasAiClassification ? t('dt.stat.highRiskTip', { hi: reviewStats.highRisk, total: reviewStats.classified }) : t('dt.chip.highRiskNeedAi')">
          <i class="bi bi-exclamation-triangle"></i> {{ t('dt.stat.highRisk') }}
          <b>{{ reviewStats.highRisk }}/{{ reviewStats.classified }}</b>
        </span>
        <span class="dt-stat" :title="t('dt.stat.notesTip')">
          <i class="bi bi-chat-left-text"></i> {{ t('dt.stat.notes') }} <b>{{ reviewStats.notes }}</b>
        </span>
        <span class="dt-stat" :title="t('dt.stat.bookmarksTip')">
          <i class="bi bi-bookmark"></i> {{ t('dt.stat.bookmarks') }} <b>{{ reviewStats.bookmarks }}</b>
        </span>
      </div>
      <div class="dt-review-chips" v-if="filterBarVisible">
        <button v-for="chip in reviewChips" :key="chip.key" type="button"
                class="dt-chip" :class="{active: reviewFilter.status === chip.key}"
                :aria-pressed="reviewFilter.status === chip.key"
                @click="setReviewStatus(chip.key)">
          {{ t(chip.labelKey) }} <span>{{ chip.count }}</span>
        </button>
        <button type="button" class="dt-chip dt-chip-risk"
                :class="{active: reviewFilter.highRisk, disabled: !hasAiClassification}"
                :aria-pressed="reviewFilter.highRisk"
                :disabled="!hasAiClassification"
                :title="hasAiClassification ? t('dt.chip.highRiskTip') : t('dt.chip.highRiskNeedAi')"
                @click="toggleHighRisk">
          {{ t('dt.chip.highRisk') }} <span>{{ reviewStats.highRisk }}</span>
        </button>
        <button type="button" class="dt-chip dt-chip-fmt"
                :class="{active: reviewFilter.onlyFormatted}"
                :aria-pressed="reviewFilter.onlyFormatted"
                :title="t('dt.chip.formattedTip')"
                @click="toggleFormattedOnly">
          {{ t('dt.chip.formatted') }} <span>{{ formattedOnlyCount }}</span>
        </button>
        <button type="button" class="dt-chip dt-chip-bm"
                :class="{active: reviewFilter.onlyBookmarks, disabled: reviewStats.bookmarks === 0}"
                :aria-pressed="reviewFilter.onlyBookmarks"
                :disabled="reviewStats.bookmarks === 0"
                :title="reviewStats.bookmarks ? t('dt.chip.bookmarksTip') : t('dt.chip.bookmarksEmpty')"
                @click="toggleBookmarksOnly">
          <i class="bi bi-bookmark"></i> {{ t('dt.chip.bookmarks') }} <span>{{ reviewStats.bookmarks }}</span>
        </button>
        <button v-if="filtersActive" type="button" class="dt-chip dt-chip-clear"
                :title="t('dt.clearFiltersTip')" @click="clearAllFilters()">
          <i class="bi bi-x-circle"></i> {{ t('dt.clearFilters') }}
        </button>
      </div>
    </div>

    <!-- 搜索框 + 正则开关 + 「...」视图/排序菜单（过滤栏可通过菜单收起，偏好记忆于 localStorage） -->
    <div class="dt-toolbar px-2 py-1" v-if="state.config && filterBarVisible">
      <div class="d-flex align-items-center gap-2">
        <div class="input-group input-group-sm flex-1">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input ref="filterInputRef" class="form-control" type="text" v-model="state.config.filterSearch"
                 :placeholder="t('tree.filterPlaceholder')" :aria-label="t('dt.searchAria')">
          <button class="btn btn-outline-secondary" type="button" :class="{active: state.config.filterRegex}"
                  @click="state.config.filterRegex = !state.config.filterRegex"
                  :title="state.config.filterRegex ? t('dt.regexModeTip') : t('dt.fuzzyModeTip')">
            <i class="bi bi-regex"></i>
          </button>
        </div>
        <!-- 全部展开/全部折叠（逐层动画 + 延迟 Tooltip；仅树视图可用） -->
        <div class="d-flex align-items-center gap-1">
          <TipButton icon="bi-plus-square" :tooltip="expandTooltip" :delay="400"
                     :disabled="viewMode !== 'tree'" @click="expandAllDirs()" />
          <TipButton icon="bi-dash-square" :tooltip="collapseTooltip" :delay="400"
                     :disabled="viewMode !== 'tree'" @click="collapseAllDirs()" />
        </div>
        <div class="position-relative" ref="viewMenuWrap">
          <button class="btn btn-outline-secondary btn-sm" type="button"
                  @click="showViewMenu = !showViewMenu"
                  :aria-expanded="showViewMenu"
                  :title="t('dt.viewSortMenu')" :aria-label="t('dt.viewSortMenu')">
            <i class="bi bi-three-dots"></i>
          </button>
          <ul class="dropdown-menu dropdown-menu-end show py-1" v-if="showViewMenu"
              style="position:absolute;right:0;top:100%;z-index:1000;min-width:14rem">
            <li><h6 class="dropdown-header" style="font-size:.72rem;padding:.25rem 1rem">{{ t('dt.viewModeHeader') }}</h6></li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setViewMode('tree')"
                 :class="{active: viewMode === 'tree'}" :title="t('dt.viewTreeTip')">
                <i class="bi me-2" :class="viewMode === 'tree' ? 'bi-check2' : 'bi-diagram-3'" style="width:1rem"></i>
                {{ t('dt.viewTree') }}
              </a>
            </li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setViewMode('list')"
                 :class="{active: viewMode === 'list'}" :title="t('dt.viewListTip')">
                <i class="bi me-2" :class="viewMode === 'list' ? 'bi-check2' : 'bi-list-ul'" style="width:1rem"></i>
                {{ t('dt.viewList') }}
              </a>
            </li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setViewMode('queue')"
                 :class="{active: viewMode === 'queue'}" :title="t('dt.viewQueueTip')">
                <i class="bi me-2" :class="viewMode === 'queue' ? 'bi-check2' : 'bi-list-check'" style="width:1rem"></i>
                {{ t('dt.viewQueue') }}
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li><h6 class="dropdown-header" style="font-size:.72rem;padding:.25rem 1rem">{{ t('dt.reviewMenuHeader') }}</h6></li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="toggleAutoNext"
                 :class="{active: autoNextUnreviewed}" :title="t('dt.autoNextTip')">
                <i class="bi me-2" :class="autoNextUnreviewed ? 'bi-check2' : 'bi-arrow-right-circle'" style="width:1rem"></i>
                {{ t('dt.autoNext') }}
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#"
                 @click.prevent="setFilterBarVisible(!filterBarVisible)"
                 :class="{active: filterBarVisible}"
                 :title="t('dt.filterBarTip')">
                <i class="bi me-2" :class="filterBarVisible ? 'bi-check2' : 'bi-funnel'" style="width:1rem"></i>
                {{ t('dt.showFilterBar') }}
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li><h6 class="dropdown-header" style="font-size:.72rem;padding:.25rem 1rem">{{ t('dt.sortHeader') }}</h6></li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setSortMode('path')"
                 :class="{active: sortMenuKey === 'path', disabled: viewMode !== 'list'}"
                 :title="viewMode !== 'list' ? t('dt.sortLockedTip') : t('dt.sortPathTipList')">
                <i class="bi me-2" :class="sortMenuKey === 'path' ? 'bi-check2' : 'bi-sort-alpha-down'" style="width:1rem"></i>
                {{ t('dt.sortPath') }}
              </a>
            </li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setSortMode('name')"
                 :class="{active: sortMenuKey === 'name', disabled: viewMode !== 'list'}"
                 :title="viewMode !== 'list' ? t('dt.sortLockedTip') : t('dt.sortNameTip')">
                <i class="bi me-2" :class="sortMenuKey === 'name' ? 'bi-check2' : 'bi-sort-alpha-down'" style="width:1rem"></i>
                {{ t('dt.sortName') }}
              </a>
            </li>
            <li>
              <a class="dropdown-item d-flex align-items-center" href="#" @click.prevent="setSortMode('status')"
                 :class="{active: sortMenuKey === 'status', disabled: viewMode !== 'list'}"
                 :title="viewMode !== 'list' ? t('dt.sortLockedTip') : t('dt.sortStatusTip')">
                <i class="bi me-2" :class="sortMenuKey === 'status' ? 'bi-check2' : 'bi-flag'" style="width:1rem"></i>
                {{ t('dt.sortStatus') }}
              </a>
            </li>
          </ul>
        </div>
      </div>
      <div class="form-text mt-1" style="font-size:.72rem" v-if="state.config.filterRegex">
        <span v-html="t('dt.regexHint')"></span>
      </div>
    </div>

    <!-- 状态过滤：可直接勾选显示/隐藏差异文件（收起时随过滤栏一并隐藏，命中计数保留在标题行） -->
    <div class="dt-filters px-2 py-1 d-flex flex-wrap gap-2" v-if="state.config && filterBarVisible">
      <div class="form-check form-check-inline mb-0">
        <input class="form-check-input" type="checkbox" id="ftMod" v-model="state.config.filterShowModified" :title="t('dt.showModifiedTip')">
        <label class="form-check-label" for="ftMod" :title="t('dt.showModifiedTip')"><i class="bi bi-circle-fill me-1" style="font-size:.5rem;color:var(--bs-warning)"></i>{{ t('info.modified') }} {{ counts.MODIFIED }}</label>
      </div>
      <div class="form-check form-check-inline mb-0">
        <input class="form-check-input" type="checkbox" id="ftAdd" v-model="state.config.filterShowAdded" :title="t('dt.showAddedTip')">
        <label class="form-check-label" for="ftAdd" :title="t('dt.showAddedTip')"><i class="bi bi-circle-fill me-1" style="font-size:.5rem;color:var(--bs-success)"></i>{{ t('info.added') }} {{ counts.ADDED }}</label>
      </div>
      <div class="form-check form-check-inline mb-0">
        <input class="form-check-input" type="checkbox" id="ftDel" v-model="state.config.filterShowDeleted" :title="t('dt.showDeletedTip')">
        <label class="form-check-label" for="ftDel" :title="t('dt.showDeletedTip')"><i class="bi bi-circle-fill me-1" style="font-size:.5rem;color:var(--bs-danger)"></i>{{ t('info.deleted') }} {{ counts.DELETED }}</label>
      </div>
      <div class="form-check form-check-inline mb-0">
        <input class="form-check-input" type="checkbox" id="ftUnc" v-model="state.config.filterShowUnchanged" :title="t('dt.showUnchangedTip')">
        <label class="form-check-label" for="ftUnc" :title="t('dt.showUnchangedTip')"><i class="bi bi-circle-fill me-1" style="font-size:.5rem;color:var(--bs-secondary)"></i>{{ t('info.unchanged') }} {{ counts.UNCHANGED }}</label>
      </div>
    </div>

    <!-- 智能分类：风险排序与风险过滤（仅在做完智能分类后出现） -->
    <div class="dt-ai px-2 py-1 d-flex align-items-center gap-2 flex-wrap" v-if="Object.keys(aiClassify).length">
      <button class="btn btn-sm btn-outline-secondary py-0" :class="{active: state.config.sortByRisk}"
              @click="state.config.sortByRisk = !state.config.sortByRisk"
              :title="t('dt.sortByRiskTip')">
        <i class="bi bi-sort-down"></i>
      </button>
      <div class="btn-group btn-group-sm" role="group" :title="t('dt.riskFilterGroup')">
        <button v-for="r in ['HIGH','MEDIUM','LOW']" :key="r" type="button"
                class="btn" :class="(state.config.filterRisk||[]).includes(r) ? 'btn-secondary' : 'btn-outline-secondary'"
                @click="toggleRisk(r)">{{ riskLabel(r) }}</button>
      </div>
      <span class="text-secondary" style="font-size:.72rem">{{ t('dt.aiMarked', { n: Object.keys(aiClassify).length }) }}</span>
    </div>

    <!-- 虚拟滚动容器：仅渲染可视行，数千节点也不卡顿 -->
    <div class="tree-scroll" ref="scrollEl" @scroll="onScroll">
      <div class="vt-root" :style="{ height: totalH + 'px' }">
        <div class="vt-window" :style="{ transform: 'translateY(' + (start * ROW_H) + 'px)' }">
          <template v-for="row in slice"
                    :key="row.kind === 'header' ? ('h-' + row.layer)
                         : (row.kind === 'dir' ? ('d-' + row.layer + ':' + row.key) : (row.key || ('l-' + row.parentKey)))">
            <div v-if="row.kind === 'header'" class="list-group-item grp-head py-0 px-2 d-flex align-items-center"
                 :style="rowStyle" :title="(LAYER_TITLE[row.layer] ? t(LAYER_TITLE[row.layer]) : '') || row.label">
              {{ row.label }}（{{ row.count }}）
            </div>
            <!-- 归档展开加载占位 -->
            <div v-else-if="row.kind === 'archiveLoading'" class="list-group-item tree-node d-flex align-items-center gap-2 py-0 px-2 text-secondary"
                 :style="rowStyleFor(row)">
              <span class="caret-slot"></span>
              <span class="spinner-border spinner-border-sm" style="width:.6rem;height:.6rem"></span>
              <span class="node-key text-truncate flex-1" style="font-size:.78rem">{{ t('dt.expandingItems') }}</span>
            </div>
            <!-- 目录节点：点击展开/折叠（VS Code 风格，仅显示当前层级目录名） -->
            <button v-else-if="row.kind === 'dir'" type="button"
                    class="list-group-item list-group-item-action tree-node tree-dir d-flex align-items-center gap-2 py-0 px-2"
                    :style="rowStyleFor(row)"
                    :class="{'base-folder': row.node && state.baseFolder === row.node.key, flash: locateFlash === row.key}"
                    :title="row.key"
                    @click="toggleDir(row.layer, row.dirKey)"
                    @contextmenu.prevent="row.node && openCtxMenu(row.node, $event)">
              <span class="caret-slot">
                <i class="bi tree-caret"
                   :class="expandedDirs[zoneDirKey(row.layer, row.dirKey)] === false ? 'bi-chevron-right' : 'bi-chevron-down'"
                   :title="expandedDirs[zoneDirKey(row.layer, row.dirKey)] === false ? t('dt.expandDir') : t('dt.collapseDir')"></i>
              </span>
              <i v-if="row.node" class="bi bi-circle-fill" style="font-size:.5rem" :style="{color: statusMeta(row.node.status).dot}"></i>
              <i class="bi" :class="expandedDirs[zoneDirKey(row.layer, row.dirKey)] === false ? 'bi-folder' : 'bi-folder2-open'"
                 style="color:var(--bs-warning)"></i>
              <span class="node-key text-truncate flex-1">{{ row.name }}</span>
              <span v-if="row.node" class="badge text-bg-light badge-fc border"
                    :title="t('dt.fileType', { fc: row.node.fileClass })">{{ row.node.fileClass }}</span>
              <template v-if="row.node && aiClassify[row.node.key]">
                <span class="badge" :class="riskMeta(aiClassify[row.node.key].risk).cls"
                      :title="t('dt.aiRisk', { label: riskLabel(aiClassify[row.node.key].risk) })">{{ riskLabel(aiClassify[row.node.key].risk) }}</span>
              </template>
            </button>
            <!-- jar 包内目录节点：按内部路径层级显示，可折叠/展开 -->
            <button v-else-if="row.kind === 'archiveDir'" type="button"
                    class="list-group-item list-group-item-action tree-node tree-dir d-flex align-items-center gap-2 py-0 px-2"
                    :style="rowStyleFor(row)"
                    :title="row.node ? row.node.name : row.key"
                    @click="toggleArchiveDir(row.key)">
              <span class="caret-slot">
                <i class="bi tree-caret"
                   :class="expandedArchiveDirs[row.key] === false ? 'bi-chevron-right' : 'bi-chevron-down'"
                   :title="expandedArchiveDirs[row.key] === false ? t('dt.expandDir') : t('dt.collapseDir')"></i>
              </span>
              <i class="bi" :class="expandedArchiveDirs[row.key] === false ? 'bi-folder' : 'bi-folder2-open'"
                 style="color:var(--bs-warning)"></i>
              <span class="node-key text-truncate flex-1">{{ row.name }}</span>
              <span v-if="row.node" class="badge text-bg-light badge-fc border"
                    :title="t('dt.statusOf', { status: row.node.status })">{{ row.node.fileClass }}</span>
            </button>
            <!-- 文件节点：点击打开内容比对；按目录深度缩进 + 类型图标 -->
            <button v-else type="button"
                    class="list-group-item list-group-item-action tree-node d-flex align-items-center gap-2 py-0 px-2"
                    :style="rowStyleFor(row)"
                    :class="{active: state.activeKey === row.node.key, 'child-row': row.kind === 'archiveChild', 'base-folder': state.baseFolder === row.node.key, flash: locateFlash === row.key}"
                    :title="row.node.key"
                    @click="onNodeClick(row.node)"
                    @contextmenu.prevent="openCtxMenu(row.node, $event)">
              <!-- 归档节点：左侧 caret 折叠/展开（点击不触发打开）；非归档：占位保持对齐 -->
              <span class="caret-slot">
                <i v-if="row.node.fileClass === 'ARCHIVE' || row.node.fileClass === 'JAR'"
                   class="bi tree-caret"
                   :class="state.expandedArchives[row.node.key] ? 'bi-chevron-down' : 'bi-chevron-right'"
                   @click.stop="toggleArchive(row.node.key)"
                   :title="state.expandedArchives[row.node.key] ? t('dt.collapseInner') : t('dt.expandInner')"></i>
              </span>
              <i class="bi bi-circle-fill" style="font-size:.5rem" :style="{color: statusMeta(row.node.status).dot}"></i>
              <i class="bi" :class="fileIcon(row.node.fileClass)" style="color:var(--bs-secondary-color)"></i>
              <span class="node-key text-truncate flex-1" :class="{'child-name': row.kind === 'archiveChild'}">{{ row.name || row.node.name || basenameOf(row.node.key) }}</span>
              <!-- T06218 文件级审阅状态：与状态栏/文件栏同源 state.review，标记后同帧更新（AC-10） -->
              <i v-if="hasReviewBadge(row.node.key)" class="bi review-tree-ic"
                 :class="reviewIconOf(row.node.key)" :style="{color: reviewColorOf(row.node.key)}"
                 :title="reviewLabelOf(row.node.key)"></i>
              <span v-if="noteCountOf(row.node.key)" class="review-tree-mark" :title="t('dt.stat.notesTip')">
                <i class="bi bi-chat-left-text"></i>{{ noteCountOf(row.node.key) }}
              </span>
              <i v-if="bookmarkCountOf(row.node.key)" class="bi bi-bookmark-fill review-tree-bm" :title="t('dt.stat.bookmarksTip')"></i>
              <span class="badge text-bg-light badge-fc border" :title="t('dt.fileType', { fc: row.node.fileClass || t('dt.unknown') })">{{ row.node.fileClass }}</span>
              <!-- T01810 FR-006 展示层：AST 口径「格式或位置变更」——被折叠为未变的格式化/重排差异显式可辨 -->
              <span v-if="row.node.formattedOnly" class="badge text-bg-light badge-fc border"
                    :title="t('dt.formattedOnly')"><i class="bi bi-brush" style="font-size:.68rem"></i> {{ t('dt.formattedOnly') }}</span>
              <template v-if="aiClassify[row.node.key] && row.kind !== 'archiveChild'">
                <span class="badge" :class="riskMeta(aiClassify[row.node.key].risk).cls"
                      :title="t('dt.aiRisk', { label: riskLabel(aiClassify[row.node.key].risk) })">{{ riskLabel(aiClassify[row.node.key].risk) }}</span>
                <span class="badge text-bg-light badge-fc border"
                      :title="t('dt.aiClassified', { cat: aiClassify[row.node.key].category, reason: aiClassify[row.node.key].reason || '' })">{{ aiClassify[row.node.key].category }}</span>
              </template>
            </button>
          </template>
        </div>
      </div>
      <!-- T03669 三态空态：比对失败 / 尚未比对 / 确实无差异。
           此前三者共用一句 dt.empty，比对失败（toast 3~5 秒后消失）就被显示成「暂无差异」，
           在发布评审场景会被误读成「两个包完全一致」。 -->
      <div v-if="state.error" class="dt-failed text-center py-4" style="font-size:.8rem">
        <i class="bi bi-exclamation-octagon-fill d-block mb-1" style="font-size:1.3rem"></i>
        <div class="fw-semibold">{{ t('dt.failed') }}</div>
        <div class="mt-1 px-3" style="word-break:break-all">{{ state.error }}</div>
        <div class="dt-failed-tip mt-2">{{ t('dt.failedTip') }}</div>
      </div>
      <div v-else-if="state.job && !jobHasDiff" class="text-center text-secondary py-4" style="font-size:.8rem">
        {{ state.sameInputCompare ? t('dt.identicalSame') : t('dt.identical') }}
      </div>
      <div v-else-if="!tree.length" class="text-center text-secondary py-4" style="font-size:.8rem">
        {{ state.job ? t('dt.noMatch') : t('dt.empty') }}
      </div>
      <!-- 修复「勾选过滤条件后机构树消失」：tree 非空但 filtered 全被过滤掉时，给出明确提示
           （避免渲染区空白用户无所适从）。常见诱因：4 个过滤全未勾选 / 搜索/风险过滤过严。 -->
      <div v-else-if="!filtered.length" class="text-center text-secondary py-4" style="font-size:.8rem">
        <i class="bi bi-funnel d-block mb-1" style="font-size:1.2rem"></i>
        {{ t('dt.noMatch') }}
        <div class="mt-2">
          <button type="button" class="btn btn-sm btn-outline-secondary py-0" style="font-size:.72rem"
                  @click="clearAllFilters()" :title="t('dt.clearFiltersTip')">{{ t('dt.clearFilters') }}</button>
        </div>
      </div>
      <div v-else-if="viewMode === 'queue' && !rows.length" class="text-center text-secondary py-4" style="font-size:.8rem">
        <i class="bi bi-list-check d-block mb-1" style="font-size:1.2rem"></i>
        {{ t('dt.queueEmpty') }}
      </div>
      <!-- 排除恢复条：右键「排除」的条目集中在这里一键恢复 -->
      <div v-if="excludedCount > 0" class="dt-excluded-bar d-flex align-items-center gap-2 px-2 py-1">
        <i class="bi bi-eye-slash text-warning"></i>
        <span class="flex-1 text-truncate" style="font-size:.75rem">{{ t('dt.excludedBar', { n: excludedCount }) }}</span>
        <button type="button" class="btn btn-sm btn-outline-secondary py-0" style="font-size:.72rem"
                @click="restoreAllExcluded()" :title="t('dt.restoreAllTip')">{{ t('dt.restore') }}</button>
      </div>
    </div>
  </div>

  <!-- 右键菜单（Teleport） -->
  <ContextMenu :visible="ctxMenu.visible" :x="ctxMenu.x" :y="ctxMenu.y" :items="menuItems"
               @select="runAction" @close="closeCtxMenu" />

  <!-- 属性弹窗 -->
  <div v-if="state.propertyNode" class="modal fade show d-block" tabindex="-1" role="dialog"
       style="background: var(--bdf-overlay-strong)" @click.self="hideProperties()">
    <div class="modal-dialog modal-dialog-centered" role="document" @keydown.esc="hideProperties()">
      <div class="modal-content">
        <div class="modal-header py-2">
          <h6 class="modal-title"><i class="bi bi-info-circle me-1"></i> {{ t('dt.props') }}</h6>
          <button type="button" class="btn-close" :aria-label="t('common.close')" @click="hideProperties()"></button>
        </div>
        <div class="modal-body" style="font-size:.82rem">
          <table class="table table-sm table-striped mb-0">
            <tbody>
              <tr><th class="text-nowrap" style="width:9rem">{{ t('dt.propPath') }}</th><td class="text-break">{{ state.propertyNode.key }}</td></tr>
              <tr><th>{{ t('dt.propStatus') }}</th><td><span class="badge" :class="statusMeta(state.propertyNode.status).cls">{{ statusLabel(state.propertyNode.status) }}</span></td></tr>
              <tr><th>{{ t('dt.propType') }}</th><td>{{ state.propertyNode.fileClass || t('dt.unknown') }}</td></tr>
              <tr><th>{{ t('dt.propSize') }}</th><td>{{ fmtSize(state.propertyNode.size) }}</td></tr>
              <tr v-if="aiClassify[state.propertyNode.key]"><th>{{ t('dt.propAi') }}</th><td>{{ aiClassify[state.propertyNode.key].category }}{{ t('dt.propRisk', { label: riskLabel(aiClassify[state.propertyNode.key].risk) }) }}</td></tr>
              <tr><th>{{ t('dt.propDisk') }}</th><td class="text-break">{{ propDiskPath || t('dt.propNoDisk') }}</td></tr>
            </tbody>
          </table>
          <div class="mt-2" v-if="state.ignoreRules.length">
            <div class="d-flex align-items-center gap-2">
              <span class="text-secondary" style="font-size:.75rem">{{ t('dt.ignoreTitle') }}</span>
              <button type="button" class="btn btn-sm btn-outline-secondary py-0 ms-auto" style="font-size:.7rem" @click="clearIgnoreRules()">{{ t('dt.ignoreClear') }}</button>
            </div>
            <ul class="list-unstyled mb-0 mt-1" style="max-height:8rem;overflow:auto">
              <li v-for="(r, i) in state.ignoreRules" :key="i" class="d-flex align-items-center gap-2" style="font-size:.75rem">
                <span class="badge text-bg-light border">{{ ruleTypeLabel(r.type) }}</span>
                <span class="flex-1 text-truncate">{{ r.value }}</span>
                <button type="button" class="btn btn-sm btn-outline-danger py-0" style="font-size:.65rem" @click="removeIgnoreRule(i)">{{ t('common.delete') }}</button>
              </li>
            </ul>
          </div>
        </div>
        <div class="modal-footer py-1">
          <button type="button" class="btn btn-sm btn-secondary" @click="hideProperties()">{{ t('common.close') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dt-review-head { border-bottom: 1px solid var(--bs-border-color); background: var(--bs-body-bg); }
.dt-review-stats { display: flex; flex-wrap: wrap; align-items: center; gap: .2rem .7rem; font-size: .72rem; color: var(--bs-secondary-color); }
.dt-stat { display: inline-flex; align-items: center; gap: .2rem; white-space: nowrap; }
.dt-stat b { color: var(--bs-body-color); font-weight: 600; font-variant-numeric: tabular-nums; }
.dt-stat small { color: var(--bs-secondary-color); margin-left: .12rem; }
.dt-review-chips { display: flex; flex-wrap: wrap; gap: .25rem; margin-top: .35rem; }
.dt-chip {
  border: 1px solid var(--bs-border-color);
  background: transparent;
  color: var(--bs-body-color);
  border-radius: 999px;
  font-size: .7rem;
  line-height: 1.5;
  padding: .02rem .45rem;
  white-space: nowrap;
}
.dt-chip span { color: var(--bs-secondary-color); margin-left: .18rem; font-variant-numeric: tabular-nums; }
.dt-chip:hover { background: var(--bs-tertiary-bg); }
.dt-chip.active { background: var(--bs-primary); border-color: var(--bs-primary); color: #fff; }
.dt-chip.active span { color: rgba(255,255,255,.82); }
.dt-chip-risk.active { background: var(--bs-danger); border-color: var(--bs-danger); }
.dt-chip.disabled { opacity: .55; cursor: not-allowed; }
.dt-chip-clear { color: var(--bs-secondary-color); border-style: dashed; }
.review-tree-mark {
  display: inline-flex; align-items: center; gap: .12rem; font-size: .68rem;
  color: var(--bs-primary); flex: 0 0 auto;
}
.review-tree-bm { color: var(--bs-primary); font-size: .72rem; flex: 0 0 auto; }
.dt-toolbar { border-bottom: 1px solid var(--bs-border-color); }
.dt-filters { border-bottom: 1px solid var(--bs-border-color); }
.dt-filters .form-check-label { font-size: .8rem; color: var(--bs-body-color); }
.dt-ai { border-bottom: 1px solid var(--bs-border-color); background: var(--bs-tertiary-bg); }
.dt-ai .btn-group-sm .btn { font-size: .72rem; padding: .1rem .5rem; }
/* 虚拟滚动：滚动容器已 overflow:auto（main.css）；内部绝对定位窗口按 translateY 偏移可视切片 */
.vt-root { position: relative; width: 100%; }
.vt-window { position: absolute; top: 0; left: 0; right: 0; }
.tree-node { overflow: hidden; }
.review-tree-ic { font-size: .68rem; flex: 0 0 auto; }
/* 归档展开 caret：与状态点同宽占位，保证子节点与父节点对齐 */
.caret-slot { display: inline-flex; width: .9rem; flex: 0 0 auto; justify-content: center; }
.tree-caret { font-size: .7rem; color: var(--bs-secondary-color); cursor: pointer; }
.tree-caret:hover { color: var(--bs-body-color); }
/* 内部条目子节点：轻微底色提示其归属父归档（缩进由行级 depth 内联控制，与目录树层级一致） */
.child-row { background: var(--bs-tertiary-bg); }
.child-row.child-name { font-size: .82rem; }
/* 目录节点：加粗 + 悬停底色，与文件节点区分（VS Code 风格） */
.tree-dir { font-weight: 600; }
.tree-dir:hover { background: var(--bs-tertiary-bg); }
/* 基准文件夹（右键「设为基准文件夹」）：描边高亮，提示其为基准锚点 */
.base-folder {
  box-shadow: inset 2px 0 0 var(--bs-primary);
  background: var(--bs-primary-bg-subtle) !important;
}
.base-folder:hover { background: var(--bs-primary-bg-subtle) !important; }
/* 路径定位（PathBar 面包屑/编辑框触发）flash 高亮：主色淡入淡出，提示目标位置 */
.tree-node.flash { animation: treeFlash 1.4s ease-out; }
@keyframes treeFlash {
  0% { background: var(--bs-primary-bg-subtle); }
  100% { background: transparent; }
}
/* 排除恢复条：贴树底部，提示可一键恢复 */
.dt-excluded-bar {
  position: sticky;
  bottom: 0;
  border-top: 1px solid var(--bs-border-color);
  background: var(--bs-warning-bg-subtle);
  z-index: 5;
}
/* T03669 比对失败态：常驻红色错误条，替代会被误读成「两个包一致」的灰色空态 */
.dt-failed {
  color: var(--bs-danger-text-emphasis);
  background: var(--bs-danger-bg-subtle);
  border-top: 1px solid var(--bs-danger-border-subtle);
}
.dt-failed-tip { color: var(--bs-danger); font-size: .74rem; }
</style>
