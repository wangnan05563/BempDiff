<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api/client'
import {
  state, closeLaunchpad, setLaunchpadStartup, isLaunchpadStartupEnabled,
  loadCompareHistory, removeCompareHistory, loadSavedSessions, saveNamedSession,
  applyNamedSession, removeNamedSession, triggerCompare, buildOptions, toast,
  loadReviewForJob, loadInsights, setReviewCockpit, openLaunchpad, generateReport
} from '../store'
import { t } from '../lib/i18n'

const favoriteName = ref('')
const sampleBusy = ref(false)
const startupEnabled = ref(isLaunchpadStartupEnabled())

onMounted(() => {
  loadCompareHistory()
  loadSavedSessions()
  startupEnabled.value = isLaunchpadStartupEnabled()
})

const recent = computed(() => (state.compareHistory || []).slice(0, 10))
const saved = computed(() => (state.savedSessions || []).slice(0, 8))
const hasPaths = computed(() => !!(state.oldPath || '').trim() && !!(state.newPath || '').trim())

function baseName(p) {
  const s = String(p || '').replace(/\\/g, '/')
  const seg = s.split('/').filter(Boolean).pop() || s
  return seg.length > 34 ? '…' + seg.slice(-32) : seg
}
function typeLabel(item) {
  return item && item.leftType === 'folder' ? t('lp.template.folder') : 'WAR/JAR'
}
function pathKey(item) {
  return String((item && item.oldPath) || '') + '\u0001' + String((item && item.newPath) || '')
}
function invalidOf(item) {
  return state.launchpadInvalid[pathKey(item)] || ''
}
function markInvalid(item, reason) {
  state.launchpadInvalid[pathKey(item)] = reason || t('lp.invalid')
}
function clearInvalid(item) {
  delete state.launchpadInvalid[pathKey(item)]
}
function statsText(item) {
  const s = item && item.stats
  if (!s) return ''
  return `${t('lp.files')} ${(Number(s.added || 0) + Number(s.deleted || 0) + Number(s.modified || 0)).toLocaleString()}`
}
function reviewText(item) {
  const r = item && item.review
  if (!r || !r.total) return ''
  return `${t('lp.reviewed')} ${r.reviewed || 0}/${r.total}`
}
function riskText(item) {
  const risk = item && item.risk
  const high = Number(risk && risk.high || 0)
  const breaking = Number(risk && risk.breaking || 0)
  if (!high && !breaking) return ''
  return `${t('lp.risk')} ${high}${breaking ? ' · ' + t('lp.breaking') + ' ' + breaking : ''}`
}
function reviewPercent(item) {
  const r = item && item.review
  return r && Number.isFinite(r.percent) ? Math.max(0, Math.min(100, r.percent)) : 0
}
function shortJob(id) {
  return id ? String(id).slice(0, 10) : ''
}

async function openRecent(item, index) {
  if (state.launchpadBusy) return
  clearInvalid(item)
  state.launchpadBusy = true
  try {
    if (item.jobId) {
      let status = null
      try {
        status = await api.jobStatus(item.jobId)
      } catch (_) {
        status = null
      }
      if (status && status.status === 'DONE' && status.tree) {
        state.job = status
        state.activeCompareId = item.jobId
        state.oldPath = item.oldPath || state.oldPath
        state.newPath = item.newPath || state.newPath
        state.leftType = item.leftType === 'folder' ? 'folder' : 'package'
        state.tabs = []
        state.activeKey = null
        state.jobProgress = { status: 'DONE', progress: 100, phase: 'done', message: t('lp.openJob') }
        closeLaunchpad()
        await loadReviewForJob(item.jobId)
        loadInsights(item.jobId).catch(() => {})
        toast('success', t('lp.openJob'))
        return
      }
      markInvalid(item, t('lp.jobExpired'))
      return
    }

    // 无 jobId 的旧历史：先只读预检确认两侧路径仍在，再复用同一 payload 进入比对。
    state.launchpadBusy = true
    let pf = null
    try {
      pf = await api.preflight({
        leftType: item.leftType || 'package',
        leftPath: item.oldPath,
        rightPath: item.newPath,
        options: buildOptions()
      })
    } catch (_) { pf = null }
    const leftGone = !!(pf && pf.left && pf.left.exists === false)
    const rightGone = !!(pf && pf.right && pf.right.exists === false)
    if (leftGone || rightGone) {
      markInvalid(item, t('lp.invalid'))
      return
    }
    state.leftType = item.leftType === 'folder' ? 'folder' : 'package'
    state.oldPath = item.oldPath
    state.newPath = item.newPath
    closeLaunchpad()
    await triggerCompare({ skipPreflight: !!pf })
    if (index >= 0) removeCompareHistory(index)
  } catch (e) {
    toast('danger', t('lp.openFail', { err: e.message || e }))
  } finally {
    state.launchpadBusy = false
  }
}

function relocate(item) {
  clearInvalid(item)
  state.leftType = item.leftType === 'folder' ? 'folder' : 'package'
  state.oldPath = item.oldPath || ''
  state.newPath = item.newPath || ''
  closeLaunchpad()
  toast('warning', t('lp.relocateHint') || t('lp.invalid'))
}

function startNew() {
  closeLaunchpad()
  // 焦点留给 CompareBar 的路径输入；用户可继续已有路径或重新选择。
}

async function openSample() {
  if (sampleBusy.value) return
  sampleBusy.value = true
  try {
    const s = await api.getSample()
    state.leftType = s.leftType || 'folder'
    state.oldPath = s.leftPath
    state.newPath = s.rightPath
    closeLaunchpad()
    const ok = await triggerCompare({
      payload: { leftType: s.leftType || 'folder', leftPath: s.leftPath, rightPath: s.rightPath, options: buildOptions() },
      skipPreflight: true
    })
    if (ok && state.job) await generateReport(false)
    if (ok) toast('success', t('lp.sampleDone'))
  } catch (e) {
    toast('danger', t('lp.openFail', { err: e.message || e }))
  } finally {
    sampleBusy.value = false
  }
}

function applyTemplate(kind) {
  state.leftType = kind === 'folder' || kind === 'config' ? 'folder' : 'package'
  closeLaunchpad()
  toast('info', t('lp.templateApplied', { name: t(`lp.template.${kind}`) }))
}

function openSaved(item, index) {
  if (!applyNamedSession(index)) return
  closeLaunchpad()
  toast('success', t('lp.openJob'))
}

function saveCurrent() {
  const name = favoriteName.value.trim()
  if (!name) {
    toast('warning', t('lp.favoriteName'))
    return
  }
  if (!hasPaths.value) {
    toast('warning', t('lp.favoriteNeedPaths'))
    return
  }
  if (saveNamedSession(name)) {
    toast('success', t('lp.favoriteSaved', { name }))
    favoriteName.value = ''
  }
}

function onStartupChange(e) {
  startupEnabled.value = e.target.checked
  setLaunchpadStartup(startupEnabled.value)
}
function onRemoveSaved(index) {
  removeNamedSession(index)
}
function onRemoveRecent(index) {
  removeCompareHistory(index)
}
function backToWorkspace() {
  closeLaunchpad()
}
</script>

<template>
  <section class="launchpad" aria-label="BempDiff 工作台">
    <div class="lp-hero">
      <div class="lp-brand">
        <div class="lp-logo" aria-hidden="true"><i class="bi bi-bezier2"></i></div>
        <div>
          <div class="lp-kicker">BempDiff</div>
          <h1 class="lp-title">{{ t('lp.title') }}</h1>
          <p class="lp-sub">{{ t('lp.subtitle') }}</p>
        </div>
      </div>
      <div class="lp-hero-actions">
        <button type="button" class="btn btn-primary lp-new" @click="startNew">
          <i class="bi bi-plus-lg"></i> {{ t('lp.new') }}
        </button>
        <button v-if="state.job" type="button" class="btn btn-outline-secondary lp-back" @click="backToWorkspace">
          <i class="bi bi-arrow-left"></i> {{ t('lp.reopen') }}
        </button>
      </div>
    </div>

    <div class="lp-grid">
      <section class="lp-panel lp-recent" aria-labelledby="lp-recent-title">
        <div class="lp-panel-head">
          <h2 id="lp-recent-title"><i class="bi bi-clock-history"></i> {{ t('lp.recent') }}</h2>
          <span class="lp-count">{{ recent.length }}</span>
        </div>
        <div v-if="!recent.length" class="lp-empty">
          <i class="bi bi-inbox"></i>
          <p>{{ t('lp.emptyRecent') }}</p>
          <button type="button" class="btn btn-sm btn-outline-primary mt-2" :disabled="sampleBusy" data-test="lp-sample"
                  @click="openSample">
            <span v-if="sampleBusy" class="spinner-border spinner-border-sm me-1"></span>
            <i v-else class="bi bi-play-circle"></i> {{ sampleBusy ? t('lp.sampleBusy') : t('lp.sample') }}
          </button>
        </div>
        <ul v-else class="lp-list">
          <li v-for="(item, i) in recent" :key="pathKey(item)" class="lp-item"
              :class="{ invalid: !!invalidOf(item) }">
            <button type="button" class="lp-item-main" :disabled="state.launchpadBusy"
                    :title="`${item.oldPath} → ${item.newPath}`"
                    @click="openRecent(item, state.compareHistory.findIndex(x => pathKey(x) === pathKey(item)))">
              <span class="lp-item-icon"><i class="bi" :class="item.leftType === 'folder' ? 'bi-folder2-open' : 'bi-file-earmark-zip'"></i></span>
              <span class="lp-item-text">
                <span class="lp-item-name">{{ baseName(item.oldPath) }} <i class="bi bi-arrow-right"></i> {{ baseName(item.newPath) }}</span>
                <span class="lp-item-meta">
                  <span>{{ typeLabel(item) }}</span>
                  <span v-if="statsText(item)">{{ statsText(item) }}</span>
                  <span v-if="riskText(item)" class="lp-risk">{{ riskText(item) }}</span>
                  <span v-if="reviewText(item)">{{ reviewText(item) }}</span>
                  <span v-if="item.jobId" class="lp-job">#{{ shortJob(item.jobId) }}</span>
                </span>
              </span>
              <span v-if="item.review && item.review.total" class="lp-ring" :title="reviewText(item)">
                <span class="lp-ring-fill" :style="{ width: reviewPercent(item) + '%' }"></span>
              </span>
              <i class="bi bi-chevron-right lp-arrow"></i>
            </button>
            <div v-if="invalidOf(item)" class="lp-invalid-row">
              <span><i class="bi bi-exclamation-triangle"></i> {{ invalidOf(item) }}</span>
              <button type="button" class="btn btn-sm btn-outline-danger" @click="relocate(item)">{{ t('lp.relocate') }}</button>
            </div>
            <button type="button" class="lp-remove" :title="t('lp.remove')" :aria-label="t('lp.remove')"
                    @click="onRemoveRecent(state.compareHistory.findIndex(x => pathKey(x) === pathKey(item)))">
              <i class="bi bi-x"></i>
            </button>
          </li>
        </ul>
      </section>

      <section class="lp-panel" aria-labelledby="lp-saved-title">
        <div class="lp-panel-head">
          <h2 id="lp-saved-title"><i class="bi bi-star"></i> {{ t('lp.favorites') }}</h2>
          <span class="lp-count">{{ saved.length }}</span>
        </div>
        <div class="lp-save-row">
          <input v-model="favoriteName" class="form-control form-control-sm" maxlength="32"
                 :placeholder="t('lp.favoriteName')" @keydown.enter.prevent="saveCurrent" />
          <button type="button" class="btn btn-sm btn-outline-primary" @click="saveCurrent" :disabled="!hasPaths">
            <i class="bi bi-star"></i> {{ t('lp.favorite') }}
          </button>
        </div>
        <div v-if="!saved.length" class="lp-empty lp-empty-sm">
          <i class="bi bi-star"></i>
          <p>{{ t('lp.emptyFavorites') }}</p>
        </div>
        <ul v-else class="lp-saved-list">
          <li v-for="(item, i) in saved" :key="'s' + i + item.name" class="lp-saved-item">
            <button type="button" class="lp-saved-main" @click="openSaved(item, i)" :title="`${item.oldPath} → ${item.newPath}`">
              <span class="lp-saved-star"><i class="bi bi-star-fill"></i></span>
              <span class="lp-saved-text">
                <b>{{ item.name }}</b>
                <small>{{ baseName(item.oldPath) }} → {{ baseName(item.newPath) }}</small>
              </span>
              <i class="bi bi-arrow-right"></i>
            </button>
            <button type="button" class="lp-remove" :title="t('lp.remove')" @click="onRemoveSaved(i)"><i class="bi bi-x"></i></button>
          </li>
        </ul>
      </section>

      <section class="lp-panel lp-templates" aria-labelledby="lp-tpl-title">
        <div class="lp-panel-head">
          <h2 id="lp-tpl-title"><i class="bi bi-grid-3x3-gap"></i> {{ t('lp.templates') }}</h2>
        </div>
        <div class="lp-template-grid">
          <button type="button" class="lp-template" @click="applyTemplate('war')">
            <i class="bi bi-file-earmark-zip"></i>
            <b>{{ t('lp.template.war') }}</b>
            <small>{{ t('lp.template.warDesc') }}</small>
          </button>
          <button type="button" class="lp-template" @click="applyTemplate('folder')">
            <i class="bi bi-folder2-open"></i>
            <b>{{ t('lp.template.folder') }}</b>
            <small>{{ t('lp.template.folderDesc') }}</small>
          </button>
          <button type="button" class="lp-template" @click="applyTemplate('config')">
            <i class="bi bi-sliders2"></i>
            <b>{{ t('lp.template.config') }}</b>
            <small>{{ t('lp.template.configDesc') }}</small>
          </button>
        </div>
      </section>
    </div>

    <section class="lp-guide" aria-labelledby="lp-guide-title">
      <div class="lp-guide-copy">
        <h2 id="lp-guide-title"><i class="bi bi-signpost-2"></i> {{ t('lp.guide') }}</h2>
        <ol>
          <li>{{ t('lp.guide1') }}</li>
          <li>{{ t('lp.guide2') }}</li>
          <li>{{ t('lp.guide3') }}</li>
        </ol>
      </div>
      <div class="lp-guide-actions">
        <span class="lp-local"><i class="bi bi-shield-check"></i> {{ t('lp.localOnly') }}</span>
        <label class="lp-startup">
          <input type="checkbox" :checked="startupEnabled" @change="onStartupChange" />
          {{ t('lp.disable') }}
        </label>
      </div>
    </section>
  </section>
</template>

<style scoped>
.launchpad {
  height: calc(100vh - 58px);
  overflow: auto;
  padding: clamp(16px, 2.4vw, 34px) clamp(16px, 3.2vw, 44px) 36px;
  background:
    radial-gradient(1200px 460px at 12% -12%, color-mix(in srgb, var(--bs-primary) 10%, transparent), transparent 70%),
    var(--bs-body-bg);
  color: var(--bs-body-color);
}
.lp-hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; max-width: 1180px; margin: 0 auto 22px; }
.lp-brand { display: flex; align-items: center; gap: 14px; min-width: 0; }
.lp-logo {
  width: 52px; height: 52px; border-radius: 15px; display: grid; place-items: center; flex: 0 0 auto;
  color: #fff; font-size: 1.55rem; background: linear-gradient(135deg, #2563eb, #0ea5e9);
  box-shadow: 0 10px 24px color-mix(in srgb, #2563eb 28%, transparent);
}
.lp-kicker { font-size: .68rem; letter-spacing: .16em; text-transform: uppercase; color: var(--bs-secondary-color); font-weight: 700; }
.lp-title { margin: 0; font-size: clamp(1.35rem, 2.1vw, 1.8rem); line-height: 1.15; letter-spacing: -.02em; }
.lp-sub { margin: 5px 0 0; color: var(--bs-secondary-color); font-size: .82rem; max-width: 660px; }
.lp-hero-actions { display: flex; gap: 8px; align-items: center; flex: 0 0 auto; }
.lp-new { white-space: nowrap; box-shadow: 0 6px 16px color-mix(in srgb, var(--bs-primary) 22%, transparent); }
.lp-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(260px, .85fr) minmax(250px, .8fr); grid-template-areas: "recent saved templates"; gap: 16px; max-width: 1180px; margin: 0 auto; align-items: start; }
.lp-recent { grid-area: recent; }
.lp-panel:nth-child(2) { grid-area: saved; }
.lp-templates { grid-area: templates; }
.lp-panel { background: var(--bs-body-bg); border: 1px solid var(--bs-border-color); border-radius: 16px; padding: 16px; min-width: 0; box-shadow: 0 8px 28px rgba(15, 23, 42, .04); }
.lp-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.lp-panel-head h2 { margin: 0; font-size: .92rem; display: flex; align-items: center; gap: 7px; }
.lp-panel-head h2 i { color: var(--bs-primary); }
.lp-count { font-size: .7rem; color: var(--bs-secondary-color); border: 1px solid var(--bs-border-color); border-radius: 999px; padding: 1px 7px; }
.lp-list, .lp-saved-list { list-style: none; margin: 0; padding: 0; }
.lp-item { position: relative; border: 1px solid var(--bs-border-color); border-radius: 11px; margin-bottom: 8px; overflow: hidden; background: color-mix(in srgb, var(--bs-body-bg) 92%, var(--bs-primary) 8%); }
.lp-item.invalid { border-color: var(--bs-danger); }
.lp-item-main { width: 100%; border: 0; background: transparent; color: inherit; text-align: left; display: flex; align-items: center; gap: 9px; padding: 10px 30px 10px 10px; }
.lp-item-main:hover { background: color-mix(in srgb, var(--bs-primary) 6%, transparent); }
.lp-item-icon { width: 30px; height: 30px; border-radius: 9px; display: grid; place-items: center; flex: 0 0 auto; color: var(--bs-primary); background: color-mix(in srgb, var(--bs-primary) 12%, transparent); }
.lp-item-text { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.lp-item-name { font-size: .82rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lp-item-meta { display: flex; gap: 9px; flex-wrap: wrap; font-size: .68rem; color: var(--bs-secondary-color); }
.lp-job { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.lp-risk { color: var(--bs-danger); font-weight: 600; }
.lp-ring { width: 34px; height: 5px; border-radius: 4px; overflow: hidden; background: var(--bs-border-color); flex: 0 0 auto; }
.lp-ring-fill { display: block; height: 100%; background: var(--bs-success); }
.lp-arrow { font-size: .75rem; color: var(--bs-secondary-color); }
.lp-remove { position: absolute; top: 7px; right: 6px; border: 0; background: transparent; color: var(--bs-secondary-color); font-size: .8rem; line-height: 1; padding: 3px; opacity: .65; }
.lp-remove:hover { color: var(--bs-danger); opacity: 1; }
.lp-invalid-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 10px 9px 49px; font-size: .7rem; color: var(--bs-danger); }
.lp-empty { text-align: center; color: var(--bs-secondary-color); padding: 22px 8px; font-size: .78rem; }
.lp-empty i { font-size: 1.35rem; display: block; margin-bottom: 7px; color: color-mix(in srgb, var(--bs-primary) 45%, var(--bs-secondary-color)); }
.lp-empty p { margin: 0; }
.lp-empty-sm { padding: 14px 4px; }
.lp-save-row { display: flex; gap: 6px; margin-bottom: 10px; }
.lp-saved-item { display: flex; align-items: center; border-bottom: 1px solid var(--bs-border-color); }
.lp-saved-item:last-child { border-bottom: 0; }
.lp-saved-main { border: 0; background: transparent; color: inherit; text-align: left; flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; padding: 8px 4px; }
.lp-saved-main:hover { color: var(--bs-primary); }
.lp-saved-star { color: #f59e0b; }
.lp-saved-text { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; }
.lp-saved-text b { font-size: .78rem; }
.lp-saved-text small { font-size: .68rem; color: var(--bs-secondary-color); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lp-saved-item .lp-remove { position: static; flex: 0 0 auto; }
.lp-template-grid { display: grid; gap: 8px; }
.lp-template { border: 1px solid var(--bs-border-color); background: color-mix(in srgb, var(--bs-primary) 3%, var(--bs-body-bg)); color: inherit; border-radius: 11px; padding: 10px; text-align: left; display: grid; grid-template-columns: 26px 1fr; grid-template-areas: "icon name" "icon desc"; column-gap: 8px; align-items: center; }
.lp-template:hover { border-color: var(--bs-primary); background: color-mix(in srgb, var(--bs-primary) 8%, var(--bs-body-bg)); }
.lp-template i { grid-area: icon; color: var(--bs-primary); font-size: 1.15rem; }
.lp-template b { grid-area: name; font-size: .78rem; }
.lp-template small { grid-area: desc; color: var(--bs-secondary-color); font-size: .67rem; line-height: 1.35; }
.lp-guide { max-width: 1180px; margin: 18px auto 0; display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 14px 18px; border: 1px dashed var(--bs-border-color); border-radius: 14px; background: color-mix(in srgb, var(--bs-primary) 2%, transparent); }
.lp-guide-copy h2 { margin: 0 0 7px; font-size: .85rem; display: flex; align-items: center; gap: 7px; }
.lp-guide-copy h2 i { color: var(--bs-primary); }
.lp-guide-copy ol { margin: 0; padding-left: 18px; display: flex; gap: 18px; flex-wrap: wrap; color: var(--bs-secondary-color); font-size: .72rem; }
.lp-guide-copy li { padding-right: 4px; }
.lp-guide-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; flex: 0 0 auto; }
.lp-local { font-size: .68rem; color: var(--bs-success); }
.lp-local i { margin-right: 4px; }
.lp-startup { font-size: .67rem; color: var(--bs-secondary-color); display: flex; gap: 5px; align-items: center; cursor: pointer; }
@media (max-width: 1080px) {
  .lp-grid { grid-template-columns: minmax(0, 1fr) minmax(250px, .8fr); grid-template-areas: "recent saved" "recent templates"; }
  .lp-templates { align-self: start; }
}
@media (max-width: 720px) {
  .launchpad { height: calc(100vh - 104px); padding: 14px 12px 26px; }
  .lp-hero { flex-direction: column; gap: 10px; }
  .lp-hero-actions { width: 100%; justify-content: flex-end; }
  .lp-grid { grid-template-columns: 1fr; grid-template-areas: "recent" "saved" "templates"; }
  .lp-guide { flex-direction: column; align-items: stretch; }
  .lp-guide-copy ol { flex-direction: column; gap: 5px; }
  .lp-guide-actions { align-items: flex-start; }
  .lp-item-meta { gap: 6px; }
}
</style>
