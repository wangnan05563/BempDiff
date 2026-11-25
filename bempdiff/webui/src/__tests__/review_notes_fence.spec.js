// M-03 回归：备注正文含三反引号时，证据 Markdown 必须用 4+ 围栏并能被渲染器闭合。
import { describe, it, expect } from 'vitest'
import { buildEvidenceMarkdown, codeFenceFor, codeFenceTicks } from '../lib/review_notes'
import { renderMarkdown } from '../lib/markdown'

describe('审阅备注代码围栏', () => {
  it('围栏长度按正文最长反引号串 +1', () => {
    expect(codeFenceTicks('plain')).toBe(3)
    expect(codeFenceTicks('a ``` b')).toBe(4)
    expect(codeFenceFor('a ``` b')).toBe('````text')
  })

  it('证据 Markdown 的 4 反引号围栏能包住正文三反引号', () => {
    const note = { id: 'n1', tag: 'other', text: 'alpha\n```\nbeta', user: 'u', createdAt: '2026-01-01T00:00:00Z' }
    const target = { path: 'A.java', scope: 'file', change: 'modified', before: 'old', after: 'new' }
    const md = buildEvidenceMarkdown('A.java', target, { notes: [note] })
    expect(md).toContain('````text\nalpha\n```\nbeta\n````')
    const html = renderMarkdown(md)
    expect(html).toContain('<pre class="md-pre"><code>alpha\n```\nbeta</code></pre>')
    expect(html).not.toContain('````')
  })
})
