<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { state, setAiSelCategory, startAiAnalysis, stopAiAnalysis, restartAiAnalysis, selectAiTask, closeAiTask, openReportPreview, AI_CATEGORIES, toast, isUnpacking } from '../store'
import { renderMarkdown } from '../lib/markdown'
// tr 为 i18n 翻译函数别名：本组件多处用 t 作任务对象变量名（v-for/局部常量），
// 函数体内需取译文时用 tr()，避免与任务变量覆蔽冲突；模板层仍用 t()
import { t, t as tr } from '../lib/i18n'

// 分析项选择提升到共享 state.aiSelCategory：与工具栏「生成报告(AI)」联动，
// 使报告生成接口能拿到所选分析项（修复：此前报告生成永远走默认分析，内容不随选择变化）。
const selCategory = computed({
  get: () => state.aiSelCategory,
  set: (v) => setAiSelCategory(v)
})
const customPrompt = ref('')
const showCustom = computed(() => selCategory.value === 'custom')
const bodyRef = ref(null)

const hasJob = computed(() => !!(state.job && state.job.status === 'DONE'))
const unpacking = computed(() => isUnpacking())
const active = computed(() => state.aiTasks.find(t => t.id === state.aiActiveTaskId) || null)
const hasTasks = computed(() => state.aiTasks.length > 0)
const runningCount = computed(() => state.aiTasks.filter(t => t.status === 'thinking' || t.status === 'streaming').length)

function newAnalysis() {
  if (!hasJob.value) return
  if (unpacking.value) { toast('warning', t('ac.waitUnpack')); return }  // 解包未完成禁止分析，避免漏判
  if (selCategory.value === 'custom' && !customPrompt.value.trim()) {
    toast('warning', t('ac.customEmpty'))
    return
  }
  if (selCategory.value === 'custom') startAiAnalysis('custom', customPrompt.value.trim())
  else startAiAnalysis(selCategory.value)
}

// 思考步骤阶段标签：文案走 i18n（与 AiAnalysisDialog 同一套 key）
const PHASE_KEY = { thinking: 'ai.phase.thinking', tool_call: 'ai.phase.tool_call', composing: 'ai.phase.composing' }
function phaseLabel(p) {
  return t(PHASE_KEY[p] || 'ai.phase.thinking')
}
// 分析类别显示名：labelKey 渲染期求值（切语言即时刷新）
const catLabel = (c) => (c && c.labelKey ? t(c.labelKey) : (c ? c.label : ''))
// P2-8：历史 AI 任务标题按 titleKey+titleParams 渲染期求值；切语言时旧任务不再停留在创建时语言。
// 兼容早期无 titleKey 的任务对象，回退其快照 title。
const taskTitle = (task) => (task && task.titleKey ? t(task.titleKey, task.titleParams || {}) : (task ? task.title : ''))
// tab 关闭按钮 title：tab 的 v-for 以 t 命名任务变量，覆蔽 i18n 的 t，故用 computed 预求
const closeTabTitle = computed(() => t('ac.closeTab'))
function statusMeta(t) {
  switch (t.status) {
    case 'thinking':
    case 'streaming': return { cls: 'bi-arrow-repeat spin', color: 'text-primary' }
    case 'done': return { cls: 'bi-check2-circle', color: 'text-success' }
    case 'error': return { cls: 'bi-exclamation-triangle', color: 'text-danger' }
    case 'aborted': return { cls: 'bi-stop-fill', color: 'text-secondary' }
    default: return { cls: 'bi-circle', color: 'text-secondary' }
  }
}
// tab 图标样式一次拼接（避免模板中对 statusMeta 重复调用，评审 P2 #17）
function tabCls(t) { const m = statusMeta(t); return m.cls + ' ' + m.color }
const mdHtml = computed(() => (active.value && active.value.answer) ? renderMarkdown(active.value.answer) : '')

// 自动滚底：监听当前任务 answer/thinking 长度变化
function scrollBottom() {
  nextTick(() => {
    const el = bodyRef.value
    if (el) el.scrollTop = el.scrollHeight
    requestAnimationFrame(() => { if (el) el.scrollTop = el.scrollHeight })
  })
}
watch(() => (active.value ? active.value.answer.length + '|' + active.value.thinking.length : ''), scrollBottom)

// 多任务滚动位置记忆：所有 AI 任务共用同一个 .console-body(bodyRef)，切换任务 tab 时
// 若不缓存，scrollTop 会停留在切换前的随机位置（视觉上像「滚到一半」）。按 taskId 缓存：
//  - 切换前保存旧任务的 scrollTop；
//  - 切到正在生成(thinking/streaming)的任务 → 走 scrollBottom 跟流（与上面的 watch 协同，不被记忆覆盖）；
//  - 切到已完成的任务 → 恢复其记忆位置（无记忆则回顶，便于从头查看报告）。
// 关闭任务时同步清理其缓存，避免 Map 无限增长。
const taskScrollMap = new Map()
let taskScrollRaf = 0
function onConsoleScroll() {
  if (taskScrollRaf) return
  taskScrollRaf = requestAnimationFrame(() => {
    taskScrollRaf = 0
    const id = state.aiActiveTaskId
    if (id != null && bodyRef.value) {
      taskScrollMap.set(id, bodyRef.value.scrollTop)
    }
  })
}
watch(() => state.aiActiveTaskId, (newId, oldId) => {
  // 切换瞬间 DOM 仍是旧任务内容，先保存旧任务的滚动位置
  if (oldId != null && bodyRef.value) {
    taskScrollMap.set(oldId, bodyRef.value.scrollTop)
  }
  if (newId != null) {
    const task = state.aiTasks.find(t => t.id === newId)
    const isLive = task && (task.status === 'streaming' || task.status === 'thinking')
    nextTick(() => {
      if (!bodyRef.value) return
      if (isLive) {
        // 正在生成：跟流滚底，后续由上面的 scrollBottom watch 接管自动滚动
        scrollBottom()
      } else if (taskScrollMap.has(newId)) {
        bodyRef.value.scrollTop = taskScrollMap.get(newId)
      } else {
        bodyRef.value.scrollTop = 0
      }
    })
  }
})
// 关闭任务后清理其滚动缓存，避免 Map 残留无用 key
watch(() => state.aiTasks.map(t => t.id), (newIds) => {
  const live = new Set(newIds)
  for (const k of [...taskScrollMap.keys()]) {
    if (!live.has(k)) taskScrollMap.delete(k)
  }
})

function previewReport() {
  if (active.value && active.value.answer) openReportPreview(active.value.answer)
}
function previewDisabled(t) { return !(t && t.answer && t.answer.length) }

/** 导出当前任务的分析结果为 Markdown 文件（本地下载，不经过后端）。 */
function exportActive() {
  const t = active.value
  if (!t || !t.answer || !t.answer.length) return
  const blob = new Blob([t.answer], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `bempdiff-ai-${(t.title || 'analysis').replace(/[\\/:*?"<>|]/g, '_')}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  toast('success', tr('ac.exportDone'))
}
function exportDisabled(t) { return !(t && t.answer && t.answer.length) }

/** R4 一键摘要（二期 T01459）：复制当前任务 Markdown 结果到剪贴板（clipboard API，非安全上下文降级 execCommand）。 */
async function copyActive() {
  const t = active.value
  if (!t || !t.answer || !t.answer.length) return
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(t.answer)
    } else {
      const ta = document.createElement('textarea')
      ta.value = t.answer
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    toast('success', tr('ac.copyDone', { n: t.answer.length }))
  } catch (_) {
    toast('danger', tr('ac.copyFail'))
  }
}
function copyDisabled(t) { return !(t && t.answer && t.answer.length) }
</script>

<template>
  <div class="ai-console">
    <div class="console-head">
      <i class="bi bi-terminal"></i><span class="ms-1">{{ t('ai.dialogTitle') }}</span>
      <span v-if="runningCount" class="badge text-bg-primary ms-2">{{ t('ac.running', { n: runningCount }) }}</span>
    </div>

    <!-- 新建分析：类别选择 + 发起 -->
    <div class="console-new d-flex align-items-center gap-2 flex-wrap">
      <select class="form-select form-select-sm" style="width:auto" v-model="selCategory" :aria-label="t('ac.category')">
        <option v-for="c in AI_CATEGORIES" :key="c.key" :value="c.key">{{ catLabel(c) }}</option>
      </select>
      <input v-if="showCustom" class="form-control form-control-sm" style="max-width:220px"
             v-model="customPrompt" :placeholder="t('ac.customPlaceholder')" :aria-label="t('ac.customAria')" :disabled="!hasJob">
      <button class="btn btn-sm btn-primary" @click="newAnalysis"
              :disabled="!hasJob || unpacking || (showCustom && !customPrompt.value.trim())"
              :aria-label="t('ac.newTip')" :title="t('ac.newTip')">
        <i class="bi bi-plus-lg"></i> {{ t('ac.newBtn') }}
      </button>
      <span v-if="unpacking" class="text-warning" style="font-size:.75rem"><i class="bi bi-boxes"></i> {{ t('ac.unpacking') }}</span>
      <span v-else-if="!hasJob" class="text-secondary" style="font-size:.75rem">{{ t('ac.needJob') }}</span>
    </div>

    <!-- 无任务占位 -->
    <div v-if="!hasTasks" class="console-empty text-secondary">
      {{ t('ac.empty') }}
    </div>

    <template v-else>
      <!-- 并行任务 tab 栏：每个任务独立窗口。
           标签栏不再设 max-height（避免报告生成后 console-body 撑高，flex 收缩把标签栏压成 ~12px，
           第 2 行及之后的标签被嵌套滚动条遮住，用户无法点击切换）。
           标签数 > 单行容量时自然换行（典型 2~4 个并行分析仅 1~2 行）。 -->
      <div class="console-tabs d-flex flex-wrap gap-1">
        <button v-for="t in state.aiTasks" :key="t.id"
                class="console-tab btn btn-sm"
                :class="{ active: t.id === state.aiActiveTaskId }"
                :aria-label="taskTitle(t)" :title="taskTitle(t)"
                @click="selectAiTask(t.id)">
          <i class="bi" :class="tabCls(t)"></i>
          <span class="tab-title">{{ taskTitle(t) }}</span>
          <i class="bi bi-x tab-close" @click.stop="closeAiTask(t.id)" :title="closeTabTitle"></i>
        </button>
      </div>

      <!-- 当前任务控制台窗口 -->
      <div class="console-body" ref="bodyRef" @scroll="onConsoleScroll">
        <div v-if="active.error" class="alert alert-danger py-2 mb-2" style="font-size:.82rem">
          <i class="bi bi-exclamation-triangle"></i> {{ active.error }}
        </div>

        <!-- 思考过程：浅灰、默认折叠、可展开 -->
        <div class="thinking-block" :class="{ collapsed: active.thinkingCollapsed !== false }">
          <div class="thinking-header" @click="active.thinkingCollapsed = !(active.thinkingCollapsed === true)">
            <i class="bi bi-lightbulb thinking-icon"></i>
            <span class="thinking-summary">
              {{ t('ai.thoughtSteps', { n: active.thinking.length }) }} · {{ active.thinkingCollapsed === false ? t('ai.clickCollapse') : t('ai.clickExpand') }}
            </span>
            <i class="bi thinking-toggle" :class="active.thinkingCollapsed === false ? 'bi-chevron-down' : 'bi-chevron-right'"></i>
          </div>
          <div class="thinking-body" v-show="active.thinkingCollapsed === false">
            <div v-for="(s, idx) in active.thinking" :key="idx" class="thinking-step">
              <span class="step-phase" :class="s.phase">{{ phaseLabel(s.phase) }}</span>
              <span class="step-message">{{ s.message }}</span>
            </div>
          </div>
        </div>

        <!-- 答案流式输出 -->
        <div class="ai-answer ai-md">
          <div v-if="!active.answer && active.status==='streaming'" class="loading-dots">
            <span></span><span></span><span></span> {{ t('ai.generating') }}
          </div>
          <div v-else-if="!active.answer && active.status==='thinking'" class="text-secondary" style="font-size:.82rem">
            {{ t('ai.thinking') }}
          </div>
          <div v-else-if="!active.answer" class="text-secondary" style="font-size:.82rem">{{ t('ai.empty') }}</div>
          <div v-html="mdHtml"></div><span v-if="active.status==='streaming'" class="stream-cursor">▋</span>
        </div>
      </div>

      <!-- 操作：中断 / 重新分析 / 预览报告 / 导出 / 关闭（纯图标按钮，功能见悬浮提示） -->
      <div class="console-foot d-flex align-items-center gap-1">
        <span class="me-auto text-secondary" style="font-size:.75rem">
          <span v-if="active.status==='streaming'"><i class="bi bi-arrow-repeat spin"></i> {{ t('ai.analyzing') }}</span>
          <span v-else-if="active.status==='thinking'"><i class="bi bi-arrow-repeat spin"></i> {{ t('ac.preparingShort') }}</span>
          <span v-else-if="active.status==='done'"><i class="bi bi-check2-circle text-success"></i> {{ t('ai.done') }}</span>
          <span v-else-if="active.status==='aborted'"><i class="bi bi-stop-fill"></i> {{ t('ai.aborted') }}</span>
          <span v-else-if="active.status==='error'"><i class="bi bi-exclamation-triangle text-danger"></i> {{ t('ai.failed') }}</span>
        </span>
        <button v-if="active.status==='thinking' || active.status==='streaming'"
                class="btn btn-outline-secondary btn-sm icon-only" :aria-label="t('ac.stop')" :title="t('ac.stop')"
                @click="stopAiAnalysis(active.id)">
          <i class="bi bi-stop-fill"></i>
        </button>
        <button v-else class="btn btn-outline-secondary btn-sm icon-only" :aria-label="t('ai.retry')" :title="t('ai.retry')"
                @click="restartAiAnalysis(active.id)" :disabled="!hasJob">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
        <button class="btn btn-outline-primary btn-sm icon-only" :aria-label="t('ac.preview')" :title="t('ac.preview')"
                :disabled="previewDisabled(active)" @click="previewReport">
          <i class="bi bi-filetype-md"></i>
        </button>
        <button class="btn btn-outline-primary btn-sm icon-only" :aria-label="t('ac.copy')" :title="t('ac.copy')"
                :disabled="copyDisabled(active)" @click="copyActive">
          <i class="bi bi-clipboard"></i>
        </button>
        <button class="btn btn-outline-primary btn-sm icon-only" :aria-label="t('ac.export')" :title="t('ac.export')"
                :disabled="exportDisabled(active)" @click="exportActive">
          <i class="bi bi-download"></i>
        </button>
        <button class="btn btn-outline-secondary btn-sm icon-only" :aria-label="t('ac.closeTab')" :title="t('ac.closeTab')"
                @click="closeAiTask(active.id)">
          <i class="bi bi-x-lg"></i>
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ai-console { flex: 1 1 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden;
  padding: .75rem .75rem .5rem;
  /* P2-6：控制台自身也铺主题底色，避免主题切换瞬间透出下层未重绘的白色区域。 */
  background: var(--bs-body-bg); }
.console-head { font-size: .8rem; font-weight: 600; color: var(--bs-secondary-color); display: flex; align-items: center; }
.console-new { margin: .4rem 0; }
.console-empty { font-size: .8rem; padding: .5rem 0; }
.console-tabs { margin-bottom: .4rem; }
.console-tab { --bs-btn-padding-y: .15rem; --bs-btn-padding-x: .5rem; font-size: .74rem; display: inline-flex; align-items: center; gap: .3rem;
  border: 1px solid var(--bs-border-color); background: var(--bs-tertiary-bg); color: var(--bs-body-color); flex: 0 0 auto; max-width: 100%; }
.console-tab.active { border-color: var(--bs-primary); background: var(--bs-primary-bg-subtle); }
.tab-title {
  /* 标题完整显示（不再硬截断）：
   *  - min-width:0：flex item 允许收缩到 0（默认 min-width:auto 对中文是最大不可断段，会撑爆布局）。
   *  - white-space:normal + word-break:break-word：超长标题在 tab 内自然换行。
   *  - 不依赖固定 max-width：按钮外层 max-width:100% 跟随面板宽度自适应，
   *    不同 col-ai 断点（380/340/300/260）下标题换行宽度都贴合可用空间，不会溢出也不会浪费。
   *  - 不再 text-overflow:ellipsis，标题文字总是可读。
   * button 已带 :title="t.title"，鼠标悬浮可看到单行原文（极端长度兜底）。 */
  min-width: 0;
  white-space: normal;
  word-break: break-word;
  line-height: 1.3;
}
.console-tab .tab-close { font-size: .7rem; opacity: .6; flex: 0 0 auto; }
.console-tab .tab-close:hover { opacity: 1; color: var(--bs-danger); }
.console-body { flex: 1 1 auto; min-height: 0; overflow: auto; border: 1px solid var(--bs-border-color);
  border-radius: 8px; padding: .6rem .75rem; background: var(--bs-body-bg); }
.console-foot { margin-top: .4rem; }
.console-foot .icon-only { --bs-btn-padding-x: .45rem; }

/* 思考块：浅灰 + 微弱紫色左边框（呼应 AiAnalysisDialog 视觉），默认折叠 */
.thinking-block { margin: 4px 0 10px; padding: 6px 10px; background: var(--bdf-ai-thinking-soft);
  border-left: 2px solid var(--bdf-ai-thinking-line); border-radius: 0 6px 6px 0; }
.thinking-header { cursor: pointer; display: flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--bs-secondary-color); }
.thinking-icon { font-size: 12px; color: var(--bs-secondary-color); }
.thinking-summary { color: var(--bs-secondary-color); font-size: 11.5px; }
.thinking-toggle { margin-left: auto; font-size: 11px; color: var(--bs-secondary-color); }
.thinking-body { margin-top: 6px; max-height: 200px; overflow-y: auto; }
.thinking-block.collapsed .thinking-body { display: none; }
.thinking-step { display: flex; gap: 6px; padding: 3px 0; font-size: 11px; color: var(--bs-secondary-color);
  border-bottom: 1px dashed var(--bdf-ai-thinking-dash); align-items: flex-start; }
.step-phase { padding: 1px 5px; border-radius: 3px; font-size: 10px; white-space: nowrap; color: var(--bs-secondary-color); background: var(--bdf-ai-thinking-chip); }
.step-message { flex: 1; color: var(--bs-secondary-color); font-size: 11px; line-height: 1.5; }

.ai-answer { margin-top: 6px; padding: 10px 12px; border: 1px solid var(--bs-border-color); border-radius: 8px; background: var(--bs-body-bg); min-height: 80px; }
.stream-cursor { display: inline-block; margin-left: 1px; color: var(--bs-primary); animation: cursor-blink 1s steps(2) infinite; }
@keyframes cursor-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
.loading-dots { color: var(--bs-secondary-color); font-size: .85rem; }
.loading-dots span { display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--bs-secondary-color); margin: 0 1px; animation: dot-bounce 1.2s infinite; }
.loading-dots span:nth-child(2) { animation-delay: .2s; }
.loading-dots span:nth-child(3) { animation-delay: .4s; }
@keyframes dot-bounce { 0%, 80%, 100% { transform: translateY(0); opacity: .4; } 40% { transform: translateY(-4px); opacity: 1; } }
.spin { animation: spin 1s linear infinite; display: inline-block; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }
</style>
