<script setup>
// T06221 备注编辑器：纯 UI 组件，保存/删除/复制证据通过事件交给 DiffView。
// 文本只用 v-model 插值，不用 v-html；备注正文在报告渲染管线中统一转义。
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '../lib/i18n'
import {
  REVIEW_NOTE_MAX, REVIEW_NOTE_TAGS, REVIEW_NOTE_TAG_META, validateReviewNote, summarizeInline
} from '../lib/review_notes'

const props = defineProps({
  visible: { type: Boolean, default: false },
  target: { type: Object, default: null },
  note: { type: Object, default: null }
})
const emit = defineEmits(['save', 'delete', 'copy', 'close'])

const text = ref('')
const tag = ref('other')
const error = ref('')
const textarea = ref(null)
const noteLen = computed(() => [...text.value].length)
const title = computed(() => props.note ? t('rv.note.editTitle') : t('rv.note.newTitle'))
const scopeLabel = computed(() => {
  const scope = props.target && props.target.scope
  return t('rv.scope.' + (scope === 'hunk' ? 'hunk' : (scope === 'file' ? 'file' : 'line')))
})
const targetMeta = computed(() => {
  const x = props.target || {}
  if (x.scope === 'hunk') return t('rv.target.hunk', { id: x.hunkId == null ? '?' : x.hunkId })
  if (x.scope === 'line') return t('rv.target.line', { old: x.oldLine || '—', new: x.newLine || '—' })
  return t('rv.target.file')
})
const pathText = computed(() => summarizeInline((props.target && props.target.path) || '', 120))

function reset() {
  text.value = (props.note && props.note.text) || ''
  tag.value = (props.note && props.note.tag) || 'other'
  error.value = ''
  nextTick(() => { if (textarea.value) textarea.value.focus() })
}
watch(() => [props.visible, props.note && props.note.id, props.target && props.target.targetKey], () => {
  if (props.visible) reset()
}, { immediate: true })

function save() {
  const check = validateReviewNote(text.value)
  if (!check.ok) {
    error.value = t(check.errorKey, { max: REVIEW_NOTE_MAX })
    return
  }
  error.value = ''
  emit('save', { text: check.text, tag: tag.value })
}
function close() { emit('close') }
function del() { emit('delete') }
function copy() { emit('copy') }
function setTag(v) { tag.value = v }
</script>

<template>
  <div v-if="visible" class="rv-note-backdrop" @click.self="close" @keydown.esc.stop="close">
    <div class="rv-note-dialog" role="dialog" aria-modal="true" :aria-label="title">
      <div class="rv-note-head">
        <div class="rv-note-title">
          <i class="bi bi-chat-left-text"></i>
          <span>{{ title }}</span>
          <span class="rv-note-scope">{{ scopeLabel }}</span>
          <span class="rv-note-meta">{{ targetMeta }}</span>
        </div>
        <button type="button" class="btn-close" :aria-label="t('common.close')" @click="close"></button>
      </div>
      <div class="rv-note-path" :title="pathText"><i class="bi bi-file-earmark-code"></i> {{ pathText }}</div>

      <div class="rv-note-tags" role="radiogroup" :aria-label="t('rv.note.tagLabel')">
        <button v-for="key in REVIEW_NOTE_TAGS" :key="key" type="button"
                class="rv-note-tag" :class="['tag-' + key, { active: tag === key }]"
                :aria-checked="tag === key" role="radio" :data-tag="key" @click="setTag(key)">
          <i class="bi" :class="REVIEW_NOTE_TAG_META[key].icon"></i>
          {{ t(REVIEW_NOTE_TAG_META[key].labelKey) }}
        </button>
      </div>

      <textarea id="rv-note-text" ref="textarea" v-model="text" class="form-control rv-note-text"
                rows="8" :placeholder="t('rv.note.placeholder')" :aria-label="t('rv.note.placeholder')"
                :maxlength="REVIEW_NOTE_MAX" @keydown.ctrl.enter.prevent="save"></textarea>
      <div class="rv-note-footnote">
        <span :class="{ 'text-danger': noteLen >= REVIEW_NOTE_MAX, 'text-secondary': noteLen < REVIEW_NOTE_MAX }">
          {{ t('rv.note.counter', { n: noteLen, max: REVIEW_NOTE_MAX }) }}
        </span>
        <span class="text-secondary">{{ t('rv.note.privacy') }}</span>
      </div>
      <div v-if="error" class="rv-note-error" role="alert"><i class="bi bi-exclamation-circle"></i> {{ error }}</div>

      <div class="rv-note-actions">
        <button type="button" class="btn btn-outline-secondary btn-sm" @click="copy">
          <i class="bi bi-clipboard"></i> {{ t('rv.evidence.copy') }}
        </button>
        <button v-if="note" type="button" class="btn btn-outline-danger btn-sm" @click="del">
          <i class="bi bi-trash"></i> {{ t('common.delete') }}
        </button>
        <span class="flex-1"></span>
        <button type="button" class="btn btn-secondary btn-sm" @click="close">{{ t('common.cancel') }}</button>
        <button id="rv-note-save" type="button" class="btn btn-primary btn-sm" @click="save">
          <i class="bi bi-check2"></i> {{ t('common.save') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rv-note-backdrop {
  position: fixed; inset: 0; z-index: 1700;
  background: var(--bdf-overlay); display: flex; align-items: flex-start; justify-content: center;
  padding: 8vh 1rem 1rem;
}
.rv-note-dialog {
  width: min(620px, 96vw); max-height: 86vh; overflow: auto;
  background: var(--bs-body-bg); color: var(--bs-body-color);
  border: 1px solid var(--bs-border-color); border-radius: .6rem;
  box-shadow: 0 1rem 3rem rgba(0,0,0,.35); padding: .9rem 1rem 1rem;
}
.rv-note-head { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
.rv-note-title { display: flex; align-items: center; gap: .4rem; font-weight: 600; min-width: 0; }
.rv-note-title > span:nth-child(2) { white-space: nowrap; }
.rv-note-scope { font-size: .72rem; font-weight: 500; color: var(--bs-primary); background: var(--bs-primary-bg-subtle); border: 1px solid var(--bs-primary-border-subtle); border-radius: 999px; padding: .05rem .4rem; }
.rv-note-meta { font-size: .75rem; font-weight: 400; color: var(--bs-secondary-color); }
.rv-note-path { margin: .35rem 0 .6rem; font-size: .76rem; color: var(--bs-secondary-color); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rv-note-tags { display: flex; flex-wrap: wrap; gap: .3rem; margin-bottom: .55rem; }
.rv-note-tag { border: 1px solid var(--bs-border-color); background: transparent; color: var(--bs-body-color); border-radius: 999px; font-size: .74rem; padding: .1rem .45rem; }
.rv-note-tag.active { border-color: var(--bs-primary); background: var(--bs-primary); color: #fff; }
.rv-note-tag.tag-risk.active { border-color: var(--bs-danger); background: var(--bs-danger); }
.rv-note-tag.tag-confirm.active { border-color: var(--bs-warning); background: var(--bs-warning); color: #212529; }
.rv-note-tag.tag-compat.active { border-color: var(--bs-primary); background: var(--bs-primary); }
.rv-note-tag.tag-config.active { border-color: var(--bs-info); background: var(--bs-info); color: #212529; }
.rv-note-text { font-size: .82rem; resize: vertical; min-height: 9rem; }
.rv-note-footnote { display: flex; justify-content: space-between; gap: .5rem; font-size: .72rem; margin-top: .3rem; }
.rv-note-error { margin-top: .4rem; font-size: .78rem; color: var(--bs-danger); }
.rv-note-actions { display: flex; align-items: center; gap: .4rem; margin-top: .8rem; }
.flex-1 { flex: 1 1 auto; }
</style>
