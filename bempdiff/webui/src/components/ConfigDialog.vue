<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { state, saveConfig, testConnection, fetchModels, loadContextStatus, defaultConfig, toast } from '../store'
import { api } from '../api/client'
import { pickPath, isElectron } from '../lib/desktop-bridge.js'
import { t } from '../lib/i18n'

// 断点续录：未保存就关闭配置中心时，把当前表单草稿暂存本地，下次打开恢复。
// 仅暂存"尚未落盘"的修改，保存成功即清除——避免误以为已保存、也避免重装后读到过期草稿。
const DRAFT_KEY = 'bempdiff:config:draft'
const DRAFT_KEYS = new Set(['aiApiKey', 'aiBaseUrl', 'aiModel', 'aiProvider', 'httpProxy', 'httpsProxy',
  'blockPrivateEndpoints', 'aiEnabled', 'internalPrefixes', 'expandAll', 'ignoreExtensions',
  'filterSearch', 'filterRegex', 'filterShowModified', 'filterShowAdded', 'filterShowDeleted',
  'filterShowUnchanged', 'filterRisk', 'sortByRisk', 'topK', 'stageATopK', 'stageBTopK',
  'stageAFileSampleLines', 'maxPromptTokens', 'maxOutputTokens', 'costGateWarnTokens', 'treeViewMode',
  'treeSortMode', 'unpackNested', 'unpackThreads', 'unpackMaxDepth', 'persistApiKey', 'projectContextDir',
  'projectContextEnabled'])
function loadDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (_) { return null }
}
function saveDraft(form) {
  // 按 persistApiKey 语义脱敏：未选择"记住 API Key"则不把明文 key 落盘草稿，
  // 与配置落盘策略保持一致（Key 只存在于当前会话内存）。
  const persist = form.persistApiKey === true
  const snap = {}
  for (const k of DRAFT_KEYS) {
    // 空密钥不写草稿：草稿的「空串」会把后端已持久化/已回显的真实 Key 覆盖成空白，
    // 表现为「勾了记住、Key 也落盘了，但输入框看不到字符串」（T01567）。空值本就不需续录。
    if (k === 'aiApiKey' && (!persist || !form[k])) continue
    if (form[k] !== undefined) snap[k] = form[k]
  }
  try { localStorage.setItem(DRAFT_KEY, JSON.stringify(snap)) } catch (_) { /* 存储满/禁用时静默放弃草稿 */ }
}
function clearDraft() {
  try { localStorage.removeItem(DRAFT_KEY) } catch (_) { /* ignore */ }
}
// 取表单的「草稿相关字段」快照（用于判定未保存修改是否仍存在）。
function snapForm(f) {
  const o = {}
  for (const k of DRAFT_KEYS) if (f[k] !== undefined) o[k] = f[k]
  return o
}
let savedSnapshot = null // 最近一次「已保存/打开时初始」字段快照，close 时据此判断是否需要写草稿
import HelpDoc from './HelpDoc.vue'
import About from './About.vue'

const props = defineProps({ visible: { type: Boolean, default: false } })
const emit = defineEmits(['close'])

// 厂商预设：value=落到后端的 provider 代码（openai/ollama/qwen/...），label=展示名。
// 与 java_core LlmPreset.builtinPresets() 保持一致（前端只发 code，不发明文厂商名）。
const PRESETS = [
  { key: 'openai',   labelKey: 'cfg.preset.openai',        baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o', local: false },
  { key: 'azure',    labelKey: 'cfg.preset.azure',        baseUrl: 'https://<resource>.openai.azure.com', model: 'gpt-4o', local: false },
  { key: 'ollama',   labelKey: 'cfg.preset.ollama', baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5:7b', local: true },
  { key: 'deepseek', labelKey: 'cfg.preset.deepseek',            baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', local: false },
  { key: 'qwen',     labelKey: 'cfg.preset.qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus', local: false },
  { key: 'glm',      labelKey: 'cfg.preset.glm',            baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-plus', local: false },
  { key: 'moonshot', labelKey: 'cfg.preset.moonshot',     baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k', local: false },
  { key: 'doubao',   labelKey: 'cfg.preset.doubao',      baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', model: 'doubao-pro-4.0-241128', local: false },
  { key: 'custom',   labelKey: 'cfg.preset.custom',   baseUrl: '', model: '', local: false }
]

// 比对级忽略的常用文件类型（多选）。value 存点号前缀的小写扩展名，与后端 CompareOptions.ignoreExtensions 对齐。
// 运行时按需增删；勾选后对比会忽略这些类型的条目（日志、临时文件、锁文件、压缩包、图片等常见噪声）。
const IGNORE_EXT_PRESETS = [
  { value: '.log',        labelKey: 'cfg.ext.log',        hintKey: 'cfg.ext.log.hint' },
  { value: '.tmp',        labelKey: 'cfg.ext.tmp',        hintKey: 'cfg.ext.tmp.hint' },
  { value: '.swp',        labelKey: 'cfg.ext.swp',        hintKey: 'cfg.ext.swp.hint' },
  { value: '.bak',        labelKey: 'cfg.ext.bak',        hintKey: 'cfg.ext.bak.hint' },
  { value: '.class',      labelKey: 'cfg.ext.class',    hintKey: 'cfg.ext.class.hint' },
  { value: '.jar',        labelKey: 'cfg.ext.jar',        hintKey: 'cfg.ext.jar.hint' },
  { value: '.zip',        labelKey: 'cfg.ext.zip',        hintKey: 'cfg.ext.zip.hint' },
  { value: '.war',        labelKey: 'cfg.ext.war',        hintKey: 'cfg.ext.war.hint' },
  { value: '.png',        labelKey: 'cfg.ext.png',        hintKey: 'cfg.ext.png.hint' },
  { value: '.jpg',        labelKey: 'cfg.ext.jpg',  hintKey: 'cfg.ext.png.hint' },
  { value: '.gif',        labelKey: 'cfg.ext.gif',        hintKey: 'cfg.ext.gif.hint' },
  { value: '.svg',        labelKey: 'cfg.ext.svg',        hintKey: 'cfg.ext.svg.hint' },
  { value: '.ico',        labelKey: 'cfg.ext.ico',        hintKey: 'cfg.ext.ico.hint' },
  { value: '.db',         labelKey: 'cfg.ext.db',       hintKey: 'cfg.ext.db.hint' },
  { value: '.lock',       labelKey: 'cfg.ext.lock',         hintKey: 'cfg.ext.lock.hint' },
  { value: '.map',        labelKey: 'cfg.ext.map',    hintKey: 'cfg.ext.map.hint' },
  { value: '.min.js',     labelKey: 'cfg.ext.minjs',   hintKey: 'cfg.ext.minjs.hint' },
  { value: '.txt',        labelKey: 'cfg.ext.txt',      hintKey: 'cfg.ext.txt.hint' }
]

// 勾选响应：把「是否已勾选」的布尔映射转回扩展名数组（value 即点号扩展名，直接存储）。
function midToggle(ev, id) {
  const checked = !!ev && !!ev.target && ev.target.checked
  const cur = Array.isArray(form.ignoreExtensions) ? form.ignoreExtensions.slice() : []
  if (!checked) form.ignoreExtensions = cur.filter(v => v !== id)
  else if (!cur.includes(id)) form.ignoreExtensions = [...cur, id]
}

// 自定义后缀：输入框内容（如 ".MF" / "properties"，允许带或不带点，允许逗号分隔多个）
const customExt = ref('')
// 归一化单个输入为点号小写扩展名；非法（空/含路径分隔符/端点）返回 null
function normalizeExt(raw) {
  let v = String(raw || '').trim()
  if (!v) return null
  const segs = v.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean)
  const out = []
  for (let s of segs) {
    if (!s.startsWith('.')) s = '.' + s
    if (s === '.') continue
    if (s.includes('/') || s.includes('\\')) continue
    out.push(s.toLowerCase())
  }
  return out
}
// 添加自定义后缀到忽略列表（立即生效，保存时随 form 一并提交）
function addCustomExt() {
  const list = normalizeExt(customExt.value)
  if (!list || !list.length) { customExt.value = ''; return }
  const cur = Array.isArray(form.ignoreExtensions) ? form.ignoreExtensions.slice() : []
  for (const v of list) { if (!cur.includes(v)) cur.push(v) }
  form.ignoreExtensions = cur
  customExt.value = ''
}
// 从忽略列表移除某扩展名
function removeCustomExt(v) {
  if (!Array.isArray(form.ignoreExtensions)) return
  form.ignoreExtensions = form.ignoreExtensions.filter(x => x !== v)
}

const cfgTab = ref('ai')
const form = reactive({})
const showKey = ref(false)
const testing = ref(false)

// API Key 状态回显（T01567）：后端 GET 一直带 hasApiKey，但前端取了不用，
// 于是空输入框无法区分「密钥已持久化/后端仍持有」与「真的没配」，用户以为持久化失败。
const serverHasKey = computed(() => !!(state.config && state.config.hasApiKey))
const apiKeyState = computed(() => {
  if (form.aiApiKey) return ''
  if (!serverHasKey.value) return t('cfg.apiKey.state.none')
  return form.persistApiKey ? t('cfg.apiKey.state.persisted') : t('cfg.apiKey.state.memOnly')
})
const apiKeyPlaceholder = computed(() =>
  serverHasKey.value && !form.aiApiKey ? t('cfg.apiKey.covered.placeholder') : t('cfg.apiKey.placeholder'))

// ---- 密钥清除的不可逆性守卫（T01615）----
// ServerConfig.toProperties() 全量重建 properties，仅在 persistApiKey && 密钥非空时写 aiApiKey 行，
// 所以「取消勾选记住 + 保存」= 删除磁盘密钥；而默认安全模式 GET 从不回显明文，删完既看不见也找不回。
// 这里把该隐式语义改为显式：保存前二次确认 + 提供「清除已保存密钥」入口 + 状态行长驻提示。
const savedKeyOnDisk = computed(() => !!(state.config && state.config.hasApiKey && state.config.persistApiKey === true))
const explicitClear = ref(false)
const clearKeyPending = ref(false)
const wipeRisk = computed(() => form.persistApiKey === false && (savedKeyOnDisk.value || explicitClear.value))
// 绿条 toast 会被后续提示覆盖，故同一后果在状态行长驻一份（空框 + 勾选记住 = 本次什么都不写）
const apiKeyNoWriteHint = computed(() => {
  if (wipeRisk.value) return t('cfg.apiKey.state.willWipe')
  if (!form.aiApiKey && form.persistApiKey === true && !serverHasKey.value) return t('cfg.apiKey.warn.emptyPersist')
  return ''
})
watch(() => form.persistApiKey, (v) => { if (v !== false) { clearKeyPending.value = false; explicitClear.value = false } })

function onClearKey() {
  form.aiApiKey = ''
  form.persistApiKey = false
  explicitClear.value = true
  clearKeyPending.value = true
}
function cancelClearKey() {
  clearKeyPending.value = false
  explicitClear.value = false
  form.persistApiKey = true
}

let prevProviderKey = null
watch(() => props.visible, (v) => {
  if (v) {
    // 打开时以「已落盘配置」为基底；若存在未保存草稿（上次未保存就关闭），用草稿覆盖，
    // 恢复未提交的输入（断点续录）。保存成功后 clearDraft，避免过期草稿误当未保存内容恢复。
    Object.assign(form, JSON.parse(JSON.stringify(state.config || {})))
    const draft = loadDraft()
    // 空密钥草稿不得覆盖后端已持久化/已回显的 Key：历史草稿把 aiApiKey 存成 ''，
    // 直接覆盖会让「已落盘的密钥」在输入框里显示为空（T01567 主诉）。
    if (draft && draft.aiApiKey === '' && form.aiApiKey) delete draft.aiApiKey
    if (draft) Object.assign(form, draft)
    savedSnapshot = snapForm(form) // 记录本次打开的"初始已保存态"，供 close 判定是否需要写草稿
    clearKeyPending.value = false
    explicitClear.value = false
    prevProviderKey = form.aiProvider // 记录当前厂商，供首次切换时判断「是否未改过默认值」
    // 打开配置中心即查询上下文索引状态（读缓存秒回；让用户一眼看到「是否已生效」）
    if (form.projectContextEnabled && form.projectContextDir) loadContextStatus()
    else state.aiContext = { loading: false, data: null, error: '' }
    // 关闭行为存在主进程，与后端配置无关：每次打开都重新读取，避免展示过期的内存副本
    closeMsg.value = ''
    loadCloseBehavior()
  }
})

// ---- 项目级上下文状态（配置中心可视化：加载中/已加载/失败 + 项目清单 + 重新扫描） ----
const ctxLoading = computed(() => state.aiContext.loading)
const ctxError = computed(() => state.aiContext.error)
const ctxData = computed(() => state.aiContext.data)
function fmtTime(ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
async function onRefreshContext() {
  await loadContextStatus(true)
}

function close() {
  // 关闭时若存在「未保存修改」（当前表单快照 != 初始已保存态），写入草稿供下次断点续录；
  // 完全未改则清除旧草稿，避免无意义的残留占位。
  const cur = snapForm(form)
  const unchanged = savedSnapshot && JSON.stringify(snappedWithDefaults(cur)) === JSON.stringify(snappedWithDefaults(savedSnapshot))
  if (unchanged) clearDraft()
  else saveDraft(form)
  emit('close')
}
// 把快照与 defaultConfig 合并后再比，规避「缺省的未改动字段」造成误判为有修改。
function snappedWithDefaults(snap) {
  const base = JSON.parse(JSON.stringify(defaultConfig() || {}))
  return Object.assign(base, snap || {})
}

// 切换厂商时，若当前 Base URL 仍等于「上一厂商的预设默认值」或为空（即用户未手动改过），
// 则自动填充新厂商的 baseUrl/model；用户已自定义则保留其填写（不覆盖）。
// 用上一厂商的预设 URL 作基准（而非模块级 prevBaseUrl），使「打开后第一次切换」也能正确填充。
function onProviderChange() {
  const newP = PRESETS.find(x => x.key === form.aiProvider)
  if (!newP || newP.key === 'custom') { prevProviderKey = form.aiProvider; return }
  const oldP = PRESETS.find(x => x.key === prevProviderKey)
  const untouched = !form.aiBaseUrl || (oldP && form.aiBaseUrl === oldP.baseUrl)
  if (untouched) {
    form.aiBaseUrl = newP.baseUrl
    form.aiModel = newP.model
  }
  prevProviderKey = newP.key
}

const canPick = computed(() => isElectron())
const pickTitle = computed(() => canPick.value
  ? t('cfg.pick.title')
  : t('cfg.pick.browser'))

async function pickContextDir() {
  const p = await pickPath({ directory: true })
  if (p) form.projectContextDir = p
}

// ---- 关闭窗口行为（桌面壳主进程持有的 close-behavior.json，不进后端配置） ----
// 与 form 分开：切换即落盘，不走「保存」按钮，避免用户改完不点保存以为已生效。
const desktopShell = computed(() => isElectron())
const closeBridge = computed(() => desktopShell.value && typeof window.bempdiff.getCloseBehavior === 'function')
const closeBehavior = reactive({ close_action: 'minimize_to_tray', remember_choice: false })
const closeBusy = ref(false)
const closeMsg = ref('')
async function loadCloseBehavior() {
  if (!closeBridge.value) return
  try {
    const c = await window.bempdiff.getCloseBehavior()
    if (c && c.ok !== false) {
      closeBehavior.close_action = c.close_action
      closeBehavior.remember_choice = c.remember_choice === true
    }
  } catch (_) { /* 读失败保持默认值展示，不阻断配置中心 */ }
}
async function onCloseBehaviorChange() {
  if (!closeBridge.value) return
  closeBusy.value = true
  closeMsg.value = ''
  try {
    const r = await window.bempdiff.setCloseBehavior({
      close_action: closeBehavior.close_action,
      remember_choice: !!closeBehavior.remember_choice
    })
    if (r && r.ok) {
      closeBehavior.close_action = r.close_action
      closeBehavior.remember_choice = r.remember_choice === true
      closeMsg.value = t('cfg.closeBehavior.saved')
    } else {
      closeMsg.value = ''
      toast('danger', t('cfg.closeBehavior.saveFail'))
    }
  } catch (e) {
    closeMsg.value = ''
    toast('danger', t('cfg.closeBehavior.saveFail'))
  } finally {
    closeBusy.value = false
  }
}

// 按当前 API Base URL + Key 拉取可用模型列表，写入 state.aiModels 供下方 datalist 补全。
const fetchingModels = ref(false)
async function onFetchModels() {
  fetchingModels.value = true
  await fetchModels({
    provider: form.aiProvider, baseUrl: form.aiBaseUrl, apiKey: form.aiApiKey,
    httpProxy: form.httpProxy, httpsProxy: form.httpsProxy,
    blockPrivateEndpoints: !!form.blockPrivateEndpoints
  })
  fetchingModels.value = false
}

async function onTest() {
  testing.value = true
  await testConnection({
    provider: form.aiProvider, baseUrl: form.aiBaseUrl, apiKey: form.aiApiKey,
    model: form.aiModel, httpProxy: form.httpProxy, httpsProxy: form.httpsProxy,
    blockPrivateEndpoints: !!form.blockPrivateEndpoints
  })
  testing.value = false
}

// 保存：仅持久化配置，不关闭窗口（用户可能要切换到别的 Tab 继续填其他信息）。
// 关闭窗口由 footer 的「关闭」按钮负责（emit close）。store.saveConfig 已弹「配置已保存」toast。
async function onSave() {
  const out = JSON.parse(JSON.stringify(form))
  if (out.topK !== undefined && out.topK !== null && out.topK !== '') out.topK = Number(out.topK)
  if (out.stageBTopK !== undefined && out.stageBTopK !== null && out.stageBTopK !== '') out.stageBTopK = Number(out.stageBTopK)
  if (out.stageATopK !== undefined && out.stageATopK !== null && out.stageATopK !== '') out.stageATopK = Number(out.stageATopK)
  if (out.stageAFileSampleLines !== undefined && out.stageAFileSampleLines !== null && out.stageAFileSampleLines !== '') out.stageAFileSampleLines = Number(out.stageAFileSampleLines)
  if (out.maxPromptTokens !== undefined && out.maxPromptTokens !== null && out.maxPromptTokens !== '') out.maxPromptTokens = Number(out.maxPromptTokens)
  if (out.maxOutputTokens !== undefined && out.maxOutputTokens !== null && out.maxOutputTokens !== '') out.maxOutputTokens = Number(out.maxOutputTokens)
  if (out.costGateWarnTokens !== undefined && out.costGateWarnTokens !== null && out.costGateWarnTokens !== '') out.costGateWarnTokens = Number(out.costGateWarnTokens)
  // 勾了「记住」但框内密钥为空、且后端也没有密钥：本次 PUT 不会写入任何密钥，
  // 不能让「配置已保存」的绿条误导用户以为密钥已记住（T01567）。
  const emptyPersist = out.persistApiKey === true && !out.aiApiKey && !state.config?.hasApiKey
  // 本次保存会删除后端持有的密钥且无法找回 → 先要求二次确认（T01615）
  if (wipeRisk.value && !clearKeyPending.value) {
    clearKeyPending.value = true
    toast('warning', t('cfg.apiKey.state.willWipe'))
    return
  }
  await saveConfig(out)
  clearKeyPending.value = false
  explicitClear.value = false
  if (emptyPersist) toast('warning', t('cfg.apiKey.warn.emptyPersist'))
  // 保存成功：清除草稿（这些字段已落盘，无需再续录），并刷新"已保存态"快照，
  // 使随后 close() 能正确判定"本次已保存、无未保存修改"。
  clearDraft()
  savedSnapshot = snapForm(form)
  // 保存后立即重扫上下文目录（新目录/刚开启都立即生效并展示加载态）
  if (out.projectContextEnabled && out.projectContextDir) await loadContextStatus(true)
}

// 手动清理临时文件：调后端 /api/admin/cleanup-temp，释放解压残留，避免磁盘爆满。
const cleaning = ref(false)
const cleanupMsg = ref('')
async function onCleanupTemp() {
  cleaning.value = true
  cleanupMsg.value = ''
  try {
    const r = await api.cleanupTemp()
    const mb = (r.freedBytes / (1024 * 1024)).toFixed(2)
    cleanupMsg.value = t('cfg.cleanup.done', { f: r.files, d: r.dirs, mb })
    toast('success', t('cfg.cleanup.toast', { mb }))
  } catch (e) {
    const m = (e && e.message) || String(e)
    cleanupMsg.value = t('cfg.cleanup.fail', { msg: m })
    toast('danger', t('cfg.cleanup.toastFail', { msg: m }))
  } finally {
    cleaning.value = false
  }
}

// FR-011 一键导出脱敏诊断包：fetch+blob 而非原生跳转——包体 <5MB 内存无忧，
// 换取失败时的明确错误提示（原生 <a> 下载失败只有浏览器静默角标，用户会以为点了没反应）
const diagBusy = ref(false)
const diagMsg = ref('')
const diagOk = ref(false)
async function onDiagExport(e) {
  e.preventDefault() // 拦截原生下载改走 fetch：失败可提示、成功可报体积
  if (diagBusy.value) return
  diagBusy.value = true
  diagMsg.value = ''
  try {
    const res = await fetch('/api/admin/diag-bundle')
    if (!res.ok) {
      let msg = `${res.status} ${res.statusText}`
      try { const j = await res.json(); msg = j.error || msg } catch (_) { /* 非 JSON 错误体 */ }
      throw new Error(msg)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `bempdiff-diag-${new Date().toISOString().slice(0, 10)}.zip`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    diagOk.value = true
    diagMsg.value = t('cfg.diag.done', { mb: (blob.size / 1048576).toFixed(2) })
    toast('success', t('cfg.diag.toast'))
  } catch (err) {
    diagOk.value = false
    const m = (err && err.message) || String(err)
    diagMsg.value = t('cfg.diag.fail', { msg: m })
    toast('danger', t('cfg.diag.toastFail', { msg: m }))
  } finally {
    diagBusy.value = false
  }
}

// ---- 配置迁移：导出 / 导入整套配置，用于重装、换机/换服务器时整体迁移 ----
const migrateMsg = ref('')
const importFile = ref(null)
const isExporting = ref(false)
const isImporting = ref(false)
// 导出：把已落盘配置脱敏后序列化为 JSON 文件下载。
// 脱敏规则与持久化策略一致：未勾选 persistApiKey 时不导出明文 apiKey，
// 避免密钥随迁移文件散落到机器之外（仅导出会失去该字段，导入时用本地持久化的 Key 兜底）。
function onExportConfig() {
  const base = JSON.parse(JSON.stringify(state.config || {}))
  const out = { app: 'bempdiff', kind: 'config', version: 1, exportedAt: new Date().toISOString(), config: { ...base } }
  if (out.config.persistApiKey !== true) out.config.aiApiKey = ''
  try {
    const blob = new Blob([JSON.stringify(out, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `bempdiff-config-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    migrateMsg.value = t('cfg.migrate.exported')
    toast('success', t('cfg.migrate.exportToast'))
  } catch (e) {
    migrateMsg.value = t('cfg.migrate.exportFail', { msg: (e && e.message) || e })
    toast('danger', t('cfg.migrate.exportToastFail'))
  }
}
// 选择导入文件 → 校验结构 → 合并到 state.config 并持久化。
function pickImportFile(ev) {
  const f = ev && ev.target && ev.target.files && ev.target.files[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = async () => {
    try {
      const obj = JSON.parse(String(reader.result || ''))
      if (!obj || obj.app !== 'bempdiff' || obj.kind !== 'config' || !obj.config || typeof obj.config !== 'object') {
        throw new Error(t('cfg.migrate.invalidFile'))
      }
      const merged = Object.assign({}, state.config, obj.config)
      isImporting.value = true
      await saveConfig(merged)
      Object.assign(form, JSON.parse(JSON.stringify(merged)))
      migrateMsg.value = t('cfg.migrate.imported')
      toast('success', t('cfg.migrate.importToast'))
    } catch (e) {
      migrateMsg.value = t('cfg.migrate.importFail', { msg: (e && e.message) || e })
      toast('danger', t('cfg.migrate.importToastFail', { msg: (e && e.message) || e }))
    } finally {
      isImporting.value = false
      if (importFile.value) importFile.value.value = ''
    }
  }
  reader.onerror = () => { migrateMsg.value = t('cfg.migrate.readFail'); toast('danger', t('cfg.migrate.readToastFail')) }
  reader.readAsText(f)
}
</script>

<template>
  <div class="modal-backdrop" v-if="visible" @click.self="close">
    <div class="modal-dialog modal-lg modal-dialog-scrollable">
      <div class="modal-content">
        <div class="modal-header py-2 px-4">
          <h6 class="modal-title mb-0"><i class="bi bi-sliders"></i>{{ t('cfg.title') }}<small class="fw-normal text-secondary ms-2" style="font-size:.75rem">{{ t('cfg.subtitle') }}</small>
          </h6>
          <button type="button"  class="btn-close" :aria-label="t('common.close')" @click="close"></button>
        </div>

        <div class="alert alert-warning d-flex gap-2 align-items-start mb-2 py-2" role="alert" style="font-size:.8rem">
          <i class="bi bi-shield-lock fs-6"></i>
          <div>{{ t('cfg.compliance') }}</div>
        </div>

        <div class="modal-body">
          <ul class="nav nav-tabs mb-3">
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='ai'}" @click="cfgTab='ai'">{{ t('cfg.tab.ai') }}</button></li>
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='parse'}" @click="cfgTab='parse'">{{ t('cfg.tab.parse') }}</button></li>
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='filter'}" @click="cfgTab='filter'">{{ t('cfg.tab.filter') }}</button></li>
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='ui'}" @click="cfgTab='ui'">{{ t('cfg.tab.ui') }}</button></li>
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='help'}" @click="cfgTab='help'">{{ t('cfg.tab.help') }}</button></li>
            <li class="nav-item"><button class="nav-link py-1" :class="{active: cfgTab==='about'}" @click="cfgTab='about'">{{ t('cfg.tab.about') }}</button></li>
          </ul>

          <!-- AI 服务 -->
          <div v-show="cfgTab==='ai'">
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.provider.hint')">{{ t('cfg.provider') }}</label>
              <div class="col-sm-9">
                <select class="form-select form-select-sm" v-model="form.aiProvider" @change="onProviderChange" :aria-label="t('cfg.provider.hint')" :title="t('cfg.provider.hint')">
                  <option v-for="p in PRESETS" :key="p.key" :value="p.key">{{ t(p.labelKey) }}</option>
                </select>
                <div class="form-text mb-0" style="font-size:.72rem" v-if="PRESETS.find(x=>x.key===form.aiProvider)?.local">{{ t('cfg.localModelHint') }}</div>
              </div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.baseUrl.hint')">Base URL</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.aiBaseUrl" :aria-label="t('cfg.baseUrl.hint')" :title="t('cfg.baseUrl.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.apiKey.hint')">API Key</label>
              <div class="col-sm-9 input-group input-group-sm">
                <input class="form-control" :type="showKey ? 'text' : 'password'" v-model="form.aiApiKey" :placeholder="apiKeyPlaceholder" :aria-label="t('cfg.apiKey.hint')" :title="t('cfg.apiKey.hint')">
                <button class="btn btn-outline-secondary" type="button" @click="showKey = !showKey" :aria-label="t('cfg.showKey')" :title="t('cfg.showKey')">
                  <i class="bi" :class="showKey ? 'bi-eye-slash' : 'bi-eye'"></i>
                </button>
                <button class="btn btn-outline-secondary" type="button" :disabled="testing" @click="onTest" :aria-label="t('cfg.test.hint')" :title="t('cfg.test.hint')">
                  <i class="bi bi-plug"></i> {{ testing ? t('cfg.testing') : t('cfg.test') }}
                </button>
                <button v-if="serverHasKey" class="btn btn-outline-danger" type="button" data-testid="clear-key-btn"
                        @click="onClearKey" :aria-label="t('cfg.apiKey.clear')" :title="t('cfg.apiKey.clear.hint')">
                  <i class="bi bi-trash"></i> {{ t('cfg.apiKey.clear') }}
                </button>
              </div>
            </div>
            <div v-if="apiKeyState" class="row g-2 mb-1">
              <div class="col-sm-9 offset-sm-3">
                <small class="text-secondary" data-testid="apikey-state">{{ apiKeyState }}</small>
              </div>
            </div>
            <div v-if="apiKeyNoWriteHint" class="row g-2 mb-2">
              <div class="col-sm-9 offset-sm-3">
                <small class="text-warning" data-testid="apikey-nowrite">{{ apiKeyNoWriteHint }}</small>
              </div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.model.hint')">{{ t('cfg.model') }}</label>
              <div class="col-sm-9">
                <div class="input-group input-group-sm">
                  <input class="form-control" list="aiModelList" v-model="form.aiModel" :placeholder="t('cfg.model.placeholder')" :aria-label="t('cfg.model.hint')" :title="t('cfg.model.hint')">
                  <datalist id="aiModelList">
                    <option v-for="m in state.aiModels" :key="m" :value="m"></option>
                  </datalist>
                  <button class="btn btn-outline-secondary" type="button" :disabled="fetchingModels" @click="onFetchModels" :aria-label="t('cfg.fetchModels.hint')" :title="t('cfg.fetchModels.hint')">
                    <i class="bi" :class="fetchingModels ? 'bi-arrow-repeat' : 'bi-list-ul'"></i> {{ fetchingModels ? t('cfg.fetching') : t('cfg.fetchModels') }}
                  </button>
                </div>
                <div class="form-text mb-0" style="font-size:.72rem" v-if="state.aiModels.length">{{ t('cfg.modelsFetched', { n: state.aiModels.length }) }}</div>
              </div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.httpProxy.hint')">{{ t('cfg.httpProxy') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.httpProxy" :placeholder="t('cfg.httpProxy.placeholder')" :aria-label="t('cfg.httpProxy.hint')" :title="t('cfg.httpProxy.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.httpsProxy.hint')">{{ t('cfg.httpsProxy') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.httpsProxy" :placeholder="t('common.optional')" :aria-label="t('cfg.httpsProxy.hint')" :title="t('cfg.httpsProxy.hint')"></div>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="checkbox" id="cfgBlock" v-model="form.blockPrivateEndpoints" :aria-label="t('cfg.ssrf.hint')" :title="t('cfg.ssrf.hint')">
              <label class="form-check-label" for="cfgBlock" :title="t('cfg.ssrf.hint')">{{ t('cfg.ssrf') }}</label>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="checkbox" id="cfgAiEnabled" v-model="form.aiEnabled" :aria-label="t('cfg.aiEnabled.hint')" :title="t('cfg.aiEnabled.hint')">
              <label class="form-check-label" for="cfgAiEnabled" :title="t('cfg.aiEnabled.hint')">{{ t('cfg.aiEnabled') }}</label>
            </div>
          </div>

          <!-- 解析与导出 -->
          <div v-show="cfgTab==='parse'">
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.internalPrefixes.hint')">{{ t('cfg.internalPrefixes') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.internalPrefixes" :placeholder="t('cfg.internalPrefixes.placeholder')" :aria-label="t('cfg.internalPrefixes.hint')" :title="t('cfg.internalPrefixes.hint')"></div>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgExpand" v-model="form.expandAll" :aria-label="t('cfg.expandAll.hint')" :title="t('cfg.expandAll.hint')">
              <label class="form-check-label" for="cfgExpand" :title="t('cfg.expandAll.hint')">{{ t('cfg.expandAll') }}</label>
            </div>
            <!-- 自动逐层解包：WAR/ZIP/JAR 嵌套归档多线程物理解包，比对完成后自动展开；AI/导出/统计覆盖嵌套子文件 -->
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.unpack.hint')">{{ t('cfg.unpack') }}</label>
              <div class="col-sm-9">
                <div class="form-check form-check-inline mb-0">
                  <input class="form-check-input" type="checkbox" id="cfgUnpack" v-model="form.unpackNested" :aria-label="t('cfg.unpack.hint2')" :title="t('cfg.unpack.hint2')">
                  <label class="form-check-label" for="cfgUnpack" :title="t('cfg.unpack.hint3')">{{ t('cfg.unpack.on') }}</label>
                </div>
              </div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.unpackThreads.hint')">{{ t('cfg.unpackThreads') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="1" max="16" v-model.number="form.unpackThreads" :aria-label="t('cfg.unpackThreads.hint')" :title="t('cfg.unpackThreads.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.unpackMaxDepth.hint')">{{ t('cfg.unpackMaxDepth') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="1" max="12" v-model.number="form.unpackMaxDepth" :aria-label="t('cfg.unpackMaxDepth.hint')" :title="t('cfg.unpackMaxDepth.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.topK.hint')">{{ t('cfg.topK') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" v-model.number="form.topK" :aria-label="t('cfg.topK.hint')" :title="t('cfg.topK.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.stageBTopK.hint')">{{ t('cfg.stageBTopK') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" v-model.number="form.stageBTopK" :aria-label="t('cfg.stageBTopK.hint')" :title="t('cfg.stageBTopK.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.stageATopK.hint')">{{ t('cfg.stageATopK') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="1" v-model.number="form.stageATopK" :aria-label="t('cfg.stageATopK.hint')" :title="t('cfg.stageATopK.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.stageALines.hint')">{{ t('cfg.stageALines') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="1" v-model.number="form.stageAFileSampleLines" :aria-label="t('cfg.stageALines.hint')" :title="t('cfg.stageALines.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.maxPromptTokens.hint')">{{ t('cfg.maxPromptTokens') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="1000" v-model.number="form.maxPromptTokens" :aria-label="t('cfg.maxPromptTokens.hint')" :title="t('cfg.maxPromptTokens.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.maxOutputTokens.hint')">{{ t('cfg.maxOutputTokens') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" min="0" v-model.number="form.maxOutputTokens" :aria-label="t('cfg.maxOutputTokens.hint')" :title="t('cfg.maxOutputTokens.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.costGate.hint')">{{ t('cfg.costGate') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" type="number" v-model.number="form.costGateWarnTokens" :aria-label="t('cfg.costGate.hint')" :title="t('cfg.costGate.hint')"></div>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.cfrJar.hint')">{{ t('cfg.cfrJar') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.cfrJar" :placeholder="t('cfg.cfrJar.placeholder')" :aria-label="t('cfg.cfrJar.hint')" :title="t('cfg.cfrJar.hint')"></div>
            </div>
            <div class="mb-1 mt-2" style="font-size:.78rem;color:var(--bs-secondary-color)">{{ t('cfg.ignoreSection') }}</div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgIgWs" v-model="form.ignoreWhitespace" :aria-label="t('cfg.ignoreWs.hint')" :title="t('cfg.ignoreWs.hint')">
              <label class="form-check-label" for="cfgIgWs" :title="t('cfg.ignoreWs.hint')">{{ t('cfg.ignoreWs') }}</label>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgIgCmt" v-model="form.ignoreComments" :title="t('cfg.ignoreCmt.hint')">
              <label class="form-check-label" for="cfgIgCmt" :title="t('cfg.ignoreCmt.hint')">{{ t('cfg.ignoreCmt') }}</label>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.ignoreRegex.hint')">{{ t('cfg.ignoreRegex') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.ignoreRegex" :placeholder="t('cfg.ignoreRegex.placeholder')" :aria-label="t('cfg.ignoreRegex.hintShort')" :title="t('cfg.ignoreRegex.hintShort')"></div>
            </div>

            <!-- 比对过滤：多选忽略常见文件类型。勾选后对比会在解析收集阶段跳过这些类型的条目（不进差异树、不参与统计）。 -->
            <div class="mb-1 mt-2" style="font-size:.78rem;color:var(--bs-secondary-color)">{{ t('cfg.filterSection') }}</div>
            <div class="d-flex flex-wrap gap-2 mb-2">
              <div v-for="ie in IGNORE_EXT_PRESETS" :key="ie.value" class="form-check form-check-inline mb-1" :title="t(ie.hintKey)">
                <input class="form-check-input" type="checkbox" :id="'iex' + ie.value.replace(/[^a-zA-Z0-9]/g, '')"
                       :checked="Array.isArray(form.ignoreExtensions) && form.ignoreExtensions.includes(ie.value)"
                       @change="midToggle($event, ie.value)"
                       :title="t(ie.hintKey)">
                <label class="form-check-label" :for="'iex' + ie.value.replace(/[^a-zA-Z0-9]/g, '')" :title="t(ie.hintKey)">{{ t(ie.labelKey) }}</label>
              </div>
            </div>
            <div v-if="!Array.isArray(form.ignoreExtensions) || !form.ignoreExtensions.includes('.min.js')" class="form-text" style="font-size:.72rem">{{ t('cfg.ignoreTip') }}</div>

            <!-- 自定义后缀：除预置勾选项外，可添加任意文件后缀（如 .MF、.properties），
                 保存时与预置项一并提交给后端解析阶段过滤。 -->
            <div class="row g-2 align-items-center mt-1 mb-1">
              <label class="col-sm-3 col-form-label col-form-label-sm text-nowrap" :title="t('cfg.customExt.hint')">{{ t('cfg.customExt') }}</label>
              <div class="col-sm-9">
                <div class="input-group input-group-sm">
                  <input class="form-control" v-model="customExt" :placeholder="t('cfg.customExt.placeholder')" @keydown.enter.prevent="addCustomExt"
                         :aria-label="t('cfg.customExt.hintShort')" :title="t('cfg.customExt.hintShort')">
                  <button class="btn btn-outline-secondary" type="button" @click="addCustomExt" :aria-label="t('cfg.customExt.add')" :title="t('cfg.customExt.add')">{{ t('common.add') }}</button>
                </div>
                <div v-if="Array.isArray(form.ignoreExtensions) && form.ignoreExtensions.length" class="mt-1 d-flex flex-wrap gap-1">
                  <span v-for="ie in form.ignoreExtensions" :key="ie"
                        class="badge rounded-pill text-bg-secondary cursor-pointer d-inline-flex align-items-center gap-1"
                        style="font-size:.7rem" role="button" @click="removeCustomExt(ie)"
                        :title="t('cfg.removeExtTip', { ext: ie })">
                    {{ ie }} <i class="bi bi-x-lg" style="font-size:.6rem"></i>
                  </span>
                </div>
                <div class="form-text mb-0" style="font-size:.72rem">{{ t('cfg.ignoredCount', { n: Array.isArray(form.ignoreExtensions) ? form.ignoreExtensions.length : 0 }) }}</div>
              </div>
            </div>
          </div>

          <!-- 差异树过滤 -->
          <div v-show="cfgTab==='filter'">
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.searchKey.hint')">{{ t('cfg.searchKey') }}</label>
              <div class="col-sm-9"><input class="form-control form-control-sm" v-model="form.filterSearch" :placeholder="t('cfg.searchKey.placeholder')" :aria-label="t('cfg.searchKey.hint')" :title="t('cfg.searchKey.hint')"></div>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgRegex" v-model="form.filterRegex" :aria-label="t('cfg.regex.hint')" :title="t('cfg.regex.hint')">
              <label class="form-check-label" for="cfgRegex" :title="t('cfg.regex.hint')">{{ t('cfg.regex') }}</label>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgAutoAi" v-model="form.autoAiOnCompare" :aria-label="t('cfg.autoAi.hint')" :title="t('cfg.autoAi.hint')">
              <label class="form-check-label" for="cfgAutoAi" :title="t('cfg.autoAi.hint')">{{ t('cfg.autoAi') }}</label>
            </div>
            <div class="mb-1" style="font-size:.78rem;color:var(--bs-secondary-color)">{{ t('cfg.showSection') }}</div>
            <div class="d-flex flex-wrap gap-3">
              <div class="form-check"><input class="form-check-input" type="checkbox" id="fsM" v-model="form.filterShowModified" :aria-label="t('cfg.showModified.hint')" :title="t('cfg.showModified.hint')"><label class="form-check-label" for="fsM" :title="t('cfg.showModified.hint')">{{ t('cfg.show.modified') }}</label></div>
              <div class="form-check"><input class="form-check-input" type="checkbox" id="fsA" v-model="form.filterShowAdded" :aria-label="t('cfg.showAdded.hint')" :title="t('cfg.showAdded.hint')"><label class="form-check-label" for="fsA" :title="t('cfg.showAdded.hint')">{{ t('cfg.show.added') }}</label></div>
              <div class="form-check"><input class="form-check-input" type="checkbox" id="fsD" v-model="form.filterShowDeleted" :aria-label="t('cfg.showDeleted.hint')" :title="t('cfg.showDeleted.hint')"><label class="form-check-label" for="fsD" :title="t('cfg.showDeleted.hint')">{{ t('cfg.show.deleted') }}</label></div>
              <div class="form-check"><input class="form-check-input" type="checkbox" id="fsU" v-model="form.filterShowUnchanged" :aria-label="t('cfg.showUnchanged.hint')" :title="t('cfg.showUnchanged.hint')"><label class="form-check-label" for="fsU" :title="t('cfg.showUnchanged.hint')">{{ t('cfg.show.unchanged') }}</label></div>
            </div>
            <div class="form-text" style="font-size:.72rem">{{ t('cfg.showTip') }}</div>
          </div>

          <!-- 界面与高级 -->
          <div v-show="cfgTab==='ui'">
            <!-- 关闭窗口行为（仅桌面壳生效）：设置页改动立即落盘并更新主进程内存，
                 下一次点关闭即按新配置执行；未勾选「记住」时关闭仍会弹一次选择框。 -->
            <div class="card card-body py-2 mb-2 close-behavior-card" style="font-size:.76rem">
              <div class="d-flex align-items-center gap-2 flex-wrap mb-2">
                <span class="fw-semibold text-nowrap"><i class="bi bi-x-square"></i>{{ t('cfg.closeBehavior') }}</span>
                <span class="text-secondary">{{ t('cfg.closeBehavior.desc') }}</span>
              </div>
              <div v-if="!desktopShell" class="text-secondary" style="font-size:.72rem">{{ t('cfg.closeBehavior.unavailable') }}</div>
              <template v-else>
                <div class="d-flex flex-wrap gap-3 mb-1">
                  <div class="form-check">
                    <input class="form-check-input" type="radio" name="cfgCloseAction" id="cfgCloseTray" value="minimize_to_tray"
                           v-model="closeBehavior.close_action" :disabled="closeBusy" @change="onCloseBehaviorChange"
                           :aria-label="t('cfg.closeBehavior.tray')" :title="t('cfg.closeBehavior.tray')">
                    <label class="form-check-label" for="cfgCloseTray" :title="t('cfg.closeBehavior.tray')">{{ t('cfg.closeBehavior.tray') }}</label>
                  </div>
                  <div class="form-check">
                    <input class="form-check-input" type="radio" name="cfgCloseAction" id="cfgCloseExit" value="exit_app"
                           v-model="closeBehavior.close_action" :disabled="closeBusy" @change="onCloseBehaviorChange"
                           :aria-label="t('cfg.closeBehavior.exit')" :title="t('cfg.closeBehavior.exit')">
                    <label class="form-check-label" for="cfgCloseExit" :title="t('cfg.closeBehavior.exit')">{{ t('cfg.closeBehavior.exit') }}</label>
                  </div>
                </div>
                <div class="form-check mb-1">
                  <input class="form-check-input" type="checkbox" id="cfgCloseRemember" v-model="closeBehavior.remember_choice"
                         :disabled="closeBusy" @change="onCloseBehaviorChange"
                         :aria-label="t('cfg.closeBehavior.rememberHint')" :title="t('cfg.closeBehavior.rememberHint')">
                  <label class="form-check-label" for="cfgCloseRemember" :title="t('cfg.closeBehavior.rememberHint')">{{ t('cfg.closeBehavior.remember') }}</label>
                </div>
                <div class="form-text" style="font-size:.72rem">{{ closeBehavior.remember_choice ? t('cfg.closeBehavior.hintOn') : t('cfg.closeBehavior.hintOff') }}</div>
                <div v-if="closeMsg" class="mt-1 text-success" style="font-size:.72rem"><i class="bi bi-check-circle"></i> {{ closeMsg }}</div>
              </template>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgPersist" v-model="form.persistApiKey" :aria-label="t('cfg.persist.hint')" :title="t('cfg.persist.hint')">
              <label class="form-check-label" for="cfgPersist" :title="t('cfg.persist.hint')">{{ t('cfg.persist') }}</label>
            </div>
            <div class="form-check form-check-inline mb-2">
              <input class="form-check-input" type="checkbox" id="cfgPc" v-model="form.projectContextEnabled" :aria-label="t('cfg.ctx.hint')" :title="t('cfg.ctx.hint')">
              <label class="form-check-label" for="cfgPc" :title="t('cfg.ctx.hint')">{{ t('cfg.ctx') }}</label>
            </div>
            <div class="row g-2 align-items-center mb-2">
              <label class="col-sm-3 col-form-label col-form-label-sm" :title="t('cfg.ctxDir.hint')">上下文目录</label>
              <div class="col-sm-9">
                <div class="input-group input-group-sm">
                  <input class="form-control" v-model="form.projectContextDir" :placeholder="t('cfg.ctxDir.placeholder')" :aria-label="t('cfg.ctxDir.hint')" :title="t('cfg.ctxDir.hint')">
                  <button class="btn btn-outline-secondary" type="button" @click="pickContextDir" :disabled="!canPick" :aria-label="pickTitle" :title="pickTitle">
                    <i class="bi bi-folder2-open"></i>
                  </button>
                </div>
              </div>
            </div>
            <!-- 上下文加载状态（借鉴 IDE「索引加载」交互：加载中/完成态徽标 + 项目统计 + 手动刷新） -->
            <div v-if="form.projectContextEnabled" class="card card-body py-2 mb-2 context-status" style="font-size:.76rem">
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <template v-if="ctxLoading">
                  <span class="spinner-border spinner-border-sm text-primary" role="status"></span>
                  <span class="text-secondary">{{ t('cfg.ctx.scanning') }}</span>
                </template>
                <template v-else-if="ctxError">
                  <i class="bi bi-exclamation-triangle-fill text-danger"></i>
                  <span class="text-danger">{{ t('cfg.ctx.failed', { err: ctxError }) }}</span>
                </template>
                <template v-else-if="ctxData && ctxData.ok">
                  <i class="bi bi-check-circle-fill text-success"></i>
                  <span class="fw-semibold text-success">{{ t('cfg.ctx.loaded') }}</span>
                  <span class="text-secondary">
                    {{ t('cfg.ctx.stats', { p: ctxData.projectCount, j: ctxData.javaFileCount ?? 0 }) }}
                    <span v-if="ctxData.fromCache" class="text-secondary"><i class="bi bi-database"></i>{{ t('cfg.ctx.cached') }}</span>
                    <span v-else class="text-secondary"><i class="bi bi-arrow-repeat"></i>{{ t('cfg.ctx.rescanned') }}</span>
                    {{ t('cfg.ctx.updatedAt', { time: fmtTime(ctxData.scannedAt) }) }}
                  </span>
                </template>
                <template v-else>
                  <i class="bi bi-dash-circle text-secondary"></i>
                  <span class="text-secondary">{{ t('cfg.ctx.notLoaded') }}</span>
                </template>
                <button class="btn btn-outline-primary btn-sm ms-auto" type="button" :disabled="ctxLoading" @click="onRefreshContext" :aria-label="t('cfg.ctx.refresh.hint')" :title="t('cfg.ctx.refresh.hint')">
                  <i class="bi bi-arrow-clockwise"></i>{{ t('cfg.ctx.refresh') }}</button>
              </div>
              <div v-if="ctxData && ctxData.projects && ctxData.projects.length" class="mt-2">
                <div class="text-secondary mb-1"><i class="bi bi-diagram-3"></i>{{ t('cfg.ctx.projects') }}</div>
                <ul class="list-unstyled mb-0 ps-2 context-project-list" style="max-height:150px;overflow:auto">
                  <li v-for="p in ctxData.projects" :key="p.relPath" class="d-flex gap-2 align-items-baseline text-nowrap" style="font-size:.74rem">
                    <code class="text-body">{{ p.relPath }}</code>
                    <span class="text-secondary">{{ p.truncated
                      ? t('cfg.ctx.projectLineTruncated', { bs: p.buildSystem, m: p.moduleCount, d: p.depCount, f: p.fileCount, a: p.analyzedFiles ?? 0 })
                      : t('cfg.ctx.projectLine', { bs: p.buildSystem, m: p.moduleCount, d: p.depCount, f: p.fileCount }) }}</span>
                  </li>
                </ul>
                <div class="form-text mt-1" style="font-size:.68rem">{{ t('cfg.ctx.note') }}</div>
              </div>
            </div>
            <!-- 配置迁移：导出/导入整套配置，用于重装、换机/换服务器时整体迁移 -->
            <div class="card card-body py-2 mb-2 migrate-card" style="font-size:.76rem">
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <span class="fw-semibold text-nowrap"><i class="bi bi-arrow-left-right"></i>{{ t('cfg.migrate') }}</span>
                <span class="text-secondary">{{ t('cfg.migrate.desc') }}</span>
                <div class="d-flex align-items-center gap-1 ms-auto">
                  <button class="btn btn-outline-secondary btn-sm" type="button" @click="onExportConfig" :disabled="isExporting"
                          :aria-label="t('cfg.migrate.exportHint')" :title="t('cfg.migrate.exportHint')">
                    <i class="bi bi-download"></i>{{ t('cfg.migrate.export') }}</button>
                  <label class="btn btn-outline-secondary btn-sm mb-0" :class="{disabled: isImporting}" :title="t('cfg.migrate.importHint')">
                    <i class="bi bi-upload"></i>{{ t('cfg.migrate.import') }}<input ref="importFile" type="file" accept=".json,application/json" class="d-none" @change="pickImportFile" :disabled="isImporting">
                  </label>
                </div>
              </div>
              <div v-if="migrateMsg" class="mt-1" style="font-size:.72rem"><i class="bi bi-chevron-right"></i> {{ migrateMsg }}</div>
            </div>
            <!-- 手动清理临时文件：解压残留导致的磁盘爆满时按需回收（不中断进行中任务） -->
            <div class="card card-body py-2 mb-0" style="font-size:.76rem">
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <span class="fw-semibold text-nowrap"><i class="bi bi-broom"></i>{{ t('cfg.cleanup') }}</span>
                <span class="text-secondary">{{ t('cfg.cleanup.desc') }}</span>
                <button class="btn btn-outline-danger btn-sm ms-auto" type="button" :disabled="cleaning"
                        @click="onCleanupTemp"
                        :aria-label="t('cfg.cleanup.hint')" :title="t('cfg.cleanup.hint')">
                  <i class="bi" :class="cleaning ? 'bi-arrow-repeat' : 'bi-broom'"></i> {{ cleaning ? t('cfg.cleanup.doing') : t('cfg.cleanup.run') }}
                </button>
              </div>
              <div v-if="cleanupMsg" class="mt-2 text-success" style="font-size:.72rem"><i class="bi bi-check-circle"></i> {{ cleanupMsg }}</div>
            </div>
            <!-- FR-011 一键导出脱敏诊断包：出错反馈时替代口头描述环境（包内已脱敏 API Key 与用户路径） -->
            <div class="card card-body py-2 mb-0" style="font-size:.76rem">
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <span class="fw-semibold text-nowrap"><i class="bi bi-clipboard-pulse"></i>{{ t('cfg.diag') }}</span>
                <span class="text-secondary">{{ t('cfg.diag.desc') }}</span>
                <a class="btn btn-outline-primary btn-sm ms-auto" href="/api/admin/diag-bundle" download
                   :aria-label="t('cfg.diag.hint')" :title="t('cfg.diag.hint')" :class="{ disabled: diagBusy }"
                   @click="onDiagExport">
                  <i class="bi bi-download"></i> {{ diagBusy ? t('cfg.diag.doing') : t('cfg.diag.run') }}
                </a>
              </div>
              <div v-if="diagMsg" class="mt-2" :class="diagOk ? 'text-success' : 'text-danger'" style="font-size:.72rem">
                <i class="bi" :class="diagOk ? 'bi-check-circle' : 'bi-exclamation-triangle'"></i> {{ diagMsg }}
              </div>
            </div>
          </div>

          <!-- 帮助文档 -->
          <div v-show="cfgTab==='help'" class="help-tab">
            <HelpDoc />
          </div>

          <!-- 关于：版本更新检查 + 手动更新指引 -->
          <div v-show="cfgTab==='about'" class="about-tab">
            <About />
          </div>
        </div>

        <div v-if="clearKeyPending" class="alert alert-warning mx-4 mt-3 mb-0 py-2" role="alert" data-testid="clear-key-confirm">
          <div class="d-flex align-items-center justify-content-between gap-3">
            <small>{{ t('cfg.apiKey.confirm.wipe') }}</small>
            <span class="d-flex gap-2 flex-shrink-0">
              <button class="btn btn-danger btn-sm" data-testid="clear-key-ok" @click="onSave">{{ t('cfg.apiKey.confirm.ok') }}</button>
              <button class="btn btn-outline-secondary btn-sm" data-testid="clear-key-cancel" @click="cancelClearKey">{{ t('common.cancel') }}</button>
            </span>
          </div>
        </div>

        <div class="modal-footer py-2 px-4 d-flex align-items-center gap-2">
          <span class="text-secondary me-auto" style="font-size:.75rem">{{ t('cfg.footerHint') }}</span>
          <button class="btn btn-outline-secondary btn-sm" @click="close"><i class="bi bi-x-lg"></i>{{ t('common.close') }}</button>
          <button class="btn btn-primary btn-sm" @click="onSave"><i class="bi bi-check2"></i>{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: flex-start; justify-content: center; z-index: 1500; padding-top: 4vh; padding-bottom: 4vh;
}
/* 自适应高度：内容再多也不会超出视口被截断。
   flex 列布局让 header/footer 固定、body 内部滚动（下拉条只作用于 body 内容区）。
   max-height 用 viewport 高度减上下留白，短屏/小窗口下 body 自动压缩出滚动条。 */
.modal-content {
  width: 100%;
  max-height: calc(100vh - 8vh);   /* 兜底：老引擎无 dvh 时不支持则用 vh */
  max-height: calc(100dvh - 8vh);
  display: flex; flex-direction: column;
  background-color: var(--bs-body-bg); color: var(--bs-body-color);
}
.modal-body { padding: 1rem 1.5rem; flex: 1 1 auto; min-height: 0; overflow-y: auto; }
</style>
