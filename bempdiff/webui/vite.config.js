import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时前端默认跑在 5180（避开与 wiki 等其他项目默认的 5173 冲突），后端 `server` 子命令默认 18765。
// dev proxy 把 /api 转发到后端，避免跨端口 CORS（后端也自带 Access-Control-Allow-Origin:* 兜底）。
// 生产（桌面壳 sidecar 或 `server --webroot`）由后端同源托管 SPA，base 用 './' 适配任意挂载路径。
const SERVER_PORT = process.env.BEMPDIFF_SERVER_PORT || '18765'

export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    port: 5180,
    strictPort: false,
    proxy: {
      '/api': {
        target: `http://127.0.0.1:${SERVER_PORT}`,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    // 本机回收站不可用 → WorkBuddy safe-delete 钩子 fail-closed：
    // vite 默认清空 dist 会调用 trash 操作并中断构建。故关闭自动清空，
    // 改为直接覆盖新哈希（旧哈希残留但无害，dist 为 gitignored 临时产物）。
    emptyOutDir: false
  }
})
