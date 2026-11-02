// T00638（验证失败反馈）回归：恢复「文件/目录两个按钮并列切换」的输入类型模式。
// 语义：工具栏分段控件显式切换 state.leftType；浏览按钮按当前模式决定原生对话框类型
// （package → 文件选择器 directory:false；folder → 目录选择器 directory:true）。
// 防线：不得再出现「文件+目录同弹（auto）」——Windows/Linux 下会被 Electron 降级为仅目录选择器，
// 导致点浏览只能选到文件夹（T00638 原始现象）。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'

const { pickPath, state, toast } = vi.hoisted(() => ({
  pickPath: vi.fn(),
  state: { oldPath: '', newPath: '', leftType: 'package', job: null, exporting: false },
  toast: vi.fn()
}))
vi.mock('../lib/desktop-bridge', () => ({
  isElectron: () => true,
  pickPath: (...a) => pickPath(...a)
}))
vi.mock('../store', () => ({
  state,
  triggerCompare: vi.fn(),
  generateReport: vi.fn(),
  downloadExport: vi.fn(),
  runAiClassify: vi.fn(),
  toast,
  applyTheme: vi.fn(),
  ingestShellPaths: vi.fn(),
  inferType: (p) => (/\.(war|jar|zip|ear|tar\.gz|tgz)$/i.test(p || '') ? 'package' : 'folder'),
  startAiAnalysis: vi.fn(),
  aiAnyRunning: () => false,
  isUnpacking: () => false,
  openExports: vi.fn(),
  loadHlTheme: () => 'default'
}))

import ToolBar from '../components/ToolBar.vue'

function mountToolBar() {
  return mount(ToolBar, {
    props: { onOpenConfig: () => {}, onOpenReport: () => {} },
    global: { stubs: { PathBreadcrumb: true, Downloads: true } }
  })
}
// 浏览按钮的 title 随模式变化（老包/老目录），故按两种文案任一匹配
function browseBtn(w, which) {
  const key = which === 'old' ? '老' : '新'
  return w.findAll('button').find((b) => {
    const t = b.attributes('title') || ''
    return t.includes(`浏览选择${key}包`) || t.includes(`浏览选择${key}目录`)
  })
}
// 类型分段按钮：按 title 定位（图标是按钮内的 <i>，不能按按钮 class 找图标名）
const typeBtn = (w, kind) => w.findAll('button').find((b) => {
  const t = b.attributes('title') || ''
  return kind === 'package' ? t.includes('war/jar 包作为输入') : t.includes('解压后的目录作为输入')
})

beforeEach(() => {
  pickPath.mockReset()
  state.oldPath = ''
  state.newPath = ''
  state.leftType = 'package'
})

describe('ToolBar 输入类型两按钮模式 + 浏览对话框路由（T00638 反馈修复）', () => {
  it('分段控件存在「文件包 / 目录」两个并列按钮，可切换 state.leftType', async () => {
    const w = mountToolBar()
    const zip = typeBtn(w, 'package')
    const folder = typeBtn(w, 'folder')
    expect(zip, '应有包类型按钮').toBeTruthy()
    expect(folder, '应有目录类型按钮').toBeTruthy()
    expect(zip.attributes('title')).toContain('war/jar')
    await folder.trigger('click')
    expect(state.leftType).toBe('folder')
    await zip.trigger('click')
    expect(state.leftType).toBe('package')
    w.unmount()
  })

  it('package 模式点浏览 → directory:false（文件选择器），可选中 war/jar 单文件', async () => {
    pickPath.mockResolvedValue('C:/pkg/app.war')
    const w = mountToolBar()
    await browseBtn(w, 'old').trigger('click')
    await nextTick()
    expect(pickPath.mock.calls[0][0]).toEqual({ directory: false })
    expect(state.oldPath).toBe('C:/pkg/app.war')
    w.unmount()
  })

  it('folder 模式点浏览 → directory:true（目录选择器）', async () => {
    state.leftType = 'folder'
    pickPath.mockResolvedValue('C:/extracted/app')
    const w = mountToolBar()
    await browseBtn(w, 'old').trigger('click')
    await nextTick()
    expect(pickPath.mock.calls[0][0]).toEqual({ directory: true })
    expect(state.oldPath).toBe('C:/extracted/app')
    w.unmount()
  })

  it('新侧同样按当前模式路由', async () => {
    state.leftType = 'folder'
    pickPath.mockResolvedValue('D:/extracted/new')
    const w = mountToolBar()
    await browseBtn(w, 'new').trigger('click')
    await nextTick()
    expect(pickPath.mock.calls[0][0]).toEqual({ directory: true })
    expect(state.newPath).toBe('D:/extracted/new')
    w.unmount()
  })

  it('回归防线：参数键固定为 directory（不得再混用 auto 同弹）', async () => {
    pickPath.mockResolvedValue('C:/x/a.war')
    const w = mountToolBar()
    await browseBtn(w, 'old').trigger('click')
    await browseBtn(w, 'new').trigger('click')
    await nextTick()
    for (const call of pickPath.mock.calls) {
      expect(Object.keys(call[0])).toEqual(['directory'])
      expect(typeof call[0].directory).toBe('boolean')
    }
    w.unmount()
  })
})
