// T06220 审阅树纯逻辑：统计、组合筛选、下一未审选择。
// 不依赖 Vue/store，便于独立 Node 校验 AC-07 的组合条件。
import { REVIEW_STATUS, normalizeReviewStatus } from './review.js'

export const REVIEW_FILTER_ALL = 'all'
export const REVIEW_FILTER_STATUSES = [
  REVIEW_FILTER_ALL,
  REVIEW_STATUS.UNREVIEWED,
  REVIEW_STATUS.IN_PROGRESS,
  REVIEW_STATUS.NEEDS_REVIEW,
  REVIEW_STATUS.REVIEWED
]

export const DEFAULT_REVIEW_TREE_FILTER = {
  status: REVIEW_FILTER_ALL,
  highRisk: false,
  onlyFormatted: false,
  onlyBookmarks: false
}

export function normalizeReviewTreeFilter(raw) {
  const src = raw && typeof raw === 'object' ? raw : {}
  const status = REVIEW_FILTER_STATUSES.includes(src.status) ? src.status : REVIEW_FILTER_ALL
  return {
    status,
    highRisk: src.highRisk === true,
    onlyFormatted: src.onlyFormatted === true,
    onlyBookmarks: src.onlyBookmarks === true
  }
}

/** 目录节点不是审阅对象；FOLDER 及无 key 的占位排除。 */
export function isReviewableFile(n) {
  return !!(n && n.key && n.fileClass !== 'FOLDER')
}

export function reviewStatusFor(review, key) {
  const f = review && review.files ? review.files[key] : null
  return f ? normalizeReviewStatus(f.status) : REVIEW_STATUS.UNREVIEWED
}

function countEntries(v) {
  if (Array.isArray(v)) return v.length
  if (typeof v === 'number') return Math.max(0, Math.floor(v))
  if (typeof v === 'string') return v.trim() ? 1 : 0
  if (v && typeof v === 'object') return Object.keys(v).length
  return 0
}

/** 兼容 T06221 可能落盘的 notes/noteCount、bookmarks/bookmark 字段。 */
export function countFileNotes(f) {
  if (!f || typeof f !== 'object') return 0
  return Math.max(countEntries(f.notes), countEntries(f.noteCount), countEntries(f.note))
}

export function countFileBookmarks(f) {
  if (!f || typeof f !== 'object') return 0
  return Math.max(
    f.bookmarked === true ? 1 : 0,
    countEntries(f.bookmarks),
    countEntries(f.bookmarkCount),
    countEntries(f.bookmark)
  )
}

/** 头部统计：仅统计可审阅文件，避免把目录/层分组头计入总数。 */
export function reviewTreeStats(tree, review, aiClassify) {
  const ai = aiClassify || {}
  const out = {
    totalFiles: 0,
    reviewed: 0,
    unreviewed: 0,
    inProgress: 0,
    needsReview: 0,
    highRisk: 0,
    classified: 0,
    notes: 0,
    bookmarks: 0,
    filePercent: 0
  }
  for (const n of tree || []) {
    if (!isReviewableFile(n)) continue
    out.totalFiles++
    const st = reviewStatusFor(review, n.key)
    if (st === REVIEW_STATUS.REVIEWED) out.reviewed++
    else if (st === REVIEW_STATUS.IN_PROGRESS) out.inProgress++
    else if (st === REVIEW_STATUS.NEEDS_REVIEW) out.needsReview++
    else out.unreviewed++
    const c = ai[n.key]
    if (c && c.risk) {
      out.classified++
      if (c.risk === 'HIGH') out.highRisk++
    }
    const f = review && review.files ? review.files[n.key] : null
    out.notes += countFileNotes(f)
    out.bookmarks += countFileBookmarks(f)
  }
  out.filePercent = out.totalFiles ? Math.round(out.reviewed * 100 / out.totalFiles) : 0
  return out
}

/** 审阅 chip + 高风险 + 仅格式变化；与原状态/搜索/风险过滤取交集。 */
export function passesReviewTreeFilter(n, filter, review, aiClassify) {
  const f = normalizeReviewTreeFilter(filter)
  if (f.status !== REVIEW_FILTER_ALL && reviewStatusFor(review, n.key) !== f.status) return false
  if (f.highRisk) {
    const c = aiClassify ? aiClassify[n.key] : null
    if (!c || c.risk !== 'HIGH') return false
  }
  if (f.onlyFormatted && n.formattedOnly !== true) return false
  if (f.onlyBookmarks) {
    const file = review && review.files ? review.files[n.key] : null
    if (countFileBookmarks(file) <= 0) return false
  }
  return true
}

/**
 * 下一未审：在传入节点顺序中，从 currentKey 后找第一个 unreviewed，末尾回到开头。
 * basePredicate 传入“除审阅状态外的其他过滤”，确保组合筛选（未审+高风险）仍生效。
 */
export function nextUnreviewedNode(nodes, currentKey, review, basePredicate = () => true) {
  const arr = (nodes || []).filter(n => isReviewableFile(n) && basePredicate(n))
  if (!arr.length) return null
  const idx = arr.findIndex(n => n.key === currentKey)
  const startAt = idx >= 0 ? idx + 1 : 0
  for (let i = 0; i < arr.length; i++) {
    const n = arr[(startAt + i) % arr.length]
    if (n.key !== currentKey && reviewStatusFor(review, n.key) === REVIEW_STATUS.UNREVIEWED) return n
  }
  return null
}
