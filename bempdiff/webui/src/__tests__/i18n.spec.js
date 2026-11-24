// R12 i18n（二期 T01477）——框架与首批覆盖验收。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { t, setLocale, i18n, tTerm, TERM_TABLE, LOCALES, LANG_KEY, allKeys } from '../lib/i18n'
import { state, defaultConfig } from '../store'
import CompareOverlay from '../components/CompareOverlay.vue'
import HelpDoc from '../components/HelpDoc.vue'
import ShortcutHelp from '../components/ShortcutHelp.vue'
import Downloads from '../components/Downloads.vue'
import CostGateDialog from '../components/CostGateDialog.vue'
import PathBar from '../components/PathBar.vue'
import PathBreadcrumb from '../components/PathBreadcrumb.vue'
import InfoPanel from '../components/InfoPanel.vue'
import GuideOverlay from '../components/GuideOverlay.vue'
import ReportPreview from '../components/ReportPreview.vue'
import ToolBar from '../components/ToolBar.vue'
import About from '../components/About.vue'
import StatusBar from '../components/StatusBar.vue'
import AiConsole from '../components/AiConsole.vue'
import DiffTree from '../components/DiffTree.vue'
import { ACCENTS } from '../lib/accents'

describe('R12 i18n 框架', () => {
  beforeEach(() => {
    try { localStorage.removeItem(LANG_KEY) } catch (_) {}
    setLocale('zh-CN')
  })

  it('双字典 key 集合一致（防漏译）', () => {
    const { default: dict } = { default: null }
    // 直接从模块内部结构验证：t() 在两种语言下均不回退 key 本身
    for (const [locale] of LOCALES.map(l => [l.key])) {
      setLocale(locale)
      for (const k of ['app.compare', 'app.cancelCompare', 'phase.parsing', 'phase.diffing',
        'step.unpack', 'step.diff', 'step.build', 'status.version', 'phase.aiWait', 'phase.timeout']) {
        expect(t(k), `${locale} 缺失 ${k}`).not.toBe(k)
      }
    }
    setLocale('zh-CN')
  })

  it('t() 插值与缺失 key 回退链（en 缺失→中文→key）', () => {
    setLocale('zh-CN')
    expect(t('nope.missing')).toBe('nope.missing')
    expect(t('app.compare')).toBe('开始比对')
    setLocale('en-US')
    expect(t('app.compare')).toBe('Start Compare')
    expect(t('nope.missing', { x: 1 })).toBe('nope.missing')
    setLocale('zh-CN')
  })

  it('setLocale 持久化并同步 html[lang]；术语对照 zh 附英文', () => {
    setLocale('en-US')
    expect(localStorage.getItem(LANG_KEY)).toBe('en-US')
    expect(document.documentElement.getAttribute('lang')).toBe('en-US')
    expect(i18n.locale).toBe('en-US')
    setLocale('zh-CN')
    // tTerm：中文语境附英文对照；英文语境直接英文
    expect(tTerm('term.diffTree')).toBe('差异树 (Diff Tree)')
    setLocale('en-US')
    expect(tTerm('term.diffTree')).toBe('Diff Tree')
    setLocale('zh-CN')
  })

  it('术语对照表结构完整（8 条，三列）', () => {
    expect(TERM_TABLE.length).toBeGreaterThanOrEqual(8)
    for (const r of TERM_TABLE) {
      expect(r.length).toBe(3)
      expect(r[0]).toBeTruthy(); expect(r[1]).toBeTruthy(); expect(r[2]).toBeTruthy()
    }
  })

  it('CompareOverlay 跟随语言渲染三段标签（EN 模式显示 Unpack/Compare/Tree）', async () => {
    state.busy = true
    state.reporting = false
    state.jobProgress = { status: 'RUNNING', phase: 'diffing', progress: 70, message: 'msg' }
    setLocale('en-US')
    const w = mount(CompareOverlay)
    const labels = w.findAll('.cmp-step').map(s => s.text().trim())
    expect(labels).toEqual(['Unpack', 'Compare', 'Tree'])
    setLocale('zh-CN')
    await w.vm.$nextTick()
    expect(w.findAll('.cmp-step').map(s => s.text().trim())).toEqual(['解包', '比对', '出树'])
    w.unmount()
  })

  it('渐进迁移护栏：zh-CN 的每个 key 在 en-US 都有非空译文（防漏译）', () => {
    const keys = allKeys()
    expect(keys.length).toBeGreaterThanOrEqual(200)
    setLocale('en-US')
    const missing = keys.filter(k => !t(k) || t(k) === k)
    expect(missing, `en-US 漏译 key：${missing.join(', ')}`).toEqual([])
    setLocale('zh-CN')
    const missingZh = keys.filter(k => !t(k))
    expect(missingZh).toEqual([])
  })

  it('批次2 组件跟随语言：快捷键/下载管理/成本闸门 EN 渲染', async () => {
    setLocale('en-US')
    const sh = mount(ShortcutHelp, { attachTo: document.body })
    expect(sh.text()).toContain('Keyboard Shortcuts')
    expect(sh.text()).toContain('Start compare')
    sh.unmount()

    state.exportRecords = []
    state.syncExports = []
    const dl = mount(Downloads, { attachTo: document.body })
    expect(dl.text()).toContain('Downloads')
    expect(dl.text()).toContain('Sync Exports')
    expect(dl.text()).toContain('Last 7 days')
    dl.unmount()

    state.costGate = { action: 'report', estimate: 90000, threshold: 20000 }
    const cg = mount(CostGateDialog, { attachTo: document.body })
    expect(cg.text()).toContain('AI Cost Estimate Confirmation')
    expect(cg.text()).toContain('AI Report')
    cg.unmount()
    state.costGate = null

    setLocale('zh-CN')
    const shZh = mount(ShortcutHelp, { attachTo: document.body })
    expect(shZh.text()).toContain('快捷键速查')
    shZh.unmount()
  })

  it('批次3 组件跟随语言：路径栏 / 面包屑 / 信息面板 EN 渲染', async () => {
    setLocale('en-US')
    state.job = { mode: 'package', tree: [], stats: null }
    const pb = mount(PathBar, { props: { path: '' }, attachTo: document.body })
    expect(pb.text()).toContain('No file selected')
    pb.unmount()

    const pbc = mount(PathBreadcrumb, { props: { modelValue: '' }, attachTo: document.body })
    expect(pbc.text()).toContain('No path set')
    pbc.unmount()

    // 隔离：jsdom 视口 1024 ≤ 自动收起阈值 1080，需显式写偏好否则智能分析栏被收起、tab 不渲染
    try { localStorage.setItem('bempdiff.analysisCollapsed', 'false') } catch (_) {}
    state.aiPanelCollapsed = false
    state.aiPanelTab = 'file'
    state.tabs = []
    state.activeKey = null
    const ip = mount(InfoPanel, { attachTo: document.body })
    expect(ip.text()).toContain('Single File')
    expect(ip.text()).toContain('Global Summary')
    expect(ip.text()).toContain('Breaking')
    ip.unmount()

    setLocale('zh-CN')
    const pbZh = mount(PathBar, { props: { path: '' }, attachTo: document.body })
    expect(pbZh.text()).toContain('未选择文件')
    pbZh.unmount()
  })

  it('批次4 组件跟随语言：引导步骤 / 报告预览 EN 渲染', async () => {
    setLocale('en-US')
    const g = mount(GuideOverlay, { attachTo: document.body })
    // 引导层默认不弹出（done/seen 已记录）；经 HelpDoc 同款「开始引导」事件强制打开
    window.dispatchEvent(new Event('bempdiff:start-guide'))
    await g.vm.$nextTick()
    expect(g.text()).toContain('Welcome to BempDiff')
    expect(g.text()).toContain('Next')
    expect(g.text()).toContain('Skip')
    g.unmount()

    state.job = { jobId: 'J1', stats: null }
    state.reportMd = ''
    state.config = { aiEnabled: false }
    const rp = mount(ReportPreview, { props: { visible: true }, attachTo: document.body })
    expect(rp.text()).toContain('Difference Analysis Report')
    expect(rp.text()).toContain('No report generated yet.')
    rp.unmount()

    setLocale('zh-CN')
    const gZh = mount(GuideOverlay, { attachTo: document.body })
    window.dispatchEvent(new Event('bempdiff:start-guide'))
    await gZh.vm.$nextTick()
    expect(gZh.text()).toContain('欢迎使用 BempDiff')
    gZh.unmount()
  })

  it('批次5 组件跟随语言：工具栏与强调色 EN 渲染', async () => {
    setLocale('en-US')
    expect(t('acc.purple')).toBe('Elegant Purple')
    for (const a of ACCENTS) {
      expect(t(a.nameKey), `强调色 ${a.key} EN 缺失`).not.toBe(a.nameKey)
    }
    state.leftType = 'package'
    state.job = { status: 'DONE' }
    state.reportMd = ''
    state.compareHistory = []
    state.exportRecords = []
    const w = mount(ToolBar, { props: { onOpenConfig: () => {}, onOpenReport: () => {} }, attachTo: document.body })
    expect(w.find('button[title="Use a single war/jar package as input (default)"]').exists()).toBe(true)
    expect(w.find('button[title="Export diff report or diff assets"]').exists()).toBe(true)
    expect(w.find('button[title="Export diff report or diff assets"]').attributes('aria-label')).toBe('Export diff report or diff assets')
    w.unmount()

    setLocale('zh-CN')
    const wZh = mount(ToolBar, { props: { onOpenConfig: () => {}, onOpenReport: () => {} }, attachTo: document.body })
    expect(wZh.find('button[title="以单个 war/jar 包作为输入（默认）"]').exists()).toBe(true)
    wZh.unmount()
  })

  it('批次6 组件跟随语言：关于/状态栏 EN 渲染', async () => {
    setLocale('en-US')
    const { api } = await import('../api/client')
    const spy = vi.spyOn(api, 'checkUpdate').mockResolvedValue({
      ok: true, current: '1.0.0', upToDate: true,
      latest: { tag: 'v1.0.0', url: '' }, repo: 'acme/bempDiff', message: 'ok'
    })
    const ab = mount(About, { attachTo: document.body })
    await new Promise(r => setTimeout(r, 0))
    expect(ab.text()).toContain('Check for Updates')
    expect(ab.text()).toContain('Already up to date - no update needed')
    expect(ab.text()).toContain('GitHub Access Token')
    ab.unmount()
    spy.mockRestore()

    state.exporting = true
    const sb = mount(StatusBar, { attachTo: document.body })
    expect(sb.text()).toContain('Exporting')
    sb.unmount()
    state.exporting = false

    setLocale('zh-CN')
    const sbZh = mount(StatusBar, { attachTo: document.body })
    expect(sbZh.text()).toContain('反编译引擎')
    sbZh.unmount()
  })

  it('P2-8 历史 AI 任务标题随语言切换实时求值（不留在创建时语言）', async () => {
    setLocale('zh-CN')
    state.aiPanelTab = 'console'
    state.aiTasks = [{ id: 'ai-h', category: 'risk', title: '整体风险分析', titleKey: 'cat.risk', titleParams: {}, prompt: '', thinking: [], answer: '', status: 'done', thinkingCollapsed: true }]
    state.aiActiveTaskId = 'ai-h'
    state.job = { status: 'DONE' }
    const w = mount(AiConsole, { attachTo: document.body })
    expect(w.text()).toContain('整体风险分析')
    setLocale('en-US')
    await nextTick()
    expect(w.text()).toContain('Overall Risk Analysis')
    expect(w.text()).not.toContain('整体风险分析')
    w.unmount()
  })

  it('批次7 组件跟随语言：AI 控制台/分析类别 EN 渲染', async () => {
    setLocale('en-US')
    state.aiPanelTab = 'console'
    state.aiTasks = [{ id: 'ai-1', category: 'risk', title: 'Overall Risk Analysis', prompt: '', thinking: [], answer: '', status: 'done', thinkingCollapsed: true }]
    state.aiActiveTaskId = 'ai-1'
    state.job = { status: 'DONE' }
    const w = mount(AiConsole, { attachTo: document.body })
    expect(w.text()).toContain('New Analysis')
    expect(w.text()).toContain('Overall Risk Analysis')
    expect(w.find('button[title="Stop current analysis"]').exists() || w.find('button[title="Re-analyze"]').exists()).toBe(true)
    w.unmount()

    setLocale('zh-CN')
    const wZh = mount(AiConsole, { attachTo: document.body })
    expect(wZh.text()).toContain('新建分析')
    wZh.unmount()
  })

  it('批次8 帮助文档外壳 EN 渲染（正文内容仍为中文，属独立批次）', async () => {
    setLocale('en-US')
    const w = mount(HelpDoc, { attachTo: document.body })
    expect(w.text()).toContain('Help Documentation')
    expect(w.text()).toContain('Basics')
    expect(w.text()).toContain('Advanced')
    expect(w.text()).toContain('FAQ')
    expect(w.find('input[aria-label="Search help documentation"]').exists()).toBe(true)
    w.unmount()
    setLocale('zh-CN')
    const wZh = mount(HelpDoc, { attachTo: document.body })
    expect(wZh.text()).toContain('帮助文档')
    expect(wZh.text()).toContain('基础功能')
    wZh.unmount()
  })

  it('批次9 差异树/右键菜单 EN 渲染', async () => {
    setLocale('en-US')
    state.config = defaultConfig()
    state.job = {
      jobId: 'j1',
      mode: 'package',
      stats: { added: 1, deleted: 2, modified: 3, unchanged: 4, bizChanged: 0, jarChanged: 0, total: 10 },
      tree: [{ key: 'a/B.class', status: 'MODIFIED', fileClass: 'CLASS', layer: 'L1', size: 10, sha: 'x' }]
    }
    state.aiClassify = {}
    state.excludedKeys = {}
    state.ignoreRules = []
    state.archiveChildren = {}
    state.expandedArchives = {}
    state.treeLocate = null
    state.propertyNode = null
    state.baseFolder = null
    state.activeKey = null
    state.treePanelCollapsed = false
    const w = mount(DiffTree, { props: { panelWidth: null }, attachTo: document.body })
    expect(w.text()).toContain('Diff Tree')
    expect(w.text()).toContain('L1 Business Code')
    expect(w.find('button[title="View mode and sorting"]').exists()).toBe(true)
    // 视图/排序下拉为点击展开，展开后校验 EN 文案
    await w.find('button[title="View mode and sorting"]').trigger('click')
    expect(w.text()).toContain('View mode')
    expect(w.text()).toContain('Tree view')
    w.unmount()

    setLocale('zh-CN')
    const wZh = mount(DiffTree, { props: { panelWidth: null }, attachTo: document.body })
    expect(wZh.text()).toContain('差异文件树')
    expect(wZh.text()).toContain('L1 业务码')
    wZh.unmount()
  })

  it('批次11 帮助正文双语 + EN 检索命中（searchHelp 双语拼装）', async () => {
    const { searchHelp, entryTitle, entryBlocks, HELP_ENTRIES } = await import('../lib/helpContent')
    setLocale('en-US')
    const w = mount(HelpDoc, { attachTo: document.body })
    // 默认首条 = 术语对照，EN 下应为英文标题与英文正文
    expect(entryTitle(HELP_ENTRIES[0])).toBe('Glossary: Chinese - English')
    expect(w.text()).toContain('Glossary: Chinese - English')
    expect(w.text()).toContain('Term list (Chinese - English: description)')
    expect(w.text()).toContain('Tips')
    w.unmount()

    // 英文关键词检索（EN 界面搜英文）
    expect(searchHelp('unpack').length).toBeGreaterThan(0)
    expect(searchHelp('proxy').length).toBeGreaterThan(0)
    expect(searchHelp('two columns').length).toBeGreaterThan(0)
    // 中文关键词仍可命中（双语拼装）
    expect(searchHelp('解包').length).toBeGreaterThan(0)
    expect(entryBlocks(HELP_ENTRIES[0])[0].text).toContain('Term list')

    setLocale('zh-CN')
    const wZh = mount(HelpDoc, { attachTo: document.body })
    expect(wZh.text()).toContain('术语中英对照')
    wZh.unmount()
  })

  it('帮助文档含「术语中英对照」条目', async () => {
    const w = mount(HelpDoc)
    expect(w.text()).toContain('术语中英对照')
    expect(w.text()).toContain('Diff Tree')
    w.unmount()
  })
})
