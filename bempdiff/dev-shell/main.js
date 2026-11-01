// BempDiff Electron 桌面壳（开发态 / 生产态共用）
// 职责：
//   1) 自适应屏幕创建主窗口 —— 避免初始窗口过大被任务栏 / 屏幕截断
//   2) 以内嵌 sidecar 方式静默启动 Java 后端（javaw + windowsHide，无控制台窗口）
//      以及可选的 vite 前端开发服务器；应用退出时统一回收子进程与端口
// 仅作为原生窗口宿主 + 进程管理器，不打包任何业务代码。
// 生产打包由 electron-builder 复用同一 main.js，区别仅在于资源根路径（resourcesPath）。

const { app, BrowserWindow, ipcMain, dialog, screen, shell, Menu, Tray, nativeImage } = require('electron')
const path = require('node:path')
const fs = require('node:fs')
const net = require('node:net')
const { spawn, spawnSync } = require('node:child_process')
const http = require('node:http')
const closeBehaviorStore = require('./close-behavior')

// ---------- 配置 ----------
const PORT = 18765
const DEV_PORT = parseInt(process.env.BEMPDIFF_DEV_PORT || '5180', 10)
const DEFAULT_W = 1280
const DEFAULT_H = 900
const MIN_W = 960
const MIN_H = 640

const ROOT = resolveRoot()
const LOGDIR = path.join(ROOT, 'bempdiff', 'logs')
const BACKEND_LOG = path.join(LOGDIR, 'backend.log')
const VITE_LOG = path.join(LOGDIR, 'vite.log')

const PRELOAD = path.join(__dirname, 'preload.js')
const LOGO = path.join(__dirname, '..', 'bempdiff-logo.png')

let children = [] // sidecar 子进程，退出时回收
let backendPid = null // Java 后端主进程 PID（记录用于退出时 taskkill /T 树杀）
let mainWindow = null // 主窗口句柄（托盘恢复 / 外部文件参数投递都指向它）
let pendingShellPaths = null
let tray = null // 托盘常驻对象（首次「最小化到托盘」时懒创建）
let isQuitting = false // 应用真正退出中：close 事件直接放行，不再弹关闭对话框
let closeDialogOpen = false // 关闭对话框已打开：拦截连点关闭造成的重入

// ---------- 资源根路径 ----------
// 开发态：bempdiff/dev-shell -> 项目根（../../）
// 生产态：electron-builder extraResources 落地在 process.resourcesPath 下
function resolveRoot() {
  const res = process.resourcesPath
  // 生产态优先 resourcesPath：asar 环境下 __dirname 指向 .asar，基于它的 devRoot 会算错，
  // 可能导致「后端 classpath 找不到 → 只弹启动页不弹主窗」。先落一份运行时探测到 shell.log 便于定位。
  if (res) {
    if (fs.existsSync(path.join(res, 'bempdiff', 'dist_input'))) {
      return res
    }
    if (fs.existsSync(path.join(res, 'dist_input'))) return res
  }
  const devRoot = path.resolve(__dirname, '..', '..')
  if (fs.existsSync(path.join(devRoot, 'bempdiff'))) return devRoot
  return res || devRoot
}

function appendLog(file, ...args) {
  try {
    if (!fs.existsSync(LOGDIR)) fs.mkdirSync(LOGDIR, { recursive: true })
    fs.appendFileSync(file, args.join(' ') + '\n')
  } catch (_) {}
}

// 启动崩溃诊断：任何未捕获异常/未处理的 Promise 拒绝都落到 SHELL 日志，便于排查
// electron 启动即静默退出的根因（主进程 JS 异常通常不会打印到无控制台的 Start-Process 场景）。
const SHELL_LOG = path.join(LOGDIR, 'shell.log')
const SHELL_LOG_MAX = 2 * 1024 * 1024 // 单文件上限 2MB，超出轮转（.old）防止长期膨胀 + 阻塞主进程
function rotateAppend(file, text) {
  try {
    if (!fs.existsSync(LOGDIR)) fs.mkdirSync(LOGDIR, { recursive: true })
    if (fs.existsSync(file)) {
      const st = fs.statSync(file)
      if (st.size > SHELL_LOG_MAX) {
        try { fs.renameSync(file, file + '.old') } catch (_) { /* 重命名失败则继续追加 */ }
      }
    }
    fs.appendFileSync(file, text)
  } catch (_) { /* 日志失败绝不阻断主进程 */ }
}
process.on('uncaughtException', (e) => {
  rotateAppend(SHELL_LOG, `[uncaughtException] ${e && e.stack ? e.stack : String(e)}\n`)
})
process.on('unhandledRejection', (r) => {
  rotateAppend(SHELL_LOG, `[unhandledRejection] ${r && r.stack ? r.stack : String(r)}\n`)
})

// 打开追加写日志句柄。electron-builder 的 extraResources 不会打包 logs 目录
// （安装目录下不存在 <install>/resources/bempdiff/logs），fs.openSync 会抛 ENOENT，
// 进而让 async 启动流程里未捕获的异常把 `app.whenReady().then(...)` 整体中断——
// result：createWindow 永不执行 → 双击无窗口"打不开"。这里先建目录，打开失败降级 'ignore'，
// 保证日志问题绝不阻断后端/前端 sidecar 与窗口的启动。
function openLog(file) {
  try {
    if (!fs.existsSync(LOGDIR)) fs.mkdirSync(LOGDIR, { recursive: true })
    return fs.openSync(file, 'a')
  } catch (_) {
    return 'ignore'
  }
}

// ---------- 端口探测 ----------
function isPortOpen(port) {
  return new Promise((resolve) => {
    const s = net.connect(port, '127.0.0.1')
    let done = false
    const finish = (v) => { if (!done) { done = true; s.destroy(); resolve(v) } }
    s.setTimeout(800)
    s.on('connect', () => finish(true))
    s.on('timeout', () => finish(false))
    s.on('error', () => finish(false))
  })
}

async function waitPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await isPortOpen(port)) return true
    await new Promise((r) => setTimeout(r, 1000))
  }
  return false
}

// 端口占用强杀（T00407）：不再经 PowerShell（Get-NetTCPConnection + Stop-Process -Force）。
// 背景：BempDiff.exe 直接 spawn powershell 执行「强杀进程」命令，会触发杀软
// 「bempdiff 正在允许 powershell」启发式告警（启动 2 次 + 关闭时 before-quit/will-quit
// 双注册共 4 次，反复弹窗）。改为 netstat -ano 解析 LISTENING PID + taskkill /F /T，
// 全部走系统标准工具，无 PowerShell 参与；语义不变（仅杀监听该端口的进程）。
function killPort(port) {
  if (process.platform !== 'win32') return killPortUnix(port)
  try {
    const out = spawnSync('netstat', ['-ano', '-p', 'tcp'], { encoding: 'utf8' })
    if (!out || !out.stdout) return
    const pids = new Set()
    for (const line of String(out.stdout).split(/\r?\n/)) {
      // 形如：TCP    127.0.0.1:18765    0.0.0.0:0    LISTENING    1234
      const m = line.match(/^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$/i)
      if (m && Number(m[1]) === Number(port)) pids.add(m[2])
    }
    for (const pid of pids) {
      if (Number(pid) === process.pid) continue // 绝不误杀自己
      try { spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], { stdio: 'ignore' }) } catch (_) {}
    }
  } catch (_) {}
}

// Unix（linux/macOS，T01546）：lsof 查监听 PID 后逐个 kill -9；lsof 缺失退回 ss。
// 语义与 Windows 版一致：仅杀监听该端口的进程，绝不误杀自身。
function killPortUnix(port) {
  let pids = []
  try {
    const out = spawnSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' })
    pids = String(out.stdout || '').split(/\r?\n/).filter(Boolean)
  } catch (_) { pids = [] }
  if (!pids.length) {
    try {
      // ss 无 -p 权限时拿不到 PID，尽力而为：解析 users:(("name",pid=N
      const out = spawnSync('ss', ['-ltnp'], { encoding: 'utf8' })
      for (const line of String(out.stdout || '').split(/\r?\n/)) {
        if (!line.includes(`:${port} `)) continue
        for (const m of String(line).matchAll(/pid=(\d+)/g)) pids.push(m[1])
      }
    } catch (_) { /* 静默 */ }
  }
  for (const pid of pids) {
    if (Number(pid) === process.pid) continue
    try { process.kill(Number(pid), 'SIGKILL') } catch (_) {}
  }
}

// 强杀指定 PID 的整个进程树（含子进程），根治 Windows 下 detached 子进程被 SIGTERM 无效而残留。
// Windows：taskkill /F /T /PID；Unix（T01546）：sidecar 以 detached 启动自成进程组，
// kill(-pid) 整组回收，组已不存在/无权限则退回单进程 SIGKILL。PID 无效时静默忽略不阻断退出。
function killTreePid(pid) {
  if (!pid) return
  if (process.platform === 'win32') {
    try {
      spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], { stdio: 'ignore' })
    } catch (_) {}
    return
  }
  try {
    process.kill(-pid, 'SIGKILL')
  } catch (_) {
    try { process.kill(pid, 'SIGKILL') } catch (_) {}
  }
}

// 关闭时确定性清理会话内缓存：
// Windows 对子进程 kill('SIGTERM') 是强杀，JVM 的 shutdown hook（cleanupTempFiles）不会执行，
// 导致关闭后 .bempdiff/{runtime,exports} 与系统临时 bempdiff-* 残留。这里是 Node 侧兜底，
// 须在后端侧边进程已终止、文件句柄释放后再删（stopSidecar 末尾调用）。
// 保留 logs / ai_replay（供问题回溯）。删除尽力而为，不阻断退出。
function cleanupCachesOnExit() {
  try {
    const home = require('os').homedir()
    const base = path.join(home, '.bempdiff')
    for (const sub of ['exports', 'runtime']) {
      try { fs.rmSync(path.join(base, sub), { recursive: true, force: true }) } catch (_) {}
    }
    try {
      const t = require('os').tmpdir()
      for (const f of fs.readdirSync(t)) {
        if (f.startsWith('bempdiff-')) {
          try { fs.rmSync(path.join(t, f), { recursive: true, force: true }) } catch (_) {}
        }
      }
    } catch (_) {}
  } catch (_) {}
}

// ---------- 资源定位 ----------
// T01546 跨平台：Windows 用 javaw.exe（无控制台），linux/macOS 用 jre/bin/java
// （macOS 归档 Contents/Home/* 已由 CI 拍平到 jre/ 下，路径与 linux 同构）。
function findJava() {
  const exe = process.platform === 'win32' ? 'javaw.exe' : 'java'
  const base = path.join(ROOT, 'bempdiff')
  const jre = path.join(base, 'dist_input', 'jre', 'bin', exe)
  if (fs.existsSync(jre)) return jre
  const tc = path.join(base, 'toolchain')
  if (fs.existsSync(tc)) {
    for (const d of fs.readdirSync(tc)) {
      if (d.startsWith('zulu21')) {
        const p = path.join(tc, d, 'bin', exe)
        if (fs.existsSync(p)) return p
      }
    }
  }
  return exe // 退回 PATH
}

function findJavac() {
  const exe = process.platform === 'win32' ? 'javac.exe' : 'javac'
  const base = path.join(ROOT, 'bempdiff')
  const tc = path.join(base, 'toolchain')
  if (fs.existsSync(tc)) {
    for (const d of fs.readdirSync(tc)) {
      if (d.startsWith('zulu21')) {
        const p = path.join(tc, d, 'bin', exe)
        if (fs.existsSync(p)) return p
      }
    }
  }
  return 'javac'
}

function findCfr() {
  const cfr = path.join(ROOT, 'bempdiff', 'dist_input', 'app', 'cfr.jar')
  return fs.existsSync(cfr) ? cfr : null
}

// 三方依赖 lib（POI 等，解析 .xls 用）：返回通配路径段，供 javac/java 的 classpath 展开。
// 优先 dist_input/app/lib（构建脚本分发），回退 toolchain/lib（源码/按需编译场景）。
function findPoiLib() {
  for (const d of [
    path.join(ROOT, 'bempdiff', 'dist_input', 'app', 'lib'),
    path.join(ROOT, 'bempdiff', 'toolchain', 'lib')
  ]) {
    if (fs.existsSync(d)) {
      const jars = fs.readdirSync(d).filter((f) => f.endsWith('.jar'))
      if (jars.length) return path.join(d, '*')
    }
  }
  return null
}

function findClasspath() {
  const base = path.join(ROOT, 'bempdiff', 'dist_input')
  const classes = path.join(base, 'classes')
  const jar = path.join(base, 'app', 'bempdiff.jar')
  const dev = path.join(base, 'dev_classes')
  if (fs.existsSync(classes)) return classes
  if (fs.existsSync(jar)) return jar
  if (fs.existsSync(dev)) return dev
  return null
}

// 无已编译产物时，按需从源码临时编译（开发态兜底）
function ensureClasspath() {
  const cp = findClasspath()
  if (cp) return cp
  const javac = findJavac()
  const srcDir = path.join(ROOT, 'bempdiff', 'java_core', 'src')
  if (!fs.existsSync(srcDir)) return null
  const out = path.join(ROOT, 'bempdiff', 'dist_input', 'dev_classes')
  fs.mkdirSync(out, { recursive: true })
  const files = []
  const walk = (d) => {
    for (const f of fs.readdirSync(d)) {
      const p = path.join(d, f)
      const st = fs.statSync(p)
      if (st.isDirectory()) walk(p)
      else if (f.endsWith('.java')) files.push(p)
    }
  }
  walk(srcDir)
  if (!files.length) return null
  const cfr = findCfr()
  const poi = findPoiLib()
  const cpParts = []
  if (cfr) cpParts.push(cfr)
  if (poi) cpParts.push(poi)
  const args = ['-encoding', 'UTF-8', '-d', out]
  if (cpParts.length) args.push('-cp', cpParts.join(path.delimiter))
  args.push(...files)
  const r = spawnSync(javac, args, { cwd: ROOT, stdio: 'ignore' })
  if (r.status !== 0) return null
  return out
}

// ---------- sidecar 启动（静默、无控制台窗口） ----------
async function startBackend() {
  if (await isPortOpen(PORT)) {
    appendLog(BACKEND_LOG, '[sidecar] backend already listening on', PORT)
    return true
  }
  const java = findJava()
  const cp = ensureClasspath()
  // 启动诊断：记录资源根与 classpath 解析结果，便于在「只弹启动页、无主窗」时定位 ROOT/产物问题
  rotateAppend(SHELL_LOG, `[boot] ROOT=${ROOT} resourcesPath=${process.resourcesPath} __dirname=${__dirname} java=${java} cp=${cp || 'NULL'}\n`)
  if (!cp) {
    rotateAppend(SHELL_LOG, '[boot] NO_CLASSPATH: 后端产物缺失或现场编译失败，不会启动后端，主窗将无法加载\n')
    console.error('[BempDiff] No backend artifact and on-the-fly compile failed. Run the build script first.')
    return false
  }
  const cfr = findCfr()
  const poi = findPoiLib()
  const cpParts = [cp]
  if (cfr) cpParts.push(cfr)
  if (poi) cpParts.push(poi)
  const classPath = cpParts.join(path.delimiter)
  // HotSpot 的 -Xmx 须连写（-Xmx2g）：Electron spawn 按数组传给 javaw 时若拆成 '-Xmx','2g' 两个 token，
  // 会被解析为“-Xmx 缺值”而报 Invalid maximum heap size: -Xmx，导致后端启动即失败。
  // 显式 2g：默认堆=物理内存 1/4，小内存机器上解包/反编译易 OOM；给 256MB 单条目 + CFR + 扁平化叶子留足余量。
  const args = ['-Xmx2g', '-cp', classPath, 'com.bempdiff.Main', 'server', '--port', String(PORT)]
  // webroot 与 Tauri 统一：优先 dist_input/webui（assemble 镜像的产物），回退 webui/dist（仅前端源码直接构建）。
  // 打包态 extraResources 只包含 dist_input/webui，故这里按相对 ROOT 的多候选查找。
  const webrootCandidates = [
    path.join('bempdiff', 'dist_input', 'webui'),
    path.join('bempdiff', 'webui', 'dist')
  ]
  const webroot = webrootCandidates.find((c) => fs.existsSync(path.join(ROOT, c)))
  if (webroot) args.push('--webroot', webroot)
  const out = openLog(BACKEND_LOG)
  const child = spawn(java, args, {
    cwd: ROOT,
    windowsHide: true, // 关键：不弹 Java 控制台窗口
    detached: true,
    stdio: ['ignore', out, out], // 日志落盘（打不开则 ignore），不污染任何控制台
    env: process.env
  })
  children.push(child)
  backendPid = child.pid || null // 记录后端主进程 PID，退出时用 taskkill /T 强杀整棵进程树
  appendLog(BACKEND_LOG, '[sidecar] backend started PID', child.pid, '->', urlSafe(classPath))
  // 等后端真正在 PORT 监听后再返回（最多 30s），避免窗口在后端就绪前加载，
  // 导致首批 API 调用（如「连接测试」）出现 "Failed to fetch"。
  const ready = await waitPort(PORT, 30000)
  if (!ready) appendLog(BACKEND_LOG, '[sidecar] WARN: 后端 30s 内未就绪，窗口可能暂时无法访问 API（检查 javaw / dist_input/classes）')
  return ready
}

async function startFrontend() {
  if (await isPortOpen(DEV_PORT)) {
    appendLog(VITE_LOG, '[sidecar] vite already listening on', DEV_PORT)
    return true
  }
  const frontend = process.env.BEMPDIFF_FRONTEND || 'auto'
  let useVite = false
  if (frontend === 'dev') useVite = true
  else if (frontend === 'auto') {
    // 仅当存在 node 且 webui 依赖已安装时才走 vite dev
    try {
      spawnSync(process.platform === 'win32' ? 'where' : 'which', ['node'], { stdio: 'ignore' })
      useVite = fs.existsSync(path.join(ROOT, 'bempdiff', 'webui', 'node_modules'))
    } catch (_) { useVite = false }
  }
  if (!useVite) return false
  const out = openLog(VITE_LOG)
  // Windows 经 cmd.exe；unix（T01546）经 sh -c，命令语义一致（进 webui 起 vite dev）
  const child = process.platform === 'win32'
    ? spawn('cmd.exe',
      ['/c', `cd /d bempdiff\\webui && npm run dev -- --port ${DEV_PORT} --host 127.0.0.1`],
      {
        cwd: ROOT,
        windowsHide: true,
        detached: true,
        stdio: ['ignore', out, out],
        env: process.env
      })
    : spawn('sh',
      ['-c', `cd bempdiff/webui && npm run dev -- --port ${DEV_PORT} --host 127.0.0.1`],
      {
        cwd: ROOT,
        detached: true,
        stdio: ['ignore', out, out],
        env: process.env
      })
  children.push(child)
  appendLog(VITE_LOG, '[sidecar] vite started PID', child.pid)
  return true
}

function urlSafe(s) { return String(s).replace(/\\/g, '/') }

// ---------- 窗口自适应 ----------
function computeSize() {
  const area = screen.getPrimaryDisplay().workAreaSize // 已排除任务栏
  const w = Math.min(DEFAULT_W, Math.floor(area.width * 0.92))
  const h = Math.min(DEFAULT_H, Math.floor(area.height * 0.92))
  const cw = Math.max(MIN_W, w)
  const ch = Math.max(MIN_H, h)
  // 即便最小尺寸都放不下时，直接最大化
  if (cw >= area.width || ch >= area.height) return { maximize: true }
  return { maximize: false, width: cw, height: ch }
}

// ---------- 启动图（splash）过渡 ----------
// 后端 sidecar 启动可能耗时数秒——这段时间若直接黑屏等待，用户会觉得"没反应 / 打不开"。
// 参照 Tauri 版启动图方案：先弹出一个只含品牌 logo + 进度条的轻量窗口，后端就绪、主窗口
// ready-to-show 后再关闭 splash。splash.html 为自包含单页（相对路径引用 ./splash-logo.png），
// 支持 loadFile 本地加载，无 Tauri 运行时自动降级为不确定进度动画，故 Electron 无需改其脚本。
let splashWin = null
let SPLASH_SIZE_W = 400
let SPLASH_SIZE_H = 300

function findSplashHtml() {
  // 优先 dist_input/webui（assemble 镜像产物，生产打包只含这里），回退 webui/dist（源码直构）
  for (const c of [
    path.join(ROOT, 'bempdiff', 'dist_input', 'webui', 'splash.html'),
    path.join(ROOT, 'bempdiff', 'webui', 'dist', 'splash.html')
  ]) {
    if (fs.existsSync(c)) return c
  }
  return null
}

function createSplashWindow() {
  if (splashWin && !splashWin.isDestroyed()) return splashWin
  const html = findSplashHtml()
  // 轻量、无边框、不可调的纯展示窗口（避免用户拖动/缩放破坏启动页布局）
  const win = new BrowserWindow({
    width: SPLASH_SIZE_W,
    height: SPLASH_SIZE_H,
    frame: false,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    show: false,
    skipTaskbar: true, // 不占任务栏——它只是过渡窗，避免闪一个多余图标
    webPreferences: { contextIsolation: true, nodeIntegration: false }
  })
  splashWin = win
  if (html) {
    win.loadFile(html)
    win.once('ready-to-show', () => { win.center(); win.show() })
  } else {
    // 无 splash 资源：回退为一个小占位窗，主窗就绪后同样关闭，保证流程一致
    win.loadURL('about:blank')
    win.once('ready-to-show', () => { win.center(); win.show() })
  }
  // 超时兜底：主窗 ready-to-show/did-fail-load 都未触发（如后端启动挂起）时，
  // 强制收回无边框+skipTaskbar 的 splash，避免其成为无法关闭的僵尸窗。
  // 正常路径会由 createWindow 更早关闭，此兜底仅兜异常场景。
  setTimeout(() => closeSplash(), 8000)
  return win
}

function closeSplash() {
  if (splashWin && !splashWin.isDestroyed()) splashWin.close()
  splashWin = null
}

function createWindow(url) {
  const size = computeSize()
  const win = new BrowserWindow({
    width: size.maximize ? MIN_W : size.width,
    height: size.maximize ? MIN_H : size.height,
    minWidth: MIN_W,
    minHeight: MIN_H,
    icon: LOGO,
    show: false, // 先隐藏，尺寸/居中确定后再显示，避免闪烁与错位
    webPreferences: {
      preload: PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  win.loadURL(url)

  win.once('ready-to-show', () => {
    if (!size.maximize) win.center() // 在主屏工作区内居中
    win.show()
    // 主窗口真正显示时才关闭启动图，避免 splash→主窗切换之间出现黑屏空档
    closeSplash()
  })

  // 主窗体 URL 加载失败（如后端 500/地址不可达）时，同样收起 splash 并显示主窗的错误页，
  // 避免 splash 无边框+skipTaskbar 无从关闭成为僵尸窗，强制用户只能任务管理器杀进程。
  win.webContents.on('did-fail-load', () => {
    closeSplash()
    win.show()
  })

  // 开发便利：F12 打开 DevTools
  win.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'F12') {
      if (win.webContents.isDevToolsOpened()) win.webContents.closeDevTools()
      else win.webContents.openDevTools()
      event.preventDefault()
    }
  })

  // 让外部链接走系统默认浏览器，不在壳内打开
  win.webContents.setWindowOpenHandler(({ url: u }) => {
    const allowed = (() => { try { return new URL(win.webContents.getURL()).host } catch (_) { return '' } })()
    try {
      const tu = new URL(u)
      if (tu.host === allowed) return { action: 'allow' }
    } catch (_) { /* ignore */ }
    require('electron').shell.openExternal(u)
    return { action: 'deny' }
  })

  // 关闭窗口交互：按配置「最小化到托盘」或「直接关闭」，未记住选择时先弹选择框。
  // 应用真正退出（app.quit / 托盘退出）时 isQuitting 已置位，此处直接放行，避免退出路径再弹框。
  win.on('close', (e) => {
    if (isQuitting) return
    e.preventDefault()
    rotateAppend(SHELL_LOG, '[close] event\n')
    handleCloseRequest(win)
  })

  return win // 返回主窗口句柄，供调用方可靠地赋给 mainWindow（避免 getAllWindows()[0] 误取 splash）
}

// ---------- 关闭前运行任务检测 ----------
// 查询后端是否有「进行中」比对任务（QUEUED/RUNNING），供关闭窗口前确认。
// GET /api/tasks/running -> { running: [{type,id,label,phase,message}] }。
// 后端不可达（尚未启动/已退出）时视为无任务，返回空数组——避免关闭流程被查询失败卡住。
function queryRunningTasks() {
  return new Promise((resolve) => {
    const req = http.get({
      host: '127.0.0.1',
      port: PORT,
      path: '/api/tasks/running',
      timeout: 2000
    }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { body += c })
      res.on('end', () => {
        try {
          const j = JSON.parse(body)
          resolve(Array.isArray(j && j.running) ? j.running : [])
        } catch (_) {
          resolve([])
        }
      })
    })
    req.on('error', () => resolve([]))
    req.on('timeout', () => { req.destroy(); resolve([]) })
  })
}

// ---------- 关闭行为（最小化到托盘 / 直接关闭） ----------
// 配置落在 userData/close-behavior.json（见 close-behavior.js），首次运行默认
// 「最小化到托盘 + 不记住」——即每次关闭都先问一次。
let closeBehavior = null

function currentCloseBehavior() {
  if (!closeBehavior) closeBehavior = closeBehaviorStore.readStore(app.getPath('userData'))
  return { ...closeBehavior }
}

// 写失败返回 null，调用方保持内存旧值（不让 UI 显示成已保存）。
function persistCloseBehavior(cfg) {
  const saved = closeBehaviorStore.writeStore(app.getPath('userData'), cfg)
  if (saved) closeBehavior = saved
  return saved
}

function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) return false
  if (mainWindow.isMinimized()) mainWindow.restore()
  if (!mainWindow.isVisible()) mainWindow.show()
  mainWindow.focus()
  return true
}

// 托盘懒创建：仅在用户选了「最小化到托盘」时才出现图标，平时不打扰任务栏。
// 创建失败返回 false——此时绝不能隐藏窗口，否则用户既看不见窗口也找不回。
function ensureTray() {
  if (tray && !tray.isDestroyed()) return true
  try {
    let img = fs.existsSync(LOGO) ? nativeImage.createFromPath(LOGO) : nativeImage.createEmpty()
    if (img.isEmpty()) {
      // 图标缺失兜底：1x1 透明图，保证托盘项仍可创建（否则窗口会变成关不掉也找不回）
      img = nativeImage.createFromDataURL(
        'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
    } else if (process.platform === 'darwin') {
      img = img.resize({ width: 18, height: 18 }) // macOS 菜单栏图标按 18pt 显示，原图会过大
    }
    tray = new Tray(img)
    tray.setToolTip('BempDiff')
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: '显示主窗口', click: () => showMainWindow() },
      { type: 'separator' },
      { label: '退出 BempDiff', click: () => quitFromTray() }
    ]))
    tray.on('click', () => showMainWindow())
    tray.on('double-click', () => showMainWindow())
    rotateAppend(SHELL_LOG, '[tray] created\n')
    return true
  } catch (e) {
    rotateAppend(SHELL_LOG, `[tray] 创建托盘失败：${e && e.message ? e.message : String(e)}\n`)
    tray = null
    return false
  }
}

// 托盘「退出」也遵守运行任务守卫：有任务在跑先确认，避免托盘一键退出静默中断比对。
async function quitFromTray() {
  const tasks = await queryRunningTasks()
  if (tasks.length && !(await confirmInterrupt(mainWindow, tasks))) return
  isQuitting = true
  app.quit()
}

// 执行关闭动作：托盘隐藏窗口（进程保留），或销毁窗口并退出应用。
async function applyCloseAction(win, action) {
  if (action === 'minimize_to_tray') {
    if (!ensureTray()) {
      dialog.showMessageBox(win, {
        type: 'error',
        title: '无法最小化到托盘',
        message: '系统托盘不可用，窗口保持打开。',
        detail: '可改用「直接关闭」，或在设置中把关闭行为改为「直接关闭」。',
        buttons: ['知道了'],
        noLink: true
      }).catch(() => {})
      return
    }
    win.hide()
    return
  }
  isQuitting = true
  if (win && !win.isDestroyed()) win.destroy()
  app.quit()
}

async function askCloseChoice(win, tasks) {
  const lines = []
  if (tasks.length) {
    const names = tasks.map((t) => `  · ${t.label || t.id || '未知任务'}`).join('\n')
    lines.push(`检测到 ${tasks.length} 个任务正在进行中：\n${names}\n\n最小化到托盘不会中断任务。`)
  }
  lines.push('「最小化到托盘」隐藏窗口并保留后台进程；「直接关闭」将退出应用。')
  const { response, checkboxChecked } = await dialog.showMessageBox(win, {
    type: tasks.length ? 'warning' : 'question',
    title: '关闭 BempDiff',
    message: '要如何处理关闭操作？',
    detail: lines.join('\n\n'),
    buttons: ['最小化到托盘', '直接关闭', '取消'],
    defaultId: 0,
    cancelId: 2,
    noLink: true,
    checkboxLabel: '记住此选择，下次不再提示',
    checkboxChecked: false
  })
  if (response === 0) return { action: 'minimize_to_tray', remember: !!checkboxChecked }
  if (response === 1) return { action: 'exit_app', remember: !!checkboxChecked }
  return null // 取消：窗口保持原样
}

async function confirmInterrupt(win, tasks) {
  const names = tasks.map((t) => `  · ${t.label || t.id || '未知任务'}`).join('\n')
  const opts = {
    type: 'warning',
    title: '比对仍在进行',
    message: `检测到 ${tasks.length} 个任务正在进行中：\n${names}\n\n立即关闭将中断这些任务，确定要强制关闭吗？`,
    buttons: ['取消关闭', '强制关闭'],
    defaultId: 0,
    cancelId: 0,
    noLink: true
  }
  const { response } = win && !win.isDestroyed()
    ? await dialog.showMessageBox(win, opts)
    : await dialog.showMessageBox(opts)
  return response === 1
}

// 关闭主流程：记住过选择 → 直接执行；否则弹选择框，勾了「记住」才落盘。
// 选「直接关闭」且仍有运行中任务时，追加一道强制关闭确认（保留原有防误关语义）。
async function handleCloseRequest(win) {
  if (closeDialogOpen) return
  closeDialogOpen = true
  try {
    const tasks = await queryRunningTasks()
    const cfg = currentCloseBehavior()
    let action = cfg.close_action
    if (cfg.remember_choice) {
      rotateAppend(SHELL_LOG, `[close] remembered action=${action} running=${tasks.length}\n`)
    } else {
      const choice = await askCloseChoice(win, tasks)
      if (!choice) {
        rotateAppend(SHELL_LOG, `[close] dialog cancel running=${tasks.length}\n`)
        return
      }
      action = choice.action
      if (choice.remember) persistCloseBehavior({ close_action: action, remember_choice: true })
      rotateAppend(SHELL_LOG, `[close] dialog action=${action} remember=${choice.remember} running=${tasks.length}\n`)
    }
    if (action === 'exit_app' && tasks.length && !(await confirmInterrupt(win, tasks))) {
      rotateAppend(SHELL_LOG, '[close] interrupt confirm cancel\n')
      return
    }
    await applyCloseAction(win, action)
    rotateAppend(SHELL_LOG, `[close] applied action=${action}\n`)
  } catch (e) {
    // 对话框/查询异常（罕见）：保守保持窗口打开，避免「窗口永远关不掉」变成数据风险
    rotateAppend(SHELL_LOG, `[close] error: ${e && e.stack ? e.stack : String(e)}\n`)
  } finally {
    closeDialogOpen = false
  }
}

// ---------- 进程回收 ----------
function stopSidecar() {
  // 1) 强杀后端 javaw 整棵进程树（根因修复：Windows 下 SIGTERM 对 detached 子进程无效，
  //    仅靠端口兜底又会因 javaw 异常不监听/权限问题漏杀，导致关闭 app 后 javaw 残留）。
  killTreePid(backendPid)
  backendPid = null
  // 2) 其余 sidecar（vite 等）尽量 SIGTERM；Windows 兜底靠下方端口强杀
  for (const c of children) {
    try { c.kill('SIGTERM') } catch (_) { /* ignore */ }
  }
  children = []
  // 3) 端口层面双保险：无论 javaw 进程名如何，都强杀 18765 / devport 占用者
  killPort(PORT)
  killPort(DEV_PORT)

  // 4) 后端/端口均已终止、句柄释放后，确定性清理会话缓存（runtime/exports/系统临时 bempdiff-*）
  cleanupCachesOnExit()
}

// 渲染进程文件/文件夹选择对话框桥
// 渲染进程文件/文件夹选择对话框桥
ipcMain.handle('bempdiff:pick-path', async (event, opts = {}) => {
  const properties = []
  if (opts.directory) properties.push('openDirectory')
  else properties.push('openFile')
  if (opts.multiple) properties.push('multiSelections')
  if (opts.multiple) properties.push('multiSelections')
  const win = BrowserWindow.fromWebContents(event.sender) || BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0]
  const result = await dialog.showOpenDialog(win, { properties })
  if (result.canceled || !result.filePaths.length) return null
  return opts.multiple ? result.filePaths : result.filePaths[0]
})

// ---------- 差异树右键菜单：系统文件操作桥（shell API） ----------
// 仅接受绝对路径（渲染进程可能被注入），非法直接拒绝。
ipcMain.handle('bempdiff:open-path', async (_event, p) => {
  if (typeof p !== 'string' || !p.trim() || !path.isAbsolute(p)) return '非法路径'
  try {
    const err = await shell.openPath(p)
    return err || '' // 空串 = 打开成功
  } catch (e) {
    return String(e && e.message ? e.message : e)
  }
})

ipcMain.handle('bempdiff:show-in-folder', async (_event, p) => {
  if (typeof p !== 'string' || !p.trim() || !path.isAbsolute(p)) return '非法路径'
  try {
    shell.showItemInFolder(p)
    return ''
  } catch (e) {
    return String(e && e.message ? e.message : e)
  }
})

// ---------- 关闭行为读写桥（配置中心「界面与高级」页调用） ----------
// 设置页切换后立即落盘并更新内存，下一次点击关闭即按新配置执行（无需重启）。
ipcMain.handle('bempdiff:get-close-behavior', () => ({ ok: true, ...currentCloseBehavior() }))
ipcMain.handle('bempdiff:set-close-behavior', (_event, cfg) => {
  const saved = persistCloseBehavior(cfg)
  return { ok: !!saved, ...(saved || currentCloseBehavior()) }
})

// ---------- 当前产品版本（preload 同步取常量 → window.bempdiff.appVersion） ----------
// 打包后 app.getVersion() 读的是 asar 内 package.json 版本，即安装包版本；
// 前端「关于/更新检查」据此上报 current，避免回落内置常量导致恒报「发现新版本」。
ipcMain.on('bempdiff:app-version', (event) => { event.returnValue = app.getVersion() })

// ---------- 版本更新：一键下载更新包并触发安装（来自 About「立即更新」） ----------
// 前端只传 GitHub Release 的 .exe 资产下载 URL；主进程下载到系统下载目录（Downloads），
// 完成后 shell.openPath 启动安装器。仅接受 https formpage 的 URL（防注入任意协议/路径）。
const https = require('node:https')
const os = require('node:os')
ipcMain.handle('bempdiff:download-install', async (_event, url) => {
  let file = ''      // 最终安装包路径（供 catch 清理半包引用）
  let tmp = ''       // 下载临时路径
  try {
    if (typeof url !== 'string' || !/^https:\/\//.test(url)) return '只支持 HTTPS 下载链接'
    const u = new URL(url)
    if (!/\.(exe)$/i.test(u.pathname)) return '不是 Windows 安装包（.exe）地址'
    // 落盘到系统「下载」目录；文件名取 URL 末段，异常则回退安装包名。
    const dlDir = path.join(os.homedir(), 'Downloads')
    if (!fs.existsSync(dlDir)) fs.mkdirSync(dlDir, { recursive: true })
    file = path.join(dlDir, path.basename(u.pathname) || 'BempDiff-update-setup.exe')
    tmp = file + '.download'
    await new Promise((resolve, reject) => {
      // Node 的 https.get **不会**自动跟随重定向（与浏览器/curl 不同）；
      // GitHub Release 的 browser_download_url（api.github.com 资产链接）必返
      // 302 跳 objects.githubusercontent.com——不跟随即报「下载失败 HTTP 302」。
      // 手动跟随：限 10 跳、每跳仅允许 https，防协议降级与重定向环。
      const getFollowed = (target, hopsLeft) => {
        https.get(target, { headers: { 'User-Agent': 'BempDiff-Updater' } }, (res) => {
          const loc = res.headers.location
          if (res.statusCode >= 300 && res.statusCode < 400 && loc) {
            res.resume()
            if (hopsLeft <= 0) { reject(new Error('下载重定向次数过多')); return }
            let next
            try { next = new URL(loc, target) } catch (_) { reject(new Error('非法下载重定向地址')); return }
            if (next.protocol !== 'https:') { reject(new Error('下载重定向仅允许 HTTPS')); return }
            getFollowed(next.toString(), hopsLeft - 1)
            return
          }
          if (res.statusCode !== 200) { res.resume(); reject(new Error('下载失败 HTTP ' + res.statusCode)); return }
          const ws = fs.createWriteStream(tmp)
          res.pipe(ws)
          ws.on('finish', () => { ws.close(); resolve() })
          ws.on('error', reject)
        }).on('error', reject)
      }
      getFollowed(url, 10)
    })
    // 原子落位：先写 .download 再改名，避免残留半包被误当安装包。
    fs.renameSync(tmp, file)
    shell.openPath(file)
    return { ok: true, file }
  } catch (e) {
    try { if (tmp) fs.unlinkSync(tmp) } catch (_) { /* ignore */ }
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})

// ---------- Shell 集成：单实例锁 + 文件参数（右键菜单 / 命令行 / 拖入） ----------
// 仅当成功获取单实例锁时才启动；否则说明已有实例运行，把参数转给它后退出。
// mainWindow / pendingShellPaths 状态在文件顶部声明（关闭行为模块也需要主窗口句柄）。

// 从启动参数提取真实存在的文件/目录路径，过滤掉 exe 自身、脚本路径与 flag。
//   - 打包态：argv = [BempDiff.exe, fileA, fileB]
//   - 开发态：argv = [electron.exe, '.', fileA, fileB]（'.' 是 cwd，需排除）
function extractPathsFromArgv(argv) {
  const self = path.resolve('.')
  const script = path.resolve(__dirname, 'main.js')
  const paths = []
  for (const a of argv.slice(1)) {
    if (!a || typeof a !== 'string' || a.startsWith('-')) continue
    let rp
    try { rp = path.resolve(a) } catch (_) { continue }
    if (rp === self || rp === script) continue
    if (!fs.existsSync(rp)) continue
    paths.push(rp)
  }
  return paths
}

function sendShellCompare(paths) {
  if (!paths || !paths.length) return
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('bempdiff:shell-compare', paths)
  } else {
    pendingShellPaths = paths // 窗口尚未就绪，先排队
  }
}

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  // 第二实例（用户在资源管理器右键了第二个文件）：把路径转给已运行实例，并把它提到前台。
  // 主窗口可能正被「最小化到托盘」隐藏，故用 showMainWindow 而非 getAllWindows()[0].focus()。
  app.on('second-instance', (event, argv) => {
    if (!showMainWindow()) {
      const w = BrowserWindow.getAllWindows()[0]
      if (w) { if (w.isMinimized()) w.restore(); if (!w.isVisible()) w.show(); w.focus() }
    }
    sendShellCompare(extractPathsFromArgv(argv))
  })

  // 退出路径（app.quit / 托盘退出 / 系统关机）先置位：close 事件据此放行，不再弹关闭对话框。
  app.on('before-quit', () => { isQuitting = true })
  app.on('before-quit', stopSidecar)
  // 双保险：will-quit 一定在退出前触发（before-quit 可能因窗口关闭竞态/被 preventDefault 跳过）。
  // stopSidecar 内部 taskkill 是幂等的，重复调用无害。
  app.on('will-quit', stopSidecar)
  app.on('will-quit', () => {
    if (tray && !tray.isDestroyed()) tray.destroy()
    tray = null
  })

  app.whenReady().then(async () => {
    let initialUrl
    if (process.env.BEMPDIFF_DEV_URL) {
      // 指向外部已运行的服务（调试用），不启动本地 sidecar
      initialUrl = process.env.BEMPDIFF_DEV_URL
    } else {
      // 清理旧实例占用的端口，允许干净重启
      killPort(PORT)
      killPort(DEV_PORT)
      // 先弹启动图，让后端 sidecar 启动的这数秒有可视反馈（否则黑屏等待）
      createSplashWindow()
      const backendOk = await startBackend()
      const viteStarted = await startFrontend()
      // 后端启动快，优先用它开窗口（同源，API 直连）；若后端未就绪（罕见：产物缺失/ javaw 缺失）
      // 且 vite 在跑，则退回 vite 地址避免白屏（此状态下 API 仍可能不可达，仅保证页面可见）。
      initialUrl = backendOk
        ? `http://127.0.0.1:${PORT}/`
        : (viteStarted ? `http://127.0.0.1:${DEV_PORT}/` : `http://127.0.0.1:${PORT}/`)
      if (viteStarted && backendOk) {
        waitPort(DEV_PORT, 60000).then((ok) => {
          if (ok) {
            const w = BrowserWindow.getAllWindows()[0]
            if (w && !w.isDestroyed()) w.loadURL(`http://127.0.0.1:${DEV_PORT}/`)
          }
        })
      }
    }

    // 直接使用 createWindow 返回值作为主窗口句柄，而非 getAllWindows()[0]——
    // splash 在 createWindow 之前创建，getAllWindows[0] 可能误取 splash（时序竞争），
    // 导致 sendShellCompare/activate 作用到 splash 而非主窗，首次带文件参数自动比对丢失。
    mainWindow = createWindow(initialUrl)
    // 冲刷排队参数（窗口就绪前收到的 second-instance）
    if (pendingShellPaths) { sendShellCompare(pendingShellPaths); pendingShellPaths = null }
    // 首次启动即带文件参数（双击 / 右键第一个文件 / 命令行）：自动开始比对
    const initPaths = extractPathsFromArgv(process.argv)
    if (initPaths.length) sendShellCompare(initPaths)

    app.on('activate', () => {
      // 托盘隐藏的窗口仍在：直接恢复显示，不再新建第二个窗口。
      if (showMainWindow()) return
      if (BrowserWindow.getAllWindows().length === 0) mainWindow = createWindow(initialUrl)
    })
  })
}

// 开发态：关闭所有窗口即退出（并回收 sidecar）
app.on('window-all-closed', () => {
  app.quit()
})
