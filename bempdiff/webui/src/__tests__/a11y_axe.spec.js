// R7 axe 自动化扫描（二期 T01468）：核心组件 axe-core 扫描，critical/serious 违规为 0。
// 说明：jsdom 无真实布局引擎，color-contrast 等依赖视觉计算的规则会被 axe 自动跳过
//（其可达性由 Bootstrap 语义变量体系内建 ≥4.5:1 + T01467 代码审计覆盖）；
// 结构类规则（button-name / aria-* / label / list 等）在 jsdom 下可靠运行。
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import axe from 'axe-core'
import { state, defaultConfig } from '../store'
import ToolBar from '../components/ToolBar.vue'
import AiConsole from '../components/AiConsole.vue'
import ShortcutHelp from '../components/ShortcutHelp.vue'
import CompareOverlay from '../components/CompareOverlay.vue'

const IMPACT_GATE = ['critical', 'serious']

async function scan(w, tag) {
  // 等一帧让异步渲染/DOM 稳定
  await new Promise(r => setTimeout(r, 30))
  const results = await axe.run(w.element, {
    resultTypes: ['violations'],
    rules: { 'color-contrast': { enabled: false } } // jsdom 无布局引擎，交给 Bootstrap 变量体系 + 代码审计
  })
  const bad = results.violations.filter(v => IMPACT_GATE.includes(v.impact))
  const detail = bad.map(v => `${v.id}(${v.impact}): ${v.nodes.length} node(s) — ${v.help} — ` +
    v.nodes.map(n => (n.html || '').slice(0, 120)).join(' | ')).join('; ')
  expect(detail || '', `${tag} axe 扫描 critical/serious 违规`).toBe('')
  return results.violations.length
}

describe('R7 axe 扫描（critical/serious = 0）', () => {
  beforeEach(() => {
    state.config = defaultConfig()
    state.job = { jobId: 'j1', status: 'DONE', tree: [], stats: null, mode: 'package' }
    state.reportMd = '# 报告'
    state.aiTasks = []
    state.exportRecords = []
    state.jobProgress = null
    state.busy = false
    state.reporting = false
  })

  it('ToolBar', async () => {
    const w = mount(ToolBar, {
      props: { onOpenConfig: () => {}, onOpenReport: () => {} },
      attachTo: document.body
    })
    await scan(w, 'ToolBar')
    w.unmount()
  })

  it('AiConsole（分析完成态）', async () => {
    state.aiTasks = [{
      id: 'ai-1', category: 'risk', title: '整体风险分析', prompt: '',
      status: 'done', thinking: [], answer: '# 摘要\n\n内容', error: '',
      createdAt: Date.now(), alive: true, thinkingCollapsed: true
    }]
    state.aiActiveTaskId = 'ai-1'
    const w = mount(AiConsole, { attachTo: document.body })
    await scan(w, 'AiConsole')
    w.unmount()
  })

  it('ShortcutHelp（模态对话框）', async () => {
    const w = mount(ShortcutHelp, { attachTo: document.body })
    await scan(w, 'ShortcutHelp')
    w.unmount()
  })

  it('CompareOverlay（比对进行态）', async () => {
    state.busy = true
    state.jobProgress = { status: 'RUNNING', phase: 'diffing', progress: 70, message: '计算差异…' }
    const w = mount(CompareOverlay, { attachTo: document.body })
    await scan(w, 'CompareOverlay')
    w.unmount()
  })
})

describe('R7 axe 扫描扩展（配置中心）', () => {
  beforeEach(() => {
    state.config = defaultConfig()
    state.showConfigPanel = true
  })

  it('ConfigDialog', async () => {
    const { default: ConfigDialog } = await import('../components/ConfigDialog.vue')
    const w = mount(ConfigDialog, { props: { visible: true }, attachTo: document.body })
    await scan(w, 'ConfigDialog')
    w.unmount()
  }, 20000)
})
