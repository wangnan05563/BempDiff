<script setup>
import { ref, computed } from 'vue'
import { state, toggleExports, refreshExports, deleteExport, removeSyncExport, fmtBytes } from '../store'
import { withToken } from '../lib/api_auth'
import { t } from '../lib/i18n'

const query = ref('')
const range = ref('all') // all / today /7d

// 状态徽章文案走 i18n（key 映射，渲染期求值以支持切语言即时刷新）
const STATUS_KEY = { queued: 'dl.status.queued', running: 'dl.status.running', done: 'dl.status.done', error: 'dl.status.error' }

const asyncList = computed(() => {
  const q = query.value.trim().toLowerCase()
  const now = Date.now()
  let dayMs = range.value === 'today' ? 86400000 : range.value === '7d' ? 7 * 86400000 : Number.MAX_SAFE_INTEGER
  return state.exportRecords
    .filter(r => (q === '' || (r.filename || '').toLowerCase().includes(q) || (r.id || '').toLowerCase().includes(q)))
    .filter(r => !dayMs || (now - (r.createdAt || 0)) <= dayMs)
})

// 同步导出资产：本机浏览器下载目录的同步导出记录（客户端持久化，最新在前）
const syncList = computed(() => state.syncExports || [])

function statusChip(r) {
  if (r.status === 'done') return 'badge text-bg-success'
  if (r.status === 'error') return 'badge text-bg-danger'
  if (r.status === 'running') return 'badge text-bg-info'
  return 'badge text-bg-secondary'
}
function fmtTime(ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<template>
  <div class="dl-panel shadow" role="dialog" :aria-label="t('dl.title')">
    <div class="dl-head">
      <span class="fw-semibold"><i class="bi bi-download me-1"></i>{{ t('dl.title') }}</span>
      <span class="text-secondary" style="font-size:.72rem">{{ t('dl.subtitle') }}</span>
      <div class="ms-auto d-flex gap-1">
        <button class="btn btn-sm btn-outline-secondary" :aria-label="t('common.refresh')" :title="t('common.refresh')" @click="refreshExports()"><i class="bi bi-arrow-clockwise"></i></button>
        <button class="btn btn-sm btn-outline-secondary" :aria-label="t('common.close')" :title="t('common.close')" @click="toggleExports()"><i class="bi bi-x-lg"></i></button>
      </div>
    </div>

    <div class="dl-hint text-secondary">
      {{ t('dl.hint') }}
    </div>

    <!-- 同步导出资产（本机浏览器下载目录，客户端回溯） -->
    <div class="dl-section-title"><i class="bi bi-lightning-charge"></i> {{ t('dl.sync') }}
      <span class="text-secondary" style="font-size:.66rem">{{ t('dl.count', { n: syncList.length }) }}</span>
    </div>
    <div class="dl-list dl-list-sync">
      <template v-if="syncList.length">
        <div v-for="s in syncList" :key="s.createdAt" class="dl-row">
          <div class="dl-main">
            <span class="dl-name" :title="s.filename">{{ s.filename }}</span>
            <span class="text-secondary dl-sub">{{ t('dl.syncSub', { time: fmtTime(s.createdAt) }) }}</span>
          </div>
          <span class="text-secondary dl-size">{{ fmtBytes(s.size) }}</span>
          <div class="dl-actions">
            <button class="btn btn-sm btn-outline-secondary" :aria-label="t('dl.removeTitle')" :title="t('dl.removeTitle')" @click="removeSyncExport(s.createdAt)">{{ t('dl.remove') }}</button>
          </div>
        </div>
      </template>
      <div v-else class="dl-empty">{{ t('dl.emptySync') }}</div>
    </div>

    <!-- 异步导出资产（后端生成记录，可下载/删除） -->
    <div class="dl-section-title"><i class="bi bi-clock-history"></i> {{ t('dl.async') }}
      <span class="text-secondary" style="font-size:.66rem">{{ t('dl.count', { n: asyncList.length }) }}</span>
    </div>
    <div class="dl-toolbar d-flex gap-2">
      <input class="form-control form-control-sm" v-model.trim="query" :placeholder="t('dl.filterPlaceholder')">
      <select class="form-select form-select-sm" style="width:auto" v-model="range">
        <option value="all">{{ t('dl.range.all') }}</option>
        <option value="today">{{ t('dl.range.today') }}</option>
        <option value="7d">{{ t('dl.range.7d') }}</option>
      </select>
    </div>
    <div class="dl-list dl-list-async">
      <template v-if="asyncList.length">
        <div v-for="r in asyncList" :key="r.id" class="dl-row">
          <div class="dl-main">
            <span class="dl-name" :title="r.filename">{{ r.filename }}</span>
            <span class="text-secondary dl-sub" :title="r.id">{{ t('dl.task', { id: r.id, time: fmtTime(r.createdAt) }) }}</span>
          </div>
          <span :class="statusChip(r)">{{ STATUS_KEY[r.status] ? t(STATUS_KEY[r.status]) : r.status }}</span>
          <span class="text-secondary dl-size">{{ r.status === 'done' ? fmtBytes(r.size) : fmtBytes(r.estimatedBytes) }}</span>
          <span v-if="r.status === 'error'" class="text-danger dl-msg" :title="r.message">{{ r.message }}</span>
          <span v-else-if="r.status === 'running' && r.etaText" class="text-secondary dl-msg">{{ r.etaText }}</span>
          <div class="dl-actions">
            <a v-if="r.status === 'done'" class="btn btn-sm btn-primary"
               :href="withToken(`/api/export/${encodeURIComponent(r.id)}/download`)" :download="r.filename">{{ t('common.download') }}</a>
            <button class="btn btn-sm btn-outline-danger" :aria-label="t('dl.deleteTitle')" :title="t('dl.deleteTitle')" @click="deleteExport(r.id)">{{ t('common.delete') }}</button>
          </div>
        </div>
      </template>
      <div v-else class="dl-empty">{{ t('dl.emptyAsync') }}</div>
    </div>
  </div>
</template>

<style scoped>
.dl-panel {
  position: fixed;
  right: 1rem;
  top: 3.4rem;
  width: 30rem;
  max-width: calc(100vw - 2rem);
  z-index: 1060;
  background: var(--bs-body-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: .5rem;
  padding: .6rem .75rem;
  display: flex;
  flex-direction: column;
  gap: .4rem;
}
.dl-head { display: flex; align-items: center; gap: .5rem; }
/* 同步/异步资产分区标题：统一图标 + 命名，层级清晰；上下留白与主体分隔 */
.dl-section-title {
  display: flex; align-items: center; gap: .35rem;
  font-size: .74rem; font-weight: 600; color: var(--bs-secondary-color);
  border-top: 1px solid var(--bs-border-color);
  padding: .35rem 0 .15rem;
  margin-top: .25rem;
}
.dl-section-title:first-of-type { border-top: none; }
.dl-hint { font-size: .72rem; }
.dl-toolbar input { min-width: 0; }
.dl-list { max-height: 20rem; overflow-y: auto; display: flex; flex-direction: column; gap: .3rem; }
.dl-row {
  display: flex; align-items: center; gap: .5rem;
  padding: .3rem .4rem; border: 1px solid var(--bs-border-color); border-radius: .4rem;
}
.dl-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; }
.dl-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dl-sub, .dl-size, .dl-msg { font-size: .7rem; }
.dl-msg { max-width: 7rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dl-actions { display: flex; gap: .3rem; }
.dl-empty { text-align: center; padding: 1rem; font-size: .78rem; }
</style>