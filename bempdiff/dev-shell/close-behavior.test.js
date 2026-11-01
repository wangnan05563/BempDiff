// close-behavior.js 单元测试：node --test bempdiff/dev-shell/close-behavior.test.js
// 仅覆盖持久化/归一化逻辑（Electron 主进程的托盘与对话框行为由真实客户端冒烟覆盖）。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const store = require('./close-behavior')

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'bempdiff-close-'))
}

test('默认值：首次运行 = 最小化到托盘 + 不记住', () => {
  assert.deepEqual(store.defaults(), { close_action: 'minimize_to_tray', remember_choice: false })
  const dir = tempDir()
  assert.deepEqual(store.readStore(dir), { close_action: 'minimize_to_tray', remember_choice: false })
  assert.equal(fs.existsSync(store.fileOf(dir)), false, '读不到文件时不应顺手创建')
})

test('normalize：非法输入回落默认值，不抛异常', () => {
  assert.deepEqual(store.normalize(null), store.defaults())
  assert.deepEqual(store.normalize('x'), store.defaults())
  assert.deepEqual(store.normalize({ close_action: 'shutdown', remember_choice: 'yes' }), store.defaults())
  assert.deepEqual(
    store.normalize({ close_action: 'exit_app', remember_choice: true }),
    { close_action: 'exit_app', remember_choice: true })
})

test('写入后读回：往返一致，且不残留临时文件', () => {
  const dir = tempDir()
  const saved = store.writeStore(dir, { close_action: 'exit_app', remember_choice: true })
  assert.deepEqual(saved, { close_action: 'exit_app', remember_choice: true })
  assert.deepEqual(store.readStore(dir), saved)
  const leftovers = fs.readdirSync(dir).filter((f) => f.includes('.tmp'))
  assert.deepEqual(leftovers, [], 'rename 成功后不应留下 .tmp')
})

test('覆盖写为原子替换：并发读只会读到完整 JSON', () => {
  const dir = tempDir()
  store.writeStore(dir, { close_action: 'exit_app', remember_choice: true })
  store.writeStore(dir, { close_action: 'minimize_to_tray', remember_choice: false })
  assert.deepEqual(store.readStore(dir), { close_action: 'minimize_to_tray', remember_choice: false })
  // 文件内容本身可被 JSON.parse（无半截写入）
  assert.doesNotThrow(() => JSON.parse(fs.readFileSync(store.fileOf(dir), 'utf8')))
})

test('文件损坏：回落默认值且不抛异常', () => {
  const dir = tempDir()
  fs.writeFileSync(store.fileOf(dir), '{ not json')
  assert.deepEqual(store.readStore(dir), store.defaults())
})

test('未知字段被丢弃，写入内容仅含约定字段', () => {
  const dir = tempDir()
  store.writeStore(dir, { close_action: 'exit_app', remember_choice: false, extra: 1 })
  const raw = JSON.parse(fs.readFileSync(store.fileOf(dir), 'utf8'))
  assert.deepEqual(raw, { close_action: 'exit_app', remember_choice: false })
})

test('写失败（目标目录被同名文件占用）返回 null，不抛出', () => {
  const dir = tempDir()
  const blocked = path.join(dir, 'blocked')
  fs.writeFileSync(blocked, 'x')
  assert.equal(store.writeStore(blocked, { close_action: 'exit_app' }), null)
})
