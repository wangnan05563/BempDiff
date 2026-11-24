// 导出按钮禁用逻辑：导出进行中（state.exporting=true）时，导出触发器按钮禁用、不能展开下拉、
// 下拉表单项也置灰不可点击——防止用户重复触发导出。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { state } from '../store'
import ToolBar from '../components/ToolBar.vue'

function mountToolBar() {
  return mount(ToolBar, {
    props: { onOpenConfig: () => {}, onOpenReport: () => {} },
    global: { stubs: { PathBreadcrumb: true, Downloads: true } }
  })
}

describe('ToolBar 导出按钮禁用逻辑', () => {
  beforeEach(() => {
    state.job = { jobId: 'j1', status: 'DONE' }
    state.exporting = false
    state.unpacking = false
  })

  it('导出进行中：导出触发器按钮禁用（灰显）', () => {
    state.exporting = true
    const w = mountToolBar()
    const btn = w.find('button[title="导出进行中，完成前禁用"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('非导出中且有比对结果：导出按钮可用', () => {
    const w = mountToolBar()
    const btn = w.find('button[title="导出差异报告或差异资产"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('导出进行中：点击禁用按钮不会展开下拉（防再次触发）', async () => {
    state.exporting = true
    const w = mountToolBar()
    const btn = w.find('button[title="导出进行中，完成前禁用"]')
    await btn.trigger('click')
    expect(w.find('ul.dropdown-menu').exists()).toBe(false)
    w.unmount()
  })

  it('非导出中：点击按钮可展开下拉', async () => {
    const w = mountToolBar()
    const btn = w.find('button[title="导出差异报告或差异资产"]')
    await btn.trigger('click')
    expect(w.find('ul.dropdown-menu').exists()).toBe(true)
    w.unmount()
  })

  it('无 lib/*.jar 变化：JAR 粒度禁用并显示原因', async () => {
    state.job = { jobId: 'j1', status: 'DONE', stats: { jarChanged: 0 } }
    const w = mountToolBar()
    await w.find('button[title="导出差异报告或差异资产"]').trigger('click')
    expect(w.find('#expGranJar').attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('本次比对没有 lib/*.jar 变化')
    w.unmount()
  })

  it('有 lib/*.jar 变化：JAR 粒度可选', async () => {
    state.job = { jobId: 'j1', status: 'DONE', stats: { jarChanged: 2 } }
    const w = mountToolBar()
    await w.find('button[title="导出差异报告或差异资产"]').trigger('click')
    expect(w.find('#expGranJar').attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('P3-R11：传入 onOpenHelp 时显示常驻「?」入口并回调', async () => {
    const onOpenHelp = vi.fn()
    const w = mount(ToolBar, {
      props: { onOpenConfig: () => {}, onOpenReport: () => {}, onOpenHelp },
      global: { stubs: { PathBreadcrumb: true, Downloads: true } }
    })
    const btn = w.find('button[title="快捷键与帮助（?）"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(onOpenHelp).toHaveBeenCalledTimes(1)
    w.unmount()
  })
})