<script setup>
// 配置中心「关于」tab：展示产品版本 + 连接 GitHub 检查最新 Release 并提供更新指引。
//
// 数据流（后端 UpdateCheckService，零依赖仅 JDK 标准库）：
//   api.checkUpdate({ current }) → POST /api/update/check
//   → GET https://api.github.com/repos/{owner}/{repo}/releases/latest（HTTPS 强制）
//   → { ok, current, upToDate(Boolean|null), latest:{tag,name,url,publishedAt,body}|null,
//       repo, message, lastError }
//
// 兼容与安全约定：
//  - 仓库地址不视为凭证，沿用后端内置默认 repo，可用环境变量 BEMPDIFF_GITHUB_REPO 覆盖，
//    前端不提供「改仓库」入口，避免凭证/地址误写；最终生效仓库以后端返回的 resp.repo 为准。
//  - 访问令牌从环境变量 GITHUB_TOKEN 读取，不外泄到前端。
//  - 检查失败永远优雅降级：展示后端 message（含限流/令牌/网络排查提示），不影响其他功能。
import { ref, onMounted } from 'vue'
import { resolveAppVersion } from '../lib/helpContent'
import { api } from '../api/client'
import { state, toast } from '../store'
import { t } from '../lib/i18n'

const currentVersion = resolveAppVersion()
const checking = ref(false)      // 检查请求进行中（true 时按钮转圈并禁用，防重复请求）
const result = ref(null)         // 后端统一响应；null=未检查
const showGuide = ref(false)     // 「手动手工更新指引」折叠面板开关
const updating = ref(false)      // 一键下载更新进行中（true 时按钮转圈并禁用）
const updateMsg = ref('')        // 一键更新结果提示
const updateMsgOk = ref(null)    // 结果语义位（true=成功/false=失败），替代 startsWith 语言耦合判断
const showErrDetail = ref(false) // P2-7：更新检查失败时展开原始技术错误（含 HTTP 状态/GitHub message）

// P2-7：复制原始错误详情，便于用户粘贴到工单/群里排查（剪贴板不可用时提示手动选择）。
async function copyErrDetail() {
  const text = String((result.value && result.value.lastError) || '')
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    toast('success', t('ab.errCopied'))
  } catch (_) {
    toast('warning', t('ab.errCopyFail'))
  }
}

// 一键更新是否可用：桌面壳（Electron）具备 downloadInstall 能力时才显示/启用。
const canOneClick = () => !!(typeof window !== 'undefined' && window.bempdiff &&
  typeof window.bempdiff.downloadInstall === 'function')

// 一键下载更新包并触发安装（调 Electron 主进程）：成功/失败都以 updateMsg + toast 反馈。
async function oneClickUpdate() {
  const dl = result.value && result.value.latest && result.value.latest.downloadUrl
  if (!dl || updating.value) return
  updating.value = true
  updateMsg.value = ''
  try {
    const r = await window.bempdiff.downloadInstall(dl)
    if (r && r.ok) {
      updateMsg.value = t('ab.dlDone', { file: r.file })
      updateMsgOk.value = true
      toast('success', t('ab.dlDoneToast'))
    } else {
      updateMsg.value = t('ab.dlFail', { msg: (r && r.message) || t('ab.unknownErr') })
      updateMsgOk.value = false
      toast('danger', updateMsg.value)
    }
  } catch (e) {
    updateMsg.value = t('ab.dlFail', { msg: (e && e.message) || String(e) })
    updateMsgOk.value = false
    toast('danger', updateMsg.value)
  } finally {
    updating.value = false
  }
}

// ---- GitHub 访问令牌配置（可选）----
// 落库 /api/config 的 githubToken 与 persistGithubToken。安全口径对齐 API Key：
// 仅在勾选「记住令牌」时明文落盘；默认不落盘，仅存活于后端进程内存（重启后需重填）。
const tokenInput = ref('')       // 令牌输入框（未持久化时留空，内存态密钥不回显）
const tokenVisible = ref(false)  // 明文/掩码切换
const persistToken = ref(false)  // 是否明文落盘
const savingToken = ref(false)   // 保存中（禁用按钮防重复提交）
const tokenMsg = ref('')         // 保存结果提示
const tokenMsgOk = ref(null)     // 结果语义位（true=成功/false=失败）
// 会话内是否已有令牌（后端 GET 返回 hasGithubToken）
const hasToken = () => !!(state.config && (state.config.hasGithubToken || state.config.githubToken))

// 打开时预填：仅当已「记住」时后端才回显明文令牌，可预填进输入框；未记住则留空（不泄露内存态密钥）。
function seedToken() {
  const cfg = state.config || {}
  persistToken.value = !!cfg.persistGithubToken
  tokenInput.value = cfg.persistGithubToken && cfg.githubToken ? String(cfg.githubToken) : ''
}

// 保存令牌：仅当用户输入了非空新值才提交 githubToken（空值视为不修改，避免把会话内内存令牌误清空）；
// persistGithubToken 勾选状态始终提交。保存成功给后端已持有令牌，旧缓存按键差异自动失效。
async function onSaveToken() {
  if (savingToken.value) return
  savingToken.value = true
  tokenMsg.value = ''
  const tok = tokenInput.value.trim()
  const patch = { ...(state.config || {}), persistGithubToken: persistToken.value }
  if (tok) patch.githubToken = tok
  try {
    const resp = await api.putConfig(patch)
    state.config = resp
    tokenMsg.value = t('ab.tokenSaved')
    tokenMsgOk.value = true
    toast('success', t('ab.tokenSavedToast'))
  } catch (e) {
    tokenMsg.value = t('ab.saveFail', { msg: (e && e.message) || String(e) })
    tokenMsgOk.value = false
    toast('danger', tokenMsg.value)
  } finally {
    savingToken.value = false
  }
}

// GitHub 仓库 URL（后端返回的 owner/repo）。后端不可达时给一个可读的占位，避免空串。
const repoUrl = () => {
  const r = result.value && result.value.repo
  return r ? `https://github.com/${r}/releases` : 'https://github.com'
}

// GMT/UTC 时间串 → 本地可读日期（ISO 8601；解析失败原样返回，避免界面报错）
function fmtDate(s) {
  if (!s) return '—'
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? s : d.toLocaleString()
}

// 执行检查：失败、成功都用后端 message 展示状态；因后端 message 已含排查提示，这里只轻提示一次 toast。
async function doCheck() {
  if (checking.value) return
  checking.value = true
  try {
    const r = await api.checkUpdate({ current: currentVersion })
    result.value = r
    // P2-7：失败详情已在页面内可展开/复制，toast 只做轻提示，避免长异常文本被 toast 裁剪。
    if (r && r.ok === false) toast('warning', t('ab.checkFailToast'))
    else toast('info', (r && r.message) || t('ab.checkDone'))
  } catch (e) {
    // 网络层/后端 500 兜底：展示通用失败，不抛出打断交互
    result.value = { ok: false, message: t('ab.checkFail', { msg: (e && e.message) || String(e) }) }
    toast('danger', result.value.message)
  } finally {
    checking.value = false
  }
}

// 打开「关于」tab：预填令牌配置，然后自动检测一次（覆盖「自动检测」机制），随后可点按钮手动再查。
onMounted(() => { seedToken(); doCheck() })
</script>

<template>
  <div class="about">
    <!-- 顶部：产品名 + 当前版本号显示区域（与 GitHub 比较的基准） -->
    <div class="d-flex align-items-center gap-2 flex-wrap mb-2">
      <i class="bi bi-info-circle-fill text-primary"></i>
      <span class="fw-semibold">{{ t('ab.title') }}</span>
      <span class="badge text-bg-light border" :title="t('ab.currentVer')">{{ t('ab.currentVer') }} v{{ currentVersion }}</span>
      <span class="text-secondary" style="font-size:.75rem">{{ t('ab.tagline') }}</span>
    </div>

    <!-- 检查更新：自动检测 + 手动触发按钮 -->
    <div class="card card-body py-2 mb-2" style="font-size:.78rem">
      <div class="d-flex align-items-center gap-2 flex-wrap">
        <span class="fw-semibold"><i class="bi bi-arrow-repeat"></i> {{ t('ab.checkSection') }}</span>
        <span class="text-secondary">{{ t('ab.checkDesc') }}</span>
        <button class="btn btn-primary btn-sm ms-auto" type="button" :disabled="checking" @click="doCheck"
                :title="t('ab.checkBtnTip')">
          <i class="bi" :class="checking ? 'bi-arrow-repeat spinning' : 'bi-search'"></i>
          {{ checking ? t('ab.checking') : t('ab.checkBtn') }}
        </button>
      </div>

      <!-- 检查结果区：错误 / 无 Release / 已最新 / 有新版 -->
      <template v-if="result">
        <!-- 失败 -->
        <div v-if="result.ok === false" class="alert alert-danger py-2 mt-2 mb-0 about-result" role="alert">
          <div><i class="bi bi-exclamation-triangle-fill me-1"></i>{{ result.message }}</div>
          <!-- P2-7：面向用户的 message 与原始异常分开；原始详情默认折叠，可展开/复制。 -->
          <div v-if="result.lastError" class="mt-1">
            <button type="button" class="btn btn-link btn-sm p-0 about-err-toggle"
                    @click="showErrDetail = !showErrDetail">
              <i class="bi" :class="showErrDetail ? 'bi-chevron-up' : 'bi-chevron-down'"></i>
              {{ showErrDetail ? t('ab.errHide') : t('ab.errShow') }}
            </button>
            <button type="button" class="btn btn-link btn-sm p-0 ms-2 about-err-toggle" @click="copyErrDetail">
              <i class="bi bi-clipboard"></i> {{ t('ab.errCopy') }}
            </button>
            <pre v-if="showErrDetail" class="about-err-pre mt-1 mb-0">{{ result.lastError }}</pre>
          </div>
        </div>
        <!-- 成功但仓库无 Release -->
        <div v-else-if="!result.latest" class="alert alert-secondary py-2 mt-2 mb-0 about-result" role="alert">
          <i class="bi bi-dash-circle me-1"></i>{{ result.message }}
        </div>
        <!-- 有最新版本时：版本对比区 + 下载/说明 -->
        <template v-else>
          <!-- 既有新版（需更新） -->
          <div v-if="result.upToDate === false"
               class="alert alert-warning py-2 mt-2 mb-2 about-result" role="alert">
            <i class="bi bi-exclamation-triangle-fill me-1"></i>{{ t('ab.newFound') }}
          </div>
          <!-- 已最新 / 当前未知 -->
          <div v-else class="alert alert-success py-2 mt-2 mb-2 about-result" role="alert">
            <i class="bi bi-check-circle-fill me-1"></i>{{ t('ab.upToDate') }}
          </div>

          <!-- 版本对比：当前 vs 最新 -->
          <div class="d-flex align-items-center gap-2 flex-wrap mb-2 about-compare" :aria-label="t('ab.verCompare')">
            <span class="badge text-bg-light border about-ver">{{ t('ab.currentShort') }} v{{ currentVersion }}</span>
            <i class="bi bi-arrow-right text-secondary"></i>
            <span class="badge text-bg-primary border about-ver">{{ result.latest.tag || t('ab.latest') }}</span>
            <!-- T01726：仅在确有新版（upToDate===false）时显示一键更新，防止已最新用户误降级安装旧版 -->
            <button v-if="result.upToDate === false && result.latest.downloadUrl && canOneClick()" class="btn btn-primary btn-sm"
                    type="button" :disabled="updating" @click="oneClickUpdate"
                    :title="t('ab.oneClickTip')">
              <i class="bi" :class="updating ? 'bi-arrow-repeat spinning' : 'bi-cloud-arrow-down'"></i>
              {{ updating ? t('ab.downloading') : t('ab.oneClick') }}
            </button>
            <a v-if="result.latest.url" class="btn btn-outline-primary btn-sm" :href="result.latest.url"
               target="_blank" rel="noopener"
               :title="t('ab.goReleaseTip')">
              <i class="bi bi-box-arrow-up-right"></i> {{ t('app.updateGo') }}
            </a>
          </div>
          <div v-if="result.latest.name || result.latest.publishedAt" class="text-secondary mb-1"
               style="font-size:.74rem">
            {{ result.latest.name }} · {{ t('ab.published', { time: fmtDate(result.latest.publishedAt) }) }}
          </div>
          <div v-if="updateMsg" class="mb-1 p-2 rounded"
               :class="updateMsgOk === false ? 'text-danger border border-danger-subtle' : 'text-success border border-success-subtle'"
               style="font-size:.74rem">
            <i class="bi" :class="updateMsgOk === false ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'"></i>
            {{ updateMsg }}
          </div>
          <!-- Release 说明（更新说明）预览 -->
          <div v-if="result.latest.body" class="border rounded p-2 mb-0 about-body" style="max-height:9rem;overflow:auto">
            <div class="text-secondary mb-1" style="font-size:.72rem">{{ t('ab.releaseNotes') }}</div>
            <pre class="mb-0" style="font-size:.74rem;white-space:pre-wrap;word-break:break-word">{{ result.latest.body }}</pre>
          </div>
        </template>
      </template>
      <!-- 进程内短缓存命中提示：避免反复检查触达 GitHub 匿名限流 -->
      <div v-if="result && result.cached" class="mt-2 text-secondary" style="font-size:.72rem">
        <i class="bi bi-database me-1"></i>{{ t('ab.cached') }}
      </div>
    </div>

    <!-- GitHub 访问令牌（配置中心可填）：规避匿名限流 / 支持私有仓库 -->
    <div class="card card-body py-2 mb-2" style="font-size:.78rem">
      <div class="d-flex align-items-center gap-2 flex-wrap">
        <span class="fw-semibold"><i class="bi bi-key"></i> {{ t('ab.tokenSection') }}</span>
        <span class="text-secondary">
          <template v-if="hasToken()">{{ t('ab.tokenSet') }}</template>
          <template v-else>{{ t('ab.tokenUnset') }}</template>
        </span>
        <button class="btn btn-outline-secondary btn-sm ms-auto" type="button"
                :disabled="savingToken" @click="onSaveToken"
                :title="t('ab.tokenSaveTip')">
          <i class="bi" :class="savingToken ? 'bi-arrow-repeat spinning' : 'bi-check2'"></i>
          {{ savingToken ? t('ab.saving') : t('ab.save') }}
        </button>
      </div>
      <div class="input-group input-group-sm mt-2">
        <input class="form-control" :type="tokenVisible ? 'text' : 'password'" v-model="tokenInput"
               :placeholder="t('ab.tokenPlaceholder')" :title="t('ab.tokenInputTitle')">
        <button class="btn btn-outline-secondary" type="button" @click="tokenVisible = !tokenVisible"
                :title="t('ab.toggleToken')">
          <i class="bi" :class="tokenVisible ? 'bi-eye-slash' : 'bi-eye'"></i>
        </button>
      </div>
      <div class="form-check form-check-inline mt-2 mb-0">
        <input class="form-check-input" type="checkbox" id="aboutPersistToken" v-model="persistToken"
               :title="t('ab.persistTip')">
        <label class="form-check-label" for="aboutPersistToken">{{ t('ab.persist') }}</label>
      </div>
      <div v-if="tokenMsg" class="mt-2 mb-0" style="font-size:.72rem"
           :class="tokenMsgOk === false ? 'text-danger' : 'text-success'">
        <i class="bi" :class="tokenMsgOk === false ? 'bi-exclamation-triangle' : 'bi-check-circle'"></i> {{ tokenMsg }}
      </div>
    </div>

    <!-- 手动更新提示区域：GitHub 仓库地址 + 人工设置操作指引 -->
    <div class="card card-body py-2" style="font-size:.78rem">
      <div class="d-flex align-items-center gap-2 flex-wrap">
        <span class="fw-semibold"><i class="bi bi-github"></i> {{ t('ab.manual') }}</span>
        <span class="text-secondary ms-auto">GitHub 仓库：<a :href="repoUrl()" target="_blank" rel="noopener">{{ result && result.repo ? result.repo : t('ab.repoPending') }}</a></span>
        <button class="btn btn-outline-secondary btn-sm" type="button" @click="showGuide = !showGuide"
                :title="showGuide ? t('ab.guideHideTip') : t('ab.guideShowTip')">
          <i class="bi" :class="showGuide ? 'bi-chevron-up' : 'bi-chevron-down'"></i>
          {{ showGuide ? t('ab.guideHide') : t('ab.guideShow') }}
        </button>
      </div>

      <template v-if="showGuide">
        <hr class="my-2">
        <ol class="mb-2 about-guide">
          <li v-html="t('ab.guide1')"></li>
          <li v-html="t('ab.guide2')"></li>
          <!-- 令牌配置：可在本页「GitHub 访问令牌」内填写保存（建议勾选「记住令牌」以便重启后保留），
               也可用环境变量 GITHUB_TOKEN；私有仓库必须配置只读令牌，令牌仅后端内存使用、默认不落盘。 -->
          <li v-html="t('ab.guide3')"></li>
          <li v-html="t('ab.guide4')"></li>
          <li v-html="t('ab.guide5')"></li>
        </ol>
        <div class="alert alert-info py-2 mb-0"><i class="bi bi-info-circle-fill me-1"></i>{{ t('ab.guideNote') }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.about { display: flex; flex-direction: column; gap: .5rem; min-height: 420px; }
/* 转圈（检查中） */
.spinning { animation: about-rotate 1s linear infinite; display: inline-block; }
@keyframes about-rotate { to { transform: rotate(360deg); } }
/* 版本徽标统一宽度，让对比行对齐 */
.about-ver { white-space: nowrap; }
.about-result { font-size: .78rem; }
.about-err-toggle { font-size: .74rem; vertical-align: baseline; }
.about-err-pre { font-size: .72rem; white-space: pre-wrap; word-break: break-all; max-height: 9rem; overflow: auto;
  background: var(--bs-tertiary-bg); border-radius: 4px; padding: .4rem .5rem; }
.about-compare { gap: .5rem; }
.about-body pre { font-family: inherit; }
.about-guide { padding-left: 1.1rem; }
</style>