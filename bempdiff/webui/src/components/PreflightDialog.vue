<script setup>
// CX-09 比对前预检确认弹窗：展示 block/warn/info 三级结果。
// 阻断项禁止继续；警告项允许「仍然开始比对」；信息项仅展示。
// 设计目标：不把预检失败伪装成比对失败——弹窗关闭即不产生 job。
import { computed, nextTick, onMounted, ref } from 'vue'
import { state, confirmPreflight, cancelPreflight } from '../store'
import { t } from '../lib/i18n'

const visible = computed(() => !!state.preflight)
const result = computed(() => (state.preflight ? state.preflight.result : null))
const blockers = computed(() => (result.value && result.value.blockers) || [])
const warnings = computed(() => (result.value && result.value.warnings) || [])
const infos = computed(() => (result.value && result.value.infos) || [])
const canContinue = computed(() => !!(result.value && result.value.ok && result.value.level === 'warn'))
const dialogRef = ref(null)

function fmtSize(n) {
  const v = Number(n) || 0
  if (v >= 1024 * 1024 * 1024) return (v / 1024 / 1024 / 1024).toFixed(1) + ' GB'
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(0) + ' KB'
  return v + ' B'
}
function sideLine(s, labelKey) {
  if (!s) return ''
  const bits = [t(labelKey)]
  if (s.directory) bits.push(t('preflight.entries') + ' ' + (s.entries || 0))
  else bits.push(t('preflight.size') + ' ' + fmtSize(Math.max(s.uncompressedBytes || 0, s.compressedBytes || 0)))
  bits.push(t('preflight.depth') + ' ' + (s.maxDepth || 0))
  return bits.join(' · ')
}
function openDiag() {
  // 诊断包接口会返回 zip 附件；window.open 在桌面壳/浏览器均能触发下载。
  window.open('/api/admin/diag-bundle', '_blank', 'noopener')
}

onMounted(() => { nextTick(() => { if (dialogRef.value) dialogRef.value.focus() }) })
</script>

<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancelPreflight" @keydown.esc="cancelPreflight">
    <div ref="dialogRef" class="modal-dialog modal-dialog-centered modal-lg" role="dialog" aria-modal="true"
         :aria-label="t('preflight.title')" tabindex="-1">
      <div class="modal-content">
        <div class="modal-header py-2 px-3">
          <div>
            <h6 class="modal-title mb-0">
              <i class="bi bi-clipboard-check text-primary"></i> {{ t('preflight.title') }}
            </h6>
            <div class="text-secondary" style="font-size:.74rem">{{ t('preflight.subtitle') }}</div>
          </div>
          <button type="button" class="btn-close" :aria-label="t('common.close')" @click="cancelPreflight"></button>
        </div>

        <div class="modal-body py-3 px-3" style="font-size:.82rem">
          <div v-if="result" class="pf-summary mb-2">
            <span class="badge" :class="blockers.length ? 'text-bg-danger' : (warnings.length ? 'text-bg-warning' : 'text-bg-success')">
              {{ blockers.length ? t('preflight.blocked') : (warnings.length ? t('preflight.warnings') : t('preflight.none')) }}
            </span>
            <span class="text-secondary ms-2">{{ sideLine(result.left, 'preflight.left') }}</span>
            <span class="text-secondary ms-2">{{ sideLine(result.right, 'preflight.right') }}</span>
            <span class="text-secondary ms-2">{{ t('preflight.estimate', { n: result.estimatedSeconds || 1 }) }}</span>
          </div>

          <div v-if="blockers.length" class="alert alert-danger py-2 mb-2">
            <div class="fw-semibold mb-1"><i class="bi bi-x-octagon"></i> {{ t('preflight.blockers') }}</div>
            <ul class="mb-0 ps-3">
              <li v-for="(b, i) in blockers" :key="'b' + i">{{ b }}</li>
            </ul>
          </div>

          <div v-if="warnings.length" class="alert alert-warning py-2 mb-2">
            <div class="fw-semibold mb-1"><i class="bi bi-exclamation-triangle"></i> {{ t('preflight.warnings') }}</div>
            <ul class="mb-0 ps-3">
              <li v-for="(w, i) in warnings" :key="'w' + i">{{ w }}</li>
            </ul>
          </div>

          <div v-if="infos.length" class="alert alert-info py-2 mb-0">
            <div class="fw-semibold mb-1"><i class="bi bi-info-circle"></i> {{ t('preflight.infos') }}</div>
            <ul class="mb-0 ps-3">
              <li v-for="(x, i) in infos" :key="'i' + i">{{ x }}</li>
            </ul>
          </div>
        </div>

        <div class="modal-footer py-2 px-3">
          <button class="btn btn-outline-secondary btn-sm" @click="openDiag">
            <i class="bi bi-life-preserver"></i> {{ t('preflight.diag') }}
          </button>
          <button class="btn btn-outline-secondary btn-sm" @click="cancelPreflight">
            <i class="bi bi-x-circle"></i> {{ t('common.cancel') }}
          </button>
          <button class="btn btn-primary btn-sm" :disabled="!canContinue" @click="confirmPreflight">
            <i class="bi bi-play-fill"></i> {{ t('preflight.continue') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 4000;
}
.modal-content {
  background-color: var(--bs-body-bg);
  color: var(--bs-body-color);
}
.pf-summary { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem 0; }
.alert ul { max-height: 9rem; overflow-y: auto; }
</style>
