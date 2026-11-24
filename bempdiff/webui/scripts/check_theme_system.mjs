// TH-02 验收③：System 偏好实时跟随 OS，且不覆盖用户显式选择的静态主题。
// 仅验证 store 的属性落盘与监听逻辑；真实 OS 切换/无闪白仍需真机录屏。
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
global.window = dom.window
global.document = dom.window.document
global.localStorage = dom.window.localStorage

let systemDark = false
const listeners = []
window.matchMedia = (q) => ({
  media: q,
  get matches() { return systemDark && q.includes('prefers-color-scheme: dark') },
  addEventListener(_evt, fn) { listeners.push(fn) },
  removeEventListener() {}
})
global.fetch = async () => ({
  ok: true,
  status: 200,
  statusText: 'OK',
  headers: { get: () => 'application/json' },
  json: async () => ({})
})

const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const store = await vite.ssrLoadModule('/src/store.js')
const root = document.documentElement
const fail = []

store.applyTheme('system')
if (root.getAttribute('data-theme') !== 'light') fail.push('system initial light')
if (root.getAttribute('data-token-pref') !== 'system') fail.push('system pref initial')

await store.init()
systemDark = true
for (const fn of listeners) fn({ matches: true })
if (root.getAttribute('data-theme') !== 'dark') fail.push(`system live dark=${root.getAttribute('data-theme')}`)
if (root.style.backgroundColor !== 'rgb(33, 37, 41)') fail.push(`system live dark bg=${root.style.backgroundColor}`)

systemDark = false
for (const fn of listeners) fn({ matches: false })
if (root.getAttribute('data-theme') !== 'light') fail.push(`system live light=${root.getAttribute('data-theme')}`)

store.applyTheme('paper')
systemDark = true
for (const fn of listeners) fn({ matches: true })
if (root.getAttribute('data-theme') !== 'paper') fail.push(`explicit paper overridden=${root.getAttribute('data-theme')}`)
if (root.getAttribute('data-token-pref') !== 'paper') fail.push('explicit paper pref')

if (fail.length) {
  console.error('THEME_SYSTEM_FAIL')
  for (const f of fail) console.error(' -', f)
  process.exit(1)
}
console.log(`THEME_SYSTEM_OK listeners=${listeners.length} live=ok explicit-preserved=ok`)
await vite.close()
