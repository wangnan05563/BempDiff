// T06221 备注 / 书签 / 证据纯逻辑：不依赖 Vue/store，便于独立 Node 校验。
//
// 审阅文档扩展字段（与 Java ReviewStatusCalculator 的「保留未知字段」兼容）：
//   files[key].notes     = [{ id, scope:'file'|'hunk'|'line', hunkId, line,
//                             oldLine, newLine, change, hunkHeader, before, after,
//                             text, tag, user, createdAt, updatedAt }]
//   files[key].bookmarks = [{ id, scope, hunkId, line, oldLine, newLine, change,
//                             hunkHeader, before, after, targetKey, user, createdAt }]
// line=对齐后的行下标（与现有 files[key].lines 同口径），oldLine/newLine 为展示用快照。

export const REVIEW_NOTE_MAX = 2000
export const REVIEW_NOTE_TAGS = ['risk', 'confirm', 'compat', 'config', 'other']
export const REVIEW_NOTE_TAG_META = {
  risk: { label: '风险', labelKey: 'rv.note.tag.risk', icon: 'bi-shield-exclamation', cls: 'danger' },
  confirm: { label: '待确认', labelKey: 'rv.note.tag.confirm', icon: 'bi-question-circle', cls: 'warning' },
  compat: { label: '兼容', labelKey: 'rv.note.tag.compat', icon: 'bi-arrow-left-right', cls: 'primary' },
  config: { label: '配置', labelKey: 'rv.note.tag.config', icon: 'bi-sliders', cls: 'info' },
  other: { label: '其他', labelKey: 'rv.note.tag.other', icon: 'bi-tag', cls: 'secondary' }
}
export const REVIEW_SCOPE_LABEL = { file: '文件', hunk: 'Hunk', line: '行' }
export const REVIEW_STATUS_LABEL = {
  unreviewed: '未审',
  in_progress: '审阅中',
  reviewed: '已审',
  needs_review: '需复核'
}
export const REVIEW_CHANGE_LABEL = { rep: '修改', add: '新增', del: '删除', file: '文件级', hunk: 'Hunk' }

const SCOPES = ['file', 'hunk', 'line']

function str(v) {
  return v == null ? '' : String(v)
}

function capText(v, max = 4000) {
  const s = str(v).replace(/\r\n?/g, '\n')
  return s.length > max ? s.slice(0, max) : s
}

export function normalizeNoteText(v) {
  return str(v).replace(/\r\n?/g, '\n').trim()
}

export function noteTextLength(v) {
  return [...normalizeNoteText(v)].length
}

/** 空/超长校验；errorKey 由 UI 翻译，纯函数不依赖 i18n。 */
export function validateReviewNote(v) {
  const text = normalizeNoteText(v)
  const length = [...text].length
  if (!text) return { ok: false, text, length, errorKey: 'rv.note.empty' }
  if (length > REVIEW_NOTE_MAX) return { ok: false, text, length, errorKey: 'rv.note.tooLong' }
  return { ok: true, text, length, errorKey: '' }
}

export function normalizeNoteScope(v) {
  return SCOPES.includes(v) ? v : 'file'
}

export function normalizeNoteTag(v) {
  return REVIEW_NOTE_TAGS.includes(v) ? v : 'other'
}

export function noteTargetKey(scope, { hunkId = null, line = null } = {}) {
  const s = normalizeNoteScope(scope)
  if (s === 'file') return 'f'
  if (s === 'hunk') return 'h:' + str(hunkId)
  return 'l:' + (Number.isInteger(Number(line)) ? Number(line) : str(line))
}

function normalizeLineNo(v) {
  if (v == null || v === '') return ''
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? String(n) : str(v)
}

function normalizeRow(v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isInteger(n) && n >= 0 ? n : null
}

/** 单条备注规范化；空正文/非法结构返回 null（保证 UI 不展示空备注）。 */
export function normalizeReviewNote(raw) {
  if (!raw || typeof raw !== 'object') return null
  const text = normalizeNoteText(raw.text)
  if (!text || [...text].length > REVIEW_NOTE_MAX) return null
  const scope = normalizeNoteScope(raw.scope)
  const line = scope === 'line' ? normalizeRow(raw.line) : null
  const hunkId = scope === 'file' ? null : (raw.hunkId == null ? null : str(raw.hunkId))
  const targetKey = noteTargetKey(scope, { hunkId, line })
  const id = str(raw.id) || ('note-' + scope + '-' + targetKey + '-' + (raw.createdAt || text.slice(0, 12)))
  return {
    id,
    scope,
    hunkId,
    line,
    targetKey,
    oldLine: normalizeLineNo(raw.oldLine),
    newLine: normalizeLineNo(raw.newLine),
    change: str(raw.change) || (scope === 'file' ? 'file' : ''),
    hunkHeader: capText(raw.hunkHeader, 240),
    before: capText(raw.before),
    after: capText(raw.after),
    text,
    tag: normalizeNoteTag(raw.tag),
    user: capText(raw.user, 120),
    createdAt: str(raw.createdAt),
    updatedAt: str(raw.updatedAt) || str(raw.createdAt)
  }
}

/** 兼容数组/对象映射两种 notes 形态；丢弃空正文与重复 id。 */
export function normalizeFileNotes(value) {
  const arr = Array.isArray(value)
    ? value
    : (value && typeof value === 'object' ? Object.values(value) : [])
  const out = []
  const ids = new Set()
  for (const raw of arr) {
    const n = normalizeReviewNote(raw)
    if (!n || ids.has(n.id)) continue
    ids.add(n.id)
    out.push(n)
  }
  return out
}

/** 单条书签规范化；targetKey 缺失时按 scope/hunk/line 推导。 */
export function normalizeReviewBookmark(raw) {
  if (!raw || typeof raw !== 'object') return null
  const scope = normalizeNoteScope(raw.scope)
  const line = scope === 'line' ? normalizeRow(raw.line) : null
  const hunkId = scope === 'file' ? null : (raw.hunkId == null ? null : str(raw.hunkId))
  const targetKey = str(raw.targetKey) || noteTargetKey(scope, { hunkId, line })
  return {
    id: str(raw.id) || ('bm-' + targetKey),
    scope,
    hunkId,
    line,
    targetKey,
    oldLine: normalizeLineNo(raw.oldLine),
    newLine: normalizeLineNo(raw.newLine),
    change: str(raw.change) || (scope === 'file' ? 'file' : ''),
    hunkHeader: capText(raw.hunkHeader, 240),
    before: capText(raw.before),
    after: capText(raw.after),
    user: capText(raw.user, 120),
    createdAt: str(raw.createdAt)
  }
}

/** 兼容数组/对象映射；同一 targetKey 只保留第一条。 */
export function normalizeFileBookmarks(value) {
  const arr = Array.isArray(value)
    ? value
    : (value && typeof value === 'object' ? Object.values(value) : [])
  const out = []
  const keys = new Set()
  for (const raw of arr) {
    const b = normalizeReviewBookmark(raw)
    if (!b || keys.has(b.targetKey)) continue
    keys.add(b.targetKey)
    out.push(b)
  }
  return out
}

export function notesForTarget(file, target) {
  const key = target && target.targetKey
    ? target.targetKey
    : noteTargetKey(target && target.scope, target || {})
  return normalizeFileNotes(file && file.notes).filter(n => n.targetKey === key)
}

export function bookmarkForTarget(file, target) {
  const key = target && target.targetKey
    ? target.targetKey
    : noteTargetKey(target && target.scope, target || {})
  return normalizeFileBookmarks(file && file.bookmarks).find(b => b.targetKey === key) || null
}

/** 新增/替换备注（按 id），返回新数组，不修改入参。 */
export function upsertReviewNote(file, note) {
  const n = normalizeReviewNote(note)
  if (!n) return normalizeFileNotes(file && file.notes)
  const list = normalizeFileNotes(file && file.notes)
  const i = list.findIndex(x => x.id === n.id)
  if (i >= 0) list.splice(i, 1, n)
  else list.push(n)
  return list
}

export function removeReviewNote(file, id) {
  return normalizeFileNotes(file && file.notes).filter(n => n.id !== id)
}

/** 一键切换书签：已存在则删除，否则新增。返回 {bookmarks, active, bookmark}。 */
export function toggleReviewBookmark(file, target, user = '') {
  const list = normalizeFileBookmarks(file && file.bookmarks)
  const key = target && target.targetKey
    ? target.targetKey
    : noteTargetKey(target && target.scope, target || {})
  const i = list.findIndex(b => b.targetKey === key)
  if (i >= 0) {
    const bookmark = list[i]
    list.splice(i, 1)
    return { bookmarks: list, active: false, bookmark }
  }
  const bookmark = normalizeReviewBookmark({
    ...(target || {}),
    targetKey: key,
    user,
    createdAt: new Date().toISOString()
  })
  if (bookmark) list.push(bookmark)
  return { bookmarks: list, active: !!bookmark, bookmark }
}

/** 行内文本摘要：去换行/反引号，按字符数截断，供证据 single-line 字段使用。 */
export function summarizeInline(v, max = 160) {
  const s = str(v).replace(/\r\n?/g, ' ').replace(/\n/g, ' ').replace(/`/g, "'").replace(/\s+/g, ' ').trim()
  const chars = [...s]
  return chars.length > max ? chars.slice(0, max).join('') + '…' : s
}

/** 代码围栏长度：比正文最长反引号串多 1，最少 3，避免备注正文截断围栏。 */
export function codeFenceTicks(text) {
  const runs = str(text).match(/`+/g) || []
  const longest = runs.reduce((m, x) => Math.max(m, x.length), 0)
  return Math.max(3, longest + 1)
}

export function codeFenceFor(text, lang = 'text') {
  return '`'.repeat(codeFenceTicks(text)) + lang
}

function mdPath(path) {
  return '`' + summarizeInline(path, 300).replace(/`/g, "'") + '`'
}

function evidenceFields(target, opts) {
  const t = target || {}
  const scope = normalizeNoteScope(t.scope)
  const hunkHeader = summarizeInline(t.hunkHeader, 240)
  const hunkText = scope === 'file' ? '文件级' : (hunkHeader || ('#' + (t.hunkId == null ? '?' : t.hunkId)))
  const lineText = scope === 'line'
    ? ('旧 ' + (t.oldLine || '—') + ' / 新 ' + (t.newLine || '—'))
    : (scope === 'hunk' ? 'Hunk 范围' : '—')
  const changeText = REVIEW_CHANGE_LABEL[t.change] || (t.change ? summarizeInline(t.change, 40) : '—')
  const before = summarizeInline(t.before, 180)
  const after = summarizeInline(t.after, 180)
  const out = []
  out.push('**文件**：' + mdPath(t.path || opts.fileKey || ''))
  out.push('**Hunk**：`' + hunkText + '`')
  out.push('**行号**：' + lineText)
  out.push('**变更**：' + changeText)
  out.push('**变更前**：' + (before ? '`' + before + '`' : '—'))
  out.push('**变更后**：' + (after ? '`' + after + '`' : '—'))
  return out
}

function noteBlocks(notes, includeText) {
  const list = Array.isArray(notes) ? notes : []
  if (!list.length) return []
  const out = ['**备注**：']
  for (const n of list) {
    const tag = (REVIEW_NOTE_TAG_META[n.tag] || REVIEW_NOTE_TAG_META.other).label
    const meta = [tag, n.user || '本地审阅人', n.updatedAt || n.createdAt].filter(Boolean).join(' · ')
    out.push('- ' + meta)
    if (includeText) {
      const fence = codeFenceFor(n.text, 'text')
      out.push(fence)
      out.push(n.text)
      out.push('`'.repeat(codeFenceTicks(n.text)))
    } else {
      out.push('- 备注正文已排除')
    }
  }
  return out
}

/**
 * 复制证据 Markdown（AC-12）：文件路径 / Hunk 头 / 行号 / 变更前后摘要。
 * target 可为单个目标，也可传 { targets:[...] } 生成多行摘录。
 */
export function buildEvidenceMarkdown(fileKey, target, opts = {}) {
  const targets = Array.isArray(target && target.targets) ? target.targets : [target || {}]
  const includeNote = opts.includeNote !== false
  const notes = Array.isArray(opts.notes) ? opts.notes
    : (opts.note ? [opts.note] : [])
  const path = summarizeInline((targets[0] && targets[0].path) || fileKey || '', 300)
  const out = ['## 证据摘录：' + path, '']
  targets.forEach((t, i) => {
    if (targets.length > 1) out.push('### ' + (i + 1) + '. ' + (REVIEW_SCOPE_LABEL[normalizeNoteScope(t.scope)] || '行') + ' ' + (t.newLine || t.oldLine || ''))
    for (const f of evidenceFields(t, { fileKey })) out.push('- ' + f)
    if (targets.length === 1 && notes.length) {
      out.push('')
      out.push(...noteBlocks(notes, includeNote))
    }
    if (i < targets.length - 1) out.push('')
  })
  out.push('')
  out.push('> 由 BempDiff 生成；可粘贴到 Markdown 报告 / 工单。')
  return out.join('\n')
}

function countEntries(v) {
  if (Array.isArray(v)) return v.length
  if (v && typeof v === 'object') return Object.keys(v).length
  return 0
}

function reviewFileCounts(f) {
  return { notes: countEntries(normalizeFileNotes(f && f.notes)), bookmarks: countEntries(normalizeFileBookmarks(f && f.bookmarks)) }
}

/** 报告附录：仅在存在备注/书签时生成；excludeNotes=true 时正文完全省略。 */
export function buildReviewAppendix(review, opts = {}) {
  const files = (review && review.files) || {}
  const rows = []
  let totalNotes = 0
  let totalBookmarks = 0
  for (const [key, f] of Object.entries(files)) {
    const c = reviewFileCounts(f)
    if (!c.notes && !c.bookmarks) continue
    totalNotes += c.notes
    totalBookmarks += c.bookmarks
    rows.push({ key, file: f, ...c })
  }
  if (!rows.length) return ''
  rows.sort((a, b) => a.key.localeCompare(b.key))
  const excludeNotes = opts.excludeNotes === true
  const at = opts.generatedAt || new Date().toISOString()
  const out = ['', '## 证据与备注', '']
  out.push('- 备注：' + totalNotes + ' 条；书签：' + totalBookmarks + ' 个')
  out.push('- 审阅人：' + summarizeInline((review && review.user) || '本地审阅人', 120))
  out.push('- 生成时间：' + at)
  out.push('- 备注正文：' + (excludeNotes ? '已排除（仅保留计数与标签）' : '包含全文'))
  out.push('')
  out.push('| 文件 | 审阅状态 | 备注 | 书签 | 最后更新 |')
  out.push('|---|---|---|---|---|')
  for (const r of rows) {
    const f = r.file || {}
    out.push('| ' + mdPath(r.key) + ' | ' + (REVIEW_STATUS_LABEL[f.status] || '未审') + ' | ' + r.notes + ' | ' + r.bookmarks + ' | ' + summarizeInline(f.updatedAt || '', 40) + ' |')
  }
  for (const r of rows) {
    const f = r.file || {}
    out.push('')
    out.push('### ' + mdPath(r.key))
    out.push('')
    out.push('- 状态：' + (REVIEW_STATUS_LABEL[f.status] || '未审') + '；备注 ' + r.notes + ' 条；书签 ' + r.bookmarks + ' 个')
    const notes = normalizeFileNotes(f.notes)
    if (notes.length) {
      out.push('')
      out.push('#### 备注')
      for (const n of notes) {
        const tag = (REVIEW_NOTE_TAG_META[n.tag] || REVIEW_NOTE_TAG_META.other).label
        out.push('')
        out.push('- **' + tag + '** · ' + (REVIEW_SCOPE_LABEL[n.scope] || '文件') + ' · ' + summarizeInline(n.user || '', 80) + ' · ' + summarizeInline(n.updatedAt || n.createdAt, 40))
        out.push('- 证据：' + mdPath(r.key) + ' · `' + summarizeInline(n.hunkHeader || (n.scope === 'file' ? '文件级' : '#' + (n.hunkId || '?')), 240) + '` · 旧 ' + (n.oldLine || '—') + ' / 新 ' + (n.newLine || '—'))
        if (!excludeNotes) {
          const fence = codeFenceFor(n.text, 'text')
          out.push(fence)
          out.push(n.text)
          out.push('`'.repeat(codeFenceTicks(n.text)))
        } else {
          out.push('- 备注正文已排除')
        }
      }
    }
    const bookmarks = normalizeFileBookmarks(f.bookmarks)
    if (bookmarks.length) {
      out.push('')
      out.push('#### 书签')
      for (const b of bookmarks) {
        out.push('- ' + (REVIEW_SCOPE_LABEL[b.scope] || '文件') + ' · 旧 ' + (b.oldLine || '—') + ' / 新 ' + (b.newLine || '—') + ' · `' + summarizeInline(b.hunkHeader || (b.scope === 'file' ? '文件级' : '#' + (b.hunkId || '?')), 240) + '`')
      }
    }
  }
  out.push('')
  out.push('> 本附录由 BempDiff 生成，离线可读；备注正文' + (excludeNotes ? '已按导出选项排除。' : '默认包含，导出前请确认隐私范围。'))
  return out.join('\n')
}

/** 在报告正文末尾追加附录；已存在同名章节时避免重复追加。 */
export function appendReviewAppendix(markdown, review, opts = {}) {
  const md = str(markdown)
  if (!md) return md
  if (/\n##\s+证据与备注\s*\n/.test(md)) return md
  return md.replace(/\s+$/, '') + '\n' + buildReviewAppendix(review, opts)
}

/** 审阅 JSON 导出；excludeNotes 时删除备注正文，不改变服务端文档。 */
export function buildReviewJsonExport(review, opts = {}) {
  const src = review && typeof review === 'object' ? review : {}
  const out = JSON.parse(JSON.stringify(src))
  out.exportedAt = opts.generatedAt || new Date().toISOString()
  out.exportMeta = {
    noteTextExcluded: opts.excludeNotes === true,
    generator: 'BempDiff'
  }
  if (opts.excludeNotes === true && out.files && typeof out.files === 'object') {
    for (const f of Object.values(out.files)) {
      if (!f || !Array.isArray(f.notes)) continue
      for (const n of f.notes) {
        if (n && typeof n === 'object') {
          delete n.text
          n.textExcluded = true
        }
      }
    }
  }
  return out
}
