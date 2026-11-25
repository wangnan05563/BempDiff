// T06218 文件级审阅纯逻辑（与 Java ReviewStatusCalculator 同口径）。
// T06221 增加 notes/bookmarks 规范化；仍不碰网络与 localStorage，便于独立 Node 单测与复用。
import { normalizeFileBookmarks, normalizeFileNotes } from './review_notes.js'

export const REVIEW_SCHEMA_VERSION = '1.0'

export const REVIEW_STATUS = {
  UNREVIEWED: 'unreviewed',
  IN_PROGRESS: 'in_progress',
  REVIEWED: 'reviewed',
  NEEDS_REVIEW: 'needs_review'
}

export const REVIEW_STATUS_META = {
  unreviewed: { cls: 'text-bg-secondary', icon: 'bi-circle', labelKey: 'rv.status.unreviewed' },
  in_progress: { cls: 'text-bg-warning', icon: 'bi-circle-half', labelKey: 'rv.status.in_progress' },
  reviewed: { cls: 'text-bg-success', icon: 'bi-check-circle-fill', labelKey: 'rv.status.reviewed' },
  needs_review: { cls: 'text-bg-danger', icon: 'bi-exclamation-circle', labelKey: 'rv.status.needs_review' }
}

const STATUSES = Object.values(REVIEW_STATUS)

export function normalizeReviewStatus(s, fallback = REVIEW_STATUS.UNREVIEWED) {
  const v = String(s || '').trim().toLowerCase().replace(/-/g, '_')
  if (v === 'unreviewed' || v === 'not_reviewed') return REVIEW_STATUS.UNREVIEWED
  if (v === 'in_progress' || v === 'reviewing') return REVIEW_STATUS.IN_PROGRESS
  if (v === 'reviewed' || v === 'approved' || v === 'done') return REVIEW_STATUS.REVIEWED
  if (v === 'needs_review' || v === 'needs_recheck') return REVIEW_STATUS.NEEDS_REVIEW
  return fallback
}

export function emptyReviewDoc(jobId = '') {
  return {
    schemaVersion: REVIEW_SCHEMA_VERSION,
    jobId: jobId || null,
    user: '',
    updatedAt: null,
    files: {}
  }
}

export function reviewStatusMeta(status) {
  return REVIEW_STATUS_META[normalizeReviewStatus(status)] || REVIEW_STATUS_META.unreviewed
}

/**
 * 文件状态聚合（与后端一致）：
 * manualOverride=true 时保留手动态；任一 Hunk needs_review → needs_review；
 * 全部 Hunk 已审 → reviewed；部分 → in_progress；无 → unreviewed。
 */
export function aggregateFileStatus({ hunkTotal = 0, hunks = {}, manualOverride = false, status = REVIEW_STATUS.UNREVIEWED } = {}) {
  if (manualOverride) return normalizeReviewStatus(status, REVIEW_STATUS.UNREVIEWED)
  let reviewed = 0
  let needsReview = false
  for (const v of Object.values(hunks || {})) {
    const s = normalizeReviewStatus(v)
    if (s === REVIEW_STATUS.REVIEWED) reviewed++
    else if (s === REVIEW_STATUS.NEEDS_REVIEW) needsReview = true
  }
  if (needsReview) return REVIEW_STATUS.NEEDS_REVIEW
  if (Number(hunkTotal) <= 0) return REVIEW_STATUS.UNREVIEWED
  if (reviewed >= Number(hunkTotal)) return REVIEW_STATUS.REVIEWED
  if (reviewed > 0) return REVIEW_STATUS.IN_PROGRESS
  return REVIEW_STATUS.UNREVIEWED
}

export function normalizeReviewFile(raw = {}) {
  const src = raw && typeof raw === 'object' ? raw : {}
  const hunks = {}
  if (src.hunks && typeof src.hunks === 'object') {
    for (const [id, v] of Object.entries(src.hunks)) {
      if (String(id)) hunks[String(id)] = normalizeReviewStatus(v)
    }
  }
  let hunkTotal = Number.isFinite(Number(src.hunkTotal)) ? Math.max(0, Number(src.hunkTotal)) : Object.keys(hunks).length
  const lines = normalizeLines(src.lines)
  const manualOverride = !!src.manualOverride
  const status = normalizeReviewStatus(src.status, REVIEW_STATUS.REVIEWED)

  const file = {
    ...src,
    hunkTotal,
    hunks,
    lines,
    manualOverride,
    // T06221 扩展字段：保留未知字段的同时统一备注/书签结构，服务端 round-trip 不丢数据。
    notes: normalizeFileNotes(src.notes),
    bookmarks: normalizeFileBookmarks(src.bookmarks),
    status: aggregateFileStatus({ hunkTotal, hunks, manualOverride, status })
  }
  // 旧 localStorage 迁移来的行标记没有 Hunk 总数：不能判「全审」，但显然已开始审。
  if (!manualOverride && hunkTotal <= 0 && lines.length > 0 && file.status === REVIEW_STATUS.UNREVIEWED) {
    file.status = REVIEW_STATUS.IN_PROGRESS
  }
  if (!manualOverride && file.status === REVIEW_STATUS.REVIEWED && hunkTotal <= 0 && lines.length === 0) {
    file.status = REVIEW_STATUS.UNREVIEWED
  }
  return file
}

export function normalizeReviewDoc(raw, jobId = '') {
  const src = raw && typeof raw === 'object' ? raw : {}
  const id = jobId || src.jobId || ''
  const files = {}
  if (src.files && typeof src.files === 'object') {
    for (const [key, file] of Object.entries(src.files)) {
      if (key) files[key] = normalizeReviewFile(file)
    }
  }
  // 接口派生字段（summary/exists/ok）不进入本地文档，避免下次 PUT 原样回传。
  const clean = { ...src }
  delete clean.summary
  delete clean.exists
  delete clean.corruptBackup
  delete clean.ok
  return {
    ...clean,
    schemaVersion: REVIEW_SCHEMA_VERSION,
    jobId: id || null,
    user: src.user || '',
    updatedAt: src.updatedAt || null,
    files
  }
}

/** 汇总四态文件数 + Hunk 进度 + 百分比；1000 文件 O(n)。 */
export function summarizeReview(files = {}) {
  const out = {
    totalFiles: 0,
    reviewedFiles: 0,
    inProgressFiles: 0,
    unreviewedFiles: 0,
    needsReviewFiles: 0,
    manualFiles: 0,
    totalHunks: 0,
    reviewedHunks: 0,
    needsReviewHunks: 0,
    filePercent: 0,
    hunkPercent: 0
  }
  for (const f of Object.values(files || {})) {
    if (!f || typeof f !== 'object') continue
    out.totalFiles++
    const st = normalizeReviewStatus(f.status)
    if (st === REVIEW_STATUS.REVIEWED) out.reviewedFiles++
    else if (st === REVIEW_STATUS.IN_PROGRESS) out.inProgressFiles++
    else if (st === REVIEW_STATUS.NEEDS_REVIEW) out.needsReviewFiles++
    else out.unreviewedFiles++
    if (f.manualOverride) out.manualFiles++
    // M-15：文件级手动“已审”覆盖 Hunk 口径，避免“文件已审、Hunk 仍需复核”的自相矛盾汇总。
    const manualReviewed = !!f.manualOverride && st === REVIEW_STATUS.REVIEWED

    let total = Math.max(0, Number(f.hunkTotal) || 0)
    let reviewed = 0
    for (const v of Object.values(f.hunks || {})) {
      const s = normalizeReviewStatus(v)
      if (s === REVIEW_STATUS.REVIEWED) reviewed++
      else if (s === REVIEW_STATUS.NEEDS_REVIEW && !manualReviewed) out.needsReviewHunks++
    }
    if (manualReviewed) reviewed = total
    if (reviewed > total) total = reviewed
    out.totalHunks += total
    out.reviewedHunks += reviewed
  }
  out.filePercent = percent(out.reviewedFiles, out.totalFiles)
  out.hunkPercent = percent(out.reviewedHunks, out.totalHunks)
  return out
}

export function updateReviewFile(doc, key, patch) {
  const base = (doc && doc.jobId) ? doc : emptyReviewDoc(doc && doc.jobId)
  if (!key) return base
  const prev = base.files[key] || {}
  const file = normalizeReviewFile({ ...prev, ...(patch || {}) })
  return {
    ...base,
    updatedAt: new Date().toISOString(),
    files: { ...base.files, [key]: file }
  }
}

/** 旧 localStorage {fileKey:[rowIndex,...]} → 审阅文档；没有可迁移条目时返回 null。 */
export function migrateLegacyReviews(legacyJob, jobId, user = '') {
  if (!legacyJob || typeof legacyJob !== 'object') return null
  const files = {}
  for (const [key, arr] of Object.entries(legacyJob)) {
    if (!key || !Array.isArray(arr) || !arr.length) continue
    const lines = normalizeLines(arr)
    if (!lines.length) continue
    files[key] = normalizeReviewFile({
      hunkTotal: 0,
      hunks: {},
      lines,
      manualOverride: false,
      status: REVIEW_STATUS.IN_PROGRESS,
      migratedFrom: 'localStorage'
    })
  }
  if (!Object.keys(files).length) return null
  return {
    schemaVersion: REVIEW_SCHEMA_VERSION,
    jobId,
    user: user || '',
    updatedAt: new Date().toISOString(),
    files
  }
}

/** 从行级标记推导 Hunk 状态：一个 Hunk 的所有行都被标记才算 reviewed。 */
export function deriveHunkStatuses(hunks = [], lines = []) {
  const marked = new Set(Array.isArray(lines) ? lines : [...lines])
  const out = {}
  for (const h of hunks) {
    if (!h || !h.rows || !h.rows.length) continue
    if (h.rows.every(ri => marked.has(ri))) out[String(h.id)] = REVIEW_STATUS.REVIEWED
  }
  return out
}

function normalizeLines(raw) {
  if (!Array.isArray(raw)) return []
  const out = []
  const seen = new Set()
  for (const v of raw) {
    // null/undefined/boolean/空串经 Number() 会变成 0，必须先按原始类型拒绝，避免伪造第 0 行。
    if (typeof v !== 'number' && typeof v !== 'string') continue
    if (typeof v === 'string' && v.trim() === '') continue
    const n = Number(v)
    if (Number.isInteger(n) && n >= 0 && n <= 2_000_000 && !seen.has(n)) {
      seen.add(n)
      out.push(n)
    }
  }
  return out
}

function percent(part, total) {
  if (!total) return 0
  return Math.round((part * 100) / total)
}

export { STATUSES as REVIEW_STATUSES }
