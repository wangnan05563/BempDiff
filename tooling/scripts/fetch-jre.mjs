#!/usr/bin/env node
/**
 * BempDiff —— 按目标架构拉取 JRE 并就位到 release/dist_input/jre。
 *
 * 背景（T01509 arm64 打包落地）：
 *   产物里的 JRE 必须与 Electron 壳同架构。x64 产物用 win_x64 JRE，
 *   arm64 产物必须换 win_aarch64，否则 sidecar 起不来（x64 JRE 在
 *   Windows on ARM 上靠仿真能跑，但性能/功耗显著劣化，且不是原生 arm64）。
 *
 * 用法：
 *   node tooling/scripts/fetch-jre.mjs                       # 默认 win x64
 *   node tooling/scripts/fetch-jre.mjs --os win --arch arm64
 *   node tooling/scripts/fetch-jre.mjs --arch x64 --force    # 强制重下
 *   node tooling/scripts/fetch-jre.mjs --check               # 只校验不下载
 *
 * 行为：
 *   1) 目标目录 release/dist_input/jre-<os>-<arch>/（不覆盖现役 release/dist_input/jre）
 *   2) 已存在且通过 PE/ELF 架构校验 → 直接复用，秒退（幂等）
 *   3) 下载 zip 到 release/dist_input/.cache/，校验体积后解压
 *   4) 打印校验结论：java -version + 架构判定，供人工核对
 *
 * 设计约束：
 *   - 不删除现役 release/dist_input/jre（承重目录，且是 x64 产线的现役载荷）
 *   - 纯 Node 实现，无第三方依赖（构建链不引入新包）
 *   - 校验用 java 自身而非 file 命令（Windows 无 file）
 */
import { execFileSync } from 'node:child_process'
import { createWriteStream } from 'node:fs'
import fs from 'node:fs'
import path from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')          // 仓库根
const BEMP = path.join(REPO, 'bempdiff')

/**
 * JRE 矩阵（对齐 docs/arm64平台评估.md §3「JRE 下载源清单」）。
 * version 固定为打包基线版本，升级时改这一处即可。
 */
const JRE_VERSION = '21.52.15-21'
const ZULU_RELEASE = 'zulu21.52.15-ca-jre21.0.12'
const CDN = 'https://cdn.azul.com/zulu/bin'

/** os+arch → zulu 发行包后缀 / 解压后的可执行相对路径 / 期望的架构标记 */
const MATRIX = {
  'win-x64': { file: `${ZULU_RELEASE}-win_x64.zip`, exe: 'bin/java.exe', os: 'win' },
  'win-arm64': { file: `${ZULU_RELEASE}-win_aarch64.zip`, exe: 'bin/java.exe', os: 'win' },
  'linux-x64': { file: `${ZULU_RELEASE}-linux_x64.tar.gz`, exe: 'bin/java', os: 'linux' },
  'linux-arm64': { file: `${ZULU_RELEASE}-linux_aarch64.tar.gz`, exe: 'bin/java', os: 'linux' },
  'macos-arm64': { file: `${ZULU_RELEASE}-macosx_aarch64.tar.gz`, exe: 'Contents/Home/bin/java', os: 'macos' },
}

function parseArgs(argv) {
  const out = { os: 'win', arch: null, force: false, check: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--os') out.os = argv[++i]
    else if (a === '--arch') out.arch = argv[++i]
    else if (a === '--force') out.force = true
    else if (a === '--check') out.check = true
    else if (a === '--help' || a === '-h') { out.help = true }
  }
  return out
}

function targetKey(os, arch) {
  return `${os}-${arch}`
}

function destDir(os, arch) {
  return path.join(REPO, 'release', 'dist_input', `jre-${os}-${arch}`)
}

function cacheFile(file) {
  return path.join(REPO, 'release', 'dist_input', '.cache', file)
}

/**
 * 探测就位状态。
 * 注意跨架构：x64 主机上无法执行 arm64 二进制（也无仿真保证），
 * 故「同架构」才用 -version 实跑；异架构退化为静态 PE 校验。
 * 返回 { kind, detail }：kind = 'run' | 'static' | 'missing'
 */
function probe(dir, meta, arch) {
  const exe = path.join(dir, meta.exe)
  if (!fs.existsSync(exe)) return { kind: 'missing', detail: 'java 不存在' }
  const hostArch = process.arch === 'arm64' ? 'arm64' : 'x64'
  if (arch !== hostArch || meta.os !== 'win') {
    return { kind: 'static', detail: '异架构，仅静态校验' }
  }
  try {
    const out = execFileSync(exe, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    const first = String(out).split(/\r?\n/)[0] || ''
    return { kind: 'run', detail: first.trim() }
  } catch (e) {
    return { kind: 'static', detail: `执行失败(${e.status ?? 'err'})，退静态校验` }
  }
}

async function download(url, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  const res = await fetch(url)
  if (!res.ok) throw new Error(`下载失败 HTTP ${res.status}: ${url}`)
  const total = Number(res.headers.get('content-length') || 0)
  process.stderr.write(`  下载中 ${(total / 1048576).toFixed(1)}MB ... `)
  let got = 0
  let lastTick = 0
  const src = Readable.fromWeb(res.body)
  src.on('data', (c) => {
    got += c.length
    const now = Date.now()
    if (now - lastTick > 2000) {
      process.stderr.write(`${(got / 1048576).toFixed(0)}/${(total / 1048576).toFixed(0)}MB  `)
      lastTick = now
    }
  })
  await pipeline(src, createWriteStream(dest))
  process.stderr.write(`完成 ${(got / 1048576).toFixed(1)}MB\n`)
  return got
}

/** zip 用 PowerShell Expand-Archive（tar.gz 交给 tar） */
function extract(archive, destDirPath) {
  fs.rmSync(destDirPath, { recursive: true, force: true })
  fs.mkdirSync(destDirPath, { recursive: true })
  if (archive.endsWith('.zip')) {
    execFileSync('powershell', [
      '-NoProfile', '-NonInteractive', '-Command',
      `Expand-Archive -LiteralPath '${archive}' -DestinationPath '${destDirPath}' -Force`,
    ], { stdio: ['ignore', 'inherit', 'inherit'] })
  } else {
    execFileSync('tar', ['-xzf', archive, '-C', destDirPath], { stdio: ['ignore', 'inherit', 'inherit'] })
  }
  // zulu 解包后是一层版本目录（zulu21.x-.../），拍平一层
  const kids = fs.readdirSync(destDirPath, { withFileTypes: true })
  if (kids.length === 1 && kids[0].isDirectory()) {
    const inner = path.join(destDirPath, kids[0].name)
    for (const e of fs.readdirSync(inner)) {
      fs.renameSync(path.join(inner, e), path.join(destDirPath, e))
    }
    fs.rmSync(inner, { recursive: true, force: true })
  }
}

function machineTypeOf(exe) {
  /** 读 PE 头的 Machine 字段（offset e_lfanew + 4），判断是否 ARM64 */
  if (!fs.existsSync(exe)) return 'missing'
  const fd = fs.openSync(exe, 'r')
  try {
    const dos = Buffer.alloc(64)
    fs.readSync(fd, dos, 0, 64, 0)
    if (dos.readUInt16LE(0) !== 0x5a4d) return 'not-pe'
    const peOff = dos.readUInt32LE(0x3c)
    const pe = Buffer.alloc(6)
    fs.readSync(fd, pe, 0, 6, peOff)
    if (pe.readUInt32LE(0) !== 0x00004550) return 'not-pe'
    const machine = pe.readUInt16LE(4)
    if (machine === 0xaa64) return 'ARM64'
    if (machine === 0x8664) return 'x64'
    if (machine === 0x01c4) return 'ARM'
    return `0x${machine.toString(16)}`
  } finally {
    fs.closeSync(fd)
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0].replace(/^\/\*\*?/, ''))
    return
  }
  // 未显式给 --arch 时：win→x64，其余按原生 arm64
  const arch = args.arch || (args.os === 'win' ? 'x64' : 'arm64')
  const key = targetKey(args.os, arch)
  const meta = MATRIX[key]
  if (!meta) {
    console.error(`不支持的目标：${key}`)
    console.error(`可选：${Object.keys(MATRIX).join(', ')}`)
    process.exit(2)
  }

  const dest = destDir(args.os, arch)
  const exe = path.join(dest, meta.exe)
  const expectMach = arch === 'arm64' ? 'ARM64' : 'x64'

  console.log(`[fetch-jre] 目标 ${key}  ->  ${path.relative(REPO, dest)}`)

  const existing = probe(dest, meta, arch)
  const mach = machineTypeOf(exe)
  const archOk = meta.os !== 'win' || mach === expectMach
  if (existing.kind !== 'missing' && archOk && !args.force) {
    console.log(`  已就位，复用（${existing.detail}）`)
    console.log(`  PE 机器类型：${mach}${meta.os === 'win' ? '' : '（非 Windows 目标，PE 字段不适用）'}`)
    console.log(`JRE_DIR=${path.relative(REPO, dest)}`)
    return
  }
  if (args.check) {
    if (existing.kind === 'missing') console.log(`  未就位（${dest}）`)
    else if (!archOk) console.log(`  架构不符：${mach} != ${expectMach}`)
    process.exit(1)
  }

  const url = `${CDN}/${meta.file}`
  const archive = cacheFile(meta.file)
  console.log(`  源：${url}`)
  if (!fs.existsSync(archive) || args.force) await download(url, archive)
  else console.log(`  复用缓存 ${(fs.statSync(archive).size / 1048576).toFixed(1)}MB`)

  console.log('  解压 ...')
  extract(archive, dest)

  const ver = probe(dest, meta, arch)
  const mach2 = machineTypeOf(exe)
  if (ver.kind === 'missing') {
    console.error(`  解压后 java 缺失：${exe}`)
    process.exit(1)
  }
  console.log(`  校验：${ver.detail}`)
  console.log(`  PE 机器类型：${mach2}${meta.os === 'win' ? '' : '（非 Windows 目标，PE 字段不适用）'}`)
  if (meta.os === 'win' && mach2 !== expectMach) {
    console.error(`  架构不符！期望 ${expectMach} 实得 ${mach2}`)
    process.exit(1)
  }
  if (ver.kind === 'static') {
    console.log('  注：当前主机架构与目标不一致，未实跑 java；产物需在目标架构机器/CI 上做启动实测。')
  }
  console.log(`JRE_DIR=${path.relative(REPO, dest)}`)
  console.log(`（下一步：npm run dist${arch === 'arm64' ? ':arm64' : ''} ）`)
}

main().catch((e) => {
  console.error('[fetch-jre] 失败：', e.message)
  process.exit(1)
})
