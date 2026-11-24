/**
 * T06230 CSV 导出与公式注入防护。
 *
 * Excel/WPS 打开 CSV 时，以 = + - @ 或 Tab/CR 开头的单元格会被当作公式执行；
 * 这里在导出前统一加单引号前缀（OWASP CSV Injection 推荐做法），再做 RFC 4180 引号转义。
 * 注意：前缀只加在「单元格文本」上，不改变原始差异数据，导出后仍可在文本编辑器还原。
 */

const FORMULA_PREFIX = /^[=+\-@\t\r]/

export function safeCsvCell(value) {
  let s = value == null ? '' : String(value)
  if (FORMULA_PREFIX.test(s)) s = "'" + s
  if (/[",\n\r]/.test(s)) s = '"' + s.replace(/"/g, '""') + '"'
  return s
}

export function buildDiffCsv(nodes) {
  const rows = [['path', 'status', 'type']]
  for (const n of Array.isArray(nodes) ? nodes : []) {
    if (!n || n.type === 'folder' || n.isDir) continue
    rows.push([n.key || n.path || n.name || '', n.status || '', n.type || ''])
  }
  return rows.map(r => r.map(safeCsvCell).join(',')).join('\r\n')
}

export function downloadCsv(filename, csv) {
  if (typeof document === 'undefined' || typeof URL === 'undefined' || typeof Blob === 'undefined') return false
  const bom = '\ufeff'
  const blob = new Blob([bom + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return true
}
