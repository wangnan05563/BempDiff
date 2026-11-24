// @vitest-environment jsdom
// TH-02 六态主题（亮/暗/午夜/护眼/高对比/跟随系统）状态机测试。
// 守护：① applyTheme 持久化偏好、Bootstrap 底座与 Token 主题分别落 DOM；
//       ② 'system' 依赖 matchMedia 解析（jsdom 无 matchMedia → 安全降级 light）；
//       ③ cycleTheme 六态循环；
//       ④ 非法/旧值回退亮色；
//       ⑤ matchMedia 可用时 system 正确解析并实时跟随（动态 import 注入 mock）。
import { describe, it, expect, beforeEach, vi } from 'vitest'

// 主 store 为单例（matchMedia 在模块加载期求值）——jsdom 无 matchMedia，
// 主题模块必须可降级：themeMedia=null 时 system 解析为 light。
import { applyTheme, cycleTheme, resolveTheme, state } from '../store'

const THEME_KEY = 'bempdiff-theme'

describe('六态主题（TH-02）', () => {
  beforeEach(() => {
    try { localStorage.removeItem(THEME_KEY) } catch (_) {}
    applyTheme('light')
  })

  it('applyTheme：持久化偏好 + Bootstrap 底座 + Token 主题同步', () => {
    applyTheme('dark')
    expect(state.theme).toBe('dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-token-theme')).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(document.documentElement.style.backgroundColor).toBe('rgb(33, 37, 41)')

    applyTheme('paper')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('paper')
    expect(document.documentElement.getAttribute('data-token-theme')).toBe('paper')
    expect(document.documentElement.style.backgroundColor).toBe('rgb(247, 243, 232)')
    expect(document.documentElement.style.color).toBe('rgb(43, 42, 38)')

    applyTheme('light')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('resolveTheme：无 matchMedia 环境（jsdom）system 安全降级 light，六主题映射到 Bootstrap 两态', () => {
    expect(resolveTheme('light')).toBe('light')
    expect(resolveTheme('dark')).toBe('dark')
    expect(resolveTheme('midnight')).toBe('dark')
    expect(resolveTheme('paper')).toBe('light')
    expect(resolveTheme('contrast')).toBe('dark')
    expect(resolveTheme('system')).toBe('light')
  })

  it('cycleTheme：亮 → 暗 → 午夜 → 护眼 → 高对比 → 系统 → 亮 循环', () => {
    applyTheme('light')
    for (const expected of ['dark', 'midnight', 'paper', 'contrast', 'system', 'light']) {
      cycleTheme()
      expect(state.theme).toBe(expected)
      expect(localStorage.getItem(THEME_KEY)).toBe(expected)
    }
  })

  it('applyTheme：非法/未知偏好回退亮色并持久化，满足旧主题迁移失败回退', () => {
    applyTheme('sepia')
    expect(state.theme).toBe('light')
    expect(localStorage.getItem(THEME_KEY)).toBe('light')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('matchMedia 可用时：system 按系统亮暗解析（暗系统 → dark）且 OS 变化无刷新跟随', async () => {
    // 动态 import 新模块实例：注入 prefers-color-scheme 的 mock。
    // matches 用可变对象——resolveTheme 每次实时读 themeMedia.matches，
    // 回调参数本身不影响解析（与浏览器真实行为一致：MQL.matches 是活动查询）。
    vi.resetModules()
    const listeners = []
    const mediaObj = { matches: true, addEventListener: (_e, cb) => listeners.push(cb) }
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue(mediaObj))
    try {
      const mod = await import('../store')
      expect(mod.resolveTheme('system')).toBe('dark')
      mod.applyTheme('system')
      expect(mod.state.theme).toBe('system')
      expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark')
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
      // init() 挂载 matchMedia change 监听（网络失败走 catch 兜底，jsdom 下安全）
      await mod.init()
      // 系统切到亮色：活动查询 matches 翻转 + change 回调 → 无刷新切回 light（验收③）
      mediaObj.matches = false
      listeners.forEach(cb => cb(mediaObj))
      expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    } finally {
      vi.unstubAllGlobals()
      vi.resetModules()
    }
  })
})
