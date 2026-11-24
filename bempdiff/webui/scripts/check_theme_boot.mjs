import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { THEMES, THEME_BOOT_MODE } from '../src/design/tokenRegistry.js'

const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1]

function boot(pref, systemDark) {
  const attrs = {}, style = {}
  const doc = { documentElement: { setAttribute(k, v) { attrs[k] = v }, removeAttribute(k) { delete attrs[k] }, style } }
  const win = { matchMedia: (q) => ({ matches: systemDark && q.includes('prefers-color-scheme: dark'), media: q }) }
  const store = { getItem: (k) => (k === 'bempdiff-theme' ? pref : null) }
  new Function('window', 'document', 'localStorage', script)(win, doc, store)
  return { attrs, style }
}
function eq(name, actual, expected) {
  if (actual !== expected) throw new Error(`${name}: expected ${expected}, got ${actual}`)
}

// TH-02：5 个静态主题逐一验证首帧 token/Bootstrap 底座/背景/前景/偏好。
for (const name of ['light', 'dark', 'midnight', 'paper', 'contrast']) {
  const t = THEMES[name]
  const r = boot(name, false)
  eq(`${name} data-token-theme`, r.attrs['data-token-theme'], name)
  eq(`${name} data-theme`, r.attrs['data-theme'], name)
  eq(`${name} data-bs-theme`, r.attrs['data-bs-theme'], THEME_BOOT_MODE[name])
  eq(`${name} data-token-pref`, r.attrs['data-token-pref'], name)
  eq(`${name} color-scheme`, r.style.colorScheme, THEME_BOOT_MODE[name])
  eq(`${name} bg`, r.style.backgroundColor, t.canvas)
  eq(`${name} fg`, r.style.color, t.text)
}

// System：OS 暗/亮分别落 dark/light；首次启动无偏好也按 System 解析。
const sysDark = boot('system', true)
eq('system-dark data-bs-theme', sysDark.attrs['data-bs-theme'], 'dark')
eq('system-dark data-token-theme', sysDark.attrs['data-token-theme'], 'dark')
eq('system-dark bg', sysDark.style.backgroundColor, THEMES.dark.canvas)

const sysLight = boot('system', false)
eq('system-light data-bs-theme', sysLight.attrs['data-bs-theme'], 'light')
eq('system-light data-token-theme', sysLight.attrs['data-token-theme'], 'light')
eq('system-light bg', sysLight.style.backgroundColor, THEMES.light.canvas)

const fresh = boot(null, true)
eq('fresh install defaults to system-dark', fresh.attrs['data-token-pref'], 'system')
eq('fresh install token', fresh.attrs['data-token-theme'], 'dark')

const invalid = boot('sepia', false)
eq('invalid pref token', invalid.attrs['data-token-theme'], 'light')
eq('invalid pref mode', invalid.attrs['data-bs-theme'], 'light')

const css = html.indexOf('bootstrap.min.css')
const inline = html.indexOf("localStorage.getItem('bempdiff-theme')")
if (!(inline >= 0 && css >= 0 && inline < css)) throw new Error('inline theme script must precede bootstrap css')
console.log('THEME_BOOT_OK: 6-theme cold-frame assertions passed; inline script precedes bootstrap css')
