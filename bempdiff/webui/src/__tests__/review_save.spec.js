// H-08 回归：审阅保存串行化 + 旧响应不得覆盖新编辑。
// 场景：PUT#1 在途时用户又改了状态；PUT#1 返回旧文档时只能落盘，不能把本地新状态回滚。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { state, flushReview, updateReviewFileState } from '../store'
import { emptyReviewDoc, REVIEW_STATUS } from '../lib/review'

let resolveFirst
vi.mock('../api/client', () => ({
  api: {
    putReview: vi.fn(async (jobId, doc) => ({
      ...doc,
      jobId,
      updatedAt: new Date().toISOString(),
      files: doc.files || {}
    }))
  }
}))

import { api } from '../api/client'

beforeEach(() => {
  state.review = emptyReviewDoc('job-h08')
  state.review.user = 'tester'
  state.review.files = { 'A.java': { status: REVIEW_STATUS.REVIEWED, manualOverride: true, hunks: {}, notes: [], bookmarks: [] } }
  state.reviewSaveState = 'pending'
  state.reviewError = ''
  resolveFirst = null
  vi.clearAllMocks()
})

describe('H-08 审阅保存并发', () => {
  it('旧 PUT 返回时不覆盖在途期间的新编辑，后续保存补写新状态', async () => {
    api.putReview.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
    const first = flushReview()
    await new Promise(r => setTimeout(r, 0))
    expect(api.putReview).toHaveBeenCalledTimes(1)

    updateReviewFileState('A.java', { status: REVIEW_STATUS.NEEDS_REVIEW, manualOverride: true })
    expect(state.review.files['A.java'].status).toBe(REVIEW_STATUS.NEEDS_REVIEW)
    expect(state.reviewSaveState).toBe('pending')

    resolveFirst({
      schemaVersion: state.review.schemaVersion,
      jobId: 'job-h08',
      user: 'tester',
      updatedAt: '2000-01-01T00:00:00.000Z',
      files: { 'A.java': { status: REVIEW_STATUS.REVIEWED, notes: [], bookmarks: [] } }
    })
    await first
    expect(state.review.files['A.java'].status).toBe(REVIEW_STATUS.NEEDS_REVIEW)
    expect(state.reviewSaveState).toBe('pending')

    await flushReview()
    expect(api.putReview).toHaveBeenCalledTimes(2)
    expect(api.putReview.mock.calls[1][1].files['A.java'].status).toBe(REVIEW_STATUS.NEEDS_REVIEW)
    expect(state.reviewSaveState).toBe('saved')
  })
})
