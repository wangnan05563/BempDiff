<script setup>
// 引导系统组件：常驻「引导」按钮 + 引导浮层。
//  - 首次访问（localStorage 未记录 seen）自动弹出，弹完后自动记录，后续不再自动弹。
//  - 之后通过常驻按钮再次唤起（支持手动步进 / 自动播放 / 跳过）。
//  - 高亮当前步骤的 target 元素（描边浮层 + 对齐说明气泡），无目标则居中展示文字。
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import {
  GUIDE_STEPS, allSteps, isGuideDone, isFirstRun, progressText, locateTarget,
  GUIDE_DONE_KEY, GUIDE_SEEN_KEY, isGuideButtonVisible
} from '../lib/guide'
import { t } from '../lib/i18n'

const emit = defineEmits(['settled'])
// 步骤文案取 i18n：有 titleKey/textKey 走 t()，否则回退清单里的中文原文
const titleOf = (s) => (s && s.titleKey ? t(s.titleKey) : (s ? s.title : ''))
const textOf = (s) => (s && s.textKey ? t(s.textKey) : (s ? s.text : ''))
const open = ref(false)
const idx = ref(0)
const autoplay = ref(false)
const target = ref(null)
// 按钮可见性：默认隐藏，由 HelpDoc「开始引导」开启（guide.js 共享状态）。渲染用响应式，
// 以便 HelpDoc 切换后立刻反映到 fab 显隐。
const renderTick = ref(isGuideButtonVisible())
watch(() => isGuideButtonVisible(), (v) => { renderTick.value = v })
let seenFirstRun = false // 本次会话是否已处理过首次自动弹出
let onStartGuide = null // 供 onBeforeUnmount 精确移除事件监听

const steps = computed(() => allSteps())
const step = computed(() => steps.value[idx.value] || steps.value[0])
const progress = computed(() => progressText(idx.value))

// 自动播放计时器
let autoTimer = null
function clearAuto() {
  if (autoTimer) { clearTimeout(autoTimer); autoTimer = null }
}

/** 定位并更新当前步骤的高亮矩形。 */
function refreshTarget() {
  const s = step.value
  target.value = s && s.target ? locateTarget(s.target) : null
}

/** 打开引导：从未开始（done 未设）则从第 0 步；重开则从上次步数继续。 */
function openGuide(fromStart = false) {
  idx.value = fromStart ? 0 : idx.value
  open.value = true
  refreshTarget()
  if (autoplay.value) scheduleAuto()
}

/** 关闭：若从未完成过则标记完成（后续不再自动弹）；若本次已启用过自动播放则记住 atool。 */
function closeGuide({ completed = true, scheduleAutoAgain = false } = {}) {
  clearAuto()
  open.value = false
  if (completed) {
    try { localStorage.setItem(GUIDE_DONE_KEY, 'true') } catch (_) { /* 存储不可用则仅本次生效 */ }
  }
  emit('settled')
  if (scheduleAutoAgain && autoplay.value) {
    // 关闭后若还想自动播放，由调用方通过常驻按钮再次触发（避免后台循环）
  }
}

function prev() {
  if (idx.value <= 0) return
  clearAuto()
  idx.value--
  refreshTarget()
  if (autoplay.value) scheduleAuto()
}
function next() {
  clearAuto()
  if (idx.value >= steps.value.length - 1) { closeGuide({ completed: true }); return }
  idx.value++
  refreshTarget()
  scheduleAuto()
}
function skip() { closeGuide({ completed: true }) }

function toggleAutoplay() {
  autoplay.value = !autoplay.value
  if (autoplay.value && open.value) scheduleAuto()
  else clearAuto()
}

function scheduleAuto() {
  clearAuto()
  if (!autoplay.value || !open.value) return
  autoTimer = setTimeout(() => {
    if (idx.value >= steps.value.length - 1) closeGuide({ completed: true })
    else { idx.value++; refreshTarget(); scheduleAuto() }
  }, 4000)
}

// 定位会在布局变化（窗口缩放、前后面板显隐）时漂移：窗口 resize 时重定位。
function onResize() { if (open.value) refreshTarget() }

// 说明气泡位置：优先放在高亮框下方，空间不足则上方；水平居中贴近目标。
function popStyle() {
  const t = target.value
  if (!t) return {}
  const vb = { w: window.innerWidth, h: window.innerHeight }
  const below = t.top + t.height + 14
  const above = t.top - 150
  const placeBelow = below + 160 <= vb.h // 气泡约 160 高
  const top = placeBelow ? below : Math.max(8, above)
  const left = Math.min(Math.max(8, t.left), Math.max(8, vb.w - 340))
  return { top: top + 'px', left: left + 'px', width: '320px' }
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  // 供 HelpDoc「开始引导」入口唤起：外部触发后打开引导（re-open 而非首弹判定）
  onStartGuide = () => openGuide(true)
  window.addEventListener('bempdiff:start-guide', onStartGuide)
  // 首次访问：读完 done/seen 后决定是否自动弹出
  let done = false
  let seen = false
  try {
    done = isGuideDone(localStorage.getItem(GUIDE_DONE_KEY))
    seen = isFirstRun(localStorage.getItem(GUIDE_SEEN_KEY)) === false
  } catch (_) { /* 存储不可用视为 not seen */ }
  seenFirstRun = !done && !seen
  if (seenFirstRun) {
    // 记录已 seen，避免重复自动弹
    try { localStorage.setItem(GUIDE_SEEN_KEY, 'true') } catch (_) { /* ignore */ }
    openGuide(true)
  }
})

onBeforeUnmount(() => {
  clearAuto()
  window.removeEventListener('resize', onResize)
  if (onStartGuide) window.removeEventListener('bempdiff:start-guide', onStartGuide)
})
</script>

<template>
  <!-- 常驻「引导」按钮：左下角悬浮。默认隐藏，需在帮助文档「开始引导」开启后显示 -->
  <button v-if="renderTick" class="guide-fab btn btn-outline-secondary btn-sm" type="button"
          :title="t('guide.fabTitle')" @click="openGuide(true)">
    <i class="bi bi-life-preserver"></i>
  </button>

  <!-- 引导浮层 -->
  <div v-if="open" class="guide-layer" @keydown.esc="closeGuide({ completed: true })">
    <!-- 半透明遮罩（弱化背景，聚焦高亮区域） -->
    <div class="guide-mask"></div>
    <!-- 高亮目标描边 + 说明气泡 -->
    <template v-if="target">
      <div class="guide-spot" :style="{ top: target.top + 'px', left: target.left + 'px', width: target.width + 'px', height: target.height + 'px' }"></div>
      <div class="guide-pop" :style="popStyle()">
        <div class="guide-num">{{ progress }}</div>
        <h6 class="guide-title mb-1">{{ titleOf(step) }}</h6>
        <p class="guide-text mb-2" v-if="step.placeholder">{{ t('guide.placeholder') }}</p>
        <p class="guide-text mb-0">{{ textOf(step) }}</p>
        <div class="d-flex align-items-center gap-2 mt-2">
          <button class="btn btn-sm btn-outline-secondary py-0" type="button" :disabled="idx === 0" @click="prev" :title="t('guide.prev')">
            <i class="bi bi-chevron-left"></i>
          </button>
          <button class="btn btn-sm btn-primary py-0" type="button" @click="next"
                  :title="idx === steps.length - 1 ? t('guide.finishTitle') : t('guide.next')">
            {{ idx === steps.length - 1 ? t('guide.finish') : t('guide.next') }} <i class="bi bi-chevron-right"></i>
          </button>
          <button class="btn btn-sm btn-outline-secondary py-0" type="button"
                  :class="{ active: autoplay }" @click="toggleAutoplay" :title="t('guide.autoplay')">
            <i class="bi bi-play-circle"></i>
          </button>
          <button class="btn btn-sm btn-link py-0 ms-auto text-secondary" type="button" @click="skip" :title="t('guide.skipTitle')">{{ t('guide.skip') }}</button>
        </div>
      </div>
    </template>
    <!-- 无高亮目标（如欢迎页 / 目标未渲染）：居中演示卡片 -->
    <template v-else>
      <div class="guide-card card">
        <div class="card-body">
          <div class="guide-num">{{ progress }}</div>
          <h5 class="guide-title mb-2">{{ titleOf(step) }}</h5>
          <p class="guide-text mb-3">{{ textOf(step) }}</p>
          <div class="d-flex align-items-center gap-2">
            <button class="btn btn-sm btn-outline-secondary py-0" type="button" :disabled="idx === 0" @click="prev" :title="t('guide.prev')"><i class="bi bi-chevron-left"></i></button>
            <button class="btn btn-sm btn-primary py-0" type="button" @click="next" :title="idx === steps.length - 1 ? t('guide.finishTitle') : t('guide.next')">{{ idx === steps.length - 1 ? t('guide.finish') : t('guide.next') }} <i class="bi bi-chevron-right"></i></button>
            <button class="btn btn-sm btn-outline-secondary py-0" type="button" :class="{ active: autoplay }" @click="toggleAutoplay" :title="t('guide.autoplayShort')"><i class="bi bi-play-circle"></i></button>
            <button class="btn btn-sm btn-link py-0 ms-auto text-secondary" type="button" @click="skip" :title="t('guide.skipTitle')">{{ t('guide.skip') }}</button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.guide-fab {
  position: fixed;
  left: 12px;
  bottom: 12px;
  z-index: 2100;
  border-radius: 50%;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.guide-layer { position: fixed; inset: 0; z-index: 2000; }
.guide-mask { position: absolute; inset: 0; background: var(--bdf-overlay-soft); }
/* 高亮框与气泡用 position: fixed，坐标即 getBoundingClientRect() 返回的视口坐标，
   与 guide-layer 的定位基准一致；不用 absolute（相对父容器），否则目标元素处于
   #app 内部/可滚动容器/布局变化后，父基准与 rect 视口坐标偏差导致高亮框漂移。 */
.guide-spot {
  position: fixed;
  border: 2px solid var(--bs-primary);
  border-radius: 6px;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--bdf-accent) 20%, transparent);
  box-sizing: border-box;
  pointer-events: none;
  z-index: 2001;
}
.guide-pop {
  position: fixed;
  background: var(--bs-body-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: 10px;
  padding: 12px 14px;
  box-shadow: var(--bs-box-shadow-lg);
  z-index: 2002;
}
.guide-card {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  width: min(420px, calc(100vw - 2rem));
  z-index: 2002;
  box-shadow: var(--bs-box-shadow-lg);
}
.guide-num { font-size: .68rem; color: var(--bs-secondary-color); font-weight: 600; }
.guide-title { font-weight: 700; }
.guide-text { font-size: .82rem; line-height: 1.5; color: var(--bs-body-color); }
</style>