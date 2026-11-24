<script setup>
import { computed, ref } from 'vue'
import { state, generateReport, toast } from '../store'
import { renderMarkdown } from '../lib/markdown'
import { colorizeReport, sevClassFromText } from '../lib/severity'
import { buildHtmlReport, defaultReportMeta } from '../lib/html_report'
import { appendReviewAppendix, buildReviewAppendix, buildReviewJsonExport } from '../lib/review_notes'
import { t } from '../lib/i18n'

const props = defineProps({ visible: { type: Boolean, default: false }, md: { type: String, default: null } })
const emit = defineEmits(['close'])

// 报告正文来源：优先用调用方传入的 md（AI 控制台预览某次任务报告），否则用全局 state.reportMd。
// 用 null 判断而非 `||`：避免传入空字符串时错误回退到全局报告（评审 P2 #14）
const baseSrc = computed(() => props.md != null ? props.md : state.reportMd)
const srcIsTask = computed(() => props.md != null)

// T06221 备注/证据附录：默认含备注全文；勾选「排除备注」后只保留计数/标签/证据元数据。
// 附录在展示/导出时动态拼接，避免备注更新后报告缓存仍是旧内容。
const excludeNotes = ref(false)
const reviewAppendix = computed(() => props.md != null ? '' : buildReviewAppendix(state.review, { excludeNotes: excludeNotes.value }))
const hasReviewData = computed(() => !!reviewAppendix.value)
const src = computed(() => {
  if (props.md != null) return props.md
  return appendReviewAppendix(baseSrc.value, state.review, { excludeNotes: excludeNotes.value })
})

// 报告正文：渲染后做 AI 风险严重性着色（sev-* class）
const html = computed(() => src.value ? colorizeReport(renderMarkdown(src.value)) : '')

// 顶部「总体风险结论」汇总条：从报告中抽取 `整体风险：**高**`
const overallRisk = computed(() => {
  const m = src.value && src.value.match(/整体风险[：:]\s*\*\*?([^\n*]+?)\*\*?/)
  return m ? m[1].trim() : null
})
const riskCls = computed(() => overallRisk.value ? sevClassFromText(overallRisk.value) : null)

/**
 * 触发浏览器下载 Blob。
 * 注意：revokeObjectURL 必须延后——同步 revoke 会在大文件（HTML 报告）尚未开始读取时
 * 把 Blob URL 失效，Chrome 表现为「点了没反应、也没有任何提示」。这里统一延迟 4s。
 */
function triggerBlobDownload(filename, content, mime) {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
  return filename
}

function downloadMd() {
  if (!src.value) return
  try {
    triggerBlobDownload(
      `bempdiff-report-${state.job ? state.job.jobId : 'job'}.md`,
      src.value,
      'text/markdown;charset=utf-8'
    )
  } catch (e) {
    toast('danger', `${t('rp.downloadFail')} ${e && e.message ? e.message : e}`)
  }
}

// ===== R11 HTML 报告导出（二期 T01476）：自定义模板（标题/落款/风险口径）+ 自包含单文件 =====
const showHtmlForm = ref(false)
const reportMeta = ref(defaultReportMeta(state))
const META_KEY = 'bempdiff.reportMeta'
try {
  const saved = JSON.parse(localStorage.getItem(META_KEY) || 'null')
  if (saved && typeof saved === 'object') reportMeta.value = { ...reportMeta.value, ...saved }
} catch (_) { /* 坏数据静默 */ }

function toggleHtmlForm() { showHtmlForm.value = !showHtmlForm.value }

// HTML 导出结果提示：浏览器下载被策略拦截时，页面必须给出可操作反馈，避免「点了没反应」
const htmlMsg = ref('')
const htmlMsgErr = ref(false)

function downloadHtml() {
  if (!src.value) return
  htmlMsg.value = ''
  htmlMsgErr.value = false
  // 模板字段持久化（落款/风险口径下次导出复用；标题/副标题同样保存）
  try { localStorage.setItem(META_KEY, JSON.stringify(reportMeta.value)) } catch (_) { /* 静默 */ }
  try {
    const html = buildHtmlReport({
      md: src.value,
      title: reportMeta.value.title,
      subtitle: reportMeta.value.subtitle || (state.job ? `任务 ${state.job.jobId}` : ''),
      author: reportMeta.value.author,
      riskNote: reportMeta.value.riskNote
    })
    const name = triggerBlobDownload(
      `bempdiff-report-${state.job ? state.job.jobId : 'job'}.html`,
      html,
      'text/html;charset=utf-8'
    )
    htmlMsg.value = t('rp.form.htmlDone', { name })
    toast('success', htmlMsg.value)
    showHtmlForm.value = false
  } catch (e) {
    htmlMsgErr.value = true
    htmlMsg.value = `${t('rp.form.htmlFail')} ${e && e.message ? e.message : e}`
    toast('danger', htmlMsg.value)
  }
}

/** T06221 审阅 JSON 导出：与报告同源；excludeNotes 时剔除所有备注正文。 */
function downloadReviewJson() {
  try {
    const data = buildReviewJsonExport(state.review, { excludeNotes: excludeNotes.value })
    const name = triggerBlobDownload(
      `bempdiff-review-${state.job ? state.job.jobId : 'job'}.json`,
      JSON.stringify(data, null, 2),
      'application/json;charset=utf-8'
    )
    toast('success', t('rp.reviewJsonDone', { name }))
  } catch (e) {
    toast('danger', `${t('rp.reviewJsonFail')} ${e && e.message ? e.message : e}`)
  }
}
</script>

<template>
  <div class="modal-backdrop" v-if="visible" @click.self="emit('close')">
    <div class="modal-dialog modal-xl modal-dialog-scrollable report-dialog">
      <div class="modal-content">
        <div class="modal-header py-2">
          <h6 class="modal-title mb-0"><i class="bi bi-filetype-md"></i> {{ t('rp.title') }}
            <small class="fw-normal text-secondary ms-2" style="font-size:.75rem">
              {{ srcIsTask ? t('rp.aiTag') : (state.reportAi ? t('rp.withAi') : t('rp.basic')) }}
            </small>
          </h6>
          <button type="button" class="btn-close" @click="emit('close')"></button>
        </div>

        <div class="modal-body report-body">
          <div v-if="overallRisk" class="risk-banner mb-3" :class="'rb-' + (riskCls || 'none')">
            <i class="bi bi-shield-exclamation"></i>
            <span>{{ t('rp.overall') }}<b :class="riskCls">{{ overallRisk }}</b></span>
          </div>
          <!-- T06221 导出提示：默认含备注全文；勾选后预览与所有导出均剔除备注正文 -->
          <div v-if="hasReviewData" class="review-export-notice mb-3">
            <i class="bi bi-shield-lock"></i>
            <span>{{ t('rp.reviewNotice') }}</span>
            <label class="form-check form-check-inline mb-0 ms-auto">
              <input id="rp-exclude-notes" v-model="excludeNotes" class="form-check-input" type="checkbox">
              <span class="form-check-label">{{ t('rp.excludeNotes') }}</span>
            </label>
          </div>
          <div v-if="src" class="md-render" v-html="html"></div>
          <div v-else class="empty-report text-center text-secondary py-5">
            <div class="ico mb-2"><i class="bi bi-file-earmark-x"></i></div>
            <div>{{ t('rp.empty') }}</div>
            <button v-if="!srcIsTask" class="btn btn-sm btn-outline-primary mt-3" :disabled="!state.job || state.busy"
                    @click="generateReport(state.config && state.config.aiEnabled)">
              <i class="bi bi-filetype-md"></i> {{ t('info.generate') }}{{ (state.config && state.config.aiEnabled) ? '(AI)' : '' }}
            </button>
          </div>
        </div>

        <!-- R11 HTML 导出：自定义模板（标题/落款/风险口径），自包含单文件 -->
        <div class="html-export-panel px-3 py-2 border-bottom" v-if="showHtmlForm" style="background:var(--bs-tertiary-bg)">
          <div class="d-flex flex-wrap gap-2 mb-2">
            <input class="form-control form-control-sm" style="max-width:16rem" v-model="reportMeta.title"
                   :placeholder="t('rp.form.title')" :aria-label="t('rp.form.title')">
            <input class="form-control form-control-sm" style="max-width:16rem" v-model="reportMeta.subtitle"
                   :placeholder="t('rp.form.subtitle')" :aria-label="t('rp.form.subtitle')">
            <input class="form-control form-control-sm" style="max-width:12rem" v-model="reportMeta.author"
                   :placeholder="t('rp.form.author')" :aria-label="t('rp.form.author')">
          </div>
          <textarea class="form-control form-control-sm mb-2" rows="2" v-model="reportMeta.riskNote"
                    :placeholder="t('rp.form.riskNote')" :aria-label="t('rp.form.riskNote')"></textarea>
          <button class="btn btn-primary btn-sm" :disabled="!src" @click="downloadHtml">
            <i class="bi bi-filetype-html"></i> {{ t('rp.form.genHtml') }}
          </button>
          <div v-if="htmlMsg" class="small mt-1" :class="htmlMsgErr ? 'text-danger' : 'text-success'" role="status">
            {{ htmlMsg }}
          </div>
        </div>
        <div class="modal-footer py-2 px-3">
          <button class="btn btn-outline-secondary btn-sm" :disabled="!src" @click="downloadMd">
            <i class="bi bi-download"></i> {{ t('rp.downloadMd') }}
          </button>
          <button class="btn btn-outline-primary btn-sm" :disabled="!src" @click="toggleHtmlForm"
                  :title="t('rp.exportHtmlTitle')">
            <i class="bi bi-filetype-html"></i> {{ t('rp.exportHtml') }}
          </button>
          <button v-if="!srcIsTask" id="rp-export-review-json" class="btn btn-outline-secondary btn-sm"
                  :disabled="!state.job" @click="downloadReviewJson" :title="t('rp.reviewJsonTitle')">
            <i class="bi bi-braces"></i> {{ t('rp.exportReviewJson') }}
          </button>
          <button v-if="!srcIsTask" class="btn btn-outline-primary btn-sm" :disabled="!state.job || state.busy"
                  @click="generateReport(state.reportAi, { category: state.reportAi ? state.reportCategory : undefined, force: true })">
            <i class="bi bi-arrow-clockwise"></i> {{ t('rp.regen') }}{{ (state.config && state.config.aiEnabled) ? '(AI)' : '' }}
          </button>
          <button class="btn btn-secondary btn-sm ms-auto" @click="emit('close')">{{ t('common.close') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0; background: var(--bdf-overlay);
  display: flex; align-items: flex-start; justify-content: center; z-index: 1600;
  padding: 3vh 2vw;
}
.report-dialog { width: min(1200px, 96vw); max-width: none; margin: 0; }
.modal-content {
  width: 100%; max-height: 94vh;
  display: flex; flex-direction: column;
  background-color: var(--bs-body-bg); color: var(--bs-body-color);
}
.report-body { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 1.25rem 1.5rem; }
.report-body .ico { font-size: 2rem; }
.review-export-notice {
  display: flex; align-items: center; gap: .45rem; flex-wrap: wrap;
  font-size: .76rem; color: var(--bs-secondary-color);
  background: var(--bs-tertiary-bg); border: 1px solid var(--bs-border-color);
  border-radius: .4rem; padding: .4rem .6rem;
}
.review-export-notice > i { color: var(--bs-primary); }
.review-export-notice .form-check-label { font-size: .76rem; }

/* 差异报告分析框：清晰边框容器 + 充足内边距，文字/表格不与边框贴边 */
.md-render {
  border: 1px solid var(--bs-border-color);
  border-radius: .5rem;
  padding: 1.25rem 1.5rem;
  background: var(--bs-body-bg);
  font-size: .85rem; line-height: 1.6;
}
/* 分析框内 Markdown 元素间距补足（在全局 .md-* 基础上） */
.md-render .md-h1 { margin-top: 0; }
.md-render .md-table { font-size: .8rem; }
.empty-report .ico { font-size: 2.5rem; opacity: .5; }
</style>
