// 报告类别维度缓存（reportCache）回归测试：多类别报告并存互不覆盖、缓存命中短路、
// force 强制刷新、新一轮比对清空缓存。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { state, generateReport } from '../store'

// mock 后端 api：按 category 返回不同内容，便于断言缓存键与调用次数
vi.mock('../api/client', () => ({
  api: {
    report: vi.fn(async (jobId, ai, category) =>
      `# 报告(${ai ? 'ai:' + (category || 'default') : 'base'}) 内容-${category || 'default'}`),
    getConfig: vi.fn(async () => ({ aiEnabled: false })),
    aiEstimate: vi.fn(async () => ({ report: 10, analyze: 10, classify: 10, threshold: 8000 }))
  }
}))

import { api } from '../api/client'

const JOB = { jobId: 'j1', stats: { added: 1, deleted: 0, modified: 0, unchanged: 0, bizChanged: 0, jarChanged: 0, total: 1 } }

beforeEach(() => {
  state.job = JOB
  state.reportMd = null
  state.reportCache = {}
  state.reportCategory = null
  state.reportAi = false
  state.busy = false
  state.reporting = false
  state.analyzing = false
  state.aiEstimate = null
  state.aiSelCategory = 'risk'
  state.config = { aiEnabled: true, projectContextEnabled: false, costGateWarnTokens: 8000 }
  vi.clearAllMocks()
})

async function settle() {
  // generateReport 内部 await（cost gate / api），等微任务链完成
  await new Promise(r => setTimeout(r, 30))
}

describe('reportCache 类别维度缓存', () => {
  it('C1 不同分析项的报告并存，互不覆盖', async () => {
    await generateReport(true, { category: 'risk' })
    await settle()
    const mdRisk = state.reportMd
    await generateReport(true, { category: 'breaking' })
    await settle()
    const mdBreaking = state.reportMd
    expect(mdRisk).not.toBe(mdBreaking)                 // 内容不同
    expect(state.reportCache['ai:risk@j1']).toBe(mdRisk)   // 各自按类别缓存（H-07：键带 jobId）
    expect(state.reportCache['ai:breaking@j1']).toBe(mdBreaking)
    expect(api.report).toHaveBeenCalledTimes(2)         // 两次真实调用
  })

  it('C2 相同类别重复生成：命中缓存短路，不再调用后端', async () => {
    await generateReport(true, { category: 'risk' })
    await settle()
    expect(api.report).toHaveBeenCalledTimes(1)
    await generateReport(true, { category: 'risk' })    // 同类别 → 缓存命中
    await settle()
    expect(api.report).toHaveBeenCalledTimes(1)         // 无第二次调用
    expect(state.reportMd).toContain('risk')            // 展示内容来自缓存且正确
  })

  it('C3 force 强制刷新：绕过缓存重新调用后端', async () => {
    await generateReport(true, { category: 'risk' })
    await settle()
    await generateReport(true, { category: 'risk', force: true })
    await settle()
    expect(api.report).toHaveBeenCalledTimes(2)         // force 会再调一次
    expect(state.reportCache['ai:risk@j1']).toBe(state.reportMd)
  })

  it('C4 基础报告（非 AI）缓存键为 base', async () => {
    await generateReport(false)
    await settle()
    expect(state.reportCache['base@j1']).toBe(state.reportMd)
    await generateReport(false)
    await settle()
    expect(api.report).toHaveBeenCalledTimes(1)         // 基础报告同样命中缓存
  })

  it('C5 切换分析项后回到旧项：展示旧缓存（reportMd 正确切换）', async () => {
    await generateReport(true, { category: 'risk' })
    await settle()
    await generateReport(true, { category: 'breaking' })
    await settle()
    await generateReport(true, { category: 'risk' })    // 回到 risk → 缓存命中
    await settle()
    expect(state.reportMd).toContain('risk')
    expect(state.reportCategory).toBe('risk')
  })

  it('C6 新一轮比对（runCompare 完成）清空 reportCache', async () => {
    await generateReport(true, { category: 'risk' })
    await settle()
    expect(api.report.mock.calls.length).toBe(1)
    expect(Object.keys(state.reportCache).length).toBe(1)
    state.reportMd = null
    state.reportCache = {} // 模拟 runCompare 完成时的重置语义（store.js 中 state.reportCache = {}）
    // 清空后同类别重新生成：不再命中旧缓存 → 重新调用后端
    await generateReport(true, { category: 'risk' })
    await settle()
    expect(api.report.mock.calls.length).toBe(2)
  })
})
