// 关于（About）单测：版本显示 / 自动+手动检查更新 / 版本对比 / 手动更新指引 / GitHub 令牌配置。
// 后端 api.checkUpdate / api.putConfig 被 mock；组件挂载时自动检测一次，随后可点按钮手动重查。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { resolveAppVersion } from '../lib/helpContent'
import { state } from '../store'

// vi.mock 工厂会被提升到文件顶部，因此 mock 函数必须用 vi.hoisted 声明，否则工厂内引用报「before initialization」。
const { checkUpdate, putConfig } = vi.hoisted(() => ({ checkUpdate: vi.fn(), putConfig: vi.fn() }))
vi.mock('../api/client', () => ({ api: { checkUpdate, putConfig } }))

import About from '../components/About.vue'

beforeEach(() => {
  // mockReset 同时清掉上一条用例残留的 mockResolvedValueOnce 队列，避免跨用例串值
  checkUpdate.mockReset()
  checkUpdate.mockResolvedValue({
    ok: true, current: resolveAppVersion(), upToDate: true,
    latest: { tag: 'v1.0.0', name: '', url: 'https://github.com/acme/bempDiff/releases/tag/v1.0.0', publishedAt: '', body: '' },
    repo: 'acme/bempDiff', message: '当前已是最新版本'
  })
  putConfig.mockReset()
  putConfig.mockImplementation((cfg) => Promise.resolve({ ...cfg, hasGithubToken: !!cfg.githubToken }))
  state.config = null
})

describe('About 组件', () => {
  it('渲染「关于」入口、当前版本号与检查更新按钮', async () => {
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('关于')
    expect(w.text()).toContain('当前版本 v' + resolveAppVersion())
    expect(w.find('button').exists()).toBe(true)
    w.unmount()
  })

  it('挂载即自动检测一次（自动检测机制），且展示已最新', async () => {
    const w = mount(About)
    expect(checkUpdate).toHaveBeenCalledTimes(1)
    expect(checkUpdate).toHaveBeenCalledWith({ current: resolveAppVersion() })
    await flushPromises()
    expect(w.text()).toContain('当前已是最新版本，无需更新')
    w.unmount()
  })

  it('发现新版时展示版本对比与下载入口（当前 v旧 → 最新 tag）', async () => {
    checkUpdate.mockResolvedValueOnce({
      ok: true, current: resolveAppVersion(), upToDate: false,
      latest: { tag: 'v9.9.9', name: '大版本', url: 'https://github.com/acme/bempDiff/releases/tag/v9.9.9', publishedAt: '2026-08-31T00:00:00Z', body: '# 更新日志\n- 修复若干问题' },
      repo: 'acme/bempDiff', message: '发现新版本 v9.9.9，请前往 Release 页下载安装包'
    })
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('发现新版本，建议立即更新')
    expect(w.text()).toContain('当前 v' + resolveAppVersion())
    expect(w.text()).toContain('v9.9.9')
    const link = w.find('a[target="_blank"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('https://github.com/acme/bempDiff/releases/tag/v9.9.9')
    w.unmount()
  })

  it('检查失败时优雅降级并展示后端 message（不抛错）', async () => {
    checkUpdate.mockResolvedValueOnce({ ok: false, message: '检查更新失败：HTTP 403 —— 多为 GitHub API 限流。可设置环境变量 GITHUB_TOKEN 提升额度，稍后重试。', lastError: 'HTTP 403 - API rate limit exceeded for 1.2.3.4' })
    checkUpdate.mockResolvedValueOnce({ ok: false, message: '检查更新失败：HTTP 401' })
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('多为 GitHub API 限流')
    // P2-7：原始技术错误默认不展开，点「查看技术详情」后才展示，且提供复制入口
    expect(w.text()).not.toContain('API rate limit exceeded')
    const detailBtn = w.findAll('button').find((b) => b.text().includes('查看技术详情'))
    expect(detailBtn).toBeTruthy()
    await detailBtn.trigger('click')
    expect(w.text()).toContain('API rate limit exceeded')
    expect(w.findAll('button').some((b) => b.text().includes('复制详情'))).toBe(true)
    w.unmount()
  })

  it('手动点击「检查更新」再次触发后端查询', async () => {
    checkUpdate.mockResolvedValueOnce({ ok: true, current: resolveAppVersion(), upToDate: null, latest: null, repo: 'acme/bempDiff', message: '仓库还没有发布任何 Release' })
    checkUpdate.mockResolvedValueOnce({ ok: true, current: resolveAppVersion(), upToDate: true, latest: { tag: 'v1.0.0', url: '' }, repo: 'acme/bempDiff', message: '当前已是最新版本' })
    const w = mount(About)
    await flushPromises() // 自动检测第 1 次
    expect(w.text()).toContain('仓库还没有发布任何 Release')
    await w.find('button').trigger('click')
    await nextTick()
    await flushPromises() // 手动第 2 次
    expect(checkUpdate).toHaveBeenCalledTimes(2)
    expect(w.text()).toContain('当前已是最新版本，无需更新')
    w.unmount()
  })

  it('「人工设置指引」可展开并包含仓库地址与关键步骤', async () => {
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('acme/bempDiff')
    // 默认收起；点击展开后出现操作步骤关键词
    expect(w.find('.about-guide').exists()).toBe(false)
    const btns = w.findAll('button')
    const toggle = btns.find((b) => b.text().includes('人工设置指引'))
    await toggle.trigger('click')
    await nextTick()
    expect(w.find('.about-guide').exists()).toBe(true)
    expect(w.text()).toContain('BEMPDIFF_GITHUB_REPO')
    expect(w.text()).toContain('GITHUB_TOKEN')
    w.unmount()
  })

  it('填写并保存 GitHub 访问令牌（提交 githubToken + persistGithubToken）', async () => {
    state.config = { persistGithubToken: false, hasGithubToken: false }
    const w = mount(About)
    await flushPromises()
    // 输入令牌并勾选「记住令牌」
    await w.find('input[type="password"]').setValue('ghp_abc')
    await w.find('#aboutPersistToken').setValue(true)
    await nextTick()
    const saveBtn = w.findAll('button').find((b) => b.text().trim() === '保存')
    await saveBtn.trigger('click')
    await nextTick()
    await flushPromises()
    expect(putConfig).toHaveBeenCalledTimes(1)
    const sent = putConfig.mock.calls[0][0]
    expect(sent.githubToken).toBe('ghp_abc')
    expect(sent.persistGithubToken).toBe(true)
    expect(w.text()).toContain('GitHub 令牌已保存')
    expect(state.config.hasGithubToken).toBe(true)
    w.unmount()
  })

  it('令牌已持久化时预填明文并可展示「已配置」', async () => {
    state.config = { persistGithubToken: true, githubToken: 'ghp_persisted', hasGithubToken: true }
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('已配置')
    expect(w.find('input[type="password"]').element.value).toBe('ghp_persisted')
    w.unmount()
  })

  it('后端返回 cached=true 时展示缓存命中提示', async () => {
    checkUpdate.mockResolvedValueOnce({
      ok: true, current: resolveAppVersion(), upToDate: true, cached: true,
      latest: { tag: 'v1.0.0', url: '' }, repo: 'acme/bempDiff', message: '当前已是最新版本'
    })
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('本次为缓存结果')
    w.unmount()
  })
})

// T01726 回归护栏：「立即更新」按钮只在确有新版（upToDate===false）时显示。
// 缺陷背景：按钮原先只看 downloadUrl && canOneClick()，已最新用户点击会把旧版安装回本机（降级）。
describe('About 一键更新按钮显隐（T01726 降级防护）', () => {
  const mkResult = (upToDate) => ({
    ok: true, current: resolveAppVersion(), upToDate,
    latest: {
      tag: 'v9.9.9', name: '', url: 'https://github.com/acme/bempDiff/releases/tag/v9.9.9',
      downloadUrl: 'https://example.com/BempDiff-9.9.9-setup.exe', publishedAt: '', body: ''
    },
    repo: 'acme/bempDiff', message: ''
  })

  // 模拟桌面壳具备一键更新能力（组件通过 window.bempdiff.downloadInstall 探测）
  function stubShell() {
    window.bempdiff = { downloadInstall: vi.fn() }
  }
  function unstubShell() { delete window.bempdiff }

  it('upToDate=false（确有新版）时显示「立即更新」', async () => {
    stubShell()
    checkUpdate.mockResolvedValueOnce(mkResult(false))
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('发现新版本，建议立即更新')
    const btn = w.findAll('button').find((b) => b.text().includes('立即更新'))
    expect(btn).toBeDefined()
    unstubShell()
    w.unmount()
  })

  it('upToDate=true（已最新）时即使有 downloadUrl 也不显示「立即更新」', async () => {
    stubShell()
    checkUpdate.mockResolvedValueOnce(mkResult(true))
    const w = mount(About)
    await flushPromises()
    expect(w.text()).toContain('当前已是最新版本，无需更新')
    const btn = w.findAll('button').find((b) => b.text().includes('立即更新'))
    expect(btn).toBeUndefined()
    unstubShell()
    w.unmount()
  })

  it('upToDate=null（版本未知）时不显示「立即更新」，保留前往下载页链接', async () => {
    stubShell()
    checkUpdate.mockResolvedValueOnce(mkResult(null))
    const w = mount(About)
    await flushPromises()
    const btn = w.findAll('button').find((b) => b.text().includes('立即更新'))
    expect(btn).toBeUndefined()
    const link = w.find('a[target="_blank"]')
    expect(link.exists()).toBe(true)
    unstubShell()
    w.unmount()
  })
})