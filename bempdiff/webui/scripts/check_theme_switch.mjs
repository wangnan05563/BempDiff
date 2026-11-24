// TH-02 六主题切换冒烟：在 jsdom 里直接调用 store.applyTheme，校验 DOM 属性/持久化/JS 落盘耗时。
// 说明：耗时只覆盖属性写入 + localStorage，不代表浏览器绘制耗时；绘制验收仍需真机截图。
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
global.window = dom.window
global.document = dom.window.document
global.localStorage = dom.window.localStorage

const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const store = await vite.ssrLoadModule('/src/store.js')
const { applyTheme, resolveTheme, state } = store
const CASES = [
  ['light', 'light', 'rgb(255, 255, 255)', 'rgb(33, 37, 41)'],
  ['dark', 'dark', 'rgb(33, 37, 41)', 'rgb(222, 226, 230)'],
  ['midnight', 'dark', 'rgb(11, 16, 32)', 'rgb(230, 237, 247)'],
  ['paper', 'light', 'rgb(247, 243, 232)', 'rgb(43, 42, 38)'],
  ['contrast', 'dark', 'rgb(0, 0, 0)', 'rgb(255, 255, 255)']
]
const root = document.documentElement
const fail = []
const timings = []
for (const [pref, mode, bg, fg] of CASES) {
  const t0 = performance.now()
  const painted = applyTheme(pref)
  timings.push({ pref, ms: performance.now() - t0 })
  if (painted.tokenTheme !== pref) fail.push(`${pref} tokenTheme=${painted.tokenTheme}`)
  if (painted.mode !== mode) fail.push(`${pref} mode=${painted.mode}`)
  if (root.getAttribute('data-bs-theme') !== mode) fail.push(`${pref} data-bs-theme=${root.getAttribute('data-bs-theme')}`)
  if (root.getAttribute('data-theme') !== pref) fail.push(`${pref} data-theme=${root.getAttribute('data-theme')}`)
  if (root.getAttribute('data-token-theme') !== pref) fail.push(`${pref} data-token-theme=${root.getAttribute('data-token-theme')}`)
  if (root.getAttribute('data-token-pref') !== pref) fail.push(`${pref} data-token-pref=${root.getAttribute('data-token-pref')}`)
  if (root.style.colorScheme !== mode) fail.push(`${pref} color-scheme=${root.style.colorScheme}`)
  if (root.style.backgroundColor !== bg) fail.push(`${pref} bg=${root.style.backgroundColor}`)
  if (root.style.color !== fg) fail.push(`${pref} fg=${root.style.color}`)
  if (localStorage.getItem('bempdiff-theme') !== pref) fail.push(`${pref} persist`)
}
applyTheme('sepia')
if (state.theme !== 'light' || root.getAttribute('data-theme') !== 'light') fail.push('invalid fallback')
applyTheme('system')
if (resolveTheme('system') !== 'light') fail.push('system fallback light (no matchMedia)')

if (fail.length) {
  console.error('THEME_SWITCH_FAIL')
  for (const f of fail) console.error(' -', f)
  process.exit(1)
}
const max = Math.max(...timings.map((t) => t.ms))
console.log(`THEME_SWITCH_OK themes=${CASES.length + 1} maxJsMs=${max.toFixed(1)} persistence=ok fallback=ok`)
await vite.close()
