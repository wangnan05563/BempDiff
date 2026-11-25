import { describe, it, expect, beforeEach, vi } from 'vitest'

describe('api_auth 本机会话 token', () => {
  beforeEach(() => {
    delete window.bempdiff
    vi.resetModules()
  })

  it('无 Electron 桥时不注入 token，仍带 CSRF 固定头', async () => {
    const m = await import('../lib/api_auth')
    expect(m.API_TOKEN).toBe('')
    expect(m.authHeaders()).toEqual({ 'X-BempDiff-Request': '1' })
    expect(m.withToken('/api/x')).toBe('/api/x')
  })

  it('preload 提供 token 时所有请求头带 token', async () => {
    window.bempdiff = { apiToken: 'tok-abc' }
    const m = await import('../lib/api_auth')
    expect(m.authHeaders({ 'Content-Type': 'application/json' })).toEqual({
      'X-BempDiff-Request': '1',
      'X-BempDiff-Token': 'tok-abc',
      'Content-Type': 'application/json'
    })
  })

  it('下载直链正确追加 token 查询参数', async () => {
    window.bempdiff = { apiToken: 'tok-abc' }
    const m = await import('../lib/api_auth')
    expect(m.withToken('/api/export/E1/download')).toBe('/api/export/E1/download?token=tok-abc')
    expect(m.withToken('/api/x?a=1')).toBe('/api/x?a=1&token=tok-abc')
  })
})
