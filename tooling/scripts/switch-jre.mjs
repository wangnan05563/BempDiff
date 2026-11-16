#!/usr/bin/env node
/**
 * BempDiff —— 打包前切换 release/dist_input/jre 的架构载荷。
 *
 * 为什么需要（配合 T01509 arm64 落地）：
 *   electron-builder 的 extraResources 固定指向 `release/dist_input/jre`（产物内路径
 *   `bempdiff/dist_input/jre`）。壳与 JRE 必须同架构，而一次打包只出一个架构，
 *   所以需要在打包前把对应架构的 JRE 摆到那个固定路径。
 *
 *   直接覆盖会毁掉 x64 产线的现役载荷（release/dist_input/jre 是承重目录，且是
 *   「不重新打包就不生效」链条的一环）。故采用「归档 + 切换」：
 *     归档：release/dist_input/jre-<os>-<arch>/   （fetch-jre.mjs 负责落位）
 *     现役：release/dist_input/jre                （本脚本原子换入/换回）
 *
 * 用法：
 *   node tooling/scripts/switch-jre.mjs --arch arm64   # 切到 win arm64
 *   node tooling/scripts/switch-jre.mjs --arch x64     # 切回 win x64
 *   node tooling/scripts/switch-jre.mjs --status       # 只看当前现役是哪个架构
 *
 * 语义：
 *   - 幂等：目标已是现役则直接退出（exit 0）
 *   - 换入用 rename（同卷原子），失败不留半成品
 *   - 不删除任何 JRE 归档，只在 jre 与 jre-<os>-<arch> 之间搬运
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const DIST = path.join(REPO, 'release', 'dist_input')
const LIVE = path.join(DIST, 'jre')
/** 现役 JRE 的架构标记文件（switch 时写/读） */
const STAMP = path.join(LIVE, '.bempdiff-jre-arch')

function parseArgs(argv) {
  const out = { arch: null, status: false }
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--arch') out.arch = argv[++i]
    else if (argv[i] === '--status') out.status = true
  }
  return out
}

function readStamp() {
  try {
    return fs.readFileSync(STAMP, 'utf8').trim()
  } catch {
    return '(无标记文件)'
  }
}

function machineTypeOf(exe) {
  if (!fs.existsSync(exe)) return 'missing'
  const fd = fs.openSync(exe, 'r')
  try {
    const dos = Buffer.alloc(64)
    fs.readSync(fd, dos, 0, 64, 0)
    if (dos.readUInt16LE(0) !== 0x5a4d) return 'not-pe'
    const peOff = dos.readUInt32LE(0x3c)
    const pe = Buffer.alloc(6)
    fs.readSync(fd, pe, 0, 6, peOff)
    const m = pe.readUInt16LE(4)
    return m === 0xaa64 ? 'ARM64' : m === 0x8664 ? 'x64' : `0x${m.toString(16)}`
  } finally {
    fs.closeSync(fd)
  }
}

/** 解包态的 JRE 目录里没有 release/dist_input/.cache 等，只需 bin/java 存在即可 */
function healthy(dir) {
  return fs.existsSync(path.join(dir, 'bin', 'java.exe')) || fs.existsSync(path.join(dir, 'bin', 'java'))
}

function main() {
  const args = parseArgs(process.argv.slice(2))

  if (args.status || !args.arch) {
    console.log(`现役 ${path.relative(REPO, LIVE)}`)
    console.log(`  标记：${readStamp()}`)
    console.log(`  PE  ：${machineTypeOf(path.join(LIVE, 'bin', 'java.exe'))}`)
    const arches = fs.existsSync(DIST)
      ? fs.readdirSync(DIST).filter((n) => /^jre-win-/.test(n))
      : []
    console.log(`  可用归档：${arches.length ? arches.join(', ') : '（无）'}`)
    if (!args.arch) return
  }

  const expect = args.arch === 'arm64' ? 'ARM64' : 'x64'
  const key = `jre-win-${args.arch}`
  const src = path.join(DIST, key)

  // 幂等短路必须排在归档检查之前：现役已是目标架构时，
  // 对应归档「理应」不在（它就是现役本身），先查归档会误报不可用。
  const liveMachNow = machineTypeOf(path.join(LIVE, 'bin', 'java.exe'))
  if (healthy(LIVE) && liveMachNow === expect) {
    console.log(`现役已是 win ${args.arch}（${liveMachNow}），无需切换。`)
    return
  }

  if (!healthy(src)) {
    console.error(`归档不可用：${path.relative(REPO, src)}`)
    console.error(`先执行：node tooling/scripts/fetch-jre.mjs --os win --arch ${args.arch}`)
    process.exit(1)
  }
  const mach = machineTypeOf(path.join(src, 'bin', 'java.exe'))
  if (mach !== expect) {
    console.error(`归档架构不符：${key} 的 java.exe 为 ${mach}，期望 ${expect}`)
    process.exit(1)
  }

  // 同卷 rename：先把现役挪成归档，再把目标挪成现役。
  // 无标记文件时（首次接管旧目录）按 PE 机器类型反推真实架构，
  // 否则会把 x64 存成 jre-prev，导致 --arch x64 找不到归档、来回切不了。
  const stamped = readStamp()
  const liveMach = machineTypeOf(path.join(LIVE, 'bin', 'java.exe'))
  const curArch = stamped === '(无标记文件)'
    ? (liveMach === 'ARM64' ? 'arm64' : liveMach === 'x64' ? 'x64' : null)
    : stamped
  if (healthy(LIVE)) {
    const park = curArch ? path.join(DIST, `jre-win-${curArch}`) : path.join(DIST, 'jre-prev')
    if (healthy(park)) {
      // 归档已存在且完好 → 回收旧副本，避免 rename 冲突
      fs.rmSync(park, { recursive: true, force: true })
    }
    fs.renameSync(LIVE, park)
    console.log(`现役归档 → ${path.basename(park)}`)
  }
  fs.renameSync(src, LIVE)
  fs.writeFileSync(STAMP, args.arch, 'utf8')
  console.log(`已切换：现役 = win ${args.arch}（${mach}）`)
  console.log(`提示：换回用 node tooling/scripts/switch-jre.mjs --arch x64`)
}

main()
