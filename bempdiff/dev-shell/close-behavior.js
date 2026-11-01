// 关闭窗口行为（最小化到托盘 / 直接关闭）的唯一读写入口。
// 落在 userData/close-behavior.json，而非后端 ~/.bempdiff 配置：
// 关闭决策发生在窗口销毁之前，此时后端可能尚未启动或已退出，不能依赖 /api/config 往返。
// 写入采用「同目录临时文件 + rename」原子替换——并发读永远看到完整 JSON，不会读到半截文件。

const fs = require('node:fs')
const path = require('node:path')

const CLOSE_ACTIONS = ['minimize_to_tray', 'exit_app']
const FILE_NAME = 'close-behavior.json'

function defaults() {
  return { close_action: 'minimize_to_tray', remember_choice: false }
}

// 非法输入一律回落默认值：配置文件被手工改坏时不能让「关不掉」或「关不掉窗口」变成死局。
function normalize(raw) {
  const out = defaults()
  if (!raw || typeof raw !== 'object') return out
  if (CLOSE_ACTIONS.includes(raw.close_action)) out.close_action = raw.close_action
  if (typeof raw.remember_choice === 'boolean') out.remember_choice = raw.remember_choice
  return out
}

function fileOf(userDataDir) {
  return path.join(userDataDir, FILE_NAME)
}

function readStore(userDataDir) {
  try {
    return normalize(JSON.parse(fs.readFileSync(fileOf(userDataDir), 'utf8')))
  } catch (_) {
    return defaults() // 首次运行 / 文件损坏 / 无权限
  }
}

// 返回已落盘并归一化后的配置；写失败（磁盘满/无权限）返回 null，由调用方保持旧值。
function writeStore(userDataDir, cfg) {
  const next = normalize(cfg)
  const file = fileOf(userDataDir)
  const tmp = `${file}.${process.pid}.tmp`
  try {
    fs.mkdirSync(userDataDir, { recursive: true })
    fs.writeFileSync(tmp, JSON.stringify(next, null, 2), 'utf8')
    fs.renameSync(tmp, file)
    return next
  } catch (_) {
    try { fs.unlinkSync(tmp) } catch (_) {}
    return null
  }
}

module.exports = { CLOSE_ACTIONS, FILE_NAME, defaults, normalize, fileOf, readStore, writeStore }
