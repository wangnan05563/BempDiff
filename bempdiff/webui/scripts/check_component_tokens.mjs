// M2/T06216 核心组件 Token 校验：
// 1) 12 个核心页面/组件不得出现硬编码 hex 色值（Token 定义文件除外）；
// 2) 组件引用的每个 --bdf-* 必须已在 tokens.css 定义，防止拼错变量静默失效。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const files = [
  'src/App.vue',
  'src/components/ToolBar.vue',
  'src/components/DiffTree.vue',
  'src/components/DiffView.vue',
  'src/components/InfoPanel.vue',
  'src/components/StatusBar.vue',
  'src/components/ConfigDialog.vue',
  'src/components/ReportPreview.vue',
  'src/components/AiConsole.vue',
  'src/components/GuideOverlay.vue',
  'src/components/Downloads.vue',
  'src/components/ThemeStudio.vue'
]

function stripComments(src) {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').filter((line) => !line.trimStart().startsWith('//')).join('\n')
}

const css = fs.readFileSync(path.join(root, 'src/styles/tokens.css'), 'utf8')
const defined = new Set([...css.matchAll(/--bdf-[a-z0-9-]+/g)].map((m) => m[0]))

const hexErrors = []
const varErrors = []
let refs = 0
for (const rel of files) {
  const src = stripComments(fs.readFileSync(path.join(root, rel), 'utf8'))
  for (const m of src.matchAll(/#[0-9a-fA-F]{3,8}\b/g)) {
    hexErrors.push(`${rel}: ${m[0]}`)
  }
  for (const m of src.matchAll(/var\((--bdf-[a-z0-9-]+)/g)) {
    refs++
    if (!defined.has(m[1])) varErrors.push(`${rel}: 未定义 ${m[1]}`)
  }
}

if (hexErrors.length || varErrors.length) {
  console.error('COMPONENT_TOKENS_FAIL')
  for (const e of [...hexErrors, ...varErrors]) console.error(' -', e)
  process.exit(1)
}
console.log(`COMPONENT_TOKENS_OK files=${files.length} hex=0 refs=${refs}`)
