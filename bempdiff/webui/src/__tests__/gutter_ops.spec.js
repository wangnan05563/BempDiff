// T01811 FR-015 gutter 操作测试：复制到对侧（rep 行左右互拷，行内高亮消失 + 还原全部）
// 与标记已审阅（job+file 维度 localStorage 持久化 + 行置灰 + 一键清除）。
// CSS hover 显隐 jsdom 不生效——直接 trigger .gt-op 按钮（display:none 不影响事件派发）。
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { state } from '../store'
import DiffView from '../components/DiffView.vue'

const oldSrc = ['const a = 1;', 'const b = 2;', 'const c = 3;'].join('\n')
const newSrc = ['const a = 1;', 'const b = 999;', 'const c = 4;'].join('\n') // 行2 rep、行3 rep
const KEY = 'pkg/Svc.js'
const NODE = { key: KEY, fileClass: 'JS', status: 'MODIFIED', size: 100 }

function cellText(row) {
  return row.find('.code[data-side]').text().replace(/\s+/g, ' ').trim()
}

describe('FR-015 gutter 操作（T01811）', () => {
  beforeEach(() => {
    try { localStorage.removeItem('bempdiff.reviewed') } catch (_) {}
    // 清残留 tab 状态：上一用例 unmount 后 state.tabs/activeKey 仍指向旧数据，
    // 下一用例 mount 时会先按旧源码解析出 rows，导致查询到的行与新夹具不符
    state.tabs = []
    state.activeKey = null
    state.job = { jobId: 'j-gutter', tree: [], stats: null, mode: 'folder' }
  })

  function mountView() {
    const w = mount(DiffView)
    state.tabs = [{ key: KEY, node: NODE, busy: false, decompile: { ok: true, engine: 'cfr', oldSource: oldSrc, newSource: newSrc } }]
    state.activeKey = KEY
    return w
  }

  it('复制到对侧：左→右覆盖后行内差异消失，「还原全部」恢复原文', async () => {
    const w = mountView()
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    // 左栏 rep 行（b=2）hover 操作条 → 复制到对侧
    const rows = w.findAll('.prow').filter(r => r.classes().includes('rep'))
    expect(rows.length).toBeGreaterThan(0)
    const repRow = rows[0]
    const copyBtn = repRow.find('.gt-op .bi-arrow-right-square')
    expect(copyBtn.exists(), 'rep 行左栏应有复制到对侧按钮').toBe(true)
    await copyBtn.trigger('click')
    await w.vm.$nextTick()
    // 复制后该行两侧内容一致：无行内高亮 span（im-del/im-add）
    const afterRows = w.findAll('.prow').filter(r => r.classes().includes('rep'))
    const copied = afterRows.find(r => r.text().includes('const b'))
    expect(copied.find('.im-del, .im-add').exists(), '两侧一致后应无行内差异高亮').toBe(false)
    // 「还原全部」按钮出现 → 点击后原文恢复（b 两侧又不同，高亮回来）
    const revertBtn = w.findAll('button').find(b => b.attributes('aria-label') === '还原全部手动修改')
    expect(revertBtn, '有手动覆盖时应显示还原按钮').toBeTruthy()
    await revertBtn.trigger('click')
    await w.vm.$nextTick()
    const restored = w.findAll('.prow').filter(r => r.classes().includes('rep')).find(r => r.text().includes('const b'))
    expect(restored.find('.im-del, .im-add').exists(), '还原后行内差异高亮应恢复').toBe(true)
    w.unmount()
  })

  it('标记已审阅：prow 加 reviewed 类并持久化 localStorage，可取消与一键清除', async () => {
    const w = mountView()
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    const repRow = w.findAll('.prow').filter(r => r.classes().includes('rep'))[0]
    const markBtn = repRow.find('.gt-op .bi-check2-circle')
    expect(markBtn.exists(), '差异行应有已审阅标记按钮').toBe(true)
    await markBtn.trigger('click')
    await w.vm.$nextTick()
    expect(repRow.classes()).toContain('reviewed')
    // T06218 起审阅真源为服务端 reviews/{jobId}.json，内存态 state.review 同步更新；
    // localStorage 仅保留旧数据迁移缓存，不再承担主存储。
    expect(state.review.files[KEY] && state.review.files[KEY].lines, '应按 job+file 维度写入审阅状态').toBeTruthy()
    // 再次点击取消标记
    const row2 = w.findAll('.prow').filter(r => r.classes().includes('rep'))[0]
    await row2.find('.gt-op .bi-check2-circle').trigger('click')
    await w.vm.$nextTick()
    expect(w.findAll('.prow').filter(r => r.classes().includes('rep'))[0].classes()).not.toContain('reviewed')
    // 重新标记 → 工具栏一键清除
    await w.findAll('.prow').filter(r => r.classes().includes('rep'))[0].find('.gt-op .bi-check2-circle').trigger('click')
    await w.vm.$nextTick()
    const clearBtn = w.findAll('button').find(b => b.attributes('aria-label') === '清除本文件已审阅标记')
    expect(clearBtn, '有标记时应显示清除按钮').toBeTruthy()
    await clearBtn.trigger('click')
    await w.vm.$nextTick()
    expect(state.review.files[KEY].lines).toEqual([])
    w.unmount()
  })

  it('del/add 行不提供复制按钮（需行号重排），仅保留已审阅标记', async () => {
    const oldSrc2 = ['only-old;', 'keep;'].join('\n')
    const newSrc2 = ['keep;', 'only-new;'].join('\n')
    const w = mount(DiffView)
    state.tabs = [{ key: KEY, node: NODE, busy: false, decompile: { ok: true, engine: 'cfr', oldSource: oldSrc2, newSource: newSrc2 } }]
    state.activeKey = KEY
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    const delRow = w.findAll('.prow').find(r => r.classes().includes('del'))
    const addRow = w.findAll('.prow').find(r => r.classes().includes('add'))
    expect(delRow, '夹具应含 del 行').toBeTruthy()
    expect(addRow, '夹具应含 add 行').toBeTruthy()
    expect(delRow.find('.bi-arrow-right-square, .bi-arrow-left-square').exists(),
      'del 行不应有复制按钮').toBe(false)
    expect(addRow.find('.bi-arrow-right-square, .bi-arrow-left-square').exists(),
      'add 行不应有复制按钮').toBe(false)
    expect(delRow.find('.bi-check2-circle').exists(), 'del 行保留已审阅标记').toBe(true)
    w.unmount()
  })
})
