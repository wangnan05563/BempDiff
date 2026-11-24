/**
 * HTML 报告导出（二期 R11 / T01476）——纯函数，供 ReportPreview「导出 HTML」使用。
 *
 * 设计要点：
 * - 自包含单文件：内联 CSS、无外部依赖、打印友好（@media print），接收方无需任何工具即可阅读；
 * - 自定义模板字段：标题 / 副标题 / 落款 / 风险口径（审计口径说明），全部经 HTML 转义后注入；
 * - 正文复用既有 renderMarkdown（行级转义）+ colorizeReport（风险等级着色）管线，与预览一致；
 * - 生成时间注入页脚，供审计留痕。
 */
import { renderMarkdown } from './markdown.js'
import { colorizeReport } from './severity.js'

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/**
 * 构建 HTML 报告。
 * @param {object} opts
 *   - md: 报告 Markdown 原文（必填）
 *   - title: 报告标题（默认「BempDiff 差异分析报告」）
 *   - subtitle: 副标题（如 jobId / 对比对象）
 *   - author: 落款（生成人/团队）
 *   - riskNote: 风险口径说明（评估基准与边界，审计必读）
 *   - generatedAt: 生成时间字符串（默认当前本地时间）
 * @returns {string} 完整 HTML 文档字符串
 */
export function buildHtmlReport(opts = {}) {
  const md = opts.md || ''
  const title = escapeHtml(opts.title || 'BempDiff 差异分析报告')
  const subtitle = escapeHtml(opts.subtitle || '')
  const author = escapeHtml(opts.author || '')
  const riskNote = escapeHtml(opts.riskNote || '')
  const at = escapeHtml(opts.generatedAt || new Date().toLocaleString('zh-CN'))
  const body = colorizeReport(renderMarkdown(md)) // 与预览同一渲染管线，风险等级着色一致

  return `<!DOCTYPE html>
<html lang="zh-CN" data-bs-theme="light">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  :root { color-scheme: light dark; }
  body { max-width: 60rem; margin: 0 auto; padding: 2rem 1.2rem 3rem;
         font: 15px/1.65 "Segoe UI", "Microsoft YaHei", system-ui, sans-serif; color: #213547; }
  h1 { font-size: 1.45rem; border-bottom: 2px solid #0d6efd; padding-bottom: .4rem; }
  h2 { font-size: 1.15rem; margin-top: 1.6rem; }
  table { border-collapse: collapse; margin: .8rem 0; }
  th, td { border: 1px solid #d0d7de; padding: .35rem .6rem; text-align: left; }
  th { background: #f6f8fa; }
  code { background: #f0f2f5; padding: .08rem .3rem; border-radius: .25rem; font-size: .9em; }
  pre { background: #f6f8fa; padding: .7rem .9rem; border-radius: .4rem; overflow: auto; }
  .rp-meta { color: #57606a; font-size: .85rem; margin: .2rem 0 .8rem; }
  .rp-risk-note { background: #fff8e1; border: 1px solid #f0c95c; border-radius: .4rem;
                  padding: .55rem .8rem; margin: .8rem 0; font-size: .85rem; }
  .rp-risk-note b { display: block; margin-bottom: .15rem; }
  .rp-footer { margin-top: 2.2rem; padding-top: .7rem; border-top: 1px dashed #d0d7de;
               color: #57606a; font-size: .78rem; display: flex; justify-content: space-between; }
  .rp-sev-高, .rp-sev-high { color: #b02a37; font-weight: 700; }
  .rp-sev-中, .rp-sev-medium { color: #9a6700; font-weight: 700; }
  .rp-sev-低, .rp-sev-low { color: #1a7f37; font-weight: 700; }
  @media (prefers-color-scheme: dark) {
    body { background: #14181d; color: #d6dde4; }
    th { background: #1d232a; } th, td { border-color: #333c45; }
    code { background: #232b33; } pre { background: #1d232a; }
    h1 { border-bottom-color: #4d8bfd; }
    .rp-risk-note { background: #2b2410; border-color: #6b5a1e; color: #d6c88a; }
    .rp-meta, .rp-footer { color: #8b96a1; }
  }
  @media print {
    body { padding: 0; }
    .rp-risk-note { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  }
</style>
</head>
<body>
<h1>${title}</h1>
${subtitle ? `<div class="rp-meta">${subtitle}</div>` : ''}
${riskNote ? `<div class="rp-risk-note"><b>风险口径说明</b>${riskNote}</div>` : ''}
<div class="rp-report-body">
${body}
</div>
<div class="rp-footer">
  <span>${author ? '落款：' + author + '　·　' : ''}生成时间：${at}</span>
  <span>由 BempDiff 生成（包/目录差异化对比）</span>
</div>
</body>
</html>`
}

/** 默认元信息（ReportPreview 打开导出面板时预填）。 */
export function defaultReportMeta(state) {
  const job = state && state.job
  return {
    title: 'BempDiff 差异分析报告',
    subtitle: job ? `任务 ${job.jobId}` : '',
    author: '',
    riskNote: ''
  }
}
