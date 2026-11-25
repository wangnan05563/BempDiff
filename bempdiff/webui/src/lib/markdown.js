// 轻量 Markdown -> HTML 渲染器（零依赖，离线可用，适配桌面壳离线打包）。
// 仅覆盖 BempDiff 报告实际使用的语法：标题 / 列表(含嵌套) / 围栏代码(含 ```diff 着色) /
// 表格 / 引用 / 分隔线 / 段落，以及行内 **粗体** `代码` *斜体* [链接](url)。
// 所有外部文本先转义，杜绝 XSS。

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')   // 补齐引号转义，防止链接 URL 经 " 逃逸出 href 属性（XSS）
    .replace(/'/g, '&#39;')
}

// 行内格式（输入已转义）。
function inline(text) {
  let s = text
  // 行内代码（优先，避免内部再被加粗解析）
  s = s.replace(/`([^`]+)`/g, (_, c) => `<code class="md-code">${c}</code>`)
  // 粗体
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 斜体
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  // 链接 [text](url)
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    (_, t, u) => `<a href="${u}" target="_blank" rel="noopener">${t}</a>`)
  return s
}

// 把 diff 围栏内容逐行着色：'- ' 删除 '+' 新增 ' ' 上下文。
function renderDiffLines(body) {
  const lines = body.split('\n')
  const out = []
  for (const ln of lines) {
    const esc = escapeHtml(ln)
    if (ln.startsWith('-') && !ln.startsWith('--- ')) {
      out.push(`<div class="diff-line del">${esc || ' '}</div>`)
    } else if (ln.startsWith('+')) {
      out.push(`<div class="diff-line add">${esc || ' '}</div>`)
    } else if (ln.startsWith('@@') || ln.startsWith('\\')) {
      out.push(`<div class="diff-line meta">${esc}</div>`)
    } else {
      out.push(`<div class="diff-line ctx">${esc || ' '}</div>`)
    }
  }
  return `<div class="md-diff">${out.join('')}</div>`
}

function renderTable(rows) {
  // rows: 含表头 + 分隔符 + 数据行，已去掉首尾 '|'
  const splitRow = (r) => r.replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim())
  const header = splitRow(rows[0])
  const body = rows.slice(2)
  const th = header.map(h => `<th>${inline(escapeHtml(h))}</th>`).join('')
  const trs = body.map(r => {
    const cells = splitRow(r)
    return '<tr>' + cells.map(c => `<td>${inline(escapeHtml(c))}</td>`).join('') + '</tr>'
  }).join('')
  return `<table class="table table-sm md-table"><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table>`
}

export function renderMarkdown(md) {
  if (!md) return ''
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  const html = []
  let i = 0
  let listStack = [] // [{ level, liOpen }] 未闭合的 <ul> 层级与当前 <li> 状态

  // 关闭最深层的 <li>（若还开着），再逐层关闭 <ul> 到指定深度。
  const closeTopLi = () => {
    const top = listStack[listStack.length - 1]
    if (top && top.liOpen) { html.push('</li>'); top.liOpen = false }
  }
  const closeLists = (toDepth = 0) => {
    while (listStack.length > toDepth) {
      closeTopLi()
      html.push('</ul>')
      listStack.pop()
    }
  }

  const openList = (level) => {
    html.push('<ul class="md-ul">')
    listStack.push({ level, liOpen: false })
  }

  while (i < lines.length) {
    const line = lines[i]

    // 围栏代码块：支持 Markdown 规定的 3 个及以上反引号（备注正文含 ``` 时会用更长的围栏）。
    const fence = line.match(/^(`{3,})([\w+-]*)\s*$/)
    if (fence) {
      closeLists()
      const ticks = fence[1].length
      const lang = fence[2] || ''
      const closeRe = new RegExp('^`{' + ticks + ',}\\s*$')
      const buf = []
      i++
      while (i < lines.length && !closeRe.test(lines[i])) {
        buf.push(lines[i]); i++
      }
      if (i < lines.length) i++ // 跳过结束围栏
      if (lang === 'diff') {
        html.push(renderDiffLines(buf.join('\n')))
      } else {
        html.push(`<pre class="md-pre"><code>${escapeHtml(buf.join('\n'))}</code></pre>`)
      }
      continue
    }

    // 标题
    const h = line.match(/^(#{1,6})\s+(.*)$/)
    if (h) {
      closeLists()
      const level = h[1].length
      html.push(`<h${level} class="md-h md-h${level}">${inline(escapeHtml(h[2]))}</h${level}>`)
      i++; continue
    }

    // 分隔线
    if (/^(\s*[-*_]){3,}\s*$/.test(line)) {
      closeLists()
      html.push('<hr class="md-hr">')
      i++; continue
    }

    // 表格（当前行以 | 开头，且下一行是分隔符）
    if (line.trim().startsWith('|') && i + 1 < lines.length &&
        /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      closeLists()
      const buf = [line]
      i++
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        buf.push(lines[i]); i++
      }
      html.push(renderTable(buf))
      continue
    }

    // 引用
    if (/^>\s?/.test(line)) {
      closeLists()
      const buf = []
      while (i < lines.length && /^>\s?/.test(lines[i])) {
        buf.push(lines[i].replace(/^>\s?/, '')); i++
      }
      html.push(`<blockquote class="md-quote">${inline(escapeHtml(buf.join(' ')))}</blockquote>`)
      continue
    }

    // 列表（- 或 * 或 1. ）
    const li = line.match(/^(\s*)[-*]\s+(.*)$/) || line.match(/^(\s*)\d+\.\s+(.*)$/)
    if (li) {
      const indent = li[1].replace(/\t/g, '  ').length
      const level = Math.floor(indent / 2)
      // 层级维护：level >= 当前深度 → 新开一层 <ul>（父级 <li> 保持未闭合，嵌套列表才真正位于父项内部）；
      // 否则先关掉更深层，再关闭当前层的上一个 <li>，追加同级项。
      if (level >= listStack.length) {
        openList(level)
      } else {
        closeLists(level + 1)
        closeTopLi()
      }
      // 裸 <li> 修复：首个列表项（listStack 为空）也会先开 <ul>，不再出现无容器 <li>。
      if (!listStack.length) openList(level)
      html.push(`<li>${inline(escapeHtml(li[2]))}`)
      listStack[listStack.length - 1].liOpen = true
      i++; continue
    }

    // 空行
    if (line.trim() === '') {
      closeLists()
      i++; continue
    }

    // 段落（聚合连续非空非特殊行）
    closeLists()
    const buf = [line]
    i++
    while (i < lines.length && lines[i].trim() !== '' &&
           !/^```/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i]) &&
           !/^>\s?/.test(lines[i]) && !/^\s*[-*]\s+/.test(lines[i]) &&
           !/^\s*\d+\.\s+/.test(lines[i]) &&
           !(lines[i].trim().startsWith('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[i + 1]))) {
      buf.push(lines[i]); i++
    }
    html.push(`<p class="md-p">${inline(escapeHtml(buf.join(' ')))}</p>`)
  }
  closeLists()
  return html.join('\n')
}

// 按 H2 标题关键字抽取章节正文（不含标题行本身）。
export function extractSection(md, keyword) {
  if (!md) return ''
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  let start = -1
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^##\s+(.*)$/)
    if (m && m[1].includes(keyword)) { start = i + 1; break }
  }
  if (start < 0) return ''
  const body = []
  for (let i = start; i < lines.length; i++) {
    if (/^##\s+/.test(lines[i])) break
    body.push(lines[i])
  }
  return body.join('\n').trim()
}
