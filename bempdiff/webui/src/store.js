import { reactive } from 'vue'
import { api } from './api/client'
import { activateTab as activateTabImpl, closeTabReducer as closeTabImpl, pinTabReducer as pinTabImpl,
  closeOtherTabsReducer as closeOtherTabsImpl, closeAllTabsReducer as closeAllTabsImpl, findActiveTab } from './lib/tabs'
import { extractFromFileName, sameBaseDifferentVersion, orderOldNew } from './lib/version'
import { loadIgnoreRules, saveIgnoreRules, addRule as addRuleFn, removeRule as removeRuleFn } from './lib/ignore'
import { hlThemeOf } from './lib/hl_themes'
import { friendlyAiError } from './lib/ai_error'
import { resolveAppVersion } from './lib/helpContent'
import { buildDiffCsv, downloadCsv } from './lib/csv_export'
import { initTokenRegistry, applyTokenTheme, THEMES, THEME_NAMES, resolveTokenTheme, resolveBootstrapMode } from './design/tokenRegistry'
import { t } from './lib/i18n'
// 别名导入：runAiTaskStream(task) 等函数以 t 作任务参数名，函数体内用 tr() 取译文避免覆蔽冲突
import { t as tr } from './lib/i18n'
import { REVIEW_STATUS, emptyReviewDoc, normalizeReviewDoc, summarizeReview, updateReviewFile, migrateLegacyReviews } from './lib/review'
import {
  normalizeFileNotes, normalizeFileBookmarks, normalizeReviewNote, upsertReviewNote, removeReviewNote,
  toggleReviewBookmark, validateReviewNote, noteTargetKey
} from './lib/review_notes'

// 状态相关展示常量，集中维护，DiffTree/DiffView/InfoPanel 共享，避免重复定义
// label 保留中文原文（兼容既有单测断言）；labelKey 为 i18n 键（渲染侧优先 t(labelKey)）
export const STATUS_META = {
  ADDED:     { cls: 'text-bg-success', label: '新增', labelKey: 'info.status.ADDED', dot: 'var(--bs-success)' },
  DELETED:   { cls: 'text-bg-danger',  label: '删除', labelKey: 'info.status.DELETED', dot: 'var(--bs-danger)' },
  MODIFIED:  { cls: 'text-bg-warning', label: '修改', labelKey: 'info.status.MODIFIED', dot: 'var(--bs-warning)' },
  UNCHANGED: { cls: 'text-bg-secondary', label: '未变', labelKey: 'info.status.UNCHANGED', dot: 'var(--bs-secondary)' }
}
export const STATUS_LABEL = { ADDED: '新增', DELETED: '删除', MODIFIED: '修改', UNCHANGED: '未变' }
/** 状态显示名（渲染期求值，支持切语言即时刷新）。 */
export function statusLabel(s) {
  const m = STATUS_META[s]
  return m ? (m.labelKey ? t(m.labelKey) : m.label) : String(s || '')
}

// tabs 中每项的形状：{ key, node, decompile, busy, error }
//   key       - 节点的全局 key（state.job.tree 中唯一）
//   node      - 该 key 对应的树节点快照（避免 DiffView 二次 find 树）
//   decompile - 反编译结果，null 表示还没好；{ ok:false, ... } 表示不可反编译
//   busy      - true 表示反编译请求中（即便 decompile 已为 null）
//   error     - 反编译抛异常的 message；与 decompile.ok=false 区分（一个是后端说不行，一个是网络/解析挂了）
// ===== T06219 工作区 IA / 旧布局回退 =====
// 默认启用新版 Compare Bar + 三栏 IA；URL `?reviewCockpit=0` 或 localStorage 可强制回退旧 ToolBar。
// 仅影响布局壳，job/paths/review/tabs 等数据全部留在同一 state，切换不丢数据（AC-05）。
const REVIEW_COCKPIT_KEY = 'bempdiff.reviewCockpit'
function readReviewCockpit() {
  try {
    if (typeof window !== 'undefined') {
      const q = new URLSearchParams(window.location.search).get('reviewCockpit')
      if (q !== null) return !/^(0|false|off)$/i.test(q)
      const raw = localStorage.getItem(REVIEW_COCKPIT_KEY)
      if (raw !== null) return !/^(0|false|off)$/i.test(raw)
    }
  } catch (_) { /* 本地存储不可用默认新版 */ }
  return true
}
export function setReviewCockpit(v) {
  state.reviewCockpit = !!v
  try { localStorage.setItem(REVIEW_COCKPIT_KEY, state.reviewCockpit ? '1' : '0') } catch (_) { /* 静默 */ }
}
export function toggleReviewCockpit() { setReviewCockpit(!state.reviewCockpit) }

// T06223 Launchpad：默认作为工作区首屏；本地偏好可关闭。旧 IA（reviewCockpit=0）不显示。
const LAUNCHPAD_KEY = 'bempdiff.launchpad'
function readLaunchpadPref() {
  try {
    const raw = localStorage.getItem(LAUNCHPAD_KEY)
    if (raw !== null) return !/^(0|false|off)$/i.test(raw)
  } catch (_) { /* 本地存储不可用默认展示 */ }
  return true
}
export function setLaunchpadEnabled(v) {
  state.launchpadOpen = !!v
  try { localStorage.setItem(LAUNCHPAD_KEY, state.launchpadOpen ? '1' : '0') } catch (_) { /* 静默 */ }
}
/** 只改「下次启动是否显示」偏好，不关闭当前工作台。 */
export function setLaunchpadStartup(v) {
  try { localStorage.setItem(LAUNCHPAD_KEY, v ? '1' : '0') } catch (_) { /* 静默 */ }
}
export function isLaunchpadStartupEnabled() { return readLaunchpadPref() }
export function openLaunchpad() {
  state.launchpadOpen = true
  state.mobileTreeOpen = false
  state.mobileAiOpen = false
}
export function closeLaunchpad() { state.launchpadOpen = false }
export function setInsightTab(tab) {
  const allowed = ['overview', 'compat', 'impact', 'evidence', 'console']
  state.insightTab = allowed.includes(tab) ? tab : 'overview'
  try { localStorage.setItem('bempdiff.insightTab', state.insightTab) } catch (_) { /* 静默 */ }
}
function readInsightTab() {
  try {
    const saved = localStorage.getItem('bempdiff.insightTab')
    if (saved && ['overview', 'compat', 'impact', 'evidence', 'console'].includes(saved)) return saved
  } catch (_) { /* 静默 */ }
  return 'overview'
}

export const state = reactive({
  reviewCockpit: readReviewCockpit(), // T06219：新版工作区 IA；false 回退旧 ToolBar
  theme: localStorage.getItem('bempdiff-theme') || 'system', // 偏好值：light|dark|midnight|paper|contrast|system；首次启动默认 System（TH-02 AC5）
  diffPalette: localStorage.getItem('bempdiff.diffPalette') === 'colorblind' ? 'colorblind' : 'default', // TH-03 色盲安全 diff 调色板
  lastThemeSwitchMs: 0, // TH-02：上一次主题切换的 JS 落盘耗时（仅属性写入+localStorage，不代表首帧绘制）
  accent: 'blue',        // R10 主题强调色 key（ToolBar 启动时从 localStorage 恢复）
  job: null,
  leftType: 'package',     // 比对输入类型：package（war/jar）| folder（解压目录）
  oldPath: 'sample_v1.war',// 老包/老目录（对比基准）
  newPath: 'sample_v2.war',// 新包/新目录（对比目标）
  tabs: [],                // 多文件对比页（DiffView 顶部 tab 栏数据源）
  activeKey: null,         // 当前激活的 tab 对应节点 key
  focusMode: false,        // 差异对比「专注模式」：隐藏左右栏，放大中间 diff 视野
  filter: 'all',
  busy: false,
  busyText: '',
  analyzing: false,        // 后台 AI 报告生成中，不弹全屏遮罩
  reporting: false,        // 生成报告锁：防止自动报告（比对后）与手动报告并发重叠
  aiPanelCollapsed: false, // 智能分析栏收起状态（与 DiffView 文件栏一键显隐联动，避免遮挡对比窗口）
  treePanelCollapsed: false, // 差异文件树栏收起状态（收起后横向空间全部让给比对窗口）
  mobileTreeOpen: false,   // T06219 <1080 单栏：左树以抽屉覆盖层打开（不持久化）
  mobileAiOpen: false,     // T06219 <1080 单栏：右栏以抽屉覆盖层打开（不持久化）
  aiPanelTab: 'file',      // 智能分析栏内容子视图：file/global/break/audit/console（控制台为独立 tab，独占内容区高度）
  // ===== T06223 Launchpad / T06227-28 洞察页签 =====
  // Launchpad 默认开启（可在页面内关闭记住偏好）；旧布局（reviewCockpit=0）不展示，保证可回退。
  launchpadOpen: readLaunchpadPref(),
  launchpadBusy: false,
  launchpadInvalid: {},   // 历史项 key: oldPath|newPath → 失效原因（点击后探测，不主动扫描全部路径）
  insightTab: readInsightTab(), // overview | compat | impact | evidence | console
  insights: null,         // /api/job/{id}/insights 结构化结果
  insightsLoading: false,
  insightsError: '',
  hunkJump: null,         // { key, hunkIndex, seq } 洞察条目 → 文件/Hunk 定位请求
  aiClassify: {},          // 智能分类结果：key -> { risk(HIGH/MEDIUM/LOW), category, reason }（A1+B2）
  classifying: false,      // 智能分类进行中
  exporting: false,        // 差异资产导出中（驱动导出按钮的加载态，避免重复点击）
  exportProgress: null,    // 同步导出实时进度 { loaded, total, percent, etaText } 或 null
  exportRecords: [],       // 下载管理：导出记录列表（含异步进行中/已完成）
  syncExports: loadSyncExports(), // 下载管理：同步导出资产（本机浏览器下载目录，客户端持久化以便回溯）
  exportsOpen: false,      // 下载管理面板开关
  aiEstimate: null,        // 成本闸门：AI token 预估缓存 { report, analyze, classify, threshold }
  aiEstimateKey: null,     // 成本闸门：当前 aiEstimate 对应的缓存键（category|prompt 指纹），用于按分析项失效缓存
  costGate: null,          // 成本闸门：强制确认弹窗载荷 { action, estimate, threshold }（非 null 即弹窗）
  preflight: null,         // CX-09 预检确认弹窗载荷 { result, payload }（非 null 即弹窗）
  preflightBusy: false,    // 预检请求进行中（防重复点击）
  // 比对任务的实时进度（后端异步，前端轮询）：{ status, progress, phase, message } 或 null
  jobProgress: null,
  compareStartedAt: null,  // RUNNING 起始时刻（用于比对超时提醒）
  sameInputCompare: false, // 左右输入归一后是否为同一路径（结果为空时给「同一输入」而非「完全一致」的准确提示）
  compareStalled: false,   // RUNNING 持继超过阈值仍未结束
  stallNotified: false,    // 超时提醒已弹（仅一次），避免重复打扰
  activeCompareId: null,   // 当前正在轮询的比对 jobId；变化即视为中止旧轮询（支持重开/取消）
  config: null,
  // 项目级上下文（上下文目录递归识别）状态：{ loading, data, error }
  // data 来自 /api/ai/context：{ ok, rootPath, projectCount, scannedAt, cacheFile, fromCache, javaFileCount, projects[], message }
  aiContext: { loading: false, data: null, error: '' },
  reportMd: null,
  reportAi: false,
  // 报告缓存（按类别维度，修复「多类别报告并存互相覆盖」）：key = 'ai:<category>' | 'base' → markdown。
  // reportMd 始终指向「当前展示」的报告；切换分析项时命中缓存可秒开，无需重复调用后端。
  reportCache: {},
  // 并行 AI 分析任务模型（替代原阻塞模态；每个任务独立 tab + 独立控制台窗口，互不阻塞）
  aiTasks: [],            // { id, category, title, prompt, status, thinking[], answer, error }
  aiActiveTaskId: null,   // 当前聚焦的 AI 分析任务 tab
  compareHistory: [],     // R5 比对会话历史（跨启动 localStorage 持久化，仅路径元数据不含差异内容）
  savedSessions: [],      // FR-010 命名保存的常用对比任务（与自动历史分池，用户显式管理不自动淘汰）
  hlTheme: 'default',     // FR-014 语法高亮主题包 key（loadHlTheme 恢复；与界面明暗联动切换）
  treeFilterFocusTick: 0, // R6：全局快捷键 Ctrl+K 请求聚焦差异树过滤框（自增信号，DiffTree watch）
  aiSelCategory: 'risk',  // 「新建分析 / 生成报告(AI)」共享的当前分析项（risk/breaking/impact/testpoints/custom）
  reportCategory: null,   // 最近一次生成报告所用的分析项（ReportPreview「重新生成」沿用；null=基础报告/默认整体分析）
  previewMd: null,        // 控制台「预览报告」临时报告正文（与全局 reportMd 解耦，避免互相覆盖）
  previewOpen: false,
  // T06218 文件级审阅：服务端 reviews/{jobId}.json 为真源，state.review 只保留当前 job；
  // reviewSummary 由本地纯函数即时重算，保证标记后状态栏/树/文件栏同帧更新（AC-10）。
  review: emptyReviewDoc(),
  reviewSummary: summarizeReview({}),
  reviewAdvanceTick: 0, // T06220：当前文件由“非已审”变“已审”时自增，树侧据此自动跳下一未审
  reviewLoading: false,
  reviewError: '',
  reviewSaveState: 'idle', // idle | pending | saving | saved | error
  reviewMigration: null,   // { status:'migrated'|'failed'|'recovered', message }
  updateAvailable: null,  // R9 启动更新检查：发现新版本时 { tag, url, message }（不自动安装）
  error: null,
  toast: null,
  // 归档展开：复合键(outer!/inner) -> { loading, error, children:[{key,status,fileClass,size,name,expandable}] }
  archiveChildren: {},
  // 当前已展开的归档复合键集合（DiffTree 用于渲染子节点 + 旋转图标）
  expandedArchives: {},
  // 右键菜单（BCompare 风格）相关状态
  excludedKeys: {},        // 排除（视图临时隐藏，可一键恢复）：key -> true
  ignoreRules: [],         // 忽略规则（持久化 localStorage）：[{ type: 'exact'|'prefix'|'name'|'ext', value }]
  baseFolder: null,        // 基准文件夹 key（folder 模式，右键「设为基准文件夹」）
  propertyNode: null,      // 「属性」弹窗目标节点快照（非 null 即显示）
  // 差异树定位请求（PathBar 面包屑点击目录段 / 编辑框确认目录时触发）：
  // { prefix, seq } —— prefix 为相对路径前缀（'' = 顶部），seq 自增触发 DiffTree watch
  treeLocate: { prefix: null, seq: 0 },
  // AI 模型列表（按 API Base URL + Key 自动获取），供配置中心「模型名称」字段下拉/补全
  aiModels: []
})

let toastTimer = null
export function toast(type, text, ms = 3200) {
  state.toast = { type, text }
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { state.toast = null }, ms)
}

// ---------------- T06218 文件级审阅状态 ----------------
// 真源：后端 ~/.bempdiff/reviews/{jobId}.json；localStorage 只做「断网/重启 window 缓存」和旧数据迁移。
// 更新路径：UI 同步改 state.review/reviewSummary（100ms 内树/状态栏同帧），80ms 防抖后异步落盘。
const LEGACY_REVIEW_KEY = 'bempdiff.reviewed'
const REVIEW_CACHE_PREFIX = 'bempdiff.review.cache.'
let reviewSaveTimer = null
let reviewLoadSeq = 0

export function reviewFileInfo(key) {
  const f = state.review && state.review.files ? state.review.files[key] : null
  return f || null
}

export function reviewStatusOf(key) {
  const f = reviewFileInfo(key)
  return f ? f.status : 'unreviewed'
}

export function hasReviewBadge(key) {
  const f = reviewFileInfo(key)
  return !!(f && (f.manualOverride || f.status === 'in_progress' || f.status === 'reviewed' || f.status === 'needs_review'))
}

function reviewCacheKey(jobId) {
  return REVIEW_CACHE_PREFIX + jobId
}

function readReviewCache(jobId) {
  try {
    const raw = JSON.parse(localStorage.getItem(reviewCacheKey(jobId)) || 'null')
    return raw && raw.files ? normalizeReviewDoc(raw, jobId) : null
  } catch (_) { return null }
}

function writeReviewCache(doc) {
  try {
    if (!doc || !doc.jobId) return
    localStorage.setItem(reviewCacheKey(doc.jobId), JSON.stringify(doc))
  } catch (_) { /* 配额/隐私模式失败不影响服务端真源 */ }
}

function readLegacyJob(jobId) {
  try {
    const all = JSON.parse(localStorage.getItem(LEGACY_REVIEW_KEY) || '{}')
    return all && all[jobId] ? all[jobId] : null
  } catch (_) { return null }
}

function dropLegacyJob(jobId) {
  try {
    const all = JSON.parse(localStorage.getItem(LEGACY_REVIEW_KEY) || '{}')
    if (!all || !all[jobId]) return
    delete all[jobId]
    if (Object.keys(all).length) localStorage.setItem(LEGACY_REVIEW_KEY, JSON.stringify(all))
    else localStorage.removeItem(LEGACY_REVIEW_KEY)
  } catch (_) { /* 旧 key 清理失败不阻塞 */ }
}

function applyReviewDoc(doc) {
  state.review = normalizeReviewDoc(doc, doc && doc.jobId)
  state.reviewSummary = summarizeReview(state.review.files)
  syncInsightsReview()
  return state.review
}

/** 标记/覆盖文件审阅状态：本地即时生效，随后 80ms 防抖落盘（AC-10）。 */
export function updateReviewFileState(key, patch) {
  if (!key) return null
  const before = state.review && state.review.files ? state.review.files[key] : null
  const next = updateReviewFile(state.review, key, patch)
  state.review = next
  state.reviewSummary = summarizeReview(next.files)
  syncInsightsReview()
  const after = next.files[key]
  // 只对“当前打开文件从未审/进行中/需复核 → 已审”自增一次；撤销已审不会误触发。
  if (key === state.activeKey && after && after.status === REVIEW_STATUS.REVIEWED &&
      (!before || before.status !== REVIEW_STATUS.REVIEWED)) {
    state.reviewAdvanceTick = (state.reviewAdvanceTick || 0) + 1
  }
  state.reviewSaveState = 'pending'
  writeReviewCache(next)
  scheduleReviewSave()
  return next.files[key] || null
}

// ---------------- T06221 备注 / 书签 ----------------
// 备注与书签都挂在 files[key] 下，复用 T06218 的 80ms 防抖落盘与 localStorage 缓存；
// 不改文件状态时不会触发 reviewAdvanceTick（自动跳转只认「已审」状态变化）。
let reviewNoteSeq = 0
function reviewerName() {
  return (state.review && state.review.user) || 'local'
}
function nextNoteId() {
  reviewNoteSeq = (reviewNoteSeq + 1) % 100000
  return 'note-' + Date.now().toString(36) + '-' + reviewNoteSeq.toString(36)
}

export function reviewNotesOf(key) {
  const f = reviewFileInfo(key)
  return normalizeFileNotes(f && f.notes)
}

export function reviewBookmarksOf(key) {
  const f = reviewFileInfo(key)
  return normalizeFileBookmarks(f && f.bookmarks)
}

/** 新增/编辑备注。payload 需含 target（含 scope/hunkId/line/快照），编辑时传 id/createdAt。 */
export function saveReviewNote(key, payload = {}) {
  if (!key) return { ok: false, errorKey: 'rv.note.empty' }
  const check = validateReviewNote(payload.text)
  if (!check.ok) return check
  const target = payload.target || {}
  const existing = payload.id
    ? reviewNotesOf(key).find(n => n.id === payload.id)
    : null
  const note = normalizeReviewNote({
    ...target,
    id: payload.id || nextNoteId(),
    text: check.text,
    tag: payload.tag,
    user: reviewerName(),
    createdAt: (existing && existing.createdAt) || new Date().toISOString(),
    updatedAt: new Date().toISOString()
  })
  if (!note) return { ok: false, errorKey: 'rv.note.empty', text: check.text, length: check.length }
  const notes = upsertReviewNote(reviewFileInfo(key), note)
  updateReviewFileState(key, { notes })
  return { ok: true, note, notes }
}

export function deleteReviewNote(key, id) {
  if (!key || !id) return { ok: false }
  const notes = removeReviewNote(reviewFileInfo(key), id)
  updateReviewFileState(key, { notes })
  return { ok: true, notes }
}

/** 一键书签：同一目标再次点击即取消。target 需含 scope/hunkId/line/快照。 */
export function toggleReviewBookmarkState(key, target = {}) {
  if (!key) return { ok: false, active: false }
  const file = reviewFileInfo(key) || {}
  const res = toggleReviewBookmark(file, target, reviewerName())
  updateReviewFileState(key, { bookmarks: res.bookmarks })
  return { ok: true, ...res }
}

export function isReviewBookmarked(key, target = {}) {
  const tKey = target.targetKey || noteTargetKey(target.scope, target)
  return reviewBookmarksOf(key).some(b => b.targetKey === tKey)
}

function scheduleReviewSave(delay = 80) {
  if (!state.review || !state.review.jobId) return
  if (reviewSaveTimer) clearTimeout(reviewSaveTimer)
  reviewSaveTimer = setTimeout(() => {
    reviewSaveTimer = null
    flushReview().catch(() => {})
  }, delay)
}

/** 立即落盘（切文件/页面隐藏/退出前调用）。keepalive=true 供 beforeunload 尽力送达。 */
export async function flushReview(opts = {}) {
  if (reviewSaveTimer) { clearTimeout(reviewSaveTimer); reviewSaveTimer = null }
  const doc = state.review
  const jobId = doc && doc.jobId
  if (!jobId) return
  state.reviewSaveState = 'saving'
  try {
    const resp = await api.putReview(jobId, doc, opts)
    if (resp && resp.files && state.review.jobId === jobId) {
      state.review = normalizeReviewDoc(resp, jobId)
      state.reviewSummary = summarizeReview(state.review.files)
      writeReviewCache(state.review)
    }
    state.reviewSaveState = 'saved'
    state.reviewError = ''
  } catch (e) {
    state.reviewSaveState = 'error'
    state.reviewError = e.message || String(e)
    throw e
  }
}

/** 同一 job 的并发加载共享一个 Promise，避免恢复会话时重复迁移/重复 toast（迁移只应发生一次）。 */
const reviewLoads = new Map()

export function loadReviewForJob(jobId) {
  if (!jobId) return Promise.resolve()
  const existing = reviewLoads.get(jobId)
  if (existing) return existing
  const p = doLoadReviewForJob(jobId).finally(() => {
    if (reviewLoads.get(jobId) === p) reviewLoads.delete(jobId)
  })
  reviewLoads.set(jobId, p)
  return p
}

/** 读取当前 job 审阅文档：服务端 → window 缓存 → 旧 localStorage 迁移。 */
async function doLoadReviewForJob(jobId) {
  const seq = ++reviewLoadSeq
  state.reviewLoading = true
  state.reviewError = ''
  state.reviewMigration = null
  const empty = emptyReviewDoc(jobId)
  applyReviewDoc(empty)
  try {
    const server = await api.getReview(jobId, {})
    if (seq !== reviewLoadSeq) return
    if (server && server.exists) {
      applyReviewDoc(server)
      state.reviewSaveState = 'saved'
      writeReviewCache(state.review)
      return
    }
    // 服务端无文件：先看本 window 缓存（例如上次退出前 80ms 防抖未落盘），再尝试旧 localStorage 迁移。
    const cached = readReviewCache(jobId)
    if (cached && Object.keys(cached.files).length) {
      applyReviewDoc(cached)
      state.reviewMigration = { status: 'recovered', message: '已从本地缓存恢复审阅记录' }
      scheduleReviewSave(0)
      return
    }
    const legacyJob = readLegacyJob(jobId)
    const migrated = migrateLegacyReviews(legacyJob, jobId, state.review.user)
    if (!migrated) return
    applyReviewDoc(migrated)
    try {
      const saved = await api.putReview(jobId, migrated)
      if (seq === reviewLoadSeq) {
        applyReviewDoc(saved)
        state.reviewSaveState = 'saved'
        writeReviewCache(state.review)
      }
      dropLegacyJob(jobId)
      state.reviewMigration = { status: 'migrated', message: '旧版行级标记已迁移到文件级审阅' }
      toast('success', '已将旧版行级审阅标记迁移到文件级审阅记录')
    } catch (e) {
      // PRD 要求失败保留旧数据并提示；不回滚本地已展示的迁移结果，避免用户看不懂「标记消失」。
      state.reviewMigration = { status: 'failed', message: e.message || String(e) }
      state.reviewError = e.message || String(e)
      toast('warning', '旧审阅标记迁移失败，旧数据已保留：' + (e.message || e))
    }
  } catch (e) {
    if (seq !== reviewLoadSeq) return
    const cached = readReviewCache(jobId)
    if (cached) {
      applyReviewDoc(cached)
      state.reviewMigration = { status: 'recovered', message: '后端不可用，已从本地缓存恢复' }
    } else {
      applyReviewDoc(empty)
    }
    state.reviewError = e.message || String(e)
    state.reviewSaveState = 'error'
  } finally {
    if (seq === reviewLoadSeq) state.reviewLoading = false
  }
}

export function resetReview() {
  if (reviewSaveTimer) { clearTimeout(reviewSaveTimer); reviewSaveTimer = null }
  reviewLoadSeq++
  reviewLoads.clear()
  applyReviewDoc(emptyReviewDoc())
  state.reviewLoading = false
  state.reviewError = ''
  state.reviewSaveState = 'idle'
  state.reviewMigration = null
}

// ---------------- T06225/27/28 洞察聚合 ----------------
let insightsSeq = 0

/** 清空洞察（新比对/关闭会话时调用，避免旧 job 的兼容/影响数据串到新树）。 */
export function resetInsights() {
  insightsSeq++
  state.insights = null
  state.insightsLoading = false
  state.insightsError = ''
}

/** 拉取后端结构化洞察；同一 job 多次调用由 seq 丢弃旧响应。 */
export async function loadInsights(jobId = currentJobId(), opts = {}) {
  if (!jobId) return null
  const seq = ++insightsSeq
  state.insightsLoading = true
  state.insightsError = ''
  try {
    const data = await api.jobInsights(jobId, { offset: 0, limit: opts.limit || 300 })
    if (seq !== insightsSeq) return null
    state.insights = data
    syncInsightsReview(false)
    syncHistoryMeta()
    return data
  } catch (e) {
    if (seq !== insightsSeq) return null
    state.insightsError = e.message || String(e)
    return null
  } finally {
    if (seq === insightsSeq) state.insightsLoading = false
  }
}

/** 审阅标记后本地刷新洞察里的审阅/证据计数（不重新请求后端，保证同帧）。 */
function syncInsightsReview(updateHistory = true) {
  const ins = state.insights
  const sum = state.reviewSummary
  if (!ins || !sum) return
  const total = Math.max(Number(ins.reviewSummary && ins.reviewSummary.total) || 0, Number(sum.totalFiles) || 0)
  let notes = 0
  let bookmarks = 0
  const files = (state.review && state.review.files) || {}
  for (const f of Object.values(files)) {
    notes += Array.isArray(f.notes) ? f.notes.length : 0
    bookmarks += Array.isArray(f.bookmarks) ? f.bookmarks.length : 0
  }
  ins.reviewSummary = {
    ...(ins.reviewSummary || {}),
    total,
    reviewed: sum.reviewedFiles || 0,
    inProgress: sum.inProgressFiles || 0,
    needsReview: sum.needsReviewFiles || 0,
    unreviewed: sum.unreviewedFiles || 0,
    notes,
    bookmarks,
    filePercent: total > 0 ? Math.round((sum.reviewedFiles || 0) * 100 / total) : 0
  }
  if (updateHistory) syncHistoryMeta()
}

/** 把当前 job 的统计/审阅进度回填到最近比对（Launchpad 不打开后端也能看到准确摘要）。 */
function syncHistoryMeta() {
  const job = state.job
  if (!job || !job.jobId) return
  const list = loadCompareHistory()
  const item = list.find(x => x.jobId === job.jobId) ||
    list.find(x => normalizeComparePath(x.oldPath) === normalizeComparePath(state.oldPath) &&
      normalizeComparePath(x.newPath) === normalizeComparePath(state.newPath))
  if (!item) return
  item.jobId = job.jobId
  if (job.stats) item.stats = { added: job.stats.added, deleted: job.stats.deleted, modified: job.stats.modified }
  const ins = state.insights
  if (ins && ins.impact) {
    item.risk = {
      high: (Number(ins.impact.critical) || 0) + (Number(ins.impact.high) || 0),
      breaking: Number(ins.summary && ins.summary.breaking) || 0
    }
  }
  const rs = (ins && ins.reviewSummary) || null
  if (rs && Number(rs.total) > 0) {
    item.review = {
      percent: Number(rs.filePercent) || 0,
      reviewed: Number(rs.reviewed) || 0,
      total: Number(rs.total) || 0
    }
  } else if (state.reviewSummary) {
    item.review = {
      percent: state.reviewSummary.filePercent || 0,
      reviewed: state.reviewSummary.reviewedFiles || 0,
      total: state.reviewSummary.totalFiles || 0
    }
  }
  saveCompareHistory()
}

/** 洞察条目跳转：打开文件并请求 DiffView 滚到第一个/指定 Hunk。 */
export async function jumpToHunk(key, hunkIndex = 0) {
  if (!key || !state.job) return false
  await selectEntry(key)
  state.hunkJump = { key, hunkIndex: Math.max(0, Number(hunkIndex) || 0), seq: Date.now() + Math.random() }
  return true
}

// 页面隐藏/关闭前尽力落盘：80ms 防抖窗口内的最后一次标记不丢（AC 持久化）。
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => {
    if (!state.review || !state.review.jobId || !reviewSaveTimer) return
    flushReview({ keepalive: true }).catch(() => {})
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && state.reviewSaveState === 'pending') flushReview().catch(() => {})
  })
}

// 主题六态（FR-004 / TH-02）：light | dark | midnight | paper | contrast | system。
// Bootstrap 只接受实际明暗两态，六主题按 THEME_BOOT_MODE 映射；Token 主题保留完整语义名。
// 持久化保存用户原始选择，解析结果实时反映；系统切换时无刷新跟随。
const THEME_KEY = 'bempdiff-theme'
const THEME_ORDER = [...THEME_NAMES]
initTokenRegistry()
const themeMedia = (typeof window !== 'undefined' && window.matchMedia)
  ? window.matchMedia('(prefers-color-scheme: dark)') : null

/** 把偏好值解析为 Bootstrap 明暗底座：'system' 依赖系统亮暗，六主题按映射表归一。 */
export function resolveTheme(pref) {
  const token = resolveTokenTheme(pref, !!(themeMedia && themeMedia.matches))
  return resolveBootstrapMode(token)
}

/** 把六主题偏好解析为 Token 主题名（供 UI 显示 system 的实际落点）。 */
export function resolveThemeToken(pref) {
  return resolveTokenTheme(pref, !!(themeMedia && themeMedia.matches))
}

/** 只落 DOM/Token，不写偏好；供 system 系统变化时无刷新重绘。 */
function paintTheme(pref) {
  const tokenTheme = resolveTokenTheme(pref, !!(themeMedia && themeMedia.matches))
  const mode = resolveBootstrapMode(tokenTheme)
  const tokens = THEMES[tokenTheme] || THEMES.light
  document.documentElement.setAttribute('data-bs-theme', mode)
  document.documentElement.setAttribute('data-theme', tokenTheme)
  applyTokenTheme(pref, mode === 'dark')
  document.documentElement.style.colorScheme = mode
  // 背景/前景取 Token canvas/text，午夜/护眼/高对比不再被 Bootstrap 两态底色覆盖。
  document.documentElement.style.backgroundColor = tokens.canvas
  document.documentElement.style.color = tokens.text
  return { tokenTheme, mode }
}

export function applyTheme(t) {
  const pref = THEME_NAMES.includes(t) ? t : 'light'
  const started = (typeof performance !== 'undefined' && performance.now) ? performance.now() : 0
  state.theme = pref
  try { localStorage.setItem(THEME_KEY, pref) } catch (_) { /* 存储不可用时仅当前会话生效 */ }
  const painted = paintTheme(pref)
  if (started) state.lastThemeSwitchMs = Number((performance.now() - started).toFixed(1))
  return painted
}

/** 循环切换 亮 → 暗 → 午夜 → 护眼 → 高对比 → 跟随系统 → 亮（工具栏单按钮六态）。 */
export function cycleTheme() {
  const i = THEME_ORDER.indexOf(state.theme)
  applyTheme(THEME_ORDER[(i + 1) % THEME_ORDER.length])
  // FR-014 联动：界面明暗切换时，若当前高亮主题的适配明暗与新界面不符，自动切到该模式的默认主题。
  // 仅在用户显式切换时联动（启动恢复不校正），避免覆盖「暗主题配亮高亮」之类的用户刻意选择。
  const mode = resolveTheme(state.theme) === 'dark'
  const cur = hlThemeOf(state.hlTheme)
  if (cur.dark !== mode) setHlTheme(mode ? 'github-dark' : 'default')
}

// ---------------- TH-03 色盲安全调色板 ----------------
const DIFF_PALETTE_KEY = 'bempdiff.diffPalette'
export const DIFF_PALETTES = {
  default: { labelKey: 'cfg.diffPalette.default' },
  colorblind: { labelKey: 'cfg.diffPalette.colorblind' }
}
/** 设置差异调色板（default | colorblind）；非法值静默回退 default 并持久化。 */
export function setDiffPalette(key) {
  const k = key === 'colorblind' ? 'colorblind' : 'default'
  state.diffPalette = k
  try { localStorage.setItem(DIFF_PALETTE_KEY, k) } catch (_) { /* 存储不可用时仅当前会话生效 */ }
  return k
}

// ---------------- FR-014 语法高亮主题包 ----------------
// 高亮主题（亮 2 / 暗 2）与界面明暗正交但联动：界面切到不匹配的模式时自动切到该模式默认主题
//（用户随后手动选别的主题仍尊重选择）。持久化独立 key，随 applyTheme 的 resolveTheme 联动。
export function loadHlTheme() {
  try {
    const k = localStorage.getItem('bempdiff.hlTheme')
    if (k) state.hlTheme = k
  } catch (_) { /* 静默 */ }
  return state.hlTheme
}

/** 设置高亮主题（非法 key 回退 default 并持久化）。 */
export function setHlTheme(key) {
  const t = hlThemeOf(key)
  state.hlTheme = t.key
  try { localStorage.setItem('bempdiff.hlTheme', t.key) } catch (_) { /* 静默 */ }
  return t
}

export async function init() {
  applyTheme(state.theme)
  // 「跟随系统」实时切换：系统亮暗变化时按当前偏好重新解析（验收③无刷新切换）。
  // 只在 system 偏好下监听也需覆盖「切换到 system 之后」的场景，故常驻监听、回调内部判偏好。
  if (themeMedia && typeof themeMedia.addEventListener === 'function') {
    themeMedia.addEventListener('change', () => {
      if (state.theme === 'system') {
        // 六主题复用同一落 DOM 路径：system 按 OS 亮暗重绘 Token + Bootstrap 底座，无刷新。
        paintTheme('system')
      }
    })
  }
  state.ignoreRules = loadIgnoreRules() // 右键菜单「忽略」规则：跨会话持久化
  try {
    state.config = await api.getConfig()
  } catch (e) {
    state.config = defaultConfig()
    console.warn('加载配置失败，使用默认值：', e.message)
  }
  // 桌面壳 Shell 集成：接收右键菜单 / 命令行传入的比对路径（Electron preload 暴露）。
  if (typeof window !== 'undefined' && window.bempdiff && typeof window.bempdiff.onShellCompare === 'function') {
    window.bempdiff.onShellCompare((paths) => { ingestShellPaths(paths) })
  }
  // UI-06/REQ-005：恢复上次已完成的比对会话（Tabs/Pin/激活页）。
  // 异步执行且失败静默——恢复失败绝不能阻塞启动；只有后端仍持有该 job 时才恢复，避免展示过期树。
  restoreLastSession().catch(() => {})
}

// ---------------- UI-06 Tab 布局持久化 ----------------
// 仅保存「已完成的比对会话」：jobId + tab 元数据（key/pinned/node）+ 激活键。
// 不保存源码/差异内容/AI 结果，沿用 Key 与内容不落盘原则。
const TAB_LAYOUT_KEY = 'bempdiff.tabLayout'
const TAB_LAYOUT_VERSION = 1
const TAB_LAYOUT_MAX = 20

function currentJobId() {
  return (state.job && state.job.jobId) || state.activeCompareId || null
}

/** 保存当前 tab 布局；无已完成 job 或空 tabs 时跳过（避免覆盖上一份可恢复会话）。 */
export function saveTabLayout() {
  try {
    const jobId = currentJobId()
    if (!jobId || !state.tabs.length) return
    const layout = {
      v: TAB_LAYOUT_VERSION,
      jobId,
      activeKey: state.activeKey,
      savedAt: Date.now(),
      oldPath: state.oldPath || '',
      newPath: state.newPath || '',
      leftType: state.leftType || 'package',
      tabs: state.tabs.slice(0, TAB_LAYOUT_MAX).map(t => ({
        key: t.key,
        pinned: !!t.pinned,
        scroll: Math.max(0, Number(t.scroll) || 0),
        node: t.node || null
      }))
    }
    localStorage.setItem(TAB_LAYOUT_KEY, JSON.stringify(layout))
  } catch (_) { /* 存储满/禁用时静默放弃布局恢复 */ }
}

/** 读取布局快照；坏数据/版本不符返回 null。 */
export function loadTabLayout() {
  try {
    const raw = JSON.parse(localStorage.getItem(TAB_LAYOUT_KEY) || 'null')
    if (!raw || raw.v !== TAB_LAYOUT_VERSION || !raw.jobId || !Array.isArray(raw.tabs)) return null
    return raw
  } catch (_) { return null }
}

/** 启动恢复：后端 job 仍为 DONE 时恢复 tabs/pin；失败或 job 已消失则清掉快照。 */
export async function restoreLastSession() {
  if (state.busy || state.job) return false // 用户已发起新比对/已有结果时不抢状态
  const layout = loadTabLayout()
  if (!layout) return false
  try {
    const status = await api.jobStatus(layout.jobId)
    if (!status || status.status !== 'DONE' || !status.tree) {
      localStorage.removeItem(TAB_LAYOUT_KEY)
      return false
    }
    state.job = status
    state.activeCompareId = layout.jobId
    state.oldPath = layout.oldPath || state.oldPath
    state.newPath = layout.newPath || state.newPath
    state.leftType = layout.leftType === 'folder' ? 'folder' : 'package'
    state.jobProgress = { status: 'DONE', progress: 100, phase: 'done', message: '已恢复上次会话' }
    await loadReviewForJob(layout.jobId)
    loadInsights(layout.jobId).catch(() => {})
    state.tabs = layout.tabs
      .filter(t => t && typeof t.key === 'string' && t.node)
      .map(t => ({ key: t.key, node: t.node, pinned: !!t.pinned, scroll: Math.max(0, Number(t.scroll) || 0), busy: false, decompile: null }))
    const validKeys = new Set(state.tabs.map(t => t.key))
    state.activeKey = validKeys.has(layout.activeKey) ? layout.activeKey : (state.tabs[0] ? state.tabs[0].key : null)
    const active = state.tabs.find(t => t.key === state.activeKey)
    if (active) ensureTabLoaded(active.key, active.node).catch(() => {})
    return state.tabs.length > 0
  } catch (_) {
    // 后端已重启/缓存被清理时 jobStatus 会 404；删除过期快照，启动继续走空态
    try { localStorage.removeItem(TAB_LAYOUT_KEY) } catch (_) {}
    return false
  }
}

/** 清除布局快照（用户主动关闭全部 tab / 新比对开始前）。 */
export function clearTabLayout() {
  try { localStorage.removeItem(TAB_LAYOUT_KEY) } catch (_) {}
}

export function defaultConfig() {
  return {
    aiProvider: 'openai',
    aiBaseUrl: 'https://api.openai.com/v1',
    aiApiKey: '',
    aiModel: 'gpt-4o',
    aiEnabled: false,
    stageBTopK: 15,
    costGateWarnTokens: 8000,
    internalPrefixes: 'com.hundsun',
    expandAll: false,
    topK: 15,
    cfrJar: '',
    ignoreWhitespace: false,
    ignoreComments: false,
    ignoreRegex: '',
    ignoreExtensions: [], // 比对级忽略扩展名（多选，如 .log/.tmp）：解析收集阶段直接跳过，不参与差异比对
    httpProxy: '',
    httpsProxy: '',
    blockPrivateEndpoints: false,
    persistApiKey: false,
    projectContextDir: '',
    projectContextEnabled: false,
    filterSearch: '',
    filterRegex: false,
    filterShowModified: true,
    filterShowAdded: true,
    filterShowDeleted: true,
    filterShowUnchanged: true,
    treeViewMode: 'tree',     // 差异树展示模式：'tree'(目录树：按目录层级递归展开) | 'list'(全平摊)
    treeSortMode: 'path',     // 差异树排序方式（仅 list 模式生效；tree 模式强制 path）
    treeFilterBarVisible: true, // 差异树过滤栏（搜索+状态勾选）默认显示；显隐切换另写 localStorage 双保险记忆
    sortByRisk: false,      // 差异树按 AI 风险等级排序（高→低）
    filterRisk: ['HIGH', 'MEDIUM', 'LOW'], // 差异树风险过滤（多选；空=不过滤）
    autoAiOnCompare: true,
    // 自动逐层解包（WAR/ZIP/JAR 嵌套归档多线程物理展开）：默认开启，比对时后端多线程逐层解包，
    // AI/导出覆盖嵌套子文件；解包完成后才置 DONE（解包中 AI/导出被禁用）。
    unpackNested: true,
    unpackThreads: 4,        // 解包并发线程数
    unpackMaxDepth: 6,       // 最大递归解包深度
    hasApiKey: false
  }
}

/** 切换差异对比「专注模式」（隐藏左右栏，放大中间 diff 视野）。 */
export function toggleFocusMode() { state.focusMode = !state.focusMode }
export function setFocusMode(v) { state.focusMode = !!v }

// 智能分析栏（.col-ai）收起/展开：与 DiffView 文件栏一键显隐联动，避免遮挡对比窗口。
// 用户显式切换时写入 localStorage，作为后续响应式避让（窄屏自动收起）的覆盖优先级。
const AI_COLLAPSE_KEY = 'bempdiff.analysisCollapsed'
const AI_AUTO_COLLAPSE_W_LEGACY = 1080
const AI_AUTO_COLLAPSE_W_COCKPIT = 1280
export function setAiPanelCollapsed(v) {
  state.aiPanelCollapsed = !!v
  localStorage.setItem(AI_COLLAPSE_KEY, String(state.aiPanelCollapsed))
}
export function toggleAiPanel() { setAiPanelCollapsed(!state.aiPanelCollapsed) }
// 差异文件树栏（.col-tree）收起/展开：手动切换，不做窄屏自动避让（树栏无自动收起需求）。
export function setTreePanelCollapsed(v) { state.treePanelCollapsed = !!v }
export function toggleTreePanel() { setTreePanelCollapsed(!state.treePanelCollapsed) }
// T06219 <1080 单栏+抽屉：左右栏不占布局宽度，按需以覆盖层打开。
// 这两个开关是会话态，不写 localStorage；>1080 时由 CSS 忽略，不改变桌面三栏偏好。
export function toggleMobileTree() {
  state.mobileTreeOpen = !state.mobileTreeOpen
  if (state.mobileTreeOpen) state.treePanelCollapsed = false
}
export function toggleMobileAi() {
  const opening = state.aiPanelCollapsed || !state.mobileAiOpen
  state.mobileAiOpen = opening
  state.aiPanelCollapsed = !opening
}
// 响应式避让：有显式偏好（localStorage）则尊重之；否则按视口宽度自动收起（窄屏不挤占 diff）。
// PRD 8.1：新版 1080–1280 右栏默认收起；1280 仍保留三栏（1280–1440 右栏 320px）。
export function applyAiPanelResponsive() {
  const pref = localStorage.getItem(AI_COLLAPSE_KEY)
  if (pref !== null) { state.aiPanelCollapsed = pref === 'true'; return }
  const threshold = state.reviewCockpit ? AI_AUTO_COLLAPSE_W_COCKPIT : AI_AUTO_COLLAPSE_W_LEGACY
  state.aiPanelCollapsed = state.reviewCockpit
    ? window.innerWidth < threshold
    : window.innerWidth <= threshold
}

/** 比对路径归一化（仅用于判断左右是否同一输入）：统一斜杠、去尾斜杠、Windows 下忽略大小写。 */
export function normalizeComparePath(p) {
  let s = String(p || '').trim().replace(/\\/g, '/').replace(/\/+/g, '/').replace(/\/+$/, '')
  if (/^[a-zA-Z]:/.test(s)) s = s.toLowerCase()
  return s
}

export async function runCompare({ leftType, leftPath, rightPath, options }) {
  // P2-5：判断两侧是否指向同一路径（Windows 大小写/斜杠/尾斜杠归一）。
  // 同一输入的 0 差异不等于「两个包完全一致」，结果区需要给出准确说明。
  state.sameInputCompare = normalizeComparePath(leftPath) === normalizeComparePath(rightPath)
  resetReview()             // 新 job：旧审阅文档立即让位，避免旧进度串到新结果
  resetInsights()           // 新 job：旧兼容/影响数据不得串到新树
  state.busy = true
  state.busyText = '正在提交比对任务…'
  state.error = null
  state.aiClassify = {}      // 新一轮比对：清空上一轮智能分类结果
  state.classifying = false
  state.aiEstimate = null   // 新一轮比对：清空上一轮成本闸门预估缓存
  state.aiEstimateKey = null
  state.costGate = null
  state.excludedKeys = {}   // 新一轮比对：清空排除/基准/属性弹窗（树已重建）
  state.baseFolder = null
  state.propertyNode = null
  state.jobProgress = { status: 'queued', progress: 0, phase: 'queued', message: '已提交，等待处理' }
  try {
    const r = await api.compare({ leftType, leftPath, rightPath, options })
    const jobId = r.jobId
    // T02741：不再按版本号自动重排——左框即老包、右框即新包，方向完全由用户显式放置决定，
    // 故移除「已自动按版本排序」提示；oldVersion/newVersion 仍用于状态栏展示文件名版号。
    state.activeCompareId = jobId
    state.jobProgress = {
      status: r.status || 'queued',
      progress: r.progress || 0,
      phase: r.phase || 'queued',
      message: r.message || '已提交'
    }
    const final = await pollJob(jobId)
    if (!final) return true // 已取消 / 被新比对取代 → 不清空旧结果，直接返回（作业确实提交成功过）
    state.job = final
    await loadReviewForJob(jobId)
    await loadInsights(jobId)
    // 重新比对：中止并清空旧 AI 分析任务（旧任务与旧树/旧 job 不再对应，避免残留任务对新 job 误跑）
    for (const t of state.aiTasks) { const c = aiControllers.get(t.id); if (c) c.abort() }
    aiControllers.clear()
    state.aiTasks = []
    state.aiActiveTaskId = null
    // 重新比对：清空旧 tab（树变化，旧 key 可能已不存在）
    state.tabs = []
    state.activeKey = null
    state.reportMd = null
    state.reportCache = {}   // 新一轮比对：清空报告缓存（旧报告与旧树 key 不再对应）
    state.archiveChildren = {}   // 新一轮比对：清空归档展开缓存
    state.expandedArchives = {}
    toast('success', `比对完成：新增 ${final.stats.added} · 删除 ${final.stats.deleted} · 修改 ${final.stats.modified}`)
    // 自动逐层解包展示（B 机制）：比对完成即后台递归展开所有归档节点（/api/entry/recursive），
    // 无需手动点击即可查看嵌套包内部；异步 fire-and-forget，不阻塞差异树首屏渲染。
    if (state.config && state.config.unpackNested !== false) autoExpandArchives(final.tree)
    if (state.config && state.config.autoAiOnCompare && state.config.aiEnabled) {
      generateReport(true, { silent: true })
    }
    return true
  } catch (e) {
    // T03669：失败必须留下持久可见的痕迹。此前只发一条 3.2 秒自动消失的 toast，toast 一走
    // 左侧就回落成「暂无差异，请先「开始比对」」——把「没比成」显示成「两个包完全一致」，
    // 在发布评审场景是代价最高的一类误判。这里清空上一轮结果（否则旧 job 的树与统计会冒充
    // 本次结果），原因留在 state.error，由差异树空态与状态栏长期显示；前缀只在此处加一次。
    state.error = e.message || String(e)
    state.job = null
    resetInsights()
    state.tabs = []
    state.activeKey = null
    state.reportMd = null
    state.reportCache = {}
    state.archiveChildren = {}
    state.expandedArchives = {}
    for (const t of state.aiTasks) { const c = aiControllers.get(t.id); if (c) c.abort() }
    aiControllers.clear()
    state.aiTasks = []
    state.aiActiveTaskId = null
    toast('danger', '比对失败：' + state.error)
    return false
  } finally {
    state.busy = false
    state.jobProgress = null
    state.activeCompareId = null
  }
}

// ---------- 比对路径入口（拖拽 / 右键菜单 / 工具栏共用） ----------

/** 由路径推断输入类型：war/jar/zip/ear 等归档 → package，其余（目录或未知）→ folder。 */
export function inferType(p) {
  return /\.(war|jar|zip|ear|tar\.gz|tgz)$/i.test(p || '') ? 'package' : 'folder'
}

/** 由当前配置构造比对 options（与 ToolBar.buildOptions 保持一致）。 */
export function buildOptions() {
  const c = state.config || {}
  return {
    expandAll: !!c.expandAll,
    topK: c.topK ?? 12,
    internalPrefixes: c.internalPrefixes || '',
    cfrJar: c.cfrJar || '',
    ignoreWhitespace: !!c.ignoreWhitespace,
    ignoreComments: !!c.ignoreComments,
    ignoreRegex: c.ignoreRegex || '',
    ignoreExtensions: Array.isArray(c.ignoreExtensions) ? c.ignoreExtensions : [],
    // 自动逐层解包：默认开启（后端多线程物理解包），可配线程数与深度
    unpackNested: c.unpackNested !== false,
    unpackThreads: Math.max(1, Number(c.unpackThreads) || 4),
    unpackMaxDepth: Math.max(1, Number(c.unpackMaxDepth) || 6)
  }
}

/** 工具栏「开始比对」按钮与拖拽/右键菜单共用入口。
 *  CX-09：默认先走只读预检；阻断项直接弹确认框（继续按钮禁用），警告项允许“仍然继续”。
 *  `skipPreflight` 仅供预检弹窗“继续”复用同一 payload 时使用，避免用户点一次比对却预检两次。 */
export async function triggerCompare(opts = {}) {
  const payload = (opts && opts.payload) ? opts.payload : {
    leftType: state.leftType,
    leftPath: (state.oldPath || '').trim(),
    rightPath: (state.newPath || '').trim(),
    options: buildOptions()
  }
  if (!payload.leftPath || !payload.rightPath) {
    toast('warning', '请先填写老包与新包（或目录）的路径')
    return false
  }
  if (!opts.skipPreflight) {
    state.preflightBusy = true
    try {
      const pf = await api.preflight(payload)
      // info 级不打扰用户；block/warn 进入确认弹窗
      if (pf && (pf.level === 'block' || pf.level === 'warn')) {
        state.preflight = { result: pf, payload }
        return false
      }
    } catch (e) {
      // 预检服务不可用时退化为“提示后继续”，不让预检自身阻塞主流程
      toast('warning', '比对预检不可用，已直接继续：' + e.message)
    } finally {
      state.preflightBusy = false
    }
  }
  // R5（二期 T01463）：成功发起的比对会话记入历史（供跨启动恢复路径对）。
  // T03669 起按后端受理结果记录：路径不存在会被输入校验直接 400 拒绝，
  // 这种从未真正开始的比对不再进「最近比对」，免得列表本身成为「做过」的假证据。
  const ok = await runCompare(payload)
  if (ok) pushCompareHistory(payload.leftPath, payload.rightPath, payload.leftType)
  return ok
}

/** CX-09 弹窗「仍要继续」：复用已预检的 payload，跳过二次预检。 */
export async function confirmPreflight() {
  const pending = state.preflight
  if (!pending) return false
  state.preflight = null
  return triggerCompare({ payload: pending.payload, skipPreflight: true })
}

/** CX-09 弹窗「取消」：仅关闭弹窗，不启动比对。 */
export function cancelPreflight() { state.preflight = null }

// ---------------- R5 比对会话历史（二期 T01463） ----------------
// 仅记录路径元数据（左右路径 + 输入类型 + 时间）并持久化 localStorage（上限 20 条滚动）；
// AI 分析结果 / 差异内容一律不落盘（延续脱敏与 Key 不落盘约束）。
const COMPARE_HISTORY_KEY = 'bempdiff.compareHistory'
const COMPARE_HISTORY_MAX = 20

/** 读取历史（首次从 localStorage 装载；坏数据静默清空）。 */
export function loadCompareHistory() {
  if (!state.compareHistory.length) {
    try {
      const raw = JSON.parse(localStorage.getItem(COMPARE_HISTORY_KEY) || '[]')
      if (Array.isArray(raw)) state.compareHistory = raw.slice(0, COMPARE_HISTORY_MAX)
    } catch (_) { /* 忽坏数据 */ }
  }
  return state.compareHistory
}
function saveCompareHistory() {
  try {
    localStorage.setItem(COMPARE_HISTORY_KEY, JSON.stringify(state.compareHistory.slice(0, COMPARE_HISTORY_MAX)))
  } catch (_) { /* 非安全上下文等异常静默 */ }
}
/** 记录一次比对会话；与最近一条完全一致时不重复插入（仅刷新时间戳）。 */
export function pushCompareHistory(oldPath, newPath, leftType) {
  const o = String(oldPath || '').trim()
  const n = String(newPath || '').trim()
  if (!o || !n) return
  const list = loadCompareHistory()
  const top = list[0]
  if (top && top.oldPath === o && top.newPath === n && top.leftType === leftType) {
    top.at = Date.now()
  } else {
    list.unshift({ oldPath: o, newPath: n, leftType: leftType || 'package', at: Date.now() })
  }
  // T06223：最近项携带 jobId/统计/审阅摘要，Launchpad 展示「文件数/高风险/已审」而不打开大包。
  if (state.job && state.job.jobId) {
    const rec = list[0]
    rec.jobId = state.job.jobId
    if (state.job.stats) rec.stats = { added: state.job.stats.added, deleted: state.job.stats.deleted, modified: state.job.stats.modified }
    if (state.reviewSummary) {
      rec.review = {
        percent: state.reviewSummary.filePercent || 0,
        reviewed: state.reviewSummary.reviewedFiles || 0,
        total: state.reviewSummary.totalFiles || 0
      }
    }
  }
  state.compareHistory = list.slice(0, COMPARE_HISTORY_MAX)
  saveCompareHistory()
}
/** 删除第 i 条历史（i 越界静默）。 */
export function removeCompareHistory(i) {
  const list = loadCompareHistory()
  if (i < 0 || i >= list.length) return
  list.splice(i, 1)
  state.compareHistory = list
  saveCompareHistory()
}
/** 恢复第 i 条历史：回填左右路径与输入类型（不自动比对，由用户确认「开始比对」）。 */
export function restoreCompareHistory(i) {
  const item = loadCompareHistory()[i]
  if (!item) return false
  state.leftType = item.leftType || 'package'
  state.oldPath = item.oldPath
  state.newPath = item.newPath
  return true
}
/** 清空全部历史（带确认由调用方负责）。 */
export function clearCompareHistory() {
  state.compareHistory = []
  try { localStorage.removeItem(COMPARE_HISTORY_KEY) } catch (_) { /* 静默 */ }
}

// ---------------- FR-010 命名保存常用对比任务 ----------------
// 与自动历史（20 条 LRU，会被新比对挤掉）分池：命名会话由用户显式管理、不自动淘汰，
// 供高频固定比对场景（如每周升级包验收）一键回填。同样仅存路径元数据（Key 不落盘原则）。
const SAVED_SESSIONS_KEY = 'bempdiff.savedSessions'
const SAVED_SESSIONS_MAX = 30

/** 读取命名会话列表（首次从 localStorage 装载；坏数据静默清空）。 */
export function loadSavedSessions() {
  if (!state.savedSessions.length) {
    try {
      const raw = JSON.parse(localStorage.getItem(SAVED_SESSIONS_KEY) || '[]')
      if (Array.isArray(raw)) state.savedSessions = raw.slice(0, SAVED_SESSIONS_MAX)
    } catch (_) { /* 忽坏数据 */ }
  }
  return state.savedSessions
}
function saveSavedSessions() {
  try {
    localStorage.setItem(SAVED_SESSIONS_KEY, JSON.stringify(state.savedSessions.slice(0, SAVED_SESSIONS_MAX)))
  } catch (_) { /* 非安全上下文等异常静默 */ }
}
/** 把当前左右路径 + 输入类型保存为命名会话；同名覆盖（刷新时间戳）。名为空不保存。 */
export function saveNamedSession(name) {
  const n = String(name || '').trim()
  const o = String(state.oldPath || '').trim()
  const p = String(state.newPath || '').trim()
  if (!n || !o || !p) return false
  const list = loadSavedSessions()
  const exist = list.find(s => s.name === n)
  if (exist) {
    exist.oldPath = o
    exist.newPath = p
    exist.leftType = state.leftType || 'package'
    exist.at = Date.now()
  } else {
    list.unshift({ name: n, oldPath: o, newPath: p, leftType: state.leftType || 'package', at: Date.now() })
  }
  state.savedSessions = list.slice(0, SAVED_SESSIONS_MAX)
  saveSavedSessions()
  return true
}
/** 应用第 i 个命名会话：回填路径与输入类型（回填不自动比对，与历史重放同款安全口径）。 */
export function applyNamedSession(i) {
  const item = loadSavedSessions()[i]
  if (!item) return false
  state.leftType = item.leftType || 'package'
  state.oldPath = item.oldPath
  state.newPath = item.newPath
  return true
}
/** 删除第 i 个命名会话（i 越界静默）。 */
export function removeNamedSession(i) {
  const list = loadSavedSessions()
  if (i < 0 || i >= list.length) return
  list.splice(i, 1)
  state.savedSessions = list
  saveSavedSessions()
}

// ---------------- R9 启动更新检查（二期 T01471） ----------------
// 启动后异步静默检查（24h 节流，避免频繁打 GitHub 匿名限流）：发现新版本 → 角落提示条
// 提供「前往下载页」；**不自动下载、不自动安装**（一键安装仍留在「关于」页由用户显式触发）。
const UPDATE_CHECK_AT_KEY = 'bempdiff.updateCheckAt'
const UPDATE_DISMISSED_TAG_KEY = 'bempdiff.updateDismissedTag'
const UPDATE_CHECK_INTERVAL_MS = 24 * 3600 * 1000
export async function checkUpdateSilently() {
  try {
    const last = Number(localStorage.getItem(UPDATE_CHECK_AT_KEY) || 0)
    if (Date.now() - last < UPDATE_CHECK_INTERVAL_MS) return
    localStorage.setItem(UPDATE_CHECK_AT_KEY, String(Date.now()))
    const r = await api.checkUpdate({ current: resolveAppVersion() })
    if (r && r.ok && r.upToDate === false && r.latest && r.latest.tag) {
      // R9（T01472）：用户已对该版本关闭过提示 → 不再打扰（更新到更新版本时仍会提示）
      const dismissed = localStorage.getItem(UPDATE_DISMISSED_TAG_KEY)
      if (dismissed && dismissed === r.latest.tag) return
      state.updateAvailable = { tag: r.latest.tag, url: r.latest.url || '', message: r.message || '' }
    }
  } catch (_) { /* 检查失败静默：不打扰主流程（「关于」页可手动检查） */ }
}
export function dismissUpdateHint() {
  // 关闭偏好持久化：记住被关闭的版本号；同版本此后不再提示，新版本照常提示
  if (state.updateAvailable && state.updateAvailable.tag) {
    try { localStorage.setItem(UPDATE_DISMISSED_TAG_KEY, state.updateAvailable.tag) } catch (_) { /* 静默 */ }
  }
  state.updateAvailable = null
}

/**
 * 接收外部传入的比对路径（右键菜单 / 拖拽 / 命令行参数），写入工具栏并视情况自动比对。
 * 采用「pending 左 → 右」方案（Beyond Compare / WinMerge 同款）：
 *   - 收到 1 个路径：若左空则填左，否则填右；仅一个时不自动比对，提示再选一个。
 *   - 收到 ≥2 个路径：第一填左、第二填右，立即自动比对。
 *   - 左右均已存在时再收到：替换左、清空右，等待下一次补全（避免误覆盖进行中的对比）。
 * @param {string|string[]} rawPaths
 */
export function ingestShellPaths(rawPaths) {
  const paths = (Array.isArray(rawPaths) ? rawPaths : [rawPaths])
    .filter((p) => typeof p === 'string' && p.trim().length)
    .map((p) => p.trim())
  if (!paths.length) return
  const items = paths.slice(0, 2).map((p) => ({ path: p, type: inferType(p) }))
  if (items.length >= 2) {
    // 智能识别：两个同为压缩包且同名不同版本 → 自动按版本排序（旧→新），无需手动分辨新旧
    let pOld = items[0].path
    let pNew = items[1].path
    if (items[0].type === 'package' && items[1].type === 'package'
        && sameBaseDifferentVersion(pOld, pNew)) {
      const ordered = orderOldNew(pOld, pNew)
      pOld = ordered[0]
      pNew = ordered[1]
      toast('info', `已识别为同一包的不同版本（${extractFromFileName(pOld)} → ${extractFromFileName(pNew)}），自动开始比对`)
    }
    state.leftType = items[0].type
    state.oldPath = pOld
    state.newPath = pNew
  } else {
    const it = items[0]
    if (!state.oldPath) {
      state.leftType = it.type
      state.oldPath = it.path
    } else if (!state.newPath) {
      state.newPath = it.path
    } else {
      state.leftType = it.type
      state.oldPath = it.path
      state.newPath = ''
    }
  }
  if (state.oldPath && state.newPath) {
    toast('info', '已通过外部入口（右键菜单 / 拖拽）接收比对路径，自动开始比对')
    triggerCompare()
  } else {
    toast('info', '已接收第一个比对路径，请从资源管理器再右键一个文件/目录以完成对比')
  }
}

/**
 * 轮询比对任务直到终态。返回 DONE 时的完整结果；CANCELLED/被取代返回 null（由调用方决定不清空旧 job）。
 * 用 state.activeCompareId 作为「当前轮询是否属于本次比对」的开关：用户重开或取消会改写它，旧轮询自然退出。
 */
async function pollJob(jobId) {
  while (state.activeCompareId === jobId) {
    const s = await api.jobStatus(jobId)
    state.jobProgress = {
      status: s.status,
      progress: s.progress || 0,
      phase: s.phase || '',
      message: s.message || ''
    }
    // 比对超时提醒：RUNNING 持续超过阈值仍未结束，触发一次显式通知（不阻塞、不中断比对）。
    // 竞态下后端可能在 RUNNING 时拒绝 AI 分析/智能分类（“比对尚未完成: RUNNING”），此提示与之一致。
    if (s.status === 'RUNNING') {
      if (!state.compareStartedAt) state.compareStartedAt = Date.now()
      if (!state.compareStalled && Date.now() - state.compareStartedAt > COMPARE_TIMEOUT_MS) {
        state.compareStalled = true
        if (!state.stallNotified) {
          state.stallNotified = true
          toast('warning', '比对进行已超过 ' + Math.round(COMPARE_TIMEOUT_MS / 60000) + ' 分钟仍未完成；可继续等待，若长时间无进展请检查磁盘/网络后重启工具')
        }
      }
    } else if (s.status === 'DONE' || s.status === 'ERROR' || s.status === 'CANCELLED') {
      // 终态：清空超时统计，下次比对重新计数/提醒
      state.compareStartedAt = null
      state.compareStalled = false
      state.stallNotified = false
    }
    if (s.status === 'DONE') return s
    if (s.status === 'ERROR') throw new Error(s.error || '比对失败')
    if (s.status === 'CANCELLED') return null
    // 退避轮询：解包等瞬态阶段高频捕捉 phase；稳定运行阶段降频（终态已在循环内 return，不受此等待影响）
    const waitMs = s.phase === 'unpacking' ? POLL_FAST_MS : POLL_NORM_MS
    await new Promise(res => setTimeout(res, waitMs))
  }
  return null // activeCompareId 已变（取消 / 新比对）→ 中止
}

/** 取消正在进行的比对（仅置标记；后端在阶段边界终止，前端轮询见 CANCELLED 即退出）。 */
export function cancelCompare() {
  const id = state.activeCompareId
  if (!id) return
  state.activeCompareId = null // 让当前轮询循环退出
  api.cancelJob(id).catch(() => {})
  state.busyText = '已取消'
}

/**
 * 打开 / 激活一个对比 tab。左树点击触发：
 * - key 已在 tabs 里 → 仅切 activeKey（不触发二次反编译）
 * - key 不在         → push 占位 tab 并异步反编译，结果回写到对应 tab
 *   （用 key 定位而不是索引，因为反编译期间用户可能开了其他 tab）
 * 状态机本身抽到 lib/tabs.js，便于纯函数单测。
 */
// 在途反编译请求按 key 记录，便于切换/重复点击时取消上一次同 key 请求（节省带宽与 CPU）。
const inflightDecompile = new Map()

export async function selectEntry(key) {
  if (!state.job) return
  const node = state.job.tree.find(n => n.key === key) || null
  // 调用 lib/tabs.js 的纯函数决定要不要新开
  const r = activateTabImpl(state.tabs, state.activeKey, key, node)
  state.tabs = r.tabs
  state.activeKey = r.activeKey
  if (r.opened) await ensureTabLoaded(key, node)
}

/**
 * UI-06 恢复会话：恢复出来的 tab 只有元数据（decompile=null），点击时必须按需反编译。
 * 同一 key 已有在途请求时直接返回（inflightDecompile 去重）；已加载/不可反编译结果也返回。
 */
export async function ensureTabLoaded(key, node) {
  const tab = state.tabs.find(x => x.key === key)
  if (!tab || !state.job || tab.decompile || inflightDecompile.has(key)) return
  tab.busy = true
  tab.error = null
  // 取消上一次同一 key 的在途请求（重复点击 / 反复切换同一文件时去重）
  const prev = inflightDecompile.get(key)
  if (prev) prev.abort()
  const ac = new AbortController()
  inflightDecompile.set(key, ac)
  try {
    const dec = await api.decompile(state.job.jobId, key, { signal: ac.signal })
    // 可能用户在这段时间点了别的并启动了别的请求；用 key 找到当时那个 tab
    const t = state.tabs.find(x => x.key === key)
    if (t) {
      t.decompile = dec
      t.busy = false
      if (!dec.ok) {
        toast('warning', `「${(node && node.key) || key}」无法反编译：${dec.error || dec.engine || 'unknown'}`)
      }
    }
  } catch (e) {
    if (e && e.name === 'AbortError') return // 被新切换/重复点击取消，静默忽略
    const t = state.tabs.find(x => x.key === key)
    if (t) { t.decompile = null; t.busy = false; t.error = e.message }
    toast('danger', '反编译失败：' + e.message)
  } finally {
    if (inflightDecompile.get(key) === ac) inflightDecompile.delete(key)
  }
}

/** 关闭一个 tab。若关的就是当前激活的，则激活邻居（右 → 左 → null）。状态机纯函数在 lib/tabs.js。 */
export function closeTab(key) {
  const r = closeTabImpl(state.tabs, state.activeKey, key)
  state.tabs = r.tabs
  state.activeKey = r.activeKey
}

/** 切换「固定/取消固定」当前 tab：pinned 置顶，关闭类操作会跳过它。 */
export function pinTab(key) {
  const r = pinTabImpl(state.tabs, state.activeKey, key)
  state.tabs = r.tabs
  state.activeKey = r.activeKey
}

/** 关闭除 keepKey 之外的所有 tab（保留固定项）。 */
export function closeOtherTabs(keepKey) {
  const r = closeOtherTabsImpl(state.tabs, state.activeKey, keepKey)
  state.tabs = r.tabs
  state.activeKey = r.activeKey
}

/** 关闭全部 tab（保留固定项）。 */
export function closeAllTabs() {
  const r = closeAllTabsImpl(state.tabs, state.activeKey)
  state.tabs = r.tabs
  state.activeKey = r.activeKey
}

// ---------- 差异树右键菜单（BCompare 风格）状态动作 ----------

/** 排除某条目（视图临时隐藏，可一键恢复）。返回当前被排除总数。 */
export function excludeEntry(key) {
  if (!key) return 0
  state.excludedKeys[key] = true
  return Object.keys(state.excludedKeys).length
}

/** 恢复全部被排除的条目。 */
export function restoreAllExcluded() {
  state.excludedKeys = {}
  toast('info', '已恢复全部被排除的条目')
}

/** 添加忽略规则（同类型同值去重），持久化 localStorage。 */
export function addIgnoreRule(type, value) {
  const next = addRuleFn(state.ignoreRules, type, value)
  if (next.length === state.ignoreRules.length) return
  state.ignoreRules = next
  saveIgnoreRules(state.ignoreRules)
  toast('success', '已添加忽略规则，树中匹配条目将被隐藏')
}

/** 按下标删除忽略规则并持久化。 */
export function removeIgnoreRule(index) {
  state.ignoreRules = removeRuleFn(state.ignoreRules, index)
  saveIgnoreRules(state.ignoreRules)
}

/** 清空全部忽略规则并持久化。 */
export function clearIgnoreRules() {
  state.ignoreRules = []
  saveIgnoreRules(state.ignoreRules)
  toast('info', '已清空忽略规则')
}

/** 设置/取消基准文件夹（文件夹对象右键「设为基准文件夹」，再次点击取消）。 */
export function setBaseFolder(key) {
  state.baseFolder = (state.baseFolder === key) ? null : key
}

/** 打开「属性」弹窗（目标节点快照，非 null 即显示）。 */
export function showProperties(node) {
  state.propertyNode = node
}
export function hideProperties() {
  state.propertyNode = null
}

/**
 * 请求差异树定位到某路径层级（PathBar 面包屑点击目录段 / 编辑框确认目录时调用）。
 * prefix：相对路径前缀（如 'WEB-INF/lib'，'' = 定位到树顶部）。
 * DiffTree watch state.treeLocate.seq 后滚动到第一个匹配节点并 flash 高亮。
 */
export function locateTreePrefix(prefix) {
  state.treeLocate = { prefix: prefix || null, seq: (state.treeLocate.seq || 0) + 1 }
}

/** 右键「AI功能总结」：对单个文件发起 AI 分析任务（复用 AiConsole 流式管线）。 */
export function startFileAiSummary(node) {
  if (!node || !state.job) {
    toast('warning', '请先完成一次「开始比对」')
    return
  }
  // 传 fileKey 走后端「单文件聚焦」管线：仅计算并分析该文件的差异内容，
  // 而非把文件名拼进自定义 prompt（那样后端仍分析全量差异，结论与整体风险分析雷同）。
  // fileStatus 必须透传：归档内部条目（复合键）不在后端顶层 DiffResult 里，
  // 后端 statusOf 对其一律误判 MODIFIED，前端树节点的 status 才是权威值（评审 H1）
  startAiAnalysis('file', '', { fileKey: node.key, fileStatus: node.status || '' })
}

/**
 * 懒加载某归档（含嵌套归档复合键）的内部条目清单（单层）。
 * 结果按复合键缓存到 state.archiveChildren；重复调用直接复用（除非 force）。
 * 用于差异树点击归档「展开」按钮时拉取子节点。
 */
export async function fetchEntryChildren(key, force = false) {
  if (!state.job) return null
  const cached = state.archiveChildren[key]
  if (cached && !force) return cached.children || []
  // 置 loading（保证响应式更新：先建对象再赋值，避免 Vue 跳过新键）
  state.archiveChildren[key] = { loading: true, error: null, children: cached ? (cached.children || []) : [] }
  try {
    const children = await api.entryChildren(state.job.jobId, key)
    state.archiveChildren[key] = { loading: false, error: null, children }
    return children
  } catch (e) {
    state.archiveChildren[key] = { loading: false, error: e.message, children: [] }
    toast('danger', '展开归档失败：' + e.message)
    return []
  }
}

/**
 * 自动递归解包：一次拉取完整嵌套差异树（后端逐层递归展开所有嵌套压缩包，
 * 受深度/节点护栏约束），并把每个嵌套归档的 children 落缓存、标记展开。
 * 用于「点开一个 zip，其内部所有嵌套 zip 自动逐层展开对比」，无需逐级手动点击。
 */
export async function fetchRecursiveTree(key) {
  if (!state.job) return null
  state.archiveChildren[key] = { loading: true, error: null, children: (state.archiveChildren[key] && state.archiveChildren[key].children) || [] }
  try {
    const tree = await api.entryRecursive(state.job.jobId, key)
    const kids = tree.children || []
    state.archiveChildren[key] = { loading: false, error: null, children: kids }
    state.expandedArchives[key] = true
    seedArchiveTree(kids)
    return kids
  } catch (e) {
    // 兼容旧后端（无 /api/entry/recursive 路由返回 404「未知 entry 操作」）：降级为单层展开，
    // 嵌套归档仍可逐级手动点击展开，避免展开失败硬报错阻塞比对浏览。
    try {
      const kids = await api.entryChildren(state.job.jobId, key)
      state.archiveChildren[key] = { loading: false, error: null, children: kids }
      state.expandedArchives[key] = true
      toast('warning', '后端未启用自动递归解包（服务端版本过旧），已降级为单层展开，可逐级点击展开嵌套归档')
      return kids
    } catch (e2) {
      state.archiveChildren[key] = { loading: false, error: e2.message, children: [] }
      toast('danger', '展开归档失败：' + e2.message)
      return []
    }
  }
}

/** 把递归解包返回的嵌套树写入缓存并标记全部嵌套归档为展开态（后端已按深度护栏截断）。 */
function seedArchiveTree(nodes) {
  for (const n of nodes) {
    if (Array.isArray(n.children) && (n.fileClass === 'ARCHIVE' || n.fileClass === 'JAR')) {
      state.archiveChildren[n.key] = { loading: false, error: null, children: n.children }
      state.expandedArchives[n.key] = true
      seedArchiveTree(n.children)
    }
  }
}

/**
 * 自动递归展开所有顶层归档节点（B 机制）：比对完成后由 runCompare 触发，无需手动点击即可
 * 查看嵌套包内部。顺序逐个拉取（/api/entry/recursive，一次返回完整嵌套树 + 递归标记展开），
 * 单节点失败不影响其余（try/catch 吞掉，避免一个坏归档阻塞整体浏览）。
 */
async function autoExpandArchives(tree) {
  if (!Array.isArray(tree)) return
  for (const n of tree) {
    if (n && (n.fileClass === 'ARCHIVE' || n.fileClass === 'JAR')) {
      try { await fetchRecursiveTree(n.key) } catch (_) { /* 单点失败不阻塞其余归档展开 */ }
    }
  }
}

/**
 * 切换归档展开态（点击展开按钮时调用）：展开则自动递归解包全部嵌套层并缓存子节点，
 * 折叠则仅清标记。
 */
export async function toggleArchive(key) {
  if (state.expandedArchives[key]) {
    delete state.expandedArchives[key]
    return false
  }
  state.expandedArchives[key] = true
  await fetchRecursiveTree(key) // 自动解包并逐层递归对比嵌套压缩包内容
  return true
}

/** 当前激活的 tab 视图（给 DiffView/InfoPanel 用）。无激活时为 null。 */
export function activeTab() {
  return findActiveTab(state.tabs, state.activeKey)
}

/** 设置共享的「分析项」选择（AiConsole 新建分析下拉 / 工具栏生成报告(AI) 共用）。 */
export function setAiSelCategory(v) { state.aiSelCategory = v }

export async function generateReport(ai, opts = {}) {
  // 统一入口守卫：所有调用方（InfoPanel / ToolBar / ReportPreview / 比对后自动报告）在缺失比对结果时都给明确反馈，
  // 避免「点了没反应、无遮罩、无提示」。此前 ToolBar 入口未守卫，会静默 return 导致同一症状。
  if (!state.job) { toast('warning', '请先完成一次比对，再生成报告'); return }
  if (state.reporting) return // 防止自动报告与手动报告并发重叠（后到者直接放弃，避免 reportMd 互相覆盖）
  // 分析项：显式传入优先（InfoPanel 破坏性→breaking / 审计→risk / ReportPreview 沿用上次）；否则用共享下拉当前选择。
  const category = opts.category || state.aiSelCategory
  const prompt = opts.prompt || null
  // 报告缓存键：'ai:<category>' 区分各 AI 分析项，'base' 为无 AI 基础报告——多类别报告并存互不覆盖。
  const cacheKey = ai ? 'ai:' + (category || 'default') : 'base'
  // 缓存命中短路：同类别报告已生成过则直接展示（秒开），无需再走成本闸门/后端调用。
  // opts.force=true（ReportPreview「重新生成」）强制刷新，绕过缓存。
  if (!opts.force && state.reportCache[cacheKey]) {
    state.reportMd = state.reportCache[cacheKey]
    state.reportAi = ai
    state.reportCategory = ai ? category : null
    if (!opts.silent) toast('info', '已展示该分析项的报告缓存（如需刷新请点「重新生成」）')
    return
  }
  // 成本闸门（P0 #5）：AI 报告超阈值需强制确认；静默自动报告超阈值则跳过并提示手动。
  if (ai) {
    const ok = await ensureAiBudget('report', { silent: !!opts.silent, category, prompt })
    if (!ok) {
      if (opts.silent) toast('warning', 'AI 报告预估超成本阈值，已跳过自动生成，请手动确认后生成')
      return
    }
  }
  // 项目级上下文目录：配置开启时随请求透传（后端未收到时亦会回退服务端配置，双保险）
  const projDir = (state.config && state.config.projectContextEnabled) ? (state.config.projectContextDir || null) : null
  state.reporting = true
  const silent = !!opts.silent
  if (silent) state.analyzing = true
  else { state.busy = true; state.busyText = ai ? '正在调用 AI 分析…' : '正在生成报告…' }
  try {
    const md = await api.report(state.job.jobId, ai, category, prompt, projDir)
    state.reportCache[cacheKey] = md
    state.reportMd = md
    state.reportAi = ai
    state.reportCategory = ai ? category : null   // 记录本次分析项，供 ReportPreview「重新生成」沿用
    if (!silent) toast('success', ai ? 'AI 分析报告已生成' : '报告已生成')
  } catch (e) {
    toast('danger', '生成报告失败：' + e.message)
  } finally {
    if (silent) state.analyzing = false
    else state.busy = false
    state.reporting = false
  }
}

/** AI 智能分类与优先级（B2 自动打标 + A1 风险分级）：对差异树文件打标并在左树展示。 */
export async function runAiClassify() {
  // 比对进行中（含解析/解包/对比）直接拦截并给进度引导，避免竞态下后端返回「409 比对尚未完成: RUNNING」。
  if (!state.job || state.job.status !== 'DONE') { toast('warning', '请先完成一次比对，再进行智能分类'); return }
  if (isRunning()) { toast('warning', '比对仍在进行中，请等待比对完成后，再进行智能分类'); return }
  if (state.classifying) return
  // 成本闸门（P0 #5）：超阈值强制确认
  const ok = await ensureAiBudget('classify')
  if (!ok) return
  state.classifying = true
  try {
    const projDir = (state.config && state.config.projectContextEnabled) ? (state.config.projectContextDir || null) : null
    const data = await api.classify(state.job.jobId, projDir)
    const map = {}
    for (const it of (data.items || [])) map[it.key] = { risk: it.risk, category: it.category, reason: it.reason }
    state.aiClassify = map
    const cov = data.coverage || Object.keys(map).length
    const total = data.totalChanged || 0
    toast('success', `智能分类完成：已标注 ${cov}/${total} 个变更文件`)
  } catch (e) {
    const msg = (e && e.message) || String(e)
    toast('danger', '智能分类失败：' + msg)
    // 竞态/结果吻合：后端在比对 RUNNING 时拒绝（“比对尚未完成: RUNNING”）。
    // 不改错误含义，仅补充操作指引；底部「比对进行中」横条会同步显示归属阶段。
    if (/RUNNING|尚未完成|比对还在|仍不是 DONE/i.test(msg)) {
      toast('info', '比对仍在进行中，请在底部查看“比对进行中”的进度说明，等待比对完成后重试。若超过 5 分钟无进展，请检查磁盘/网络后重启工具。')
    }
  } finally {
    state.classifying = false
  }
}

// ---------------- 并行 AI 分析（非阻塞控制台，替代原阻塞模态） ----------------
// 每次「新建分析」生成一个独立 task 写入 state.aiTasks；后端 ai-analyze 按请求走各自虚拟线程，
// 天然支持并行。前端不再置 state.busy，故不会弹出全屏遮罩、不阻塞用户操作。
// task.status ∈ { thinking, streaming, done, error, aborted }

/** AI 分析类别目录：用户可在控制台下拉选择，并行发起多种类别的分析。 */
// label 保留中文原文（兼容既有单测断言）；labelKey 为 i18n 键（下拉与任务标题渲染侧优先）
export const AI_CATEGORIES = [
  { key: 'risk',       label: '整体风险分析',   labelKey: 'cat.risk',       icon: 'bi-shield-exclamation' },
  { key: 'breaking',   label: '破坏性变更专项', labelKey: 'cat.breaking',   icon: 'bi-exclamation-octagon' },
  { key: 'impact',     label: '影响范围分析',   labelKey: 'cat.impact',     icon: 'bi-diagram-3' },
  { key: 'testpoints', label: '测试要点分析',   labelKey: 'cat.testpoints', icon: 'bi-list-check' },
  { key: 'custom',     label: '自定义问题',     labelKey: 'cat.custom',     icon: 'bi-chat-left-text' }
]

let aiTaskSeq = 0
// AbortController 置于 reactive 之外（原生对象被响应式包裹会破坏 .abort() 的 this 绑定）
const aiControllers = new Map()

/** 是否有 AI 分析任务正在运行（供工具栏在分析中禁用对比/路径输入，避免干扰结果）。 */
export function aiAnyRunning() {
  return state.aiTasks.some(t => t.status === 'thinking' || t.status === 'streaming')
}

/** 是否正处于「逐层解包」阶段。解包是异步多线程逐层展开，后端 phase=unpacking 时禁止一切 AI 分析
 *  与报告生成（须待解包完全完成、快照就绪后方可分析，否则分析不全面）。
 *  与 aiAnyRunning 一样作单一判定源，供 StatusBar/AiConsole/ToolBar 共用，避免双处维护漂移。 */
export function isUnpacking() {
  return !!state.jobProgress && state.jobProgress.phase === 'unpacking'
}

/** 比对超时提醒阈值：RUNNING 持续超过此值时提示用户（默认 5 分钟）。 */
const COMPARE_TIMEOUT_MS = 5 * 60 * 1000
// 轮询退避：仅「自动迭代解包」等瞬态阶段需高频轮询以捕捉瞬时 phase（此前一律 80ms 造成比对全程高频请求）；
// 稳定运行阶段用更低频，减少比对生命周期内的无谓后端请求。
const POLL_FAST_MS = 80
const POLL_NORM_MS = 200

/** 是否正处于比对进行中（QUEUED/RUNNING，覆盖解析/解包/比对各阶段）。 */
export function isRunning() {
  const st = (state.jobProgress && state.jobProgress.status) || ''
  return st === 'RUNNING' || st === 'QUEUED' || st === 'queued'
}

/** 当前处理阶段的可读文案（与“比对尚未完成: RUNNING”逻辑呼应）。 */
export function phaseLabel() {
  const ph = (state.jobProgress && state.jobProgress.phase) || ''
  switch (ph) {
    case 'parsing': return t('phase.parsing')
    case 'unpacking': return t('phase.unpacking')
    case 'diffing': return t('phase.diffing')
    case 'building': return t('phase.building')
    default: return t('phase.default')
  }
}

function fileSummaryLabel(fileKey) {
  const k = String(fileKey || '')
  const i = k.lastIndexOf('!/')
  if (i < 0) return k.split('/').pop() || k
  const outer = k.slice(0, i).split('/').pop()
  const inner = k.slice(i + 2).split('/').pop()
  return outer + '!/' + inner
}

/** 发起一个新的 AI 分析任务（非阻塞）。category 取自 AI_CATEGORIES；custom 时传 prompt；
 *  opts.fileKey 非空时为单文件「AI功能总结」任务（仅分析该文件）。 */
export function startAiAnalysis(category = 'risk', prompt = '', opts = {}) {
  // 比对进行中（含解析/解包/对比）直接拦截并给进度引导，避免竞态下后端返回「409 比对尚未完成: RUNNING」。
  if (isRunning()) {
    toast('warning', t('ac.waitCompare'))
    return
  }
  if (!state.job || state.job.status !== 'DONE') {
    toast('warning', t('ac.needCompare'))
    return
  }
  // 'file' 不在下拉目录中（仅右键入口使用），找不到时构造虚拟类别避免误标为「整体风险分析」
  const cat = AI_CATEGORIES.find(c => c.key === category) || { key: category, label: t('cat.file'), labelKey: 'cat.file', icon: 'bi-file-earmark-text' }
  const fileKey = opts.fileKey || null
  const fileStatus = opts.fileStatus || null
  const id = 'ai-' + (++aiTaskSeq)
  // P2-8：标题保存 titleKey + titleParams（而非当时语言的成品字符串），切换中英文后历史任务标题可重新求值。
  const titleKey = cat.key === 'custom'
    ? 'ac.customTitle'
    : (fileKey ? 'ac.fileTitle' : (cat.labelKey || 'cat.file'))
  const titleParams = cat.key === 'custom'
    ? { q: (prompt || '').slice(0, 18) }
    : (fileKey ? { name: fileSummaryLabel(fileKey) } : {})
  const title = t(titleKey, titleParams)
  const task = {
    id, category: cat.key, title, titleKey, titleParams, prompt: cat.key === 'custom' ? (prompt || '') : '',
    fileKey, fileStatus,
    status: 'thinking', thinking: [], answer: '', error: '', createdAt: Date.now(),
    alive: true,          // 任务存活令牌：中断/关闭后置 false；闸门等待窗口内被中断则不再启动流（评审 P0 #2）
    thinkingCollapsed: true // 思维链折叠态（显式初始化，避免依赖 undefined 隐式语义，评审 P2 #12）
  }
  state.aiTasks.push(task)
  // 注意：此处局部变量有意命名 taskRef 而非 t——模块级 t 是 i18n 翻译函数，
  // 若用 const t 覆蔽会与其上方 title 计算处的 t() 调用形成 TDZ 冲突（同函数作用域）。
  const taskRef = state.aiTasks[state.aiTasks.length - 1] // 取响应式代理引用，后续 mutation 都走它
  state.aiActiveTaskId = id
  state.aiPanelCollapsed = false // 展开智能分析栏以露出控制台
  state.aiPanelTab = 'console'    // 自动切到「控制台」tab，确保用户即时看到流式输出
  ensureAiBudget('analyze', { category: cat.key, prompt: cat.key === 'custom' ? (prompt || '') : '', fileKey }).then((ok) => {
    if (!taskRef.alive) return // 等待闸门期间任务已被中断/关闭，丢弃，不复活（评审 P0 #2）
    if (!ok) {
      taskRef.status = 'error'
      taskRef.error = t('ai.cancelled')
      return
    }
    runAiTaskStream(taskRef)
  })
}

/** 拉起某个 task 的 SSE 流（从头开始）。
 *  回调归属守卫：若该 task 已被「重新分析/关闭」换上新 controller，旧流的异步回调
 *  （onThinking/onAnswer/onDone/onError）一律忽略，避免污染新流答案或误删新 controller。 */
function runAiTaskStream(t) {
  t.status = 'streaming'
  t.thinking = []
  t.answer = ''
  t.error = ''
  const body = { category: t.category, prompt: t.prompt || '' }
  // 单文件任务：透传 fileKey/fileStatus，后端走「单文件聚焦」管线（仅分析该文件）；
  // fileStatus 为前端树节点权威变更类型，复合键场景后端无法自行判定（评审 H1）
  if (t.fileKey) {
    body.fileKey = t.fileKey
    if (t.fileStatus) body.fileStatus = t.fileStatus
  }
  // 项目级上下文目录：配置开启时随流式分析透传（后端未收到时亦会回退服务端配置）
  if (state.config && state.config.projectContextEnabled && state.config.projectContextDir) {
    body.projectDir = state.config.projectContextDir
  }
  const controller = api.analyzeStream(state.job.jobId, {
    onThinking: (d) => {
      // 仅当当前流的 controller 仍是本 controller 时才写入（防止陈旧流污染）
      if (aiControllers.get(t.id) !== controller) return
      t.thinking.push({ phase: d.phase || 'thinking', message: d.message || '' })
    },
    onAnswer: (d) => {
      if (aiControllers.get(t.id) !== controller) return
      t.answer += (d.text || '')
    },
    onDone: (d) => {
      if (aiControllers.get(t.id) !== controller) return
      aiControllers.delete(t.id)
      t.status = (d && d.aborted) ? 'aborted' : 'done'
    },
    onError: (d) => {
      if (aiControllers.get(t.id) !== controller) return
      aiControllers.delete(t.id)
      t.status = 'error'
      t.error = (d && d.message) || tr('ai.analyzeFailed')
    }
  }, body)
  aiControllers.set(t.id, controller)
}

/** 中断某任务：中止 SSE 读取（服务端继续跑完也无妨，前端已停止消费）；置 alive=false 防止闸门未决窗口内复活（评审 P0 #2）。 */
export function stopAiAnalysis(id) {
  const c = aiControllers.get(id)
  if (c) { c.abort(); aiControllers.delete(id) }
  const t = state.aiTasks.find(x => x.id === id)
  if (t) {
    t.alive = false
    if (t.status === 'thinking' || t.status === 'streaming') t.status = 'aborted'
  }
}

/** 重新开始某任务（复用同一 task 对象重置后重跑）。同样受成本闸门约束，放行后才重跑（评审 P0 #3）。 */
export function restartAiAnalysis(id) {
  const t = state.aiTasks.find(x => x.id === id)
  if (!t) return
  stopAiAnalysis(id) // 中断旧流 + alive=false
  t.alive = true     // 标记本任务将继续；等待闸门期间若被再次中断/关闭则置回 false
  t.status = 'thinking'
  t.thinking = []
  t.answer = ''
  t.error = ''
  ensureAiBudget('analyze', { category: t.category, prompt: t.prompt, fileKey: t.fileKey }).then((ok) => {
    if (!t.alive) return // 等待闸门期间任务被中断/关闭，丢弃
    if (!ok) {
      t.status = 'error'
      t.error = '已取消：本次 AI 分析预估超成本阈值，未发起调用。'
      return
    }
    runAiTaskStream(t)
  })
}

/** 切换聚焦的任务 tab。 */
export function selectAiTask(id) { state.aiActiveTaskId = id }

/** 关闭某任务：先中断（若运行中）再移出列表；若关的是当前聚焦，则聚焦邻居。 */
export function closeAiTask(id) {
  stopAiAnalysis(id)
  const i = state.aiTasks.findIndex(x => x.id === id)
  if (i < 0) return
  state.aiTasks.splice(i, 1)
  if (state.aiActiveTaskId === id) {
    state.aiActiveTaskId = state.aiTasks.length ? state.aiTasks[Math.max(0, i - 1)].id : null
  }
}

/** 打开「预览报告」弹窗（展示某个 task 的 Markdown 结论，与全局 reportMd 解耦）。 */
export function openReportPreview(md) {
  if (!md) return
  state.previewMd = md
  state.previewOpen = true
}
export function closeReportPreview() {
  state.previewOpen = false
  state.previewMd = null
}

export function downloadExport(granularity = 'all') {
  // 兼容旧入口：一律走「小包同步 / 大包异步」的新机制；granularity 透传导出粒度（T02742）。
  return startExport(granularity)
}

/** T06230：导出差异清单 CSV（路径/状态/类型）。公式注入在 csv_export 内统一转义。 */
export function downloadDiffCsv() {
  const job = state.job
  if (!job || !Array.isArray(job.tree) || !job.tree.length) {
    toast('warning', t('mm.csvEmpty'))
    return false
  }
  const csv = buildDiffCsv(job.tree)
  const ok = downloadCsv(`bempdiff-diff-${job.jobId || 'job'}.csv`, csv)
  if (ok) toast('success', t('mm.csvDone'))
  return ok
}

/** 格式化字节为可读字符串。 */
export function fmtBytes(n) {
  if (!n || n <= 0) return '—'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}

/** 打开/关闭「下载管理」面板，并拉取最新导出记录。 */
export function openExports() { state.exportsOpen = true; refreshExports() }
export function closeExports() { state.exportsOpen = false }
export function toggleExports() {
  state.exportsOpen = !state.exportsOpen
  if (state.exportsOpen) refreshExports()
}
export async function refreshExports(list) {
  try {
    const r = await api.exportList()
    if (Array.isArray(r)) { state.exportRecords = r; return r }
  } catch (e) { /* 后端不可用等，静默 */ }
  return state.exportRecords
}
export async function deleteExport(id) {
  try {
    await api.exportDelete(id)
    state.exportRecords = state.exportRecords.filter(x => x.id !== id)
  } catch (e) {
    toast('danger', '删除导出记录失败：' + (e.message || e))
  }
}

// ---- 同步导出资产（客户端记录，随浏览器下载目录留存，供「下载管理」回溯） ----
const SYNC_EXPORT_KEY = 'bempdiff-sync-exports'
const SYNC_EXPORT_CAP = 50 // 菜单项数量上限：仅保留最近 50 条，避免无限累积
/** 从 localStorage 载入同步导出记录（数组缺失/损坏时兜底为空）。 */
function loadSyncExports() {
  try {
    const raw = localStorage.getItem(SYNC_EXPORT_KEY)
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? arr : []
  } catch (e) { return [] }
}
/** 记录一次同步导出完成（unshift 最新在前，超出上限截断并持久化）。 */
export function recordSyncExport(entry) {
  const next = [{ filename: entry.filename, size: entry.size || 0, createdAt: entry.createdAt || Date.now() }, ...state.syncExports]
  const capped = next.slice(0, SYNC_EXPORT_CAP)
  state.syncExports = capped
  try { localStorage.setItem(SYNC_EXPORT_KEY, JSON.stringify(capped)) } catch (e) { /* 存储不可用则仅内存 */ }
}
/** 从同步导出列表中移除一条（按 createdAt 定位，仅本机记录，不影响浏览器已下载文件）。 */
export function removeSyncExport(createdAt) {
  state.syncExports = state.syncExports.filter(x => x.createdAt !== createdAt)
  try { localStorage.setItem(SYNC_EXPORT_KEY, JSON.stringify(state.syncExports)) } catch (e) { /* 忽略 */ }
}

// 当前同步导出请求的取消句柄；非空表示导出进行中，可被用户取消（底部状态栏“导出中”旁的 ×）。
let exportAbort = null

/** 主动取消本次导出：中断请求、恢复按钮与提示（取消/异常均走 startExport 的 finally 兜底复位）。 */
export function cancelExport() {
  const c = exportAbort
  exportAbort = null
  if (c) { try { c.abort() } catch (e) { /* 忽略 */ } }
  state.exporting = false
  state.exportProgress = null
  toast('info', '已取消导出')
}

/** 差异资产导出入口：由后端按规模分流 —— 小包同步流式（读流显示进度）、大包异步（后台生成 + 下载管理页）。
 *  @param {string} granularity 导出粒度：'all'=增量全量，'jar'=仅变化 jar，'class'=仅变化 class（T02742）。 */
export async function startExport(granularity = 'all') {
  if (!state.job || state.exporting) return
  // 解包中禁止导出：须待自动逐层解包比对完成后（job DONE）方可导出差异资产；
  // 后端 /export/start 同样以 status==DONE 兜底 409，此处前端先行拦截以给出友好提示。
  if (isUnpacking()) { toast('warning', '正在自动逐层解包，请稍候解包完成后导出'); return }
  state.exporting = true
  state.exportProgress = null
  const ctrl = new AbortController()
  exportAbort = ctrl
  try {
    const res = await fetch(`/api/job/${encodeURIComponent(state.job.jobId)}/export/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ granularity }),
      signal: ctrl.signal
    })
    const ct = (res.headers.get('content-type') || '').toLowerCase()
    if (ct.includes('application/json')) {
      // 大包：异步任务已启动，写入下载管理并轮询状态
      let rec
      try { rec = await res.json() } catch (e) { rec = {} }
      toast('info', `导出已启动，预计 ${rec.etaText || '若干时间'} 完成，可在「下载管理」查看`)
      await refreshExports(rec)
      // 方案 B：不自动弹出「下载管理」面板——避免覆盖工具栏区域的导出下拉菜单（遮挡其可见项/导出按钮）。
      // 保持『导出差异资产』为纯手动触发：用户按上一条 toast 指引点「下载管理」（openExports）再查看/下载。
      if (rec && rec.id) pollExport(rec.id)
    } else {
      // 小包：同步读流，展示实时进度；完成后触发浏览器保存
      await consumeExportStream(res, granularity)
    }
  } catch (e) {
    // 用户取消（AbortError）不应视为失败；取消提示已由 cancelExport 给出。
    if (!(e && e.name === 'AbortError')) {
      toast('danger', '导出失败：' + (e.message || e))
    }
  } finally {
    exportAbort = null
    state.exporting = false
    state.exportProgress = null
  }
}

/** 同步导出：读取响应体并实时更新进度，写完转为 Blob 下载。
 *  @param {string} granularity 导出粒度，用于下载文件名区分（T02742）。 */
async function consumeExportStream(res, granularity) {
  if (!res || !res.ok) {
    let msg = `${res ? res.status : ''} ${res ? res.statusText : '无法连接'}`
    try { if (res) { const j = await res.json(); if (j && j.error) msg = j.error } } catch (e) { /* 非 JSON 忽略 */ }
    throw new Error(msg || '导出失败')
  }
  const total = Number(res.headers.get('content-length') || 0)
  const reader = res.body.getReader()
  const chunks = []
  let loaded = 0
  const started = Date.now()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) { chunks.push(value); loaded += value.length }
      if (total > 0) {
        const percent = Math.min(100, Math.round((loaded / total) * 100))
        const speed = loaded / Math.max(1, (Date.now() - started) / 1000)
        const remainSec = speed > 0 ? Math.max(0, (total - loaded) / speed) : 0
        state.exportProgress = {
          loaded, total, percent,
          etaText: fmtEta(remainSec)
        }
      }
    }
  } finally {
    reader.releaseLock()
  }
  const blob = new Blob(chunks, { type: 'application/zip' })
  const url = URL.createObjectURL(blob)
  // T02742：文件名携带粒度（all 不额外标注），便于区分三种导出产物。
  const g = (granularity && granularity !== 'all') ? '-' + granularity : ''
  const filename = `bempdiff-export${g}-${state.job.jobId}.zip`
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
  toast('success', '导出完成，已开始下载')
  // 同步导出同样记入客户端「下载管理」的同步资产列表，便于回溯（文件本体存于浏览器下载目录）。
  recordSyncExport({ filename, size: loaded, createdAt: Date.now() })
}

function fmtEta(secs) {
  if (!secs || secs <= 0) return ''
  if (secs < 60) return `约剩 ${Math.ceil(secs)} 秒`
  return `约剩 ${Math.ceil(secs / 60)} 分钟`
}

/** 异步导出：轮询记录直至 done/error，done 后刷新下载管理列表。 */
async function pollExport(id, attempts = 0) {
  try {
    const rec = await api.exportGet(id)
    if (rec && (rec.status === 'done' || rec.status === 'error')) {
      await refreshExports()
      if (rec.status === 'done') {
        toast('success', '导出完成，点击「下载管理」中的下载即可获取')
      } else {
        toast('danger', rec.message || '导出失败，请在下载管理查看')
      }
      return
    }
    if (attempts < 600) {
      setTimeout(() => pollExport(id, attempts + 1), 2000)
    }
  } catch (e) {
    // 网络抖动等，隔几秒重试直至不再增长
    if (attempts < 120) setTimeout(() => pollExport(id, attempts + 1), 3000)
  }
}

export { fmtBytes as formatBytes }

export async function saveConfig(cfg) {
  try {
    const r = await api.putConfig(cfg)
    // 采用服务端回读（PUT 返回的就是 toJson()，含 hasApiKey 与按 persist 语义的明文回显）而不是请求体：
    // 请求体没有 hasApiKey 字段，直接 state.config = cfg 会把它变成 undefined，于是「密钥已落盘」
    // 在前端永久失明——T01615 的「将删除已落盘 API Key」二次确认在会话内保存过一次后即失效，
    // 取消勾选「记住」再保存会静默删掉 properties 里的密钥行（真实安装版复验实测）。
    state.config = r && typeof r === 'object' ? r : cfg
    toast('success', '配置已保存')
  } catch (e) {
    toast('danger', '保存配置失败：' + e.message)
  }
}

export async function testConnection(payload) {
  try {
    const r = await api.testAi(payload)
    if (r && r.ok) {
      toast('success', '连接测试：' + (r.message || '已连通'))
      return true
    }
    const msg = (r && r.message) || '未连通'
    // R8（二期 T01469）：结构化归因 + 建议动作，替代裸报错文案
    toast('warning', '连接测试未通过。' + friendlyAiError(msg))
    return false
  } catch (e) {
    toast('danger', '连接测试未通过。' + friendlyAiError(e.message))
    return false
  }
}

/**
 * 查询/刷新「上下文目录」的递归项目索引状态（配置中心可视化 + 生效态感知）。
 * refresh=true 强制后端重扫（忽略缓存）；默认读缓存（命中时秒回）。
 * 返回 { ok, projectCount, scannedAt, fromCache, javaFileCount, projects[] } 或 null。
 */
export async function loadContextStatus(refresh = false) {
  const dir = (state.config && state.config.projectContextEnabled)
    ? (state.config.projectContextDir || '') : ''
  if (!dir) {
    state.aiContext = { loading: false, data: null, error: '' }
    return null
  }
  state.aiContext.loading = true
  state.aiContext.error = ''
  try {
    const data = await api.contextStatus(dir, refresh)
    state.aiContext.data = (data && data.ok) ? data : null
    if (!data || !data.ok) state.aiContext.error = (data && data.message) || '上下文加载失败'
    return data
  } catch (e) {
    state.aiContext.data = null
    state.aiContext.error = e.message || '上下文加载失败'
    return null
  } finally {
    state.aiContext.loading = false
  }
}

/**
 * 按 API Base URL + Key 自动获取可用模型列表（后端 /api/ai/models）。
 * payload: { provider, baseUrl, apiKey, httpProxy?, httpsProxy?, blockPrivateEndpoints? }
 * 成功时把模型 ID 列表写入 state.aiModels 供配置中心「模型名称」字段下拉/补全，返回该列表；
 * 失败/为空时弹告警并返回空数组（不抛，调用方按返回值处理）。
 */
export async function fetchModels(payload) {
  try {
    const r = await api.fetchModels(payload)
    if (r && r.ok && Array.isArray(r.models) && r.models.length) {
      state.aiModels = r.models
      toast('success', `已获取 ${r.models.length} 个可用模型`)
      return r.models
    }
    const msg = (r && r.lastError) ? r.lastError : '未获取到模型列表'
    toast('warning', '获取模型列表失败：' + msg)
    return []
  } catch (e) {
    toast('danger', '获取模型列表失败：' + e.message)
    return []
  }
}

// ---------------- 成本闸门（P0 #5）：超阈值强制确认 ----------------

// 强制确认弹窗的等待队列（置于 reactive 之外，避免函数被 Vue 响应式包裹）。
// 队列化：多个 AI 入口（分析/报告/分类）并发超阈值时逐个确认、先到先弹，
// 避免单例 resolve 句柄被覆盖导致先到入口的 Promise 永久悬挂（评审 P0 #1）。
const costGateQueue = []   // [{ resolve, action, estimate, threshold }]

function showNextCostGate() {
  const item = costGateQueue[0]
  state.costGate = item ? { action: item.action, estimate: item.estimate, threshold: item.threshold } : null
}

/** 用户确认成本闸门：放行队头被拦截的 AI 调用，并展示下一个等待确认的入口（如有）。 */
export function confirmCostGate() {
  const item = costGateQueue.shift()
  if (item) item.resolve(true)
  showNextCostGate()
}

/** 用户取消成本闸门：放弃队头本次 AI 调用，并展示下一个等待确认的入口（如有）。 */
export function cancelCostGate() {
  const item = costGateQueue.shift()
  if (item) item.resolve(false)
  showNextCostGate()
}

/**
 * 成本闸门核心：判断某 AI 入口是否可放行。
 *  - 闸门关闭（threshold<=0）→ 直接放行
 *  - 预估未超阈值 → 直接放行
 *  - 预估超阈值：
 *      - 交互入口（silent=false）→ 弹强制确认弹窗，等待用户确认/取消
 *      - 自动/静默入口（silent=true，如比对后自动报告）→ 不弹窗，返回 false 由调用方提示手动
 * action ∈ { 'report', 'analyze', 'classify' }。
 */
export async function ensureAiBudget(action, opts = {}) {
  const silent = !!opts.silent
  if (!state.config) return true
  const threshold = state.config.costGateWarnTokens || 0
  if (threshold <= 0) return true // 闸门未启用
  // 取预估（per-job 缓存；缓存键绑定「分析项 + prompt 指纹 + 单文件键」，换类别、换自定义问题
  // 或换目标文件即失效，避免闸门估算与实际消耗脱节）。
  // 不同分析项的 prompt 不同、token 预估不同；同一 category 下 custom 换问题也要失效（评审 P1 #5）。
  const cat = opts.category || null
  const pr = opts.prompt || null
  const fk = opts.fileKey || null
  const estKey = (cat || '') + '|' + (pr || '') + '|' + (fk || '')
  if (state.aiEstimate && state.aiEstimateKey !== estKey) {
    state.aiEstimate = null
  }
  if (!state.aiEstimate && state.job) {
    try {
      state.aiEstimate = await api.aiEstimate(state.job.jobId, cat, pr, fk)
      state.aiEstimateKey = estKey
    }
    catch (e) { return true }
  }
  const est = state.aiEstimate ? (state.aiEstimate[action] || 0) : 0
  if (est <= threshold) return true
  if (silent) return false
  // 超阈值 → 弹强制确认，等待用户决策（队列化：并发超阈值的入口逐个确认，先到先弹，互不覆盖）
  return await new Promise((resolve) => {
    costGateQueue.push({ resolve, action, estimate: est, threshold })
    if (!state.costGate) showNextCostGate()
  })
}