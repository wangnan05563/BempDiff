// TH-02 六主题矩阵校验：主题注册、Bootstrap 底座映射、文本/焦点对比度。
import { THEMES, THEME_NAMES, THEME_BOOT_MODE, resolveTokenTheme } from '../src/design/tokenRegistry.js'

function parseHex(h) {
  const s = h.replace('#', '')
  if (s.length === 3) return s.split('').map((c) => parseInt(c + c, 16))
  if (s.length === 4) return s.slice(0, 3).split('').map((c) => parseInt(c + c, 16))
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)]
}
function lum(h) {
  const [r, g, b] = parseHex(h).map((v) => {
    const x = v / 255
    return x <= .03928 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4
  })
  return .2126 * r + .7152 * g + .0722 * b
}
function ratio(a, b) {
  const l1 = lum(a), l2 = lum(b)
  return (Math.max(l1, l2) + .05) / (Math.min(l1, l2) + .05)
}
const fail = []
const rows = []
for (const name of ['light', 'dark', 'midnight', 'paper', 'contrast']) {
  const t = THEMES[name]
  const text = ratio(t.text, t.canvas)
  const muted = ratio(t.textMuted, t.canvas)
  const focus = ratio(t.focusRing, t.canvas)
  const onAccent = ratio(t.onAccent, t.accent)
  rows.push({ name, mode: THEME_BOOT_MODE[name], text, muted, focus, onAccent })
  if (text < 4.5) fail.push(`${name} text ${text.toFixed(2)} < 4.5`)
  if (muted < 4.5) fail.push(`${name} textMuted ${muted.toFixed(2)} < 4.5`)
  if (focus < 3) fail.push(`${name} focusRing ${focus.toFixed(2)} < 3`)
  if (onAccent < 4.5) fail.push(`${name} onAccent ${onAccent.toFixed(2)} < 4.5`)
}
if (THEME_NAMES.length !== 6) fail.push(`THEME_NAMES=${THEME_NAMES.length}, expected 6`)
for (const name of ['light', 'dark', 'midnight', 'paper', 'contrast']) {
  if (!THEMES[name]) fail.push(`missing theme ${name}`)
  if (!['light', 'dark'].includes(THEME_BOOT_MODE[name])) fail.push(`${name} boot mode invalid`)
}
if (resolveTokenTheme('system', true) !== 'dark') fail.push('system dark resolve')
if (resolveTokenTheme('system', false) !== 'light') fail.push('system light resolve')

if (fail.length) {
  console.error('THEME_MATRIX_FAIL')
  for (const f of fail) console.error(' -', f)
  for (const r of rows) console.error(r)
  process.exit(1)
}
console.log('THEME_MATRIX_OK themes=6 static=5 contrast=AA')
for (const r of rows) console.log(`  ${r.name.padEnd(9)} mode=${r.mode.padEnd(5)} text=${r.text.toFixed(1)} muted=${r.muted.toFixed(1)} focus=${r.focus.toFixed(1)} onAccent=${r.onAccent.toFixed(1)}`)
