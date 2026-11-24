import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { validateRegistry, validateTheme, THEME_NAMES, THEMES, TOKEN_SCHEMA, REQUIRED_COLOR_TOKENS } from '../src/design/tokenRegistry.js'

const errors = validateRegistry()
if (errors.length) {
  console.error('TOKEN_REGISTRY_INVALID')
  for (const e of errors) console.error('-', e)
  process.exit(1)
}

for (const name of THEME_NAMES) {
  if (name === 'system') continue
  const themeErrors = validateTheme(name)
  if (themeErrors.length) {
    console.error('THEME_INVALID', name, themeErrors)
    process.exit(1)
  }
}

// CSS 落值层覆盖：每个 schema 变量都必须在 tokens.css 出现，防止 JS Registry 与 CSS 漂移。
const css = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8')
const missingCss = []
for (const group of Object.values(TOKEN_SCHEMA)) {
  for (const v of Object.values(group)) {
    if (!css.includes(`${v}:`)) missingCss.push(v)
  }
}
if (missingCss.length) {
  console.error('TOKENS_CSS_MISSING', missingCss)
  process.exit(1)
}

// 每个静态主题的 CSS 块必须完整覆盖 20 个颜色 Token，且值必须与 JS Registry 一致，
// 避免“全局存在但某个主题漏写”或 focusRing 之类的手工同步漂移。
const blockErrors = []
for (const [name, theme] of Object.entries(THEMES)) {
  const blockMatch = css.match(new RegExp(`\\[data-token-theme="${name}"\\]\\s*\\{([^}]*)\\}`, 's'))
  if (!blockMatch) {
    blockErrors.push(`${name}: CSS 主题块缺失`)
    continue
  }
  const block = blockMatch[1]
  for (const tokenName of REQUIRED_COLOR_TOKENS) {
    const varName = TOKEN_SCHEMA.color[tokenName]
    const valueMatch = block.match(new RegExp(`${varName}:\\s*([^;]+);`))
    if (!valueMatch) {
      blockErrors.push(`${name}: 缺 ${varName}`)
      continue
    }
    const cssValue = valueMatch[1].trim()
    if (cssValue.toLowerCase() !== String(theme[tokenName]).toLowerCase()) {
      blockErrors.push(`${name}: ${varName}=${cssValue} != Registry ${theme[tokenName]}`)
    }
  }
}
if (blockErrors.length) {
  console.error('TOKENS_CSS_DRIFT')
  for (const e of blockErrors) console.error('-', e)
  process.exit(1)
}
console.log(`TOKEN_REGISTRY_OK themes=${THEME_NAMES.length} static=${Object.keys(THEMES).length} cssVars=${Object.values(TOKEN_SCHEMA).reduce((n, g) => n + Object.keys(g).length, 0)} cssThemes=${Object.keys(THEMES).length} aligned=ok`)
