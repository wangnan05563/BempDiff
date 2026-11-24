// UI-06 Tab 溢出菜单：超过 8 个 tab 时只展示 8 个，其余进入「+N」菜单；
// pinned 与 active 必须优先保留；不触发网络加载，仅验证渲染与菜单可达。
// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { state, saveTabLayout, loadTabLayout } from '../store'
import DiffView from '../components/DiffView.vue'

function tab(key, pinned = false) {
  return {
    key,
    pinned,
    busy: false,
    node: { key, fileClass: 'CLASS', status: 'MODIFIED', size: 1 },
    decompile: { ok: true, engine: 'cfr', oldSource: 'a', newSource: 'b' }
  }
}

function seed(n) {
  const list = []
  for (let i = 0; i < n; i++) list.push(tab('k' + i, i === 0))
  state.tabs = list
  state.activeKey = 'k' + (n - 1) // 激活最后一个（非 pinned）
  return list
}

describe('UI-06 Tab 溢出', () => {
  afterEach(() => { state.tabs = []; state.activeKey = null; state.job = null })

  it('9 个 tab：可见 8 个 + 溢出菜单 1 个，active 保留', async () => {
    seed(9)
    const w = mount(DiffView)
    await w.vm.$nextTick()
    expect(w.findAll('.dvt-tab')).toHaveLength(8)
    expect(w.find('.dvt-tab-overflow').exists()).toBe(true)
    expect(w.find('.dvt-overflow-toggle').text()).toContain('+1')
    // active 必须在可见 tab 内（标题不是断言重点，直接看按钮 active 类）
    expect(w.findAll('.dvt-tab .nav-link.active')).toHaveLength(1)
    w.unmount()
  })

  it('打开溢出菜单可看到剩余 tab', async () => {
    seed(10)
    const w = mount(DiffView)
    await w.vm.$nextTick()
    await w.find('.dvt-overflow-toggle').trigger('click')
    expect(w.find('.dvt-overflow-menu').exists()).toBe(true)
    expect(w.findAll('.dvt-overflow-item')).toHaveLength(2)
    w.unmount()
  })

  it('8 个及以内不出现溢出菜单', async () => {
    seed(8)
    const w = mount(DiffView)
    await w.vm.$nextTick()
    expect(w.find('.dvt-tab-overflow').exists()).toBe(false)
    w.unmount()
  })

  it('溢出菜单可键盘打开、Esc 关闭并访问隐藏 Tab', async () => {
    seed(12)
    const w = mount(DiffView, { attachTo: document.body })
    await w.vm.$nextTick()
    const toggle = w.find('.dvt-overflow-toggle')
    expect(toggle.exists()).toBe(true)
    await toggle.trigger('keydown', { key: 'ArrowDown' })
    await w.vm.$nextTick()
    expect(w.find('.dvt-overflow-menu').exists()).toBe(true)
    const items = w.findAll('.dvt-overflow-item')
    expect(items.length).toBe(4)
    expect(document.activeElement).toBe(items[0].element)
    await items[0].trigger('keydown', { key: 'Escape' })
    await w.vm.$nextTick()
    expect(w.find('.dvt-overflow-menu').exists()).toBe(false)
    expect(document.activeElement).toBe(toggle.element)
    w.unmount()
  })

  it('布局快照保存 jobId/pin/active，可被读回（重启恢复的数据基础）', () => {
    seed(20)
    state.job = { jobId: 'job-restore' }
    state.oldPath = 'old.war'
    state.newPath = 'new.war'
    state.tabs[1].scroll = 640
    saveTabLayout()
    const layout = loadTabLayout()
    expect(layout).toBeTruthy()
    expect(layout.jobId).toBe('job-restore')
    expect(layout.activeKey).toBe('k19')
    expect(layout.tabs).toHaveLength(20)
    expect(layout.tabs[0].pinned).toBe(true)
    expect(layout.tabs[1].scroll).toBe(640)
    localStorage.removeItem('bempdiff.tabLayout')
  })
})
