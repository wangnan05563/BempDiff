// M2/REQ-013 设计 Token 架构（T06215）
// 语义 Token Registry：颜色/间距/圆角/字体四类，主题只允许声明颜色 Token；
// 非法主题（缺 Token、非法值）回退 light，并返回可复核的校验错误，避免运行时静默崩坏。
//
// 约定：
// - 主题 key：light | dark | midnight | paper | contrast | system（system 由 matchMedia 解析到 light/dark）
// - CSS 变量名统一 --bdf-*；tokens.css 负责把注册值落成 CSS 变量。
// - 组件禁止直接写 #hex/rgb：优先 var(--bdf-...)，需要 Bootstrap 语义时用 var(--bs-...)。

export const TOKEN_SCHEMA = {
  color: {
    canvas: '--bdf-canvas',
    surface: '--bdf-surface',
    surfaceRaised: '--bdf-surface-raised',
    text: '--bdf-text',
    textMuted: '--bdf-text-muted',
    border: '--bdf-border',
    accent: '--bdf-accent',
    onAccent: '--bdf-on-accent',
    focusRing: '--bdf-focus-ring',
    diffAddBg: '--bdf-diff-add-bg',
    diffAddFg: '--bdf-diff-add-fg',
    diffDelBg: '--bdf-diff-del-bg',
    diffDelFg: '--bdf-diff-del-fg',
    diffModBg: '--bdf-diff-mod-bg',
    diffModFg: '--bdf-diff-mod-fg',
    riskBreaking: '--bdf-risk-breaking',
    riskHigh: '--bdf-risk-high',
    riskMedium: '--bdf-risk-medium',
    riskLow: '--bdf-risk-low',
    riskNone: '--bdf-risk-none'
  },
  space: {
    xxs: '--bdf-space-xxs',
    xs: '--bdf-space-xs',
    sm: '--bdf-space-sm',
    md: '--bdf-space-md',
    lg: '--bdf-space-lg',
    xl: '--bdf-space-xl'
  },
  radius: {
    sm: '--bdf-radius-sm',
    md: '--bdf-radius-md',
    lg: '--bdf-radius-lg',
    pill: '--bdf-radius-pill'
  },
  font: {
    family: '--bdf-font-family',
    sizeXs: '--bdf-font-size-xs',
    sizeSm: '--bdf-font-size-sm',
    sizeMd: '--bdf-font-size-md',
    sizeLg: '--bdf-font-size-lg',
    weightStrong: '--bdf-font-weight-strong'
  }
}

export const REQUIRED_COLOR_TOKENS = Object.keys(TOKEN_SCHEMA.color)
export const FALLBACK_THEME = 'light'
export const THEME_NAMES = ['light', 'dark', 'midnight', 'paper', 'contrast', 'system']

// Bootstrap 只认 light/dark：6 主题中的午夜/高对比走 dark 底座，护眼走 light 底座；
// Token 主题仍保留 midnight/paper/contrast，由 data-token-theme 驱动语义色。
export const THEME_BOOT_MODE = {
  light: 'light', dark: 'dark', midnight: 'dark', paper: 'light', contrast: 'dark'
}
export function resolveBootstrapMode(tokenTheme) {
  return THEME_BOOT_MODE[tokenTheme] || 'light'
}

// 颜色值校验：允许 hex/rgb/rgba/hsl/hsla、CSS 命名色与 var(--x)；
// 不允许空值/透明关键字以外的任意字符串，防止把拼错的变量写进主题。
const COLOR_RE = /^(#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|(?:rgb|rgba|hsl|hsla)\([^)]*\)|var\(--[a-zA-Z0-9_-]+(?:\s*,\s*[^)]+)?\)|transparent|currentColor|[a-zA-Z]+)$/

export const THEMES = {
  light: {
    canvas: '#ffffff', surface: '#f8f9fa', surfaceRaised: '#ffffff',
    text: '#212529', textMuted: '#6c757d', border: '#dee2e6',
    accent: '#0d6efd', onAccent: '#ffffff', focusRing: '#0b5ed7',
    diffAddBg: '#e6ffed', diffAddFg: '#116329',
    diffDelBg: '#ffebe9', diffDelFg: '#82071e',
    diffModBg: '#fff8c5', diffModFg: '#7d4e00',
    riskBreaking: '#dc3545', riskHigh: '#fd7e14', riskMedium: '#ffc107',
    riskLow: '#198754', riskNone: '#6c757d'
  },
  dark: {
    canvas: '#212529', surface: '#2b3035', surfaceRaised: '#343a40',
    text: '#dee2e6', textMuted: '#adb5bd', border: '#495057',
    accent: '#4dabf7', onAccent: '#0b1220', focusRing: '#74c0fc',
    diffAddBg: '#033a1f', diffAddFg: '#75b798',
    diffDelBg: '#4a0d14', diffDelFg: '#f1aeb5',
    diffModBg: '#3d3200', diffModFg: '#ffda6a',
    riskBreaking: '#ff6b6b', riskHigh: '#ffa94d', riskMedium: '#ffd43b',
    riskLow: '#69db7c', riskNone: '#adb5bd'
  },
  midnight: {
    canvas: '#0b1020', surface: '#121a2e', surfaceRaised: '#1a2440',
    text: '#e6edf7', textMuted: '#9aa7bd', border: '#2a3652',
    accent: '#7aa2ff', onAccent: '#081020', focusRing: '#91b4ff',
    diffAddBg: '#0c2e22', diffAddFg: '#8ce3b4',
    diffDelBg: '#3a1020', diffDelFg: '#ff9eb5',
    diffModBg: '#332a10', diffModFg: '#ffe08a',
    riskBreaking: '#ff6b8a', riskHigh: '#ffa94d', riskMedium: '#ffd43b',
    riskLow: '#63e6be', riskNone: '#9aa7bd'
  },
  paper: {
    canvas: '#f7f3e8', surface: '#efe8d8', surfaceRaised: '#fffaf0',
    text: '#2b2a26', textMuted: '#6b665c', border: '#d8cdb8',
    accent: '#8a5a2b', onAccent: '#fffaf0', focusRing: '#8a5a2b',
    diffAddBg: '#e4f3d8', diffAddFg: '#24552a',
    diffDelBg: '#f7ded6', diffDelFg: '#7c2d12',
    diffModBg: '#f5e8bd', diffModFg: '#6b4a00',
    riskBreaking: '#b3261e', riskHigh: '#c26a00', riskMedium: '#a97900',
    riskLow: '#2f6b3a', riskNone: '#6b665c'
  },
  contrast: {
    canvas: '#000000', surface: '#000000', surfaceRaised: '#111111',
    text: '#ffffff', textMuted: '#ffffff', border: '#ffffff',
    accent: '#ffff00', onAccent: '#000000', focusRing: '#00ffff',
    diffAddBg: '#003300', diffAddFg: '#00ff00',
    diffDelBg: '#330000', diffDelFg: '#ff5555',
    diffModBg: '#333300', diffModFg: '#ffff00',
    riskBreaking: '#ff5555', riskHigh: '#ffaa00', riskMedium: '#ffff00',
    riskLow: '#00ff66', riskNone: '#ffffff'
  }
}

const STATIC_THEME_TOKENS = {
  space: { xxs: '2px', xs: '4px', sm: '8px', md: '12px', lg: '16px', xl: '24px' },
  radius: { sm: '4px', md: '6px', lg: '10px', pill: '999px' },
  font: {
    family: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif',
    sizeXs: '11px', sizeSm: '12px', sizeMd: '13px', sizeLg: '15px', weightStrong: '600'
  }
}

function isColor(v) {
  return typeof v === 'string' && COLOR_RE.test(v.trim())
}

/** 校验单个主题；system 是解析别名，不参与静态校验。 */
export function validateTheme(name) {
  const errors = []
  if (name === 'system') return errors
  const theme = THEMES[name]
  if (!theme) {
    errors.push(`未知主题：${name}`)
    return errors
  }
  for (const token of REQUIRED_COLOR_TOKENS) {
    if (!(token in theme)) errors.push(`${name} 缺 Token：${token}`)
    else if (!isColor(theme[token])) errors.push(`${name}.${token} 非法颜色值：${String(theme[token])}`)
  }
  return errors
}

/** Registry 加载校验：6 个主题名必须注册，light/dark 等 5 个静态主题必须完整。 */
export function validateRegistry() {
  const errors = []
  for (const name of THEME_NAMES) {
    if (name !== 'system' && !THEMES[name]) errors.push(`Registry 未注册主题：${name}`)
    if (name !== 'system') errors.push(...validateTheme(name))
  }
  return errors
}

/** 启动时加载 Registry；校验失败回退 light，但保留错误供诊断，不阻断 UI。 */
export function initTokenRegistry() {
  const errors = validateRegistry()
  if (errors.length) {
    console.warn('[token-registry] 校验失败，已回退 light：', errors)
    return { ok: false, fallback: FALLBACK_THEME, errors }
  }
  return { ok: true, fallback: FALLBACK_THEME, errors: [] }
}

/** 解析可渲染主题名：system 按系统亮暗；非法值回退 light。 */
export function resolveTokenTheme(pref, systemDark) {
  if (pref === 'system') return systemDark ? 'dark' : 'light'
  return THEMES[pref] && pref !== 'system' ? pref : FALLBACK_THEME
}
/** 把主题属性写到 html（CSS 变量由 tokens.css 对应选择器生效）。 */
export function applyTokenTheme(pref, systemDark) {
  const theme = resolveTokenTheme(pref, systemDark)
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-token-theme', theme)
    document.documentElement.setAttribute('data-token-pref', THEME_NAMES.includes(pref) ? pref : FALLBACK_THEME)
  }
  return theme
}

export function staticTokens() {
  return STATIC_THEME_TOKENS
}
