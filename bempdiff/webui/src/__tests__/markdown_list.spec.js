// P3：Markdown 列表语义修复——首个列表项必须有 <ul> 容器，嵌套列表必须位于父 <li> 内部。
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '../lib/markdown'

describe('renderMarkdown 列表结构', () => {
  it('顶层列表项不再输出裸 <li>（必须有 <ul> 容器）', () => {
    const html = renderMarkdown('- alpha\n- beta')
    expect(html.startsWith('<ul class="md-ul">')).toBe(true)
    expect(html).not.toContain('</ul><li>')
    expect((html.match(/<li>/g) || []).length).toBe(2)
    expect((html.match(/<\/li>/g) || []).length).toBe(2)
    expect((html.match(/<ul /g) || []).length).toBe(1)
  })

  it('嵌套列表位于父 <li> 内，同级项正确闭合', () => {
    const html = renderMarkdown('- parent\n  - child-a\n  - child-b\n- sibling').replace(/\n/g, '')
    expect(html).toBe(
      '<ul class="md-ul"><li>parent<ul class="md-ul"><li>child-a</li><li>child-b</li></ul></li><li>sibling</li></ul>'
    )
  })

  it('缩进回退时先关闭深层列表，再追加同级项', () => {
    const html = renderMarkdown('- a\n  - a1\n    - a11\n- b').replace(/\n/g, '')
    expect(html).toContain('<li>a<ul class="md-ul"><li>a1<ul class="md-ul"><li>a11</li></ul></li></ul></li><li>b</li>')
    expect((html.match(/<ul /g) || []).length).toBe(3)
    expect((html.match(/<\/ul>/g) || []).length).toBe(3)
  })
})
