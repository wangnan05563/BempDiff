// T03669：比对失败后不得回落成「暂无差异」空态。
// 覆盖三态空态（比对失败 / 尚未比对 / 确实无差异）、失败原因常驻可见、
// 状态栏不再为失败的比对渲染版本号，以及 toast 前缀不重复。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { state, runCompare, normalizeComparePath } from '../store'
import { defaultConfig } from '../store'
import { api } from '../api/client'
import DiffTree from '../components/DiffTree.vue'
import StatusBar from '../components/StatusBar.vue'

function freshState() {
  state.config = defaultConfig()
  state.job = null
  state.jobProgress = null
  state.compareStalled = false
  state.exporting = false
  state.error = null
  state.toast = null
  state.aiClassify = {}
  state.excludedKeys = {}
  state.ignoreRules = []
  state.archiveChildren = {}
  state.expandedArchives = {}
  state.treeLocate = null
  state.propertyNode = null
  state.baseFolder = null
  state.activeKey = null
  state.sameInputCompare = false
  state.oldPath = 'D:/pkgs/demo_v1.war'
  state.newPath = 'D:/pkgs/__no_such__.war'
}

describe('DiffTree 空态三态区分（T03669）', () => {
  beforeEach(() => { freshState() })

  it('尚未比对：给出「下一步该做什么」而不是「暂无差异」', () => {
    const w = mount(DiffTree)
    expect(w.text()).toContain('尚未开始比对')
    expect(w.text()).not.toContain('本次比对未成功')
    w.unmount()
  })

  it('比对完成但两包完全一致：即使存在被过滤的「未变」条目，也必须给出「完全一致」结论', () => {
    state.job = { jobId: 'j1', tree: [{ key: 'a.txt', status: 'UNCHANGED' }], stats: { added: 0, deleted: 0, modified: 0, unchanged: 1 } }
    const w = mount(DiffTree)
    expect(w.text()).toContain('两个包完全一致')
    expect(w.text()).not.toContain('尚未开始比对')
    expect(w.text()).not.toContain('没有匹配的条目')
    w.unmount()
  })

  it('同一输入自比对：0 差异不能表述成「两个包完全一致」', () => {
    state.job = { jobId: 'j2', tree: [], stats: { added: 0, deleted: 0, modified: 0, unchanged: 0 } }
    state.sameInputCompare = true
    const w = mount(DiffTree)
    expect(w.text()).toContain('同一个目录/包')
    expect(w.text()).not.toContain('两个包完全一致')
    w.unmount()
  })

  it('路径归一：Windows 大小写/斜杠/尾斜杠不影响同一输入判定', () => {
    expect(normalizeComparePath('D:\\Pkgs\\Demo.WAR\\')).toBe(normalizeComparePath('d:/pkgs/demo.war'))
    expect(normalizeComparePath('/tmp/a/')).toBe(normalizeComparePath('/tmp/a'))
    expect(normalizeComparePath('/tmp/a')).not.toBe(normalizeComparePath('/tmp/b'))
  })

  it('比对失败：常驻红色失败态并显示原因，不再出现「暂无差异」类文案', () => {
    state.error = '老包文件不存在：D:/pkgs/__no_such__.war'
    const w = mount(DiffTree)
    expect(w.find('.dt-failed').exists()).toBe(true)
    expect(w.text()).toContain('本次比对未成功')
    expect(w.text()).toContain('老包文件不存在：D:/pkgs/__no_such__.war')
    expect(w.text()).toContain('这不是「两个包一致」')
    expect(w.text()).not.toContain('两个包完全一致')
    expect(w.text()).not.toContain('尚未开始比对')
    w.unmount()
  })

  it('比对失败时树栏计数显示「无结果」而不是「0 项」', () => {
    state.error = '文件不存在：x'
    const w = mount(DiffTree)
    expect(w.find('.pane-head').text()).toContain('无结果')
    expect(w.find('.pane-head').text()).not.toContain('0 项')
    w.unmount()
  })
})

describe('StatusBar 比对失败态（T03669）', () => {
  beforeEach(() => { freshState() })

  it('失败时常驻「比对未成功」标记，且不为缺失文件渲染版本号', () => {
    state.error = '新包文件不存在：D:/pkgs/__no_such__.war'
    const w = mount(StatusBar)
    expect(w.text()).toContain('比对未成功')
    expect(w.text()).toContain('版本：—')
    expect(w.text()).not.toContain('__no_such__')
    w.unmount()
  })

  it('未比对时仍按原设计显示填入的两个包名（不回归）', () => {
    const w = mount(StatusBar)
    expect(w.text()).toContain('demo_v1.war → __no_such__.war')
    expect(w.text()).not.toContain('比对未成功')
    w.unmount()
  })
})

describe('runCompare 失败处理（T03669）', () => {
  beforeEach(() => { freshState() })

  it('失败：清空上一轮结果、原因留在 state.error、toast 前缀只出现一次', async () => {
    // 上一轮成功结果必须在失败后被清掉，否则旧树会冒充本次结果
    state.job = { jobId: 'old', tree: [{ key: 'a', status: 'MODIFIED' }], stats: { added: 1, deleted: 0, modified: 0, unchanged: 0 } }
    state.tabs = [{ key: 'a' }]
    state.reportMd = '# 旧报告'
    vi.spyOn(api, 'compare').mockRejectedValue(new Error('新包文件不存在：D:/pkgs/__no_such__.war'))
    const ok = await runCompare({ leftType: 'package', leftPath: state.oldPath, rightPath: state.newPath, options: {} })
    expect(ok).toBe(false)
    expect(state.error).toBe('新包文件不存在：D:/pkgs/__no_such__.war')
    expect(state.job).toBe(null)
    expect(state.tabs).toEqual([])
    expect(state.reportMd).toBe(null)
    expect(state.toast.type).toBe('danger')
    // 后端已不再自带「比对失败: 」前缀，界面只加一次
    expect(state.toast.text.match(/比对失败/g) || []).toHaveLength(1)
    expect(state.toast.text).toBe('比对失败：新包文件不存在：D:/pkgs/__no_such__.war')
  })

  it('成功：清掉上一轮的失败痕迹', async () => {
    state.error = '上一轮失败原因'
    vi.spyOn(api, 'compare').mockResolvedValue({ jobId: 'j2', status: 'DONE' })
    vi.spyOn(api, 'jobStatus').mockResolvedValue({
      status: 'DONE', tree: [], stats: { added: 0, deleted: 0, modified: 0, unchanged: 0 }
    })
    const ok = await runCompare({ leftType: 'package', leftPath: state.oldPath, rightPath: state.newPath, options: {} })
    expect(ok).toBe(true)
    expect(state.error).toBe(null)
    expect(state.job).not.toBe(null)
  })
})
