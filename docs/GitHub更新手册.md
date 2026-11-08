# BempDiff 更新功能 · GitHub 更新手册

> 合并自：《GitHub更新-人工操作手册.md》（面向人工的准备 / 配置 / 发布 / 排查操作）+ 《GitHub更新-技术说明.md》（组件、接口契约与认证方式）。

## 第一部分：人工操作手册
本手册面向需要在 GitHub 上人工准备、配置发布源并排查更新功能的人员。应用的「关于 → 检查更新」会自动连接 GitHub 读取最新 Release 并对比版本。

- 默认发布仓库：`wangnan05563/BempDiff`
- 覆盖仓库地址：环境变量 `BEMPDIFF_GITHUB_REPO`（格式 `owner/repo`）

---

#### 1. 一键验收对照

动过 GitHub 侧配置后，在应用内「配置中心 → 关于 → 检查更新」点一次，期望：

| 场景 | 期望提示 |
| --- | --- |
| 当前版本已是发布的最新版本 | `当前已是最新版本，无需更新` |
| 仓库有更新 | `发现新版本，建议立即更新` + 版本对比与下载链接 |
| 仓库从未发布 Release | `仓库还没有发布任何 Release` |
| 网络 / 限流 / 令牌问题 | 带有排查建议的错误提示（可重试） |

---

#### 2. 仓库权限配置步骤（Read/Write）

工具运行时**只需要读权限**，但发布 Release 需要写权限（通常由管理员操作）。把两类账号分开：

1. 发布管理（写权限，管理员用）：
   - 仓库页面 → `Settings → Collaborators and teams`，为发布人添加 `Maintain` 或 `Admin` 角色。创建/编辑 Release 需要至少 `Maintain`（Read + Write）权限。
2. 本工具运行时（只读，业务账号用）：
   - 本工具匿名访问已足够；若仓库为**私有**，或反复遇到「匿名 60 次/小时限流」，需给只读令牌。推荐直接在应用内配置：
     - 打开「配置中心 → 关于」→「GitHub 访问令牌」→ 填写令牌；如希望重启后仍保留，勾选「记住令牌」（否则仅本次运行有效）。
   - 也可用环境变量方式（备选）：在运行主机设置 `GITHUB_TOKEN` 后重启后端。
   - 令牌只应存在于后端进程内存（默认不落盘），**不要**写入日志。

> 最简私有化方案：把仓库设为公开（无业务敏感信息），则完全不需要 token。

---

#### 3. 发布新版本（releases）

在新代码打好安装包后，前往 `仓库 → Releases → Draft a new release`：

1. **Choose a tag**：建议使用语义化版本号，格式 `v1.2.3`（前导 `v` 可有可无，工具比较时会自动剥离 `v/`）。
2. **Release title / description**：填写更新说明（会在「关于」页的发布说明区展示）。
3. 上传对应平台的安装包（.exe 等）作为附件。
4. 点击 **Publish release**。

**版本比较口径**：工具对 tag 剥离前导 `v/` 后，按数字分段逐一比较（如 `1.10` > `1.9`）；仅当「当前版本 ≥ 最新 tag」时判定为已最新。请保证 tag 数字可自增，否则会出现“发布后仍提示无更新或乱序”。

---

#### 4. Webhook 触发条件设置（可选，用于“发布即通知”）

名次的更新检查是应用内**主动轮询**（打开「关于」或手动点按钮），不依赖 Webhook。如需“新版本发布时自动提醒/推送”，可按下面配置：

1. `仓库 → Settings → Webhooks → Add webhook`。
2. **Payload URL**：填入接收方地址（如内部机器人/群通知服务）。
3. **Content type**：`application/json`。
4. **Which events**：勾选 `Releases`（发布、预发布、删除均触发）。
5. **Just the release event**：勾选 `Send me everything` 或仅在 `Releases` 频道触发。

> Webhook 仅是“发布事件”触发器，用于通知；**不能替代**本工具读取最新版本的逻辑（那仍走 Releases API）。

---

#### 5. 版本信息获取方式说明

| 数据 | 获取方式 | 说明 |
| --- | --- | --- |
| 最新版本号 / 发布说明 | GitHub **Releases API** `GET https://api.github.com/repos/{owner}/{repo}/releases/latest` | 取最新一条正式 release（tag / name / html_url / published_at / body） |
| 当前版本号 | 安装包 `appVersion` | 桌面壳注入 `window.bempdiff.appVersion`；无则取前端内置 `APP_VERSION` |
| 仓库地址 | 默认 `wangnan05563/BempDiff`，可用环境变量 `BEMPDIFF_GITHUB_REPO` 覆盖 | 仓库地址不算凭证，允许内置默认并带覆盖 |
| 访问令牌 | 应用「关于」页「GitHub 访问令牌」（推荐）或环境变量 `GITHUB_TOKEN` | 仅私有仓库 / 规避匿名限流时提供；「关于」页勾选「记住令牌」才明文落盘 |

工具**不允许**通过特殊分支或本地文件判断版本，统一以 GitHub Releases 为准，避免分支与发布状态不一致。为避免频繁检查触发限流，后端对同一仓库的拉取结果有 5 分钟进程内缓存，命中时「关于」页会提示「本次为缓存结果」。

---

#### 6. 故障排查常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 提示 `HTTP 403 / 429` | GitHub 匿名限流（60 次/小时/IP） | 在「关于」页「GitHub 访问令牌」填写保存（或设 `GITHUB_TOKEN`）提升额度，稍后重试；重复触发的已内置 5 分钟缓存缓解 |
| 提示 `HTTP 401` | 令牌无效或权限不足 | 重新生成 token（私有仓库需 `repo` 权限），核对「关于」页或环境变量中的令牌是否正确 |
| 提示 `HTTP 404` | 仓库不存在 / 私有且未提供有效令牌 | 检查 `BEMPDIFF_GITHUB_REPO` 是否写错；私有仓库配 token |
| 网络报错（超时/DNS） | 本机到 `api.github.com` 不通 | 检查网络/代理是否可达；通报推送设定的超时后自动降级、不影响其他功能 |
| 发了新版本但仍提示“已最新” | tag 版本号没有大于当前版本 | 核对 tag 语义化递增；检查是否误用“草稿/预发布”的 tag |
| 版本显示为 `当前版本未知` | 壳层未注入 `appVersion` 且内置版本缺失 | 检查桌面壳 `preload` 是否暴露 `appVersion` |
| 点击后无反应（一直转圈） | 每 5s/8s 请求超时 | 网络慢会最终超时降级并给出提示，不要反复狂点 |

---

#### 7. 变更发布一份操作清单（快速回顾）

1. 打新安装包并上传到 Releases（tag 用递增的语义化版本）。
2. 需规避限流/私有仓库时：在「关于」页「GitHub 访问令牌」填写并保存（可选勾选「记住令牌」），或在主机设 `BEMPDIFF_GITHUB_REPO` / `GITHUB_TOKEN`，重启后端。
3. 应用内「关于 → 检查更新」确认显示新版本与正确差异。

## 第二部分：技术说明
本文档说明「关于 → 检查更新」所依赖的组件、接口契约与认证方式，供后续维护与排查。

---

#### 1. 依赖项

| 依赖 | 说明 |
| --- | --- |
| JDK 标准库（`java.net` / `java.nio`） | 后端 `UpdateCheckService.java`，零第三方依赖 |
| GitHub API（REST，HTTPS 强制） | `https://api.github.com/repos/{owner}/{repo}/releases/latest` |
| 前端通信 | Web UI 通过 `api.checkUpdate()` 调后端 `/api/update/check`（同源，无需额外依赖） |

> 数据链路全程 **HTTPS**：`api.github.com`、后端到前端均走同一域下的接口（无明文传输）。

---

#### 2. 认证方式

**强制约定：不硬编码任何凭证。令牌可从两个来源注入（优先级：显式配置 → 环境变量）。**

| 来源 | 说明 | 是否必填 |
| --- | --- | --- |
| 前端配置（推荐） | 应用「关于」页「GitHub 访问令牌」填写并保存到后端配置（`/api/config` 的 `githubToken`）；勾选「记住令牌」(`persistGithubToken=true`) 才明文落盘，默认不落盘仅存进程内存 | 否 |
| 环境变量 `GITHUB_TOKEN` | 显式令牌为空时的回落来源 | 否 |
| 环境变量 `BEMPDIFF_GITHUB_REPO` | 覆盖仓库地址，格式 `owner/repo`；非法格式则回落内置默认 | 否 |

- 令牌仅在 `UpdateCheckService` 请求头 `Authorization: Bearer <token>` 中使用，**不写日志、不在未开启「记住」时回显给前端**。
- 未设令牌走匿名访问：限 60 次/小时/IP。
- 请求携带 `User-Agent`、`Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2022-11-28`（GitHub 对缺失 User-Agent 返回 403）。

**请求超时**：连接 5s / 读取 8s。GitHub 不可达时优雅降级为 `ok=false + message`，不影响比对等其他功能。

**进程内短缓存（防限流）**：对同一 `repo + token` 的「latest release 拉取结果」缓存 5 分钟（键不同不命中；失败不缓存，可立即重试；`clearLatestCache()` 可强制清空）。开启缓存不被视为新请求，显著降低反复点检查/重开关于页触达限流的概率。缓存命中的响应 `cached=true`。

---

#### 3. 接口契约

##### `POST /api/update/check`

请求体（可选）：
```json
{ "current": "0.1.2026083102" }
```

响应体（HTTP 恒为 200，以 `ok` 字段区分成功/失败）：
```json
{
  "ok": true | false,
  "current": "当前版本号",
  "upToDate": true | false | null,
  "latest": { "tag": "v1.2.3", "name": "...", "url": "...", "publishedAt": "ISO8601", "body": "..." } | null,
  "repo": "owner/repo",
  "message": "用户可读提示",
  "cached": "是否命中进程内缓存",
  "lastError": "失败时的底层错误信息（仅失败时出现）"
}
```

字段语义：
- `upToDate = true`：当前 ≥ 最新；`false`：存在新版本；`null`：未比较（当前版本为空或仓库无 Release）。
- `latest = null` 且 `ok=true`：仓库尚无任何 Release。
- `cached = true`：本次结果来自 5 分钟进程内缓存（未发起新 API 请求）。
- `ok=false`：网络 / 限流 / 令牌 / 404 等问题，`message` 附带排查建议。

前端组件：`webui/src/components/About.vue`；接口封装：`webui/src/api/client.js` 的 `checkUpdate`；后端：`java_core/.../server/UpdateCheckService.java`、`BempServer.handleUpdateCheck`。

---

#### 4. 版本比较逻辑

- 版本号来自安装包 `appVersion`（桌面壳注入 `window.bempdiff.appVersion`），缺失时回落前端 `APP_VERSION`。
- 对 tag 剥离前导 `v/ /V` 后，复用后端 `PackageVersion.compare`（数字分段语义化比较：`1.10 > 1.9`）。
- 判定：`compare(stripV(current), stripV(tag)) >= 0` → 已最新；否则存在新版本。

---

#### 5. 前端行为

- 打开「关于」tab 自动检查一次（自动检测），并提供「检查更新」按钮手动重查。
- 状态展示：检查中（按钮转圈禁用，防重复请求）→ 成功/失败均以后端 `message` 为准，「有更新」时展示 当前 vs 最新 版本对比与下载链接及发布说明。
- 单测：`webui/src/__tests__/about.spec.js`（mock `api.checkUpdate`，覆盖自动/手动/有更新/失败降级/指引展开）。

---

#### 6. 维护提示

- 变更「检查更新」后端时，保持响应字段结构稳定（前端按 `ok/upToDate/latest/repo/message` 消费）。
- 若要支持超过 60 次/小时的匿名查询，务必配置 `GITHUB_TOKEN`。
- 契约有后端单测守护：`UpdateCheckServiceContractTest`（`build_and_test.ps1` 全量套件）。它通过反射把 `UpdateCheckService.API_BASE` 指向进程内假 GitHub 服务，覆盖有新版/已最新/无 Release/HTTP 500/限流 403 等场景，**不依赖外网**；因此 `API_BASE` 被设计为 `volatile` 可覆盖（生产恒为 `https://api.github.com/repos/`）。改动契约时需同步更新该测试。
