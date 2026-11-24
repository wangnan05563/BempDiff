<script setup>
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { state, activeTab, generateReport, setAiPanelCollapsed, applyAiPanelResponsive } from '../store'
import { renderMarkdown, extractSection } from '../lib/markdown'
import { t } from '../lib/i18n'
import AiConsole from './AiConsole.vue'

// 内容子视图 tab（单文件/全局汇总/破坏性/审计/控制台）提升到共享 state.aiPanelTab，
// 以便「新建分析」时 store 能自动切到控制台 tab 让用户即时看到流式输出。
const tab = computed({
  get: () => state.aiPanelTab,
  set: (v) => { state.aiPanelTab = v }
})

// 滚动位置记忆：file/global/break/audit 四个报告类子视图共用同一个 .ai-body 滚动容器，
// 切换 tab 时若不缓存，各 tab 的 scrollTop 会互相串台（前一个 tab 的位置被后一个 tab 直接看到）。
// 用 Map 按 tab key 缓存 scrollTop：切换前保存旧 tab 位置、切换后恢复新 tab 位置（无记忆则回顶）。
// console tab 走 AiConsole 自管，不在此恢复（.ai-body 在 console 下 v-show 隐藏）。
const aiBodyRef = ref(null)
const aiScrollMap = new Map()
let aiScrollRaf = 0
function onAiBodyScroll() {
  if (aiScrollRaf) return
  aiScrollRaf = requestAnimationFrame(() => {
    aiScrollRaf = 0
    const t = state.aiPanelTab
    if (t && t !== 'console' && aiBodyRef.value) {
      aiScrollMap.set(t, aiBodyRef.value.scrollTop)
    }
  })
}
watch(() => state.aiPanelTab, (newTab, oldTab) => {
  // 切换瞬间 DOM 仍是旧 tab 内容，先保存旧 tab 的滚动位置
  if (oldTab && oldTab !== 'console' && aiBodyRef.value) {
    aiScrollMap.set(oldTab, aiBodyRef.value.scrollTop)
  }
  // 新 tab 若是报告类视图，恢复其记忆位置；console 不在此恢复（由 AiConsole 处理）
  if (newTab && newTab !== 'console') {
    nextTick(() => {
      if (aiBodyRef.value) {
        aiBodyRef.value.scrollTop = aiScrollMap.has(newTab) ? aiScrollMap.get(newTab) : 0
      }
    })
  }
})

// 智能分析栏收起状态改由共享 state.aiPanelCollapsed 驱动（与 DiffView 文件栏一键显隐联动）。
// 挂载即按「显式偏好 > 视口宽度」应用一次响应式避让；之后视口变化也跟随避让，避免窄屏挤占对比窗口。
onMounted(() => { applyAiPanelResponsive(); window.addEventListener('resize', applyAiPanelResponsive) })
onUnmounted(() => window.removeEventListener('resize', applyAiPanelResponsive))
// 状态徽章文案走 i18n（渲染期求值，切语言即时刷新）
const STATUS_KEY = { ADDED: 'info.status.ADDED', DELETED: 'info.status.DELETED', MODIFIED: 'info.status.MODIFIED', UNCHANGED: 'info.status.UNCHANGED' }

// AI 是否启用：集中判定，供「生成报告」标签后缀与点击传参共用，避免 AI 判定逻辑在多处重复（评审 A/D）。
const aiEnabled = computed(() => !!(state.config && state.config.aiEnabled))
const aiLabelSuffix = computed(() => aiEnabled.value ? '(AI)' : '')
// 宽度经 prop 传入（App 拖拽调宽），根节点显式绑定，与 DiffTree 采用一致方式
const props = defineProps({ panelWidth: { type: Number, default: null } })

// node / decompile 都从当前激活的 tab 取；对应「DiffView 顶部 tab 栏选哪一个这里就显示哪一个」。
const node = computed(() => {
  const at = activeTab()
  return at ? at.node : null
})
const dec = computed(() => {
  const at = activeTab()
  return at ? at.decompile : null
})
const s = computed(() => (state.job && state.job.stats) || null)
// 全量统计（含 bizChanged/jarChanged）；差异数字统一以后端全量 stats 为准
const fullStats = computed(() => state.job && state.job.stats ? state.job.stats : null)

// 从已生成报告中抽取「六、破坏性变更」与「七/八、审计摘要」章节并渲染为 HTML。
const breakHtml = computed(() => {
  if (!state.reportMd) return ''
  const body = extractSection(state.reportMd, '破坏性变更')
  return body ? renderMarkdown(body) : `<p class="text-secondary mb-0">${t('info.noBreak')}</p>`
})
const auditHtml = computed(() => {
  if (!state.reportMd) return ''
  const body = extractSection(state.reportMd, '审计摘要')
  return body ? renderMarkdown(body) : `<p class="text-secondary mb-0">${t('info.noAudit')}</p>`
})

function fmtSize(b) {
  if (!b) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, n = b
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return (i ? n.toFixed(1) : n) + ' ' + u[i]
}

// 点击守卫：no-job 提示已下沉到 store.generateReport 内部（统一 InfoPanel / ToolBar / 自动报告入口），
// 此处仅负责把「是否启用 AI」与「分析项」传入，避免 AI 判定逻辑在多处重复（评审 A/D）。
// 修复：生成报告携带对应分析项（破坏性→breaking / 审计→risk），使报告内容随所选维度变化。
function onGenerateReport(category) {
  generateReport(aiEnabled.value, category ? { category } : undefined)
}
</script>

<template>
  <div class="col-ai" :class="{ collapsed: state.aiPanelCollapsed }"
       :style="props.panelWidth != null && !state.aiPanelCollapsed ? { width: props.panelWidth + 'px' } : undefined">
    <div v-if="!state.aiPanelCollapsed" class="pane-head">
      <i class="bi bi-cpu" role="img" :title="t('term.aiAnalysis')" :aria-label="t('term.aiAnalysis')"></i> {{ t('term.aiAnalysis') }}
      <button class="btn btn-sm btn-outline-secondary border-0 ms-auto px-1 py-0" :aria-label="t('info.collapse')" :title="t('info.collapse')"
              @click="setAiPanelCollapsed(true)">
        <i class="bi bi-layout-sidebar-inset-reverse"></i>
      </button>
    </div>
    <div v-else class="ai-collapsed-bar" :title="t('info.expand')" @click="setAiPanelCollapsed(false)">
      <i class="bi bi-chevron-left"></i>
      <i class="bi bi-cpu" role="img" :aria-label="t('term.aiAnalysis')" :title="t('term.aiAnalysis')"></i>
    </div>

    <template v-if="!state.aiPanelCollapsed">
    <ul class="nav nav-tabs px-2 pt-2">
      <li class="nav-item"><button class="nav-link py-1" :class="{active: tab==='file'}" @click="tab='file'">{{ t('info.tab.file') }}</button></li>
      <li class="nav-item"><button class="nav-link py-1" :class="{active: tab==='global'}" @click="tab='global'">{{ t('info.tab.global') }}</button></li>
      <li class="nav-item"><button class="nav-link py-1" :class="{active: tab==='break'}" @click="tab='break'">{{ t('info.tab.break') }}</button></li>
      <li class="nav-item"><button class="nav-link py-1" :class="{active: tab==='audit'}" @click="tab='audit'">{{ t('info.tab.audit') }}</button></li>
      <li class="nav-item"><button class="nav-link py-1" :class="{active: tab==='console'}" @click="tab='console'">{{ t('info.tab.console') }}</button></li>
    </ul>

    <!-- 信息面板四个子视图：仅在未选中「控制台」时显示，以便控制台独占整块内容区高度 -->
    <div class="ai-body" v-show="tab!=='console'" ref="aiBodyRef" @scroll="onAiBodyScroll">
      <!-- 单文件 -->
      <div v-show="tab==='file'">
        <div v-if="node" class="ai-card">
          <h4><i class="bi bi-file-earmark-code"></i> {{ node.key }}</h4>
          <div class="kv">{{ t('info.kv.status') }}<b>{{ STATUS_KEY[node.status] ? t(STATUS_KEY[node.status]) : node.status }}</b></div>
          <div class="kv">{{ t('info.kv.layer') }}<b>{{ node.layer }}</b> · {{ t('info.kv.type') }}<b>{{ node.fileClass }}</b></div>
          <div class="kv">{{ t('info.kv.size') }}<b>{{ fmtSize(node.size) }}</b></div>
          <div class="kv">{{ t('info.kv.engine') }}<b>{{ dec ? dec.engine : '—' }}</b></div>
          <div v-if="dec && !dec.ok" class="text-danger mt-2" style="font-size:.8rem">
            {{ dec.error || t('info.decompileFail', { engine: dec.engine }) }}
          </div>
        </div>
        <div v-else class="text-secondary" style="font-size:.85rem">
          {{ t('info.fileHint') }}
        </div>
      </div>

      <!-- 全局汇总 -->
      <div v-show="tab==='global'">
        <div v-if="s" class="ai-card">
          <h4><i class="bi bi-bar-chart"></i> {{ t('info.globalTitle') }}</h4>
          <div class="kv">{{ t('info.kv.version') }}<b>{{ state.job.oldVersion }} → {{ state.job.newVersion }}</b></div>
          <div class="kv">{{ t('info.added') }} <b class="text-success">{{ s.added }}</b> · {{ t('info.deleted') }} <b class="text-danger">{{ s.deleted }}</b> ·
            {{ t('info.modified') }} <b class="text-warning">{{ s.modified }}</b> · {{ t('info.unchanged') }} {{ s.unchanged }}</div>
          <div class="kv">{{ t('info.bizChanged') }} <b>{{ fullStats.bizChanged }}</b> · {{ t('info.jarChanged') }} <b>{{ fullStats.jarChanged }}</b></div>
          <div class="kv">{{ t('info.total') }} <b>{{ fullStats.total }}</b></div>
        </div>
        <div v-else class="text-secondary" style="font-size:.85rem">{{ t('info.notCompared') }}</div>
      </div>

      <!-- 破坏性 -->
      <div v-show="tab==='break'">
        <div v-if="state.reportMd" class="ai-md" v-html="breakHtml"></div>
        <div v-else class="ai-card">
          <h4><i class="bi bi-exclamation-octagon"></i> {{ t('info.breakTitle') }}</h4>
          <p class="text-secondary mb-2" style="font-size:.85rem">
            {{ t('info.breakHint') }}
          </p>
          <button class="btn btn-sm btn-outline-primary" :disabled="state.busy || state.reporting"
                  @click="onGenerateReport('breaking')">
            <i class="bi bi-filetype-md"></i> {{ t('info.generate') }}{{ aiLabelSuffix }}
          </button>
        </div>
      </div>

      <!-- 审计 -->
      <div v-show="tab==='audit'">
        <div v-if="state.reportMd" class="ai-md" v-html="auditHtml"></div>
        <div v-else class="ai-card">
          <h4><i class="bi bi-shield-check"></i> {{ t('info.auditTitle') }}</h4>
          <p class="text-secondary mb-2" style="font-size:.85rem">
            {{ t('info.auditHint') }}
          </p>
          <button class="btn btn-sm btn-outline-primary" :disabled="state.busy || state.reporting"
                  @click="onGenerateReport('risk')">
            <i class="bi bi-filetype-md"></i> {{ t('info.generate') }}{{ aiLabelSuffix }}
          </button>
        </div>
      </div>
    </div>

    <!-- AI 分析面板：与原四个子视图互斥，选中「控制台」tab 时独占整块内容区，获得最大展示空间 -->
    <AiConsole v-show="tab==='console'" />
    </template>
  </div>
</template>

<style scoped>
/* 5 个内容子视图 tab：窄屏下不换行，改为单行横向滚动，
 * 既保持 tab 栏整洁，又避免换行挤占控制台纵向高度（布局优化的核心诉求）。 */
/* 窄面板（英文标签更长）下允许换行，保证 5 个 tab 全部可读；宽面板仍是一行。 */
.nav-tabs { flex-wrap: wrap; row-gap: 2px; }
.nav-tabs .nav-link { white-space: nowrap; flex-shrink: 0; padding-left: .55rem; padding-right: .55rem; }
/* 细滚动条，保持视觉干净 */
.nav-tabs::-webkit-scrollbar { height: 4px; }
.nav-tabs::-webkit-scrollbar-thumb { background: var(--bs-border-color); border-radius: 2px; }
</style>
