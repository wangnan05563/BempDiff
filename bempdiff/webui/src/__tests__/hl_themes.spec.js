// FR-014 语法高亮主题包测试。
// 守护：① validateAll 主题包完整性（≥4 套、key 唯一、13 个 hex 变量合法）；
//       ② hlThemeOf 非法 key 回退 default；
//       ③ setHlTheme 持久化 + 非法回退；loadHlTheme 从 localStorage 恢复；
//       ④ cycleTheme 界面明暗联动（暗界面自动切暗高亮主题，反之亦然）。
import { describe, it, expect, beforeEach } from 'vitest'

// 主 store 为单例（jsdom 环境，localStorage 可用）
import { HL_THEMES, HL_VAR_NAMES, hlThemeOf, isValidTheme, validateAll } from '../lib/hl_themes'
import { applyTheme, cycleTheme, loadHlTheme, setHlTheme, state } from '../store'

const HL_KEY = 'bempdiff.hlTheme'

describe('语法高亮主题包（FR-014）', () => {
  beforeEach(() => {
    try { localStorage.removeItem(HL_KEY) } catch (_) {}
    state.hlTheme = 'default'
    applyTheme('light')
  })

  it('validateAll：内置 4 套主题齐全、key 唯一、13 个 hex 色值合法', () => {
    expect(HL_THEMES.length).toBe(4)
    expect(validateAll()).toBe(true)
    // 亮 2 / 暗 2 的适配约定（FR-014 联动依赖 dark 标记准确）
    expect(HL_THEMES.filter(t => t.dark).length).toBe(2)
    // 变量名覆盖 13 个 token 类别
    expect(HL_VAR_NAMES.length).toBe(13)
  })

  it('isValidTheme：缺变量 / 非法色值 / 非对象均判不合法', () => {
    expect(isValidTheme(null)).toBe(false)
    expect(isValidTheme({ vars: { kw: '#fff' } })).toBe(false)   // 缺大部分变量
    const bad = { ...HL_THEMES[0], vars: { ...HL_THEMES[0].vars, kw: 'red' } }
    expect(isValidTheme(bad)).toBe(false)                        // 非 hex 色值
  })

  it('hlThemeOf：合法 key 返回对应主题，非法 key 回退 default', () => {
    expect(hlThemeOf('github-dark').key).toBe('github-dark')
    expect(hlThemeOf('one-dark').key).toBe('one-dark')
    expect(hlThemeOf('no-such-theme').key).toBe('default')
    expect(hlThemeOf(undefined).key).toBe('default')
  })

  it('setHlTheme：持久化到 localStorage 并更新 state；非法 key 回退 default', () => {
    setHlTheme('one-dark')
    expect(state.hlTheme).toBe('one-dark')
    expect(localStorage.getItem(HL_KEY)).toBe('one-dark')
    setHlTheme('bad-key')
    expect(state.hlTheme).toBe('default')
    expect(localStorage.getItem(HL_KEY)).toBe('default')
  })

  it('loadHlTheme：从 localStorage 恢复上次选择，无记录时保持当前值不改写', () => {
    localStorage.setItem(HL_KEY, 'github-dark')
    expect(loadHlTheme()).toBe('github-dark')
    expect(state.hlTheme).toBe('github-dark')
    // 无持久化记录时不清空 state（恢复语义：仅读取，不产生默认值副作用）
    localStorage.removeItem(HL_KEY)
    expect(loadHlTheme()).toBe('github-dark')
  })

  it('cycleTheme 联动：亮界面配亮主题 → 切暗自动换 github-dark，切到护眼（亮）自动换 default', () => {
    // jsdom 无 matchMedia：system 解析为 light。TH-02 六态：light→dark→midnight→paper→contrast→system。
    applyTheme('light')
    setHlTheme('default') // 亮主题配亮界面：不触发联动
    cycleTheme()          // → dark 界面
    expect(state.theme).toBe('dark')
    expect(state.hlTheme).toBe('github-dark')
    cycleTheme()          // → midnight（仍是暗界面）：保持暗高亮，不反复切换
    expect(state.hlTheme).toBe('github-dark')
    cycleTheme()          // → paper（亮界面）：自动换回亮主题
    expect(state.theme).toBe('paper')
    expect(state.hlTheme).toBe('default')
  })

  it('cycleTheme 联动：暗高亮主题已适配暗界面时不重复切换', () => {
    applyTheme('light')
    setHlTheme('github-dark')
    cycleTheme()          // → dark 界面；当前主题已 dark，保持用户选择
    expect(state.theme).toBe('dark')
    expect(state.hlTheme).toBe('github-dark')
  })
})
