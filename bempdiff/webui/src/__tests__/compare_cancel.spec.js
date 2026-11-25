// H-06 回归：取消新比对后，上一个作业的审阅/洞察/分类状态必须恢复，且 runCompare 返回 false。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { state, runCompare } from '../store'
import { emptyReviewDoc, REVIEW_STATUS } from '../lib/review'

vi.mock('../api/client', () => ({
  api: {
    putReview: vi.fn(),
    compare: vi.fn(async () => ({ jobId: 'job-new', status: 'queued' })),
    jobStatus: vi.fn(async () => ({ status: 'CANCELLED' })),
    cancelJob: vi.fn(),
    getReview: vi.fn(),
    jobInsights: vi.fn()
  }
}))

import { api } from '../api/client'

function oldReview() {
  const doc = emptyReviewDoc('job-old')
  doc.user = 'tester'
  doc.files = { 'Old.java': { status: REVIEW_STATUS.REVIEWED, manualOverride: true, hunks: {}, notes: [], bookmarks: [] } }
  return doc
}

beforeEach(() => {
  state.job = { jobId: 'job-old', status: 'DONE', stats: { added: 1, deleted: 0, modified: 0, unchanged: 0 } }
  state.review = oldReview()
  state.reviewSaveState = 'saved'
  state.reviewSummary = null
  state.insights = { reviewSummary: { total: 1 } }
  state.aiClassify = { kept: { risk: 'LOW' } }
  state.excludedKeys = { 'Old.java': true }
  state.baseFolder = 'Old.java'
  state.hunkJump = { key: 'Old.java', hunkIndex: 0 }
  state.reviewAdvanceTick = 0
  state.activeCompareId = null
  state.busy = false
  state.error = null
  vi.clearAllMocks()
})

describe('H-06 取消比对恢复旧视图', () => {
  it('后端返回 CANCELLED：恢复旧审阅/洞察/分类，runCompare=false', async () => {
    const ok = await runCompare({ leftType: 'package', leftPath: 'a.war', rightPath: 'b.war', options: {} })
    expect(ok).toBe(false)
    expect(state.job.jobId).toBe('job-old')
    expect(state.review.jobId).toBe('job-old')
    expect(state.review.files['Old.java'].status).toBe(REVIEW_STATUS.REVIEWED)
    expect(state.insights.reviewSummary.total).toBe(1)
    expect(state.aiClassify).toEqual({ kept: { risk: 'LOW' } })
    expect(state.excludedKeys).toEqual({ 'Old.java': true })
    expect(state.baseFolder).toBe('Old.java')
    expect(state.busy).toBe(false)
    expect(api.compare).toHaveBeenCalledTimes(1)
    expect(api.jobStatus).toHaveBeenCalledTimes(1)
  })
})
