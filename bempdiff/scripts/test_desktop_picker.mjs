// BempDiff 桌面壳桥接（Electron）路由单测。
// 直接 import 真实模块 webui/src/lib/desktop-bridge.js，mock window 全局。
// 运行：node bempdiff/scripts/test_desktop_picker.mjs
import { isElectron, pickPath } from '../webui/src/lib/desktop-bridge.js'

let pass = 0, fail = 0
function check(name, cond) {
  if (cond) pass++
  else { fail++; console.log('  FAIL:', name) }
}
function deepEq(a, b) {
  if (a === b) return true
  if (typeof a !== typeof b) return false
  if (Array.isArray(a)) return a.length === b.length && a.every((x, i) => deepEq(x, b[i]))
  if (a && typeof a === 'object') {
    return Object.keys(a).every(k => deepEq(a[k], b[k])) && Object.keys(b).every(k => k in a)
  }
  return false
}

// 提供 window 全局（Node 里没有），便于测试探测函数读 typeof window !== 'undefined'。
globalThis.window = {}

// 工具：清空 window.bempdiff 桥
function resetBridges() {
  try { delete globalThis.window.bempdiff } catch { globalThis.window.bempdiff = undefined }
}

// 集中执行，避免异步检查漂移
;(async () => {
  // ── A1/A2/A3 isElectron 探测 ─────────────────────────────────────
  resetBridges()
  check('A2 无 window.bempdiff → isElectron=false', isElectron() === false)

  globalThis.window.bempdiff = {}
  check('A3 window.bempdiff 无 pickPath 方法 → isElectron=false', isElectron() === false)

  globalThis.window.bempdiff = { pickPath: () => {} }
  check('A1 window.bempdiff.pickPath 是函数 → isElectron=true', isElectron() === true)

  // ── A6 浏览器（无桌面壳）→ pickPath 返回 null ───────────────────
  resetBridges()
  const r0 = await pickPath({})
  check('A6 浏览器场景 pickPath 返回 null', r0 === null)

  // ── A7 Electron 路由 + 单选参数透传 ─────────────────────────────
  resetBridges()
  let receivedA7 = null
  globalThis.window.bempdiff = { pickPath: async (opts) => { receivedA7 = opts; return '/abs/file.war' } }
  const r1 = await pickPath({ directory: true, multiple: false })
  check('A7 Electron 路由返回桥接值', r1 === '/abs/file.war')
  check('A7 Electron 路由透传 opts(directory:true, multiple:false)', deepEq(receivedA7, { directory: true, multiple: false }))

  // ── A8 Electron 多选 ────────────────────────────────────────────
  resetBridges()
  let receivedA8 = null
  globalThis.window.bempdiff = { pickPath: async (opts) => { receivedA8 = opts; return ['/a.war', '/b.war'] } }
  const r2 = await pickPath({ multiple: true })
  check('A8 Electron 多选透传 opts', deepEq(receivedA8, { directory: false, multiple: true }))
  check('A8 Electron 多选返回数组', Array.isArray(r2) && r2.length === 2)

  // ── A11 Electron 桥接抛错 → pickPath 返回 null，不向上抛 ───────────
  resetBridges()
  globalThis.window.bempdiff = { pickPath: async () => { throw new Error('user cancelled modal') } }
  let caught = null
  const r5 = await pickPath({}).catch(e => { caught = e; return 'thrown' })
  check('A11 Electron 桥接抛错被捕获、pickPath 返回 null', r5 === null && caught === null)

  // ── A12 Electron 桥接返回 null（用户取消）→ 原样返回 null ─────────
  resetBridges()
  globalThis.window.bempdiff = { pickPath: async () => null }
  const r6 = await pickPath({})
  check('A12 Electron 返回 null（取消）时原样返回 null', r6 === null)

  console.log(`desktop picker: PASS=${pass} FAIL=${fail} (total ${pass + fail})`)
  process.exit(fail ? 1 : 0)
})().catch(e => {
  console.log('UNCAUGHT in test runner:', e)
  process.exit(2)
})
