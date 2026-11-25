// M-12 回归：已有比对/预检/任务进行中时，不能重复创建第二个 compare job。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { state, runCompare, triggerCompare } from '../store'

vi.mock('../api/client', () => ({
  api: {
    compare: vi.fn(async () => ({ jobId: 'should-not-run', status: 'queued' })),
    preflight: vi.fn(async () => ({ level: 'info', blockers: [], warnings: [], infos: [] }))
  }
}))

import { api } from '../api/client'

beforeEach(() => {
  state.busy = false
  state.preflightBusy = false
  state.preflight = null
  state.review = null
  state.reviewSaveState = 'saved'
  state.job = null
  vi.clearAllMocks()
})

const payload = { leftType: 'package', leftPath: 'a.war', rightPath: 'b.war', options: {} }

describe('M-12 比对入口 busy 防护', () => {
  it('runCompare 在 busy 时早退，不请求后端', async () => {
    state.busy = true
    const ok = await runCompare(payload)
    expect(ok).toBe(false)
    expect(api.compare).not.toHaveBeenCalled()
  })

  it('triggerCompare 在 preflightBusy 时早退，不重复预检', async () => {
    state.preflightBusy = true
    const ok = await triggerCompare({ payload })
    expect(ok).toBe(false)
    expect(api.preflight).not.toHaveBeenCalled()
    expect(api.compare).not.toHaveBeenCalled()
  })

  it('预检确认框未处理时不允许发起新比对', async () => {
    state.preflight = { result: { level: 'warn' }, payload }
    const ok = await triggerCompare({ payload })
    expect(ok).toBe(false)
    expect(api.compare).not.toHaveBeenCalled()
  })
})
