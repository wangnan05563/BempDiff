// 拖拽遮罩「接入」护栏（T04096）：DragDropOverlay 组件本体与 5 条单测一直存在，
// 但 App.vue 从未 import 它，模板里的 <DragDropOverlay /> 解析不到组件 → 遮罩实际从不渲染。
// 组件级单测直接 mount 组件，天然测不出这类装配缺失，故这里从 App 装配层补两条护栏：
//   1) 运行时：挂载 App，派发 window 级文件拖拽，断言全屏遮罩真的出现在文档里；
//   2) 静态：锁住 App.vue 的 import 与模板用法，防止再次被误删。
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import App from '../App.vue'
import { state } from '../store'

const appSrc = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'App.vue'), 'utf-8')

// jsdom 不暴露 DataTransfer，按组件 hasFiles()/files[0].path 的约定构造最小拖拽事件。
function sendFileDrag(type) {
  const file = new File(['x'], 'v1.war', { type: 'application/zip' })
  Object.defineProperty(file, 'path', { value: 'C:/pkg/v1.war', configurable: true })
  const ev = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(ev, 'dataTransfer', { value: { types: ['Files'], files: [file], items: { add: () => {} } } })
  window.dispatchEvent(ev)
  return new Promise((r) => setTimeout(r, 0))
}

describe('DragDropOverlay 在 App 中的装配', () => {
  let fetchSpy
  beforeEach(() => {
    // App 挂载即 init()/静默更新检查；本测试不关心后端，统一让请求失败并被既有 catch 吞掉。
    fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('offline for wiring test'))
    state.oldPath = ''
    state.newPath = ''
    while (document.body.firstChild) document.body.removeChild(document.body.firstChild)
  })
  afterEach(() => fetchSpy.mockRestore())

  it('挂载 App 后拖入文件能真的弹出全屏遮罩，移出后收起', async () => {
    const w = mount(App, { attachTo: document.body })
    await nextTick()
    expect(document.querySelector('.drop-mask')).toBeNull()

    await sendFileDrag('dragenter')
    await nextTick()
    const mask = document.querySelector('.drop-mask')
    expect(mask, '遮罩应由 App 渲染').not.toBeNull()
    expect(mask.querySelector('.drop-mask-title').textContent).toBe('请拖入老包')

    await sendFileDrag('dragleave')
    await nextTick()
    expect(document.querySelector('.drop-mask')).toBeNull()

    w.unmount()
  })

  it('App.vue 保留 DragDropOverlay 的 import 与模板用法（防再次漏注册）', () => {
    expect(appSrc).toContain("import DragDropOverlay from './components/DragDropOverlay.vue'")
    expect(appSrc).toContain('<DragDropOverlay />')
  })
})
