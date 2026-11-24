// 包路径面包屑（PathBreadcrumb.vue）交互测试。
// 覆盖：层级拆分（盘符/相对/UNC/尾斜杠）、点击层级 → 更新路径前缀并高亮、最后段不重复更新、
// 空白点击 → 编辑模式（全选/Enter 确认/Esc 恢复）、复制完整路径、空态占位、disabled 禁用。
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'

vi.mock('../store', () => {
  const state = reactive({})
  return { state, toast: vi.fn() }
})

import PathBreadcrumb from '../components/PathBreadcrumb.vue'
import { toast } from '../store'

function mountCrumb(props = {}) {
  return mount(PathBreadcrumb, {
    props: { modelValue: '', label: '老包', disabled: false, ...props },
    attachTo: document.body
  })
}
async function flush() { await nextTick(); await nextTick() }

/** 点击面包屑空白区域进入编辑模式（dispatch 原生事件，绕开 trigger 的 target 限制）。 */
function clickBlank(w) {
  w.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})
afterEach(() => { vi.useRealTimers() })

describe('层级拆分', () => {
  it('Windows 盘符绝对路径按 / 拆段，首段为盘符', () => {
    const w = mountCrumb({ modelValue: 'D:/prod/old/sample_v1.war' })
    expect(w.findAll('.pb-seg').map(s => s.text().trim())).toEqual(['D:', 'prod', 'old', 'sample_v1.war'])
    expect(w.find('.pb-drive').exists()).toBe(true)
  })

  it('相对路径（文件名）仅一段', () => {
    const w = mountCrumb({ modelValue: 'sample_v1.war' })
    expect(w.findAll('.pb-seg').map(s => s.text().trim())).toEqual(['sample_v1.war'])
  })

  it('尾斜杠目录不产生空段', () => {
    const w = mountCrumb({ modelValue: 'D:/a/b/' })
    expect(w.findAll('.pb-seg').map(s => s.text().trim())).toEqual(['D:', 'a', 'b'])
  })

  it('UNC 路径（双斜杠）空段被滤除', () => {
    const w = mountCrumb({ modelValue: '\\\\srv\\share\\x' })
    expect(w.findAll('.pb-seg').map(s => s.text().trim())).toEqual(['srv', 'share', 'x'])
  })

  it('空路径显示占位', () => {
    const w = mountCrumb({ modelValue: '' })
    expect(w.find('.pb-empty').text()).toContain('未设置老包路径')
  })
})

describe('点击层级导航', () => {
  it('点击中间层级 → 更新为该层级前缀路径', async () => {
    const w = mountCrumb({ modelValue: 'D:/prod/old/sample_v1.war' })
    await w.findAll('.pb-seg')[2].trigger('click')
    expect(w.emitted('update:modelValue')).toBeTruthy()
    expect(w.emitted('update:modelValue')[0][0]).toBe('D:/prod/old')
  })

  it('点击盘符根 → D:/', async () => {
    const w = mountCrumb({ modelValue: 'D:/prod/old.war' })
    await w.findAll('.pb-seg')[0].trigger('click')
    expect(w.emitted('update:modelValue')[0][0]).toBe('D:/')
  })

  it('点击最后一段（文件名）→ 不重复 emit（路径无变化）', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/x.jar' })
    await w.findAll('.pb-seg')[2].trigger('click')
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })

  it('点击层级后该级高亮（flash），900ms 后消失', async () => {
    vi.useFakeTimers()
    const w = mountCrumb({ modelValue: 'D:/a/b/c.war' })
    await w.findAll('.pb-seg')[1].trigger('click')
    expect(w.find('.pb-flash').exists()).toBe(true)
    expect(w.find('.pb-flash').text().trim()).toBe('a')
    vi.advanceTimersByTime(901)
    await flush()
    expect(w.find('.pb-flash').exists()).toBe(false)
  })

  it('disabled 时点击不更新路径', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war', disabled: true })
    await w.findAll('.pb-seg')[1].trigger('click')
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })
})

describe('编辑模式（查看/编辑完整路径）', () => {
  it('点击空白 → 进入编辑框，值为完整路径', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    clickBlank(w)
    await flush()
    expect(w.find('input.pb-input').exists()).toBe(true)
    expect(w.find('input.pb-input').element.value).toBe('D:/a/b.war')
  })

  it('短路径面包屑铺满时，点编辑图标也能进入完整路径编辑', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    expect(w.find('.pb-edit').exists()).toBe(true)
    await w.find('.pb-edit').trigger('click')
    await flush()
    expect(w.find('input.pb-input').exists()).toBe(true)
    expect(w.find('input.pb-input').element.value).toBe('D:/a/b.war')
  })

  it('disabled 时编辑图标不进入编辑', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war', disabled: true })
    await w.find('.pb-edit').trigger('click')
    expect(w.find('input.pb-input').exists()).toBe(false)
  })

  it('点击层级节点不进入编辑', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    await w.findAll('.pb-seg')[0].trigger('click')
    expect(w.find('input.pb-input').exists()).toBe(false)
  })

  it('Enter 提交新路径', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    clickBlank(w)
    await flush()
    const input = w.find('input.pb-input')
    await input.setValue('D:/x/y.war')
    await input.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('update:modelValue')[0][0]).toBe('D:/x/y.war')
    expect(w.find('input.pb-input').exists()).toBe(false) // 已恢复面包屑
  })

  it('Esc 取消，不更新', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    clickBlank(w)
    await flush()
    const input = w.find('input.pb-input')
    await input.setValue('D:/x/y.war')
    await input.trigger('keydown', { key: 'Escape' })
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })

  it('失焦（点击页面其他区域）确认新路径，避免手填内容丢失', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    clickBlank(w)
    await flush()
    const input = w.find('input.pb-input')
    await input.setValue('D:/x/y.war')
    await input.trigger('blur')
    await flush()
    expect(w.emitted('update:modelValue')[0][0]).toBe('D:/x/y.war')
    expect(w.find('input.pb-input').exists()).toBe(false)
  })

  it('Esc 取消后紧接的失焦不提交', async () => {
    const w = mountCrumb({ modelValue: 'D:/a/b.war' })
    clickBlank(w)
    await flush()
    const input = w.find('input.pb-input')
    await input.setValue('D:/x/y.war')
    await input.trigger('keydown', { key: 'Escape' })
    await input.trigger('blur')
    await flush()
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })
})

describe('复制完整路径', () => {
  it('点击复制按钮 → clipboard 写入完整路径 + toast', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    const w = mountCrumb({ modelValue: 'D:/prod/sample_v1.war', label: '老包' })
    await w.find('.pb-copy').trigger('click')
    await flush()
    expect(writeText).toHaveBeenCalledWith('D:/prod/sample_v1.war')
    expect(toast).toHaveBeenCalledWith('success', expect.stringContaining('老包'))
    expect(toast).toHaveBeenCalledWith('success', expect.stringContaining('D:/prod/sample_v1.war'))
  })
})
