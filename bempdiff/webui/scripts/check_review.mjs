// T06218 审阅纯逻辑独立校验（绕开本机卡住的 Vitest，直接 Node 运行）。
// 覆盖：Hunk 聚合、手动覆盖、迁移、Hunk 推导、1000 文件摘要 <200ms。
import assert from 'node:assert/strict'
import {
  REVIEW_STATUS, aggregateFileStatus, deriveHunkStatuses, migrateLegacyReviews,
  normalizeReviewDoc, summarizeReview, updateReviewFile
} from '../src/lib/review.js'

const checks = []
function check(name, fn) {
  const t0 = performance.now()
  fn()
  checks.push({ name, ms: performance.now() - t0 })
  console.log(`✓ ${name} (${(performance.now() - t0).toFixed(1)}ms)`)
}

check('Hunk 聚合：部分→审阅中 / 全部→已审 / 需复核优先', () => {
  assert.equal(aggregateFileStatus({ hunkTotal: 3, hunks: { 0: 'reviewed' } }), REVIEW_STATUS.IN_PROGRESS)
  assert.equal(aggregateFileStatus({ hunkTotal: 2, hunks: { 0: 'reviewed', 1: 'reviewed' } }), REVIEW_STATUS.REVIEWED)
  assert.equal(aggregateFileStatus({ hunkTotal: 2, hunks: { 0: 'reviewed', 1: 'needs_review' } }), REVIEW_STATUS.NEEDS_REVIEW)
  assert.equal(aggregateFileStatus({ hunkTotal: 0, hunks: {} }), REVIEW_STATUS.UNREVIEWED)
})

check('AC-11 手动覆盖优先：Hunk 未全审也可已审，取消后回到聚合态', () => {
  const manual = normalizeReviewDoc({ files: { 'a/Foo.class': { hunkTotal: 5, hunks: {}, manualOverride: true, status: 'reviewed' } } }, 'job-1')
  assert.equal(manual.files['a/Foo.class'].status, REVIEW_STATUS.REVIEWED)
  assert.equal(manual.files['a/Foo.class'].manualOverride, true)
  const auto = updateReviewFile(manual, 'a/Foo.class', { manualOverride: false, status: 'unreviewed' })
  assert.equal(auto.files['a/Foo.class'].status, REVIEW_STATUS.UNREVIEWED)
})

check('旧 localStorage 迁移：行标记 → 文件 in_progress + lines', () => {
  const doc = migrateLegacyReviews({ 'a/Foo.class': [1, 2, 2, 3] }, 'job-legacy', 'local')
  assert.ok(doc)
  const f = doc.files['a/Foo.class']
  assert.deepEqual(f.lines, [1, 2, 3])
  assert.equal(f.status, REVIEW_STATUS.IN_PROGRESS)
  assert.equal(f.migratedFrom, 'localStorage')
  assert.equal(normalizeReviewDoc(doc, 'job-legacy').files['a/Foo.class'].status, REVIEW_STATUS.IN_PROGRESS)
})

check('Hunk 推导：整段行标记才 reviewed', () => {
  const hunks = [{ id: '0', rows: [3, 4, 5] }, { id: '1', rows: [8] }]
  assert.deepEqual(deriveHunkStatuses(hunks, [3, 4]), {})
  assert.deepEqual(deriveHunkStatuses(hunks, [3, 4, 5, 8]), { 0: 'reviewed', 1: 'reviewed' })
})

check('摘要四态与进度', () => {
  const doc = normalizeReviewDoc({ files: {
    a: { hunkTotal: 2, hunks: { 0: 'reviewed', 1: 'reviewed' }, status: 'reviewed' },
    b: { hunkTotal: 2, hunks: { 0: 'reviewed', 1: 'unreviewed' }, status: 'in_progress' },
    c: { hunkTotal: 2, hunks: { 0: 'needs_review' }, status: 'needs_review' },
    d: { hunkTotal: 1, hunks: {}, status: 'unreviewed' }
  } }, 'job-sum')
  const s = summarizeReview(doc.files)
  assert.equal(s.totalFiles, 4)
  assert.equal(s.reviewedFiles, 1)
  assert.equal(s.inProgressFiles, 1)
  assert.equal(s.needsReviewFiles, 1)
  assert.equal(s.unreviewedFiles, 1)
  assert.equal(s.filePercent, 25)
})

check('1000 文件摘要 <200ms（含 10000 Hunk）', () => {
  const files = {}
  for (let i = 0; i < 1000; i++) {
    const hunks = {}
    for (let h = 0; h < 10; h++) hunks[h] = h < (i % 11) ? 'reviewed' : 'unreviewed'
    files['dir/file-' + i + '.class'] = { hunkTotal: 10, hunks, status: i % 2 ? 'unreviewed' : 'reviewed' }
  }
  for (let w = 0; w < 5; w++) summarizeReview(files)
  let best = Infinity
  for (let r = 0; r < 5; r++) {
    const t0 = performance.now()
    const s = summarizeReview(files)
    best = Math.min(best, performance.now() - t0)
    assert.equal(s.totalFiles, 1000)
    assert.equal(s.totalHunks, 10000)
  }
  assert.ok(best < 200, `实测 ${best.toFixed(2)}ms`)
  console.log(`  best=${best.toFixed(2)}ms`)
})

console.log(`\n${checks.length} 项审阅逻辑校验全部通过`)
