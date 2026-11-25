// M-18 回归：审阅 JSON 导出按钮必须等 review 属于当前 job 且加载完成。
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ReportPreview from '../components/ReportPreview.vue'
import { state } from '../store'

beforeEach(() => {
  state.job = { jobId: 'job-B', status: 'DONE', stats: { added: 1, deleted: 0, modified: 0, unchanged: 0 } }
  state.review = { jobId: 'job-A', files: {} }
  state.reviewLoading = false
  state.reportMd = '# 报告'
  state.reportAi = false
  state.reportCategory = null
  state.config = { aiEnabled: false }
  localStorage.clear()
})

function button(w) { return w.find('#rp-export-review-json') }

describe('M-18 审阅 JSON 导出一致性', () => {
  it('review 属于旧 job / 正在加载时禁用；切到当前 job 且加载完成才可用', async () => {
    const w = mount(ReportPreview, { props: { visible: true } })
    expect(button(w).attributes('disabled')).toBeDefined()

    state.review = { jobId: 'job-B', files: {} }
    state.reviewLoading = true
    await nextTick()
    expect(button(w).attributes('disabled')).toBeDefined()

    state.reviewLoading = false
    await nextTick()
    expect(button(w).attributes('disabled')).toBeUndefined()
    w.unmount()
  })
})
