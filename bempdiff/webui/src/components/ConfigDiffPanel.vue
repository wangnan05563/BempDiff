<script setup>
import { computed, ref } from 'vue'
import { t } from '../lib/i18n'
import { toast } from '../store'
import { diffConfig, configDiffMarkdown } from '../lib/config_semantic'

const props = defineProps({
  fileKey: { type: String, default: '' },
  kind: { type: String, default: 'json' },
  oldText: { type: String, default: '' },
  newText: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const query = ref('')
const filter = ref('all')
const showSecret = ref(false)
const collapsed = ref({})
const shownCount = ref({})

const rawResult = computed(() => diffConfig(props.kind, props.oldText, props.newText, { maskSecrets: !showSecret.value }))
const maskedResult = computed(() => diffConfig(props.kind, props.oldText, props.newText, { maskSecrets: true }))

const LABEL = { add: 'cd.filter.add', remove: 'cd.filter.remove', change: 'cd.filter.change', type: 'cd.filter.type', reorder: 'cd.filter.reorder', duplicate: 'cd.filter.duplicate' }
const filteredGroups = computed(() => {
  const r = rawResult.value
  if (!r.ok) return []
  const q = query.value.trim().toLowerCase()
  const groups = []
  for (const g of r.groups) {
    const changes = g.changes.filter(c => {
      if (filter.value !== 'all' && c.change !== filter.value) return false
      if (!q) return true
      return (String(c.path) + ' ' + String(c.oldValue) + ' ' + String(c.newValue)).toLowerCase().includes(q)
    })
    if (changes.length) groups.push({ group: g.group, changes })
  }
  return groups
})

const totalFiltered = computed(() => filteredGroups.value.reduce((n, g) => n + g.changes.length, 0))

function shownFor(group) {
  return shownCount.value[group] || 50
}
function canShowMore(group, changes) {
  return changes.length > shownFor(group)
}
function showMore(group) {
  shownCount.value = { ...shownCount.value, [group]: shownFor(group) + 50 }
}
function toggle(group) {
  collapsed.value = { ...collapsed.value, [group]: !collapsed.value[group] }
}
function typeLabel(type) {
  return type || ''
}
function changeClass(c) {
  return 'cdv-' + (c.change || 'change')
}
function changeLabel(c) {
  return t(LABEL[c.change] || 'cd.filter.change')
}
function secretTip(c) {
  return c.secret ? t('cd.secret') : ''
}
function groupTitle(g) {
  return g.group || '—'
}
function formatValue(v) {
  const s = String(v == null ? '' : v)
  return s.length > 220 ? s.slice(0, 220) + '…' : s
}
async function copyMasked() {
  if (!maskedResult.value.ok || !maskedResult.value.changes.length) return
  const text = configDiffMarkdown(maskedResult.value, props.fileKey)
  try {
    await navigator.clipboard.writeText(text)
    toast('success', t('cd.copied'))
  } catch (e) {
    toast('warning', e.message || 'copy failed')
  }
}
</script>

<template>
  <div class="cdv-root" data-test="config-semantic-diff">
    <div class="cdv-head">
      <div class="cdv-title-wrap">
        <div class="cdv-title"><i class="bi bi-braces-asterisk"></i> {{ t('cd.title') }}</div>
        <div class="cdv-subtitle">{{ t('cd.subtitle') }}</div>
      </div>
      <div class="cdv-head-actions">
        <span v-if="rawResult.ok && rawResult.stats.secret" class="cdv-badge cdv-badge-secret">
          <i class="bi bi-shield-lock"></i> {{ t('cd.secret') }} {{ rawResult.stats.secret }}
        </span>
        <label class="cdv-secret-toggle" :title="t('cd.secretShown')">
          <input v-model="showSecret" type="checkbox" data-test="cd-secret-toggle" />
          <span>{{ t('cd.secretToggle') }}</span>
        </label>
        <button type="button" class="btn btn-sm btn-outline-secondary py-0 px-2" @click="emit('close')">
          <i class="bi bi-file-diff"></i> {{ t('cd.raw') }}
        </button>
        <button type="button" class="btn btn-sm btn-outline-primary py-0 px-2" :disabled="!maskedResult.ok || !maskedResult.changes.length" @click="copyMasked">
          <i class="bi bi-clipboard"></i> {{ t('cd.copy') }}
        </button>
      </div>
    </div>

    <div v-if="!rawResult.ok" class="cdv-error" data-test="cd-parse-error">
      <i class="bi bi-exclamation-triangle"></i>
      <span>{{ t('cd.parseError', { err: rawResult.error || '' }) }}</span>
      <button type="button" class="btn btn-sm btn-outline-primary py-0 px-2" @click="emit('close')">{{ t('cd.raw') }}</button>
    </div>

    <template v-else>
      <div class="cdv-tools">
        <div class="cdv-stats">
          <button type="button" class="cdv-stat" :class="{ active: filter === 'all' }" @click="filter = 'all'">
            {{ t('cd.filter.all') }} <b>{{ rawResult.stats.total }}</b>
          </button>
          <button type="button" class="cdv-stat cdv-add" :class="{ active: filter === 'add' }" @click="filter = 'add'">
            +{{ rawResult.stats.add }}
          </button>
          <button type="button" class="cdv-stat cdv-remove" :class="{ active: filter === 'remove' }" @click="filter = 'remove'">
            −{{ rawResult.stats.remove }}
          </button>
          <button type="button" class="cdv-stat cdv-change" :class="{ active: filter === 'change' }" @click="filter = 'change'">
            ~{{ rawResult.stats.change }}
          </button>
          <button type="button" class="cdv-stat cdv-type" :class="{ active: filter === 'type' }" @click="filter = 'type'">
            ⇄{{ rawResult.stats.type }}
          </button>
          <button v-if="rawResult.stats.duplicate" type="button" class="cdv-stat cdv-dup-stat" :class="{ active: filter === 'duplicate' }" @click="filter = 'duplicate'">
            DUP {{ rawResult.stats.duplicate }}
          </button>
        </div>
        <div class="cdv-search">
          <i class="bi bi-search"></i>
          <input v-model="query" type="search" :placeholder="t('cd.search')" data-test="cd-search" />
          <button v-if="query" type="button" class="cdv-clear" @click="query = ''" aria-label="clear"><i class="bi bi-x"></i></button>
        </div>
      </div>

      <div v-if="!rawResult.stats.total" class="cdv-empty" data-test="cd-empty">
        <i class="bi bi-check2-circle"></i> {{ t('cd.empty') }}
        <div v-if="rawResult.duplicates && rawResult.duplicates.length" class="cdv-dup">
          DUP: {{ rawResult.duplicates.slice(0, 8).join(', ') }}
        </div>
      </div>

      <div v-else-if="!totalFiltered" class="cdv-empty">
        <i class="bi bi-search"></i> 0
      </div>

      <div v-else class="cdv-groups" data-test="cd-groups">
        <section v-for="g in filteredGroups" :key="g.group" class="cdv-group" :data-group="g.group">
          <button type="button" class="cdv-group-head" @click="toggle(g.group)">
            <i class="bi" :class="collapsed[g.group] ? 'bi-chevron-right' : 'bi-chevron-down'"></i>
            <span class="cdv-group-name">{{ groupTitle(g) }}</span>
            <span class="cdv-group-count">{{ g.changes.length }}</span>
          </button>
          <div v-show="!collapsed[g.group]" class="cdv-table-wrap">
            <table class="cdv-table">
              <thead>
                <tr>
                  <th class="cdv-col-change">{{ t('cd.change') }}</th>
                  <th class="cdv-col-key">{{ t('cd.key') }}</th>
                  <th>{{ t('cd.old') }}</th>
                  <th>{{ t('cd.new') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in g.changes.slice(0, shownFor(g.group))" :key="c.path + '|' + c.change" :class="changeClass(c)">
                  <td class="cdv-col-change">
                    <span class="cdv-pill" :class="changeClass(c)">{{ changeLabel(c) }}</span>
                    <span v-if="c.placeholder" class="cdv-pill cdv-placeholder">{{ t('cd.placeholder') }}</span>
                    <i v-if="c.secret" class="bi bi-shield-lock cdv-secret-ic" :title="secretTip(c)"></i>
                  </td>
                  <td class="cdv-col-key"><code>{{ c.path }}</code></td>
                  <td class="cdv-val cdv-old" :title="String(c.oldValue)"><span>{{ formatValue(c.oldValue) }}</span><span v-if="c.oldType" class="cdv-valtype">{{ typeLabel(c.oldType) }}</span></td>
                  <td class="cdv-val cdv-new" :title="String(c.newValue)"><span>{{ formatValue(c.newValue) }}</span><span v-if="c.newType" class="cdv-valtype">{{ typeLabel(c.newType) }}</span></td>
                </tr>
              </tbody>
            </table>
            <button v-if="canShowMore(g.group, g.changes)" type="button" class="cdv-more" @click="showMore(g.group)">
              {{ t('cd.more', { n: g.changes.length - shownFor(g.group) }) }}
            </button>
          </div>
        </section>
      </div>

      <div class="cdv-foot">
        <span>{{ t('cd.narrowHint') }}</span>
        <span v-if="rawResult.duplicates && rawResult.duplicates.length">DUP {{ rawResult.duplicates.length }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cdv-root {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--bs-body-bg, #fff);
  color: var(--bs-body-color, #212529);
  overflow: hidden;
}
.cdv-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: .75rem;
  padding: .65rem .9rem;
  border-bottom: 1px solid var(--bs-border-color, #dee2e6);
  background: var(--bs-tertiary-bg, #f8f9fa);
  flex-wrap: wrap;
}
.cdv-title-wrap { min-width: 0; }
.cdv-title { font-weight: 650; font-size: .92rem; display: flex; align-items: center; gap: .4rem; }
.cdv-subtitle { font-size: .76rem; color: var(--bs-secondary-color, #6c757d); margin-top: .15rem; }
.cdv-head-actions { display: flex; align-items: center; gap: .45rem; flex-wrap: wrap; }
.cdv-badge { font-size: .73rem; border-radius: 999px; padding: .12rem .5rem; border: 1px solid #f0c36b; color: #8a5a00; background: #fff6df; }
.cdv-secret-toggle { display: inline-flex; align-items: center; gap: .3rem; font-size: .76rem; color: var(--bs-secondary-color, #6c757d); user-select: none; }
.cdv-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: .75rem;
  padding: .45rem .9rem;
  border-bottom: 1px solid var(--bs-border-color, #dee2e6);
  flex-wrap: wrap;
}
.cdv-stats { display: flex; align-items: center; gap: .25rem; flex-wrap: wrap; }
.cdv-stat {
  border: 1px solid transparent;
  background: var(--bs-secondary-bg, #e9ecef);
  color: var(--bs-body-color, #212529);
  border-radius: 6px;
  padding: .12rem .45rem;
  font-size: .76rem;
  display: inline-flex;
  align-items: center;
  gap: .3rem;
  cursor: pointer;
}
.cdv-stat.active { border-color: var(--bs-primary, #0d6efd); background: var(--bs-primary-bg-subtle, #cfe2ff); }
.cdv-add { color: #146c43; }
.cdv-remove { color: #b02a37; }
.cdv-change { color: #997404; }
.cdv-type { color: #6f42c1; }
.cdv-search {
  display: inline-flex;
  align-items: center;
  gap: .3rem;
  border: 1px solid var(--bs-border-color, #dee2e6);
  border-radius: 6px;
  padding: .1rem .4rem;
  background: var(--bs-body-bg, #fff);
  min-width: 13rem;
}
.cdv-search input { border: 0; outline: none; background: transparent; color: inherit; font-size: .78rem; width: 100%; }
.cdv-clear { border: 0; background: transparent; color: var(--bs-secondary-color, #6c757d); cursor: pointer; padding: 0 .1rem; }
.cdv-error { margin: .8rem; padding: .7rem .8rem; border-radius: 6px; border: 1px solid #f1c27d; background: #fff7e8; color: #7a4d00; display: flex; align-items: center; gap: .5rem; flex-wrap: wrap; }
.cdv-empty { margin: 1.2rem; padding: 1rem; border: 1px dashed var(--bs-border-color, #dee2e6); border-radius: 8px; color: var(--bs-secondary-color, #6c757d); display: flex; align-items: center; gap: .5rem; }
.cdv-groups { overflow: auto; flex: 1; padding: .5rem .8rem .9rem; }
.cdv-group { border: 1px solid var(--bs-border-color, #dee2e6); border-radius: 8px; margin-bottom: .55rem; overflow: hidden; }
.cdv-group-head {
  display: flex;
  align-items: center;
  gap: .4rem;
  width: 100%;
  border: 0;
  background: var(--bs-tertiary-bg, #f8f9fa);
  color: inherit;
  padding: .35rem .5rem;
  text-align: left;
  font-size: .8rem;
  cursor: pointer;
}
.cdv-group-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cdv-group-count { margin-left: auto; font-size: .72rem; color: var(--bs-secondary-color, #6c757d); background: var(--bs-secondary-bg, #e9ecef); border-radius: 999px; padding: 0 .4rem; }
.cdv-table-wrap { overflow-x: auto; }
.cdv-table { width: 100%; border-collapse: collapse; font-size: .76rem; table-layout: fixed; }
.cdv-table th { position: sticky; top: 0; background: var(--bs-body-bg, #fff); border-bottom: 1px solid var(--bs-border-color, #dee2e6); padding: .35rem .45rem; text-align: left; font-weight: 600; color: var(--bs-secondary-color, #6c757d); }
.cdv-col-change { width: 5.2rem; }
.cdv-col-key { width: 32%; }
.cdv-table td { padding: .3rem .45rem; border-bottom: 1px solid var(--bs-border-color-translucent, rgba(0,0,0,.06)); vertical-align: top; }
.cdv-table tr.cdv-add td { background: rgba(25, 135, 84, .045); }
.cdv-table tr.cdv-remove td { background: rgba(220, 53, 69, .045); }
.cdv-table tr.cdv-type td { background: rgba(111, 66, 193, .06); }
.cdv-col-key code { font-size: .75rem; color: var(--bs-body-color, #212529); word-break: break-all; }
.cdv-pill { display: inline-block; border-radius: 4px; padding: 0 .3rem; font-size: .7rem; line-height: 1.35; white-space: nowrap; margin-right: .2rem; }
.cdv-add .cdv-pill, .cdv-pill.cdv-add { background: #d1e7dd; color: #0f5132; }
.cdv-remove .cdv-pill, .cdv-pill.cdv-remove { background: #f8d7da; color: #842029; }
.cdv-change .cdv-pill, .cdv-pill.cdv-change { background: #fff3cd; color: #664d03; }
.cdv-type .cdv-pill, .cdv-pill.cdv-type { background: #e2d9f3; color: #432874; }
.cdv-pill.cdv-placeholder { background: #cfe2ff; color: #084298; }
.cdv-secret-ic { color: #8a5a00; margin-left: .15rem; }
.cdv-val { overflow: hidden; }
.cdv-val span:first-child { display: block; word-break: break-word; white-space: pre-wrap; max-height: 7.2em; overflow: auto; }
.cdv-old { color: #842029; }
.cdv-new { color: #0f5132; }
.cdv-type { font-size: .68rem; color: var(--bs-secondary-color, #6c757d); margin-top: .1rem; }
.cdv-valtype { font-size: .68rem; color: var(--bs-secondary-color, #6c757d); margin-top: .1rem; }
.cdv-more { width: 100%; border: 0; border-top: 1px dashed var(--bs-border-color, #dee2e6); background: var(--bs-tertiary-bg, #f8f9fa); color: var(--bs-primary, #0d6efd); padding: .3rem; font-size: .75rem; cursor: pointer; }
.cdv-foot { display: flex; justify-content: space-between; gap: .5rem; padding: .3rem .9rem; border-top: 1px solid var(--bs-border-color, #dee2e6); font-size: .72rem; color: var(--bs-secondary-color, #6c757d); }
@media (max-width: 760px) {
  .cdv-table thead { display: none; }
  .cdv-table, .cdv-table tbody, .cdv-table tr, .cdv-table td { display: block; width: 100%; }
  .cdv-table tr { border-bottom: 1px solid var(--bs-border-color, #dee2e6); padding: .25rem 0; }
  .cdv-table td { border: 0; padding: .18rem .45rem; }
  .cdv-col-key { width: auto; }
  .cdv-head-actions { width: 100%; }
}
</style>
