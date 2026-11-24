// @vitest-environment jsdom
// TH-03 色盲安全配色：store 持久化/回退 + DiffView 结构线索守卫。
// jsdom 无真实布局，颜色对比交给 CSS 变量；这里锁定「可切换、可记住、非颜色线索存在」三条契约。
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import { state, setDiffPalette } from '../store'
import DiffView from '../components/DiffView.vue'

const KEY = 'bempdiff.diffPalette'
const srcPath = [
  resolve(process.cwd(), 'src/components/DiffView.vue'),
  resolve(process.cwd(), 'bempdiff/webui/src/components/DiffView.vue')
].find(existsSync)
const src = readFileSync(srcPath, 'utf8')

describe('TH-03 差异调色板', () => {
  beforeEach(() => {
    try { localStorage.removeItem(KEY) } catch (_) {}
    setDiffPalette('default')
  })

  it('切换色盲模式会持久化并更新 state', () => {
    expect(setDiffPalette('colorblind')).toBe('colorblind')
    expect(state.diffPalette).toBe('colorblind')
    expect(localStorage.getItem(KEY)).toBe('colorblind')
  })

  it('非法值回退 default，不会写入未知值', () => {
    setDiffPalette('rainbow')
    expect(state.diffPalette).toBe('default')
    expect(localStorage.getItem(KEY)).toBe('default')
  })

  it('DiffView 暴露 data-diff-palette，并用 +/−/≈ 文本符号与纹理条作为非颜色线索', () => {
    expect(src).toContain(':data-diff-palette="state.diffPalette"')
    expect(src).toContain('gtSymbol(v, \'left\')')
    expect(src).toContain("v.mod ? '≈'")
    expect(src).toContain('repeating-linear-gradient(45deg')
    expect(src).toContain('repeating-linear-gradient(135deg')
  })

  it('色盲模式下实际渲染 +/−/≈ 符号（不只靠颜色区分增删改）', async () => {
    setDiffPalette('colorblind')
    state.tabs = [{
      key: 'pkg/C.java',
      node: { key: 'pkg/C.java', fileClass: 'CLASS', status: 'MODIFIED' },
      busy: false,
      decompile: { ok: true, engine: 'cfr', oldSource: 'a\nvalue = old\ngone\nc', newSource: 'a\nvalue = new\nc\nd' }
    }]
    const w = mount(DiffView)
    state.activeKey = 'pkg/C.java'
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    const root = w.find('.col-center')
    expect(root.exists()).toBe(true)
    expect(root.attributes('data-diff-palette')).toBe('colorblind')
    const text = w.text()
    expect(text).toContain('+')
    expect(text).toContain('−')
    expect(text).toContain('≈')
    w.unmount()
  })

  afterEach(() => {
    state.tabs = []
    state.activeKey = null
    state.job = null
  })
})
