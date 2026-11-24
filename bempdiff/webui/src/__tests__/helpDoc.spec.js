// 帮助文档（HelpDoc）单测：数据模型结构 / 检索 / 版本同步 / 组件渲染与交互。
// 覆盖任务要求：tab 显示、内容检索、版本同步、层级化结构、至少 3 个功能模块示例。
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  CATEGORIES, HELP_ENTRIES, BLOCK_TYPES, searchHelp, validateHelp,
  contentMatchesVersion, PRODUCT_NAME, APP_VERSION, DOC_VERSION
} from '../lib/helpContent'
import HelpDoc from '../components/HelpDoc.vue'
// 安装包版本唯一来源（Electron app.getVersion() 读取的就是 dev-shell/package.json）。
// 用静态 JSON import 而非运行期 new URL(import.meta.url)：Vitest 的 import.meta.url 是 http 协议，
// readFileSync 会报 "URL must be of scheme file"。
import installerPkg from '../../../dev-shell/package.json'
const INSTALLER_VERSION = installerPkg.version

describe('帮助内容 数据模型', () => {
  it('结构自检通过（无非法块/缺字段/id 重复）', () => {
    expect(validateHelp(HELP_ENTRIES)).toEqual([])
  })

  it('分类体系完整且覆盖基础/高级/FAQ/技巧，且至少 3 个功能模块', () => {
    const keys = CATEGORIES.map((c) => c.key)
    ;['basics', 'advanced', 'faq', 'tips'].forEach((k) => expect(keys).toContain(k))
    expect(HELP_ENTRIES.length).toBeGreaterThanOrEqual(8) // 远多于 3 个功能模块示例
  })

  it('每个条目具备层级化字段（id/title/intro/blocks），id 全局唯一', () => {
    const ids = new Set()
    for (const e of HELP_ENTRIES) {
      expect(e.id).toBeTruthy()
      expect(e.title).toBeTruthy()
      expect(typeof e.intro).toBe('string')
      expect(Array.isArray(e.blocks)).toBe(true)
      expect(ids.has(e.id)).toBe(false)
      ids.add(e.id)
      for (const b of e.blocks) expect(BLOCK_TYPES).toContain(b.type)
    }
  })

  it('格式化常量完备（产品名/版本/内容版本）', () => {
    expect(PRODUCT_NAME).toBe('BempDiff')
    expect(APP_VERSION).toBeTruthy()
    expect(DOC_VERSION).toBeTruthy()
  })

  it('浏览器模式回落的产品版本与安装包版本一致（防止关于页显示版本回退）', () => {
    expect(INSTALLER_VERSION).toMatch(/^\d+\.\d+\.\d+/)
    expect(APP_VERSION).toBe(INSTALLER_VERSION)
  })
})

describe('帮助检索 searchHelp', () => {
  it('按关键词命中条目（导出/解包/AI），大小写不敏感', () => {
    const ids = (q) => searchHelp(q).map((e) => e.id)
    expect(ids('导出')).toContain('export')
    expect(ids('解包')).toContain('unpack')
    expect(ids('ai').length).toBeGreaterThan(0)
    expect(ids('导出')).toContain('export')
    expect(ids('拖拽')).toContain('drop')
    expect(ids('自动填入')).toContain('drop')
  })

  it('空白查询返回空（不误全量返回）', () => {
    expect(searchHelp('   ')).toEqual([])
    expect(searchHelp('')).toEqual([])
  })

  it('无匹配返回空数组', () => {
    expect(searchHelp('不存在的关键词xyz')).toEqual([])
  })
})

describe('版本同步 contentMatchesVersion', () => {
  it('默认内容与产品主版本一致 → 同步（不提示滞后）', () => {
    expect(contentMatchesVersion(DOC_VERSION, APP_VERSION)).toBe(true)
  })
  it('主版本一致视为同步；不一致视为滞后', () => {
    expect(contentMatchesVersion('0.1', '0.1.2')).toBe(true)
    expect(contentMatchesVersion('0.2', '0.1.9')).toBe(false)
    expect(contentMatchesVersion('1.0', '0.1')).toBe(false)
  })
})

describe('HelpDoc 组件（tab 显示 / 内容检索 / 导航）', () => {
  it('渲染标题、版本标注与分类目录，默认展示首个条目内容', () => {
    const w = mount(HelpDoc)
    expect(w.text()).toContain('帮助文档')
    expect(w.text()).toContain('BempDiff v')
    expect(w.text()).toContain('基础功能')
    expect(w.text()).toContain('高级功能')
    expect(w.text()).toContain('常见问题（FAQ）')
    // 默认展示第一个条目标题（basics.compare）
    expect(w.find('.hm-h').text()).toBe(HELP_ENTRIES[0].title)
    w.unmount()
  })

  it('搜索过滤目录，点击命中项后详情随之切换', async () => {
    const w = mount(HelpDoc)
    const input = w.find('input[type="text"]')
    await input.setValue('导出')
    await nextTick()
    // 目录只保留命中「导出」的条目；详情变为对应条目（active 因命中唯一而切换逻辑在点击处）
    const items = w.findAll('.hm-item')
    expect(items.length).toBeGreaterThan(0)
    expect(items.map((i) => i.text()).join(' ')).toContain('导出差异资产')
    // 点击命中条目 → 详情标题同步
    const target = items.find((i) => i.text().includes('导出差异资产'))
    await target.trigger('click')
    await nextTick()
    expect(w.find('.hm-h').text()).toBe('导出差异资产')
    w.unmount()
  })

  it('无匹配时给出友好空态提示', async () => {
    const w = mount(HelpDoc)
    await w.find('input[type="text"]').setValue('不存在的关键词xyz')
    await nextTick()
    expect(w.text()).toContain('未找到与“不存在的关键词xyz”相关的内容')
    w.unmount()
  })
})