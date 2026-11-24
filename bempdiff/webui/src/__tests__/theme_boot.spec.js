// TH-09 / TH-02 首屏主题注入：index.html 首帧注入必须满足
//   ① 内联脚本位于 Bootstrap CSS 之前（样式加载前先定主题/背景）
//   ② 读取与 store.js 相同的偏好 key `bempdiff-theme`
//   ③ 支持 system 的 prefers-color-scheme 同步解析
//   ④ 同时写 data-bs-theme / data-theme / data-token-theme / data-token-pref / color-scheme / 背景与前景
//   ⑤ 六主题首帧背景与 Token Registry 一致；首次无偏好默认 System
// 用最小 fake document 执行 html 内联脚本，模拟冷启动首帧，不依赖 jsdom。
import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const htmlPath = [
  resolve(process.cwd(), 'index.html'),
  resolve(process.cwd(), 'bempdiff/webui/index.html')
].find(existsSync)
const html = readFileSync(htmlPath, 'utf8')
const bootScript = html.match(/<script>([\s\S]*?)<\/script>/)[1]

function bootTheme(pref, systemDark) {
  const attrs = {}
  const style = {}
  const root = {
    setAttribute(k, v) { attrs[k] = v },
    removeAttribute(k) { delete attrs[k] },
    style
  }
  const doc = { documentElement: root }
  const win = { matchMedia: (q) => ({ matches: systemDark && q.includes('prefers-color-scheme: dark'), media: q }) }
  const store = { getItem: (k) => (k === 'bempdiff-theme' ? pref : null) }
  new Function('window', 'document', 'localStorage', bootScript)(win, doc, store)
  return { attrs, style }
}

describe('TH-09/TH-02 首屏主题注入', () => {
  it('暗色偏好冷启动：首帧即暗色背景，不经过白色默认态', () => {
    const { attrs, style } = bootTheme('dark', false)
    expect(attrs['data-bs-theme']).toBe('dark')
    expect(attrs['data-theme']).toBe('dark')
    expect(attrs['data-token-theme']).toBe('dark')
    expect(style.colorScheme).toBe('dark')
    expect(style.backgroundColor).toBe('#212529')
  })

  it('六主题冷启动：Bootstrap 底座、Token 主题、背景/前景均正确', () => {
    const cases = [
      ['light', 'light', '#ffffff', '#212529'],
      ['dark', 'dark', '#212529', '#dee2e6'],
      ['midnight', 'dark', '#0b1020', '#e6edf7'],
      ['paper', 'light', '#f7f3e8', '#2b2a26'],
      ['contrast', 'dark', '#000000', '#ffffff']
    ]
    for (const [name, mode, bg, fg] of cases) {
      const { attrs, style } = bootTheme(name, false)
      expect(attrs['data-bs-theme']).toBe(mode)
      expect(attrs['data-theme']).toBe(name)
      expect(attrs['data-token-theme']).toBe(name)
      expect(attrs['data-token-pref']).toBe(name)
      expect(style.backgroundColor).toBe(bg)
      expect(style.color).toBe(fg)
    }
  })

  it('system 偏好按 OS 暗色/亮色同步解析', () => {
    const dark = bootTheme('system', true)
    expect(dark.attrs['data-bs-theme']).toBe('dark')
    expect(dark.style.backgroundColor).toBe('#212529')
    const light = bootTheme('system', false)
    expect(light.attrs['data-bs-theme']).toBe('light')
    expect(light.style.backgroundColor).toBe('#ffffff')
  })

  it('首次启动无偏好时默认 System', () => {
    const fresh = bootTheme(null, true)
    expect(fresh.attrs['data-token-pref']).toBe('system')
    expect(fresh.attrs['data-token-theme']).toBe('dark')
  })

  it('非法偏好回退亮色', () => {
    const { attrs } = bootTheme('sepia', false)
    expect(attrs['data-token-theme']).toBe('light')
    expect(attrs['data-bs-theme']).toBe('light')
  })

  it('内联主题脚本位于 bootstrap.min.css 之前', () => {
    const scriptIdx = html.indexOf("localStorage.getItem('bempdiff-theme')")
    const cssIdx = html.indexOf('/vendor/bootstrap.min.css')
    expect(scriptIdx).toBeGreaterThan(0)
    expect(cssIdx).toBeGreaterThan(0)
    expect(scriptIdx).toBeLessThan(cssIdx)
  })

  it('注入 data-bs-theme / data-theme / data-token-theme / data-token-pref / color-scheme', () => {
    expect(html).toContain("setAttribute('data-bs-theme', t.mode)")
    expect(html).toContain("setAttribute('data-theme', tokenTheme)")
    expect(html).toContain("setAttribute('data-token-theme', tokenTheme)")
    expect(html).toContain("setAttribute('data-token-pref', pref)")
    expect(html).toContain('style.colorScheme = t.mode')
  })
})
