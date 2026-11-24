/**
 * T06226 配置语义 Diff：properties / YAML / XML / JSON。
 *
 * 设计目标：
 * - 不引入第三方解析依赖；YAML 只做安全的缩进子集解析，锚点/别名只做标记，不执行合并；
 * - XML 禁用 DOCTYPE/ENTITY（在正则解析器入口直接失败），只读元素/属性/文本；
 * - JSON 用原生 JSON.parse；键路径对齐忽略对象键顺序；
 * - 密钥类 key 默认脱敏，输出对象中不保留原始值（复制/报告不会带明文）；
 * - 解析失败返回 ok=false + error，由 UI 降级回原始 Diff，不阻塞比对。
 */

const CONFIG_KINDS = {
  properties: ['properties', 'props', 'ini', 'conf', 'cfg', 'config', 'toml'],
  yaml: ['yaml', 'yml'],
  xml: ['xml', 'xsd', 'xsl', 'xslt', 'pom'],
  json: ['json', 'json5']
}

const SECRET_KEY_RE = /(password|passwd|pwd|secret|token|apikey|api[-_]?key|access[-_]?key|private[-_]?key|credential|auth|session|cookie|jwt|salt|signature|passphrase|privatekey)$/i
const PLACEHOLDER_RE = /^(?:\$\{[^}]+\}|\{\{[^}]+\}\}|<[^>]+>|%[A-Za-z_][A-Za-z0-9_]*%?)$/

export function configKindOf(key = '') {
  const ext = String(key).split(/[?#]/)[0].split('.').pop().toLowerCase()
  for (const [kind, exts] of Object.entries(CONFIG_KINDS)) {
    if (exts.includes(ext)) return kind
  }
  return null
}

export function isSecretKey(path = '') {
  const leaf = String(path).split(/[.[\]]/).filter(Boolean).pop() || ''
  return SECRET_KEY_RE.test(leaf.replace(/["']/g, ''))
}

function joinPath(parent, key) {
  const k = String(key)
  if (!parent) return /^[A-Za-z0-9_-]+$/.test(k) ? k : `["${k.replace(/"/g, '\\"')}"]`
  if (/^[A-Za-z0-9_-]+$/.test(k)) return `${parent}.${k}`
  return `${parent}["${k.replace(/"/g, '\\"')}"]`
}

function joinIndex(parent, i) {
  return `${parent}[${i}]`
}

function typeOfScalar(v) {
  if (v == null) return 'null'
  if (typeof v === 'boolean') return 'boolean'
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number'
  if (typeof v === 'string') return PLACEHOLDER_RE.test(v.trim()) ? 'placeholder' : 'string'
  if (Array.isArray(v)) return 'array'
  if (typeof v === 'object') return 'object'
  return 'string'
}

function scalarValue(raw, quoted = false) {
  const s = String(raw == null ? '' : raw).trim()
  if (quoted) {
    if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
      return s.slice(1, -1).replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"')
    }
  }
  if (/^(true|yes|on)$/i.test(s)) return true
  if (/^(false|no|off)$/i.test(s)) return false
  if (/^(null|~)$/i.test(s)) return null
  if (/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:e[+-]?[0-9]+)?$/i.test(s)) return Number(s)
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) return scalarValue(s, true)
  return s
}

function addEntry(map, path, value, extra = {}) {
  if (!path) return
  if (map.has(path)) {
    const n = (map.get(path).duplicates || 0) + 1
    map.set(path, { ...map.get(path), value, duplicates: n, ...extra })
  } else {
    map.set(path, { value, type: typeOfScalar(value), ...extra })
  }
}

// ---------------- properties / ini ----------------
function logicalLines(text) {
  const raw = String(text || '').replace(/\r\n?/g, '\n').split('\n')
  const lines = []
  let buf = ''
  let start = 0
  for (let i = 0; i < raw.length; i++) {
    const line = raw[i]
    if (!buf && !line.trim()) continue
    if (!buf) start = i + 1
    let s = line
    let backslashes = 0
    for (let j = s.length - 1; j >= 0 && s[j] === '\\'; j--) backslashes++
    if (backslashes % 2 === 1) {
      buf += s.slice(0, -1)
      continue
    }
    buf += s
    lines.push({ text: buf, line: start })
    buf = ''
  }
  if (buf) lines.push({ text: buf, line: start })
  return lines
}

function unescapePropertyKey(k) {
  return k.replace(/\\([=: \\\\])/g, '$1')
}

function parseProperties(text) {
  const entries = new Map()
  const duplicates = []
  for (const { text: line } of logicalLines(text)) {
    const s = line.replace(/^\s+/, '')
    if (!s || s.startsWith('#') || s.startsWith('!')) continue
    let sep = -1
    let escaped = false
    for (let i = 0; i < s.length; i++) {
      const c = s[i]
      if (escaped) { escaped = false; continue }
      if (c === '\\') { escaped = true; continue }
      if (c === '=' || c === ':') { sep = i; break }
    }
    if (sep < 0) continue
    const key = unescapePropertyKey(s.slice(0, sep).trim())
    const value = s.slice(sep + 1).trim()
    const path = key
    if (!key) continue
    if (entries.has(path)) duplicates.push(path)
    addEntry(entries, path, value)
  }
  return { entries, duplicates }
}

// ---------------- YAML（安全缩进子集） ----------------
function stripYamlComment(s) {
  let quote = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (quote) {
      if (c === quote) quote = ''
      continue
    }
    if (c === '"' || c === "'") { quote = c; continue }
    if (c === '#' && (i === 0 || /\s/.test(s[i - 1]))) return s.slice(0, i)
  }
  return s
}

function yamlKeyOf(s) {
  const m = /^(.+?):(?:\s|$)/.exec(s)
  if (!m) return null
  return m[1].trim()
}

const MAX_YAML_DEPTH = 64

function parseYaml(text) {
  const entries = new Map()
  const duplicates = []
  const lines = String(text || '').replace(/\r\n?/g, '\n').split('\n')
  // frame: { indent, path, kind: 'map'|'seq', seq }
  const stack = [{ indent: -1, path: '', kind: 'map', seq: 0 }]

  const top = () => stack[stack.length - 1]
  // T06230：YAML 深度限制。安全缩进子集不做别名合并，但极深嵌套仍会放大路径拼接与后续 diff 成本，
  // 超过 64 层直接拒绝解析（UI 降级为原始文本 Diff），避免恶意/畸形配置拖死界面。
  const push = (frame) => {
    if (stack.length >= MAX_YAML_DEPTH + 1) throw new Error('YAML 嵌套层级超过 ' + MAX_YAML_DEPTH + ' 层，已拒绝解析')
    stack.push(frame)
  }

  for (let i = 0; i < lines.length; i++) {
    let raw = lines[i]
    if (!raw.trim() || /^\s*#/.test(raw) || /^\s*---\s*$/.test(raw) || /^\s*\.\.\.\s*$/.test(raw)) continue
    if (/^\t/.test(raw)) throw new Error('YAML 不允许用 Tab 缩进（第 ' + (i + 1) + ' 行）')
    const indent = raw.match(/^ */)[0].length
    let content = stripYamlComment(raw.slice(indent)).trimEnd()
    if (!content) continue
    while (stack.length > 1 && indent <= top().indent) stack.pop()
    const parent = top()

    if (content.startsWith('- ')) {
      const rest = content.slice(2).trim()
      const path = joinIndex(parent.path, parent.seq++)
      if (!rest) {
        addEntry(entries, path, '{}', { type: 'object', empty: true })
        push({ indent, path, kind: 'map', seq: 0 })
        continue
      }
      const inlineKey = yamlKeyOf(rest)
      if (inlineKey != null) {
        const val = rest.slice(inlineKey.length + 1).trim()
        const childPath = joinPath(path, inlineKey)
        if (!val) {
          addEntry(entries, childPath, '{}', { type: 'object', empty: true })
        } else if (val.startsWith('&') || val.startsWith('*')) {
          addEntry(entries, childPath, val, { type: val.startsWith('&') ? 'anchor' : 'alias' })
          if (val.startsWith('&')) push({ indent, path: childPath, kind: 'map', seq: 0 })
        } else {
          addEntry(entries, childPath, scalarValue(val))
        }
        push({ indent, path, kind: 'map', seq: 0 })
        if (val.startsWith('&')) push({ indent, path: childPath, kind: 'map', seq: 0 })
      } else if (rest.startsWith('&') || rest.startsWith('*')) {
        addEntry(entries, path, rest, { type: rest.startsWith('&') ? 'anchor' : 'alias' })
        if (rest.startsWith('&')) push({ indent, path, kind: 'map', seq: 0 })
      } else {
        addEntry(entries, path, scalarValue(rest))
        push({ indent, path, kind: 'map', seq: 0 })
      }
      continue
    }

    const key = yamlKeyOf(content)
    if (key == null) throw new Error('YAML 第 ' + (i + 1) + ' 行缺少冒号')
    const val = content.slice(content.indexOf(':') + 1).trim()
    const path = joinPath(parent.path, key)
    if (entries.has(path)) duplicates.push(path)
    if (!val) {
      addEntry(entries, path, '{}', { type: 'object', empty: true })
      push({ indent, path, kind: 'map', seq: 0 })
      continue
    }
    if (/^[|>][+-]?$/.test(val)) {
      const block = []
      let j = i + 1
      for (; j < lines.length; j++) {
        const l = lines[j]
        if (l.trim() && (l.match(/^ */)[0].length <= indent)) break
        block.push(l.slice(Math.min(l.length, indent + 2)))
      }
      i = j - 1
      addEntry(entries, path, block.join('\n').replace(/\n+$/, ''))
      continue
    }
    if (/^\[/.test(val)) {
      addEntry(entries, path, val, { type: 'array', inline: true })
      continue
    }
    if (/^\{/.test(val)) {
      addEntry(entries, path, val, { type: 'object', inline: true })
      continue
    }
    if (val.startsWith('&')) {
      addEntry(entries, path, '{}', { type: 'anchor', anchor: val.slice(1).trim() })
      push({ indent, path, kind: 'map', seq: 0 })
      continue
    }
    if (val.startsWith('*')) {
      addEntry(entries, path, val, { type: 'alias' })
      continue
    }
    addEntry(entries, path, scalarValue(val))
  }
  return { entries, duplicates }
}

// ---------------- JSON ----------------
function flattenJson(v, path, map) {
  const t = typeOfScalar(v)
  if (t === 'array') {
    if (!v.length) addEntry(map, path, '[]', { type: 'array', empty: true })
    for (let i = 0; i < v.length; i++) flattenJson(v[i], joinIndex(path, i), map)
    return
  }
  if (t === 'object') {
    const keys = Object.keys(v)
    if (!keys.length) addEntry(map, path, '{}', { type: 'object', empty: true })
    for (const k of keys) flattenJson(v[k], joinPath(path, k), map)
    return
  }
  if (!path) addEntry(map, '$', v)
  else addEntry(map, path, v)
}

function parseJson(text) {
  let data
  try {
    data = JSON.parse(String(text || '').trim() || 'null')
  } catch (e) {
    throw new Error(e.message || 'JSON 解析失败')
  }
  const entries = new Map()
  flattenJson(data, '', entries)
  return { entries, duplicates: [] }
}

// ---------------- XML（无外部实体/DTD） ----------------
function decodeXmlText(s) {
  return String(s)
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&amp;/g, '&')
}

function parseXml(text) {
  let src = String(text || '')
  if (/<!DOCTYPE|<!ENTITY/i.test(src)) throw new Error('XML 含 DOCTYPE/ENTITY，已拒绝解析')
  src = src.replace(/<!--[\s\S]*?-->/g, '').replace(/<![CDATA[\s\S]*?]]>/g, '')
  if (/<!DOCTYPE|<!ENTITY/i.test(src)) throw new Error('XML 含 DOCTYPE/ENTITY，已拒绝解析')

  const entries = new Map()
  const root = { name: '#root', children: [], attrs: {}, text: '' }
  const stack = [root]
  const tagRe = /<([A-Za-z_][\w:.-]*)((?:\s+[\w:.-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(?:(\/)|>)|<\/\s*([A-Za-z_][\w:.-]*)\s*>/g
  let lastIndex = 0
  let m
  while ((m = tagRe.exec(src)) !== null) {
    const before = src.slice(lastIndex, m.index).trim()
    if (before) stack[stack.length - 1].text += before
    lastIndex = tagRe.lastIndex
    if (m[4]) {
      // closing tag
      const name = m[4]
      const top = stack.pop()
      if (!top || top.name !== name) throw new Error('XML 标签不配对：</' + name + '>')
      if (stack.length === 0) throw new Error('XML 结构不完整')
      continue
    }
    const name = m[1]
    const self = !!m[3]
    const attrs = {}
    const ar = /([A-Za-z_][\w:.-]*)\s*=\s*("[^"]*"|'[^']*')/g
    let am
    while ((am = ar.exec(m[2] || '')) !== null) attrs[am[1]] = decodeXmlText(am[2].slice(1, -1))
    const node = { name, children: [], attrs, text: '' }
    stack[stack.length - 1].children.push(node)
    if (!self) stack.push(node)
  }
  if (stack.length !== 1) throw new Error('XML 标签未闭合')
  if (!root.children.length) throw new Error('XML 无根元素')

  const seen = new Map()
  function walk(node, parentPath) {
    const local = node.name.includes(':') ? node.name.split(':').pop() : node.name
    const parent = parentPath || local
    const path = parentPath ? joinPath(parentPath, local) : local
    const count = seen.get(path) || 0
    seen.set(path, count + 1)
    const current = count === 0 ? path : `${path}[${count}]`
    for (const [k, raw] of Object.entries(node.attrs || {})) {
      // xmlns 声明只用于命名空间解析，不参与键路径差分；事件属性前缀归一为本地名，
      // 使 <p:server p:name="a"> 与 <q:server q:name="a"> 对齐到同一路径。
      if (/^xmlns(?::|$)/i.test(k)) continue
      const localAttr = k.includes(':') ? k.split(':').pop() : k
      addEntry(entries, `${current}.@${localAttr}`, raw)
    }
    const text = node.text.trim()
    if (node.children.length === 0) {
      if (text) addEntry(entries, `${current}.#text`, decodeXmlText(text))
      else addEntry(entries, current, '', { type: 'string', empty: true })
      return
    }
    if (text) addEntry(entries, `${current}.#text`, decodeXmlText(text))
    for (const c of node.children) walk(c, current)
  }
  for (const c of root.children) walk(c, '')
  return { entries, duplicates: [] }
}

// ---------------- Diff ----------------
function displayValue(entryValue, secret, maskSecrets) {
  if (secret && maskSecrets) return '••••••'
  const v = entryValue
  if (v === null) return 'null'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v == null ? '' : v)
}

export function parseConfig(kind, text) {
  try {
    let res
    if (kind === 'properties') res = parseProperties(text)
    else if (kind === 'yaml') res = parseYaml(text)
    else if (kind === 'json') res = parseJson(text)
    else if (kind === 'xml') res = parseXml(text)
    else throw new Error('不支持的配置类型: ' + kind)
    return { ok: true, kind, entries: res.entries, duplicates: res.duplicates, error: '' }
  } catch (e) {
    return { ok: false, kind, entries: new Map(), duplicates: [], error: e.message || String(e) }
  }
}

function groupOf(path) {
  if (!path) return ''
  const first = path.split(/(?:\.|\[)/)[0]
  return first || path
}

/**
 * 对比两份配置文本。
 * @param {string} kind properties|yaml|xml|json
 * @param {string} oldText
 * @param {string} newText
 * @param {{maskSecrets?: boolean}} opts
 */
export function diffConfig(kind, oldText, newText, opts = {}) {
  const maskSecrets = opts.maskSecrets !== false
  const oldRes = parseConfig(kind, oldText)
  const newRes = parseConfig(kind, newText)
  if (!oldRes.ok || !newRes.ok) {
    return {
      ok: false, kind, changes: [], groups: [], duplicates: [],
      error: oldRes.error || newRes.error || '配置解析失败',
      oldOk: oldRes.ok, newOk: newRes.ok
    }
  }
  const all = new Set([...oldRes.entries.keys(), ...newRes.entries.keys()])
  const changes = []
  for (const path of all) {
    const o = oldRes.entries.get(path)
    const n = newRes.entries.get(path)
    const secret = isSecretKey(path)
    if (!o) {
      changes.push({
        path, group: groupOf(path), change: 'add',
        oldValue: '', newValue: displayValue(n.value, secret, maskSecrets),
        oldType: '', newType: n.type, secret, placeholder: n.type === 'placeholder'
      })
    } else if (!n) {
      changes.push({
        path, group: groupOf(path), change: 'remove',
        oldValue: displayValue(o.value, secret, maskSecrets), newValue: '',
        oldType: o.type, newType: '', secret, placeholder: o.type === 'placeholder'
      })
    } else if (o.type !== n.type) {
      changes.push({
        path, group: groupOf(path), change: 'type',
        oldValue: displayValue(o.value, secret, maskSecrets), newValue: displayValue(n.value, secret, maskSecrets),
        oldType: o.type, newType: n.type, secret,
        placeholder: o.type === 'placeholder' || n.type === 'placeholder'
      })
    } else if (!!o.duplicates !== !!n.duplicates) {
      changes.push({
        path, group: groupOf(path), change: 'duplicate',
        oldValue: displayValue(o.value, secret, maskSecrets), newValue: displayValue(n.value, secret, maskSecrets),
        oldType: o.type, newType: n.type, secret,
        placeholder: o.type === 'placeholder' || n.type === 'placeholder'
      })
    } else if (String(o.value) !== String(n.value)) {
      changes.push({
        path, group: groupOf(path), change: 'change',
        oldValue: displayValue(o.value, secret, maskSecrets), newValue: displayValue(n.value, secret, maskSecrets),
        oldType: o.type, newType: n.type, secret,
        placeholder: o.type === 'placeholder' || n.type === 'placeholder'
      })
    }
  }
  const groupsMap = new Map()
  for (const c of changes) {
    if (!groupsMap.has(c.group)) groupsMap.set(c.group, [])
    groupsMap.get(c.group).push(c)
  }
  const groups = [...groupsMap.entries()].map(([group, list]) => ({ group, changes: list }))
  const stats = {
    total: changes.length,
    add: changes.filter(c => c.change === 'add').length,
    remove: changes.filter(c => c.change === 'remove').length,
    change: changes.filter(c => c.change === 'change').length,
    type: changes.filter(c => c.change === 'type').length,
    duplicate: changes.filter(c => c.change === 'duplicate').length,
    reorder: 0,
    secret: changes.filter(c => c.secret).length
  }
  const duplicates = [...new Set([...oldRes.duplicates, ...newRes.duplicates])]
  return { ok: true, kind, changes, groups, stats, duplicates, error: '' }
}

/** 脱敏后的 Markdown 片段：用于复制/报告附录，永不包含密钥明文。 */
export function configDiffMarkdown(result, fileKey = '') {
  if (!result || !result.ok || !result.changes.length) return ''
  const lines = [
    `### 配置语义变更${fileKey ? '：`' + fileKey + '`' : ''}`,
    '',
    '| 变更 | key 路径 | 旧值 | 新值 |',
    '|---|---|---|---|'
  ]
  const LABEL = { add: '新增', remove: '删除', change: '修改', type: '类型变更', duplicate: '重复 key' }
  for (const c of result.changes) {
    const esc = v => String(v == null ? '' : v).replace(/\|/g, '\\|').replace(/\n/g, '<br>')
    lines.push(`| ${LABEL[c.change] || c.change} | \`${esc(c.path)}\` | ${esc(c.oldValue)} | ${esc(c.newValue)} |`)
  }
  if (result.stats.secret) lines.push('', '> 敏感值已脱敏。')
  return lines.join('\n')
}
