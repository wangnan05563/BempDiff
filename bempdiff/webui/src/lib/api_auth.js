// 本机 API 会话 token：优先 Electron preload（主进程每次启动随机生成），
// 浏览器开发态回退 Vite 注入的 VITE_BEMPDIFF_API_TOKEN。
// 生产浏览器由后端首页下发 HttpOnly + SameSite=Strict Cookie；token 头是桌面壳/开发态的补充。
function readToken() {
  try {
    if (typeof window !== 'undefined' && window.bempdiff && window.bempdiff.apiToken) {
      return String(window.bempdiff.apiToken)
    }
  } catch (_) { /* 非浏览器环境 */ }
  try {
    return String(import.meta.env.VITE_BEMPDIFF_API_TOKEN || '')
  } catch (_) {
    return ''
  }
}

export const API_TOKEN = readToken()

/** 统一请求头：CSRF 固定头 + 会话 token（无 token 时保持旧行为，便于纯浏览器同源 Cookie）。 */
export function authHeaders(extra = {}) {
  const h = { 'X-BempDiff-Request': '1', ...extra }
  if (API_TOKEN) h['X-BempDiff-Token'] = API_TOKEN
  return h
}

/** 给下载直链 / window.open 追加 token（Cookie 不可用的开发态兜底）。 */
export function withToken(path) {
  if (!API_TOKEN) return path
  const sep = String(path).includes('?') ? '&' : '?'
  return `${path}${sep}token=${encodeURIComponent(API_TOKEN)}`
}
