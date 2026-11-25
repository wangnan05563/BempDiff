// M-13 回归：报告生成失败必须返回 false 并清掉旧报告/缓存，不能留下“旧报告可打开”的假状态。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { state, generateReport } from '../store'

vi.mock('../api/client', () => ({
  api: {
    report: vi.fn(async () => { throw new Error('后端报告失败') })
  }
}))

import { api } from '../api/client'

beforeEach(() => {
  state.job = { jobId: 'j-report', status: 'DONE', stats: { added: 1, deleted: 0, modified: 0, unchanged: 0 } }
  state.reportMd = '# 旧报告'
  state.reportAi = false
  state.reportCategory = null
  state.reportCache = { 'base@j-report': '# 旧报告' }
  state.busy = false
  state.reporting = false
  state.config = { aiEnabled: false }
  state.reviewSaveState = 'saved'
  state.review = null
  vi.clearAllMocks()
})

describe('M-13 报告失败清理旧状态', () => {
  it('失败返回 false，清空 reportMd / reportCache', async () => {
    const ok = await generateReport(false, { force: true })
    expect(ok).toBe(false)
    expect(state.reportMd).toBeNull()
    expect(state.reportCache['base@j-report']).toBeUndefined()
    expect(state.reporting).toBe(false)
    expect(state.busy).toBe(false)
    expect(api.report).toHaveBeenCalledTimes(1)
  })
})
