// FR-022 虚拟滚动性能护栏（compare三期 T01805）：万行级差异文件挂载到首帧可交互。
// 口径对齐 tree_perf_50k.spec.js：jsdom 无法测真实帧率，以「挂载→首帧渲染完成」为代理指标。
// 10 万行真机帧率基准由 M4 性能验收脚本在 Chrome 中采样，单测只守 PRD REQ-018 的 1 万行 SLA。
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { state } from '../store'
import DiffView from '../components/DiffView.vue'

const LINES = 10000
// 逐行 1:1 修改（rep 行 1 万）：覆盖行级对齐 + rows 全量构建 + 窗口切片最重路径
const oldSrc = Array.from({ length: LINES }, (_, i) => 'old line ' + i).join('\n')
const newSrc = Array.from({ length: LINES }, (_, i) => 'new line ' + i).join('\n')

const KEY = 'pkg/Huge.java'
const NODE = { key: KEY, fileClass: 'CLASS', status: 'MODIFIED', size: 4096 }

describe('FR-022 1 万行 diff 渲染性能', () => {
  afterEach(() => {
    state.tabs = []
    state.activeKey = null
    state.job = null
  })

  it(`1 万行挂载到首帧 ≤ 护栏线，DOM 行数受窗口约束`, async () => {
    const w = mount(DiffView)
    state.tabs = [{
      key: KEY, node: NODE, busy: false,
      decompile: { ok: true, engine: 'cfr', oldSource: oldSrc, newSource: newSrc }
    }]
    state.activeKey = KEY
    const t0 = performance.now()
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    const elapsed = performance.now() - t0
    console.info(`[perf] 1 万行 diff 首帧实测 ${elapsed.toFixed(0)}ms`)
    // 虚拟窗口生效：DOM 行数恒定，不随 1 万总量铺开
    const prows = w.findAll('.prow')
    expect(prows.length, '首帧应渲染可视窗口行').toBeGreaterThan(0)
    expect(prows.length, `1 万行文件实际渲染 ${prows.length} 行（窗口 + OVERSCAN）`).toBeLessThan(500)
    // 1 万行未触发 6 万行降级线：应正常显示行内差异（不出现降级提示条）
    const banner = w.find('.oversized-banner')
    expect(banner.exists(), '1 万行未达 6 万降级阈值，不应出现降级提示条').toBe(false)
    // 护栏线 12000ms：全量套件并行噪声余量（PRD 性能 SLA 见非功能约束 §5.2，
    // 单独运行实测值记入回传存证）
    expect(elapsed, `1 万行首帧实测 ${elapsed.toFixed(0)}ms（护栏线 12000ms）`).toBeLessThan(12000)
    w.unmount()
  })
})

