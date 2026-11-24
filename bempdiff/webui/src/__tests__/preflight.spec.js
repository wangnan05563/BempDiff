// @vitest-environment jsdom
// CX-09 预检弹窗：阻断项必须禁用「仍然开始比对」，警告项允许继续，取消不产生副作用。
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PreflightDialog from '../components/PreflightDialog.vue'
import { state, cancelPreflight } from '../store'

function seed(result) {
  state.preflight = {
    result: {
      ok: result.ok,
      level: result.level,
      blockers: result.blockers || [],
      warnings: result.warnings || [],
      infos: result.infos || [],
      estimatedSeconds: 12,
      left: { path: 'old.war', directory: false, entries: 100, compressedBytes: 1024, uncompressedBytes: 4096, maxDepth: 3 },
      right: { path: 'new.war', directory: false, entries: 120, compressedBytes: 2048, uncompressedBytes: 8192, maxDepth: 3 }
    },
    payload: { leftType: 'package', leftPath: 'old.war', rightPath: 'new.war', options: {} }
  }
}

describe('CX-09 PreflightDialog', () => {
  beforeEach(() => { cancelPreflight() })

  it('阻断态展示原因并禁用继续', async () => {
    seed({ ok: false, level: 'block', blockers: ['老包与新包指向同一路径'] })
    const w = mount(PreflightDialog)
    expect(w.text()).toContain('老包与新包指向同一路径')
    const primary = w.findAll('button').find(b => b.text().includes('仍然开始比对'))
    expect(primary).toBeTruthy()
    expect(primary.attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('警告态允许继续，按钮不禁用', () => {
    seed({ ok: true, level: 'warn', warnings: ['约 300 MB，可能较慢'] })
    const w = mount(PreflightDialog)
    const primary = w.findAll('button').find(b => b.text().includes('仍然开始比对'))
    expect(primary).toBeTruthy()
    expect(primary.attributes('disabled')).toBeUndefined()
    expect(state.preflight).not.toBe(null)
    w.unmount()
  })

  it('取消仅关闭弹窗，不启动比对', () => {
    seed({ ok: false, level: 'block', blockers: ['x'] })
    const w = mount(PreflightDialog)
    const cancel = w.findAll('button').find(b => b.text().includes('取消'))
    expect(cancel).toBeTruthy()
    expect(state.preflight).not.toBe(null)
    w.unmount()
  })
})
