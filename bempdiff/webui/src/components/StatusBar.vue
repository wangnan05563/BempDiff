<script setup>
import { computed } from 'vue'
import { state, isRunning, phaseLabel, cancelExport, setReviewCockpit, flushReview } from '../store'
import { t } from '../lib/i18n'

function retryReviewSave() {
  flushReview().catch(() => {})
}

function basename(p) {
  if (!p) return ''
  return p.replace(/\\/g, '/').split('/').pop() || p
}

const s = computed(() => (state.job && state.job.stats) || null)
// 全量统计（含 bizChanged/jarChanged）；差异数字统一以后端全量 stats 为准
const fullStats = computed(() => state.job && state.job.stats ? state.job.stats : null)
const versions = computed(() => {
  // T03669：本次比对失败时不给任何版本号——此前即使新包文件根本不存在，
  // 状态栏照样渲染「版本：demo_v1.war → __no_such__war」，强化「比对已完成」的错觉。
  if (state.error) return '—'
  if (state.job) return `${state.job.oldVersion} → ${state.job.newVersion}`
  const o = basename(state.oldPath)
  const n = basename(state.newPath)
  if (o || n) return `${o || '—'} → ${n || '—'}`
  return '—'
})
</script>

<template>
  <footer class="bg-body-tertiary border-top px-3 py-1 d-flex gap-4 flex-wrap" style="font-size:.75rem;color:var(--bs-secondary-color)">
    <!-- 比对进行中：覆盖解析/解包/比对各阶段，实时展示进度与归属文案。
        与后端拒绝消息「比对尚未完成: RUNNING」逻辑呼应——此阶段触发智能分类/AI分析会得到该提示 -->
    <span v-if="isRunning()" class="text-primary fw-semibold d-flex align-items-center gap-2" style="flex-basis:100%">
      <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
      {{ phaseLabel() }}
      <template v-if="state.jobProgress && (state.jobProgress.message || state.jobProgress.progress)">
        <span class="text-muted fw-normal">{{ state.jobProgress.message }}</span>
        <span class="text-muted fw-normal" v-if="state.jobProgress.progress != null">{{ state.jobProgress.progress }}%</span>
      </template>
      <span class="text-danger fw-normal">{{ t('phase.aiWait') }}</span>
    </span>
    <!-- 导出进行中：从点击「导出差异资产」直至本地保存完成前，底部固定提示「导出中」（可取消，恢复按钮） -->
    <span v-if="state.exporting" class="text-primary fw-semibold" :title="t('sb.exportingTip')">
      <i class="bi bi-arrow-repeat" style="animation:spinner-border .75s linear infinite"></i> {{ t('sb.exporting') }}
      <button type="button" class="btn btn-link btn-sm p-0 ms-1 align-baseline" @click="cancelExport()" :aria-label="t('sb.cancelExport')" :title="t('sb.cancelExport')">{{ t('common.cancel') }}</button>
    </span>
    <!-- 比对超时提醒：RUNNING 超过阈值仍未结束 -->
    <span v-if="state.compareStalled" class="text-danger fw-semibold">
      <i class="bi bi-exclamation-triangle"></i> {{ t('phase.timeout') }}
    </span>
    <!-- 比对失败的常驻标记（T03669）：差异树栏可被收起、toast 会自动消失，
         故状态栏必须独立留一处不会自己走掉的失败痕迹 -->
    <span v-if="state.error" class="text-danger fw-semibold" :title="state.error">
      <i class="bi bi-x-octagon-fill"></i> {{ t('sb.compareFailed') }}
    </span>
    <span v-if="s" :title="t('sb.statsTip')">
      {{ t('sb.statsPrefix') }}{{ t('info.added') }} <b class="text-body">{{ s.added }}</b> · {{ t('info.deleted') }} <b class="text-body">{{ s.deleted }}</b> ·
      {{ t('info.modified') }} <b class="text-body">{{ s.modified }}</b> · {{ t('info.unchanged') }} <b class="text-body">{{ s.unchanged }}</b>
    </span>
    <span v-if="fullStats" :title="t('sb.bizTip')">{{ t('info.bizChanged') }} <b class="text-body">{{ fullStats.bizChanged }}</b> · {{ t('info.jarChanged') }} <b class="text-body">{{ fullStats.jarChanged }}</b></span>
    <span v-if="state.reviewSaveState === 'error' && state.review && state.review.jobId" class="text-danger fw-semibold review-save-error"
          :title="t('sb.reviewSaveTip', { msg: state.reviewError || '' })">
      <i class="bi bi-cloud-slash"></i> {{ t('sb.reviewSaveFailed') }}
      <button type="button" class="btn btn-link btn-sm p-0 ms-1 align-baseline" @click="retryReviewSave()"
              :aria-label="t('sb.reviewSaveRetry')" :title="t('sb.reviewSaveRetry')">{{ t('sb.reviewSaveRetry') }}</button>
    </span>
    <!-- T06218 审阅进度：由 state.reviewSummary 即时驱动，标记后同帧更新（AC-10） -->
    <span v-if="state.job && state.reviewSummary && state.reviewSummary.totalFiles" class="review-summary"
          :title="t('sb.reviewTip', { files: state.reviewSummary.totalFiles, hunks: state.reviewSummary.totalHunks })">
      <i class="bi bi-check2-square"></i> {{ t('sb.review') }}
      <b class="text-body">{{ state.reviewSummary.reviewedFiles }}/{{ state.reviewSummary.totalFiles }}</b>
      · {{ state.reviewSummary.filePercent }}%
      <span class="text-secondary ms-1">{{ t('sb.hunk', { done: state.reviewSummary.reviewedHunks, total: state.reviewSummary.totalHunks }) }}</span>
      <span v-if="state.reviewSummary.needsReviewFiles" class="text-danger ms-1">{{ t('sb.needsReview', { n: state.reviewSummary.needsReviewFiles }) }}</span>
    </span>
    <span :title="state.error ? t('sb.verFailedTip') : t('sb.verTip')">{{ t('status.version') }}：<b class="text-body">{{ versions }}</b></span>
    <!-- T06219 feature flag：布局切换常驻状态栏，确保旧布局/新版布局都能随时回退，且不丢数据（AC-05） -->
    <button type="button" class="btn btn-link btn-sm p-0 align-baseline sb-layout-toggle"
            :title="t('sb.layoutTip')" :aria-label="t('sb.layoutTip')"
            @click="setReviewCockpit(!state.reviewCockpit)">
      <i class="bi bi-layout-sidebar"></i> {{ state.reviewCockpit ? t('sb.layoutNew') : t('sb.layoutLegacy') }}
    </button>
    <span v-if="state.analyzing" class="text-primary"><i class="bi bi-cpu"></i> {{ t('sb.aiReporting') }}</span>
    <span class="ms-auto" :title="t('sb.engineTip')">{{ t('sb.engine') }} <b class="text-body">CFR</b> · {{ t('sb.analysis') }} <b class="text-body">{{ state.config && state.config.aiEnabled ? 'AI' : t('sb.local') }}</b></span>
  </footer>
</template>
