# BempDiff Web UI 迁移方案（详细改造 + 工作量估算）

> 目标：解决当前 JavaFX GUI 的两大痛点——① UI 美观度天花板低（BootstrapFX 非真 Bootstrap，无 Flexbox/Grid）；② 打包脆弱（jpackage + jlink 模块裁剪踩坑，已修 `jdk.crypto.ec` 但本质是把"反编译引擎的桌面壳"做得过重）。
> 核心结论：**保留 `java_core` 纯 Java 后端不动，仅替换 UI 层**。后端新增一个内嵌 HTTP 服务（`server` 子命令），前端用 Web 技术（Vue 3 + Bootstrap 5.3.3，复用现有 `ui_prototype.html` 设计基线）实现三栏布局。打包采用 **Tauri 2.x 桌面壳**（Rust + WebView2，把 JRE+jar 作为 sidecar 打包，原生对话框免上传）；核心服务仍为内嵌 HTTP + 本地 SPA（与"浏览器启动器"路径共用同一套前后端，仅外壳不同）。

> **现状修正（2026-11-02，T04092）**：本文是 2026-08 的迁移方案记录。其中选定为最终方案的「路径 B：Tauri 2.x 桌面壳」曾以 in-tree 备选形态保留，但发布链路已全面切换 Electron + electron-builder；Tauri 侧实现（`bempdiff/src-tauri/`、`webui/.tauri-placeholder/`、`webui/src/lib/tauri.js` 的 Tauri 分支、打包编排脚本的 `cargo tauri icon/build` 步骤）已于 T04092 剔除，编排脚本更名为 `bempdiff/scripts/build_dist_input.ps1`（只装配 dist_input，不再出安装包）。本文其余结论（保留 `java_core` 后端、内嵌 HTTP + Web SPA、jlink 瘦 JRE）仍然成立；下文提及的 Tauri 文件与命令均为历史记录。

---

## 0. 现状与约束（已核实）

| 项 | 现状 | 结论 |
|---|---|---|
| 后端耦合 | `java_core` 全部源码 **零 `javafx` 导入**，纯 Java CLI（`Main` 9 个子命令）+ `UiConfig` | 后端可无缝复用，无需改算法 |
| 外部依赖 | 仅 CFR 反编译 jar（classpath 普通 jar，非模块） | jlink 只需极少模块 |
| JRE 模块 | 现需 `javafx.*` + `bootstrapfx` + `ikonli` 等 ~6 个；TLS 已修 `jdk.crypto.ec` | 迁移后改为 `jdk.httpserver` + `jdk.crypto.ec`，JRE 体积显著下降 |
| Web 设计基线 | `bempdiff/ui_prototype.html` + `bempdiff/assets/`（Bootstrap 5.3.3 + BI 1.11.3 本地化） | 前端直接复用，UI 风格已定 |
| 当前 UI 功能面 | 8 个 JavaFX 文件：App / ConfigDialog / DiffTree / DiffView / DiffRowsBuilder / DiffRow / ReportViewer / UiConfig | 功能需 1:1 映射到 Web 组件 |

**关键约束**：CFR 是 Java 库 → 后端必须保留 Java。架构升级只动 UI 层，这是本方案成立的前提。

---

## 1. 总体架构（推荐）

```
┌──────────────────────────────────────────────────────────┐
│  前端 SPA（Vue 3 + Bootstrap 5.3.3，本地静态资源）          │
│  三栏：左差异树 │ 中 DiffView(Tab) │ 右信息Tab(汇总/单文件/  │
│        破坏性/审计)  + 顶部工具栏 + 设置弹窗 + 比对遮罩      │
└───────────────┬──────────────────────────────────────────┘
                │  HTTP / JSON  (localhost 127.0.0.1 仅本机)
┌───────────────▼──────────────────────────────────────────┐
│  java_core 新增 `server` 子命令（com.sun.net.httpserver）   │
│  - 复用现有：PackageParser / DiffEngine / Decompiler /      │
│    MarkdownReport / AssetExporter / HttpAiAnalyzer /       │
│    UiConfig（改造成内存态 + REST CRUD）                     │
│  - 静态资源从 jar 内 ClassLoader 提供                        │
└───────────────┬──────────────────────────────────────────┘
                │  sidecar 子进程（Tauri 拉起）
┌───────────────▼──────────────────────────────────────────┐
│  jlinked 瘦身 JRE（base + java.logging + jdk.httpserver    │
│   + jdk.crypto.ec）+ bempdiff.jar（作为 Tauri sidecar）     │
│  Tauri 桌面壳：原生窗口 + WebView2 渲染 SPA + 原生对话框    │
│  + 系统托盘；双击 exe → 起 sidecar server → 窗口内渲染      │
└──────────────────────────────────────────────────────────┘
```

**为什么核心架构是"内嵌 HTTP + Web SPA"（与外壳无关）**：
- 彻底绕开 jpackage/JavaFX/jlink 模块地狱（本次 TLS 事故的根因），无论外壳用浏览器还是 Tauri，后端形态不变。
- WebView2 / 浏览器渲染 Bootstrap 5.3.3 = 真 Flexbox/Grid，UI 美化天花板远高于 BootstrapFX。
- 调试极简：直接 `java -jar bempdiff.jar --server` 即可在浏览器全功能验证，无需打包即可跑通前后端。
- 后端零新增依赖（JDK 自带 `com.sun.net.httpserver`，无需 Spring）；外壳（Tauri）与 Java 服务解耦，sidecar 进程互不影响。

---

## 2. 两条打包路径（需你拍板）

### 路径 A — 浏览器 localhost 启动器（**备选**）
- 一个 **极薄启动器 exe**（Go / Rust / 或 .NET 均可，~几 MB）：双击 → 后台拉起 `jre/bin/java -jar bempdiff.jar --server --port <随机>` → 自动 `ShellExecute` 打开默认浏览器到 `http://127.0.0.1:<port>`。
- 文件选择走浏览器原生 `<input type=file webkitdirectory>`（文件夹）/ `<input type=file multiple>`（包文件）→ **multipart 上传**到 Java server（落临时目录后处理）。WAR 通常 <50MB，上传可接受；大文件做分片 + 进度条。
- **优点**：无原生 UI 代码、无 WebView2 依赖、跨平台一致、调试简单、打包最稳。
- **缺点**：依赖用户浏览器；"应用感"弱一点；无法做系统托盘（可后续加）。

### 路径 B — Tauri 桌面壳（**✅ 已选定**）
- Rust + Tauri 2.x，把 `jre/` + `bempdiff.jar` 作为 **sidecar** 打包；Tauri 提供原生窗口 + **原生文件/文件夹对话框**（直接拿到绝对路径，无需上传）+ 托盘 + 自动更新。
- WebView2（Windows 基本自带）渲染同一套 SPA。
- **优点**：原生桌面体验、原生对话框避免上传、可托盘常驻、单窗体专业感。
- **缺点**：引入 Rust 工具链 + Tauri sidecar 打包复杂度 + WebView2 依赖；工时显著增加；与本次"打包要稳"的诉求短期相悖。

> **决策（2026-08-13 更新）**：选定 **路径 B（Tauri 桌面壳）** 作为最终打包方案。两条路径**前端与后端完全共用**，差异仅在 P4 打包环节——B 引入 Rust 工具链 + Tauri 2.x + WebView2，以 sidecar 方式打包 JRE+jar，并用**原生文件/文件夹对话框**直接拿绝对路径（免 multipart 上传）。总工期由 ~23 人日调整为 **~28 人日**（+5 天用于 Tauri sidecar 打包与签名）。开发与联调阶段仍可用 `java -jar ... server` 在浏览器直跑，无需提前装 Rust。

---

## 3. REST API 设计（java_core 新增 `server` 子命令）

绑定 `127.0.0.1`，端口随机或 `--port` 指定；同源（server 同时托管 SPA），无需 CORS。统一错误包 `{error, message, code}`。

| 方法 | 路径 | 说明 | 复用现有 |
|---|---|---|---|
| POST | `/api/session/compare` | body: `{leftType:'package'\|'folder', leftPath?/leftFiles, rightPath?/rightFiles, options}` → 返回 `{jobId, tree, stats}` | PackageParser+DiffEngine（包）/FolderDiff（文件夹） |
| GET  | `/api/job/:id/status` | 轮询进度（比对/反编译/AI） | — |
| GET  | `/api/entry/:key/decompile` | 返回 `{oldSrc,newSrc,diffText,engine,ok}`（懒加载，点开才反编译） | Decompiler + FrontendTextDiff |
| POST | `/api/job/:id/report` | `{ai:bool}` → 返回 Markdown 文本（或写文件返路径） | MarkdownReport（含 StageA/B 写回） |
| POST | `/api/job/:id/export` | 返回 zip 下载（差异 class/jar + 反编译源码） | AssetExporter |
| POST | `/api/ai/test` | `{provider,baseUrl,apiKey,model,proxies}` → 连接测试结果（3 策略阶梯） | HttpAiAnalyzer.testConnection |
| POST | `/api/ai/analyze` | `{jobId}` → 两阶段分析 `{summary, fileAnalysis[]}` | AiAnalyzer.stageA/stageB |
| GET/PUT | `/api/config` | UiConfig JSON（GET 不回显 apiKey 除非 persist ApiKey） | UiConfig |
| POST | `/upload` | multipart 接收包/文件夹（路径 A 用） | — |

**进度处理**：长任务（AI 分析、大包反编译）放后台线程，前端轮询 `/status` 或 SSE；避免 HTTP 超时。`Decompiler` 调外部 `java -jar cfr.jar` 子进程，需在 server 进程内复用（已有 `findJava()` 逻辑）。

**安全**（沿用现有合规基线）：
- 仅绑 `127.0.0.1`；SSRF 守卫 `blockPrivateEndpoints` 保留。
- `apiKey` 内存态为主，GET `/config` 默认不回显；不写日志。
- 上传落临时目录，处理完即删；限制单文件/总大小。

---

## 4. 功能映射表（JavaFX → Web）

| JavaFX 组件 | 文件 | Web 等价实现 |
|---|---|---|
| `start`/三栏 `SplitPane` | App.java | Bootstrap 栅格 + CSS Grid 三栏布局 |
| 顶部工具栏（浏览包/目录、比对、AI、导出报告/资产、预览、打开、设置） | App.java | Bootstrap Navbar + 按钮组 |
| 左侧差异树（展开/折叠层级、状态图例、搜索/过滤） | DiffTree.java | 虚拟列表树 + 过滤输入框 + 状态色块 |
| 中部 DiffView（多 Tab、双栏源码 diff、语法高亮） | DiffView.java / DiffRowsBuilder.java / DiffRow.java | 双栏编辑器（CodeMirror 6 / Monaco 或轻量 jsdiff+highlight.js）按 Tab 切换 |
| 右侧信息 Tab（全局汇总 / 单文件分析 / 破坏性 / 审计） | App.java + infoTabPane | Bootstrap Tab 四页 |
| 设置弹窗（21 字段 + 悬停提示→底部状态栏） | ConfigDialog.java / UiConfig.java | Bootstrap Modal + 底部固定状态条 |
| 比对遮罩 / 进度条 | App.java `buildBusyOverlay` | Bootstrap 全屏 overlay + progress |
| 报告预览 / 打开报告 | ReportViewer.java | marked.js 渲染 Markdown 面板 |
| 文件/文件夹选择 | App.java | **Tauri 原生对话框**（`dialog.open()` 直接拿绝对路径，免上传） |

**不丢功能清单**（须全量覆盖）：L0/L1/L2 分层差异、前端 JS/HTML/CSS 美化 diff（FR4.4）、差异 lib jar 内部源码对比（Req6）、两阶段 AI 分析 + 成本闸门、连接测试、项目级上下文增强、差异树过滤偏好持久化、密钥不落盘默认、文件夹对比（FR11）。

---

## 5. 分阶段实施计划 + 工作量估算（单人、专注）

| 阶段 | 内容 | 工期 | 产出 |
|---|---|---|---|
| **P0** | API + 数据模型 + 错误包设计文档；确认打包路径 A/B | 1 d | 接口契约 |
| **P1** | java_core 新增 `server` 子命令：`com.sun.net.httpserver` 路由、compare/report/export/decompile/ai/test/config 端点、上传接收、后台进度、安全绑定 | 4 d | 可 `java -jar ... server` 起服务 |
| **P2** | 前端脚手架：Vite + Vue3 + Bootstrap5.3.3（复用 assets）、三栏布局、API client、深浅色主题、路由 | 3 d | 空三栏可跑 |
| **P3** | 核心功能：差异树+过滤、DiffView 双栏高亮、信息四 Tab、设置弹窗(21字段+状态条)、报告预览、导出、AI 流程+连接测试、比对遮罩、文件选择(上传/dialog) | 8 d | 全功能 Web UI |
| **P4** | 打包（**路径 B：Tauri 2.x sidecar** 打包瘦 JRE+jar、原生对话框、托盘、签名/冒烟）；瘦 JRE 仍用 jlink | 8 d（+5 vs A） | 双击即用 exe（Tauri 窗口） |
| **P5** | 端到端验证（Win 实测）、安全复核、删 `javafx_ui`、回归核心 CLI、文档 | 4 d | 稳定版本 + 文档 |

- **路径 B 总计 ≈ 28 工作日（约 5.5 周）**（含 Tauri sidecar 打包 +5 天）；若回退路径 A 则为 ~23 人日。
- 后端 P1 与前端 P2/P3 可并行（API 契约先行）。

---

## 6. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| jlink 模块不全导致 server 起不来 | 中 | 新模块集显式列 `jdk.httpserver,jdk.crypto.ec`；用既有 `JimageModuleLister` 手法验证 runtime 含这些模块 |
| 大 WAR 经浏览器上传内存/磁盘压力（路径 A 才有） | 低 | 选定 B 后走原生对话框直接传绝对路径，无上传环节；若日后回退 A 再启用流式落盘+大小上限 |
| 误删/漏迁 JavaFX 引用 | 低 | P1 前 grep 确认 `javafx_ui` 无被 core 引用；P5 才删 `javafx_ui` |
| 长任务 HTTP 超时（AI/大反编译） | 中 | 后台线程 + 轮询/SSE 进度，接口快速返回 jobId |
| 静态资源从 jar 提供 | 低 | `ClassLoader.getResource` + `com.sun.net.httpserver` 静态处理器 |
| 密钥经 localhost 传输 | 低 | 仅 127.0.0.1；GET/config 不回显；不落盘默认 |
| CLI 能力回退 | 低 | 保留 `compare/report/export/ai/folderdiff` 等子命令，server 为增量 |

---

## 7. 迁移策略（增量、零功能丢失）

1. **P1 先加 `server` 子命令**，旧 JavaFX GUI 与 CLI 全部保留，可并行验证。
2. **P2/P3 做 Web 前端**，直接连 `java -jar ... server` 在浏览器开发验证（无需打包）。
3. **P4 换打包**：启动器 + 瘦 JRE 替换 jpackage/JavaFX 构建；`javafx_ui` 模块退役。
4. **P5 删除 `javafx_ui`**，保留 CLI 作 headless/CI 用途；`report --ai` 等原能力不丢。

---

## 8. 已确认决策（2026-08-13）

1. **打包路径：B（Tauri 桌面壳）** —— 原生体验 + 原生对话框免上传 + 托盘 + 单窗体专业感；代价 +5 人日（Rust/Tauri/WebView2/sidecar 打包与签名）。
2. **前端框架：Vue 3** —— 与既有 `ui_prototype.html` 设计基线、个人项目栈一致，与 Bootstrap 5.3.3 集成成熟。

P0/P1 已完成（API 契约 + `server` 子命令端到端验证通过）。下一步按 **P2（前端脚手架）→ P3（核心功能）→ P4(B)（Tauri 打包）→ P5（验证收尾）** 推进。

---

## 9. P4(B) Tauri 桌面壳落地（2026-08-14，已随 T04092 退役）

> P3 已交付全功能 Web UI（三栏 + 报告预览 + 原生对话框 stub + 后端 `server` 子命令端到端验证通过）。P4 落地 Tauri 2.x 桌面壳，把「内嵌 JRE + bempdiff.jar + 前端 dist」打包为双击即用的 Windows 安装包。

### 9.1 架构要点（与方案的差异微调）
- **进程模型**：Java 后端仍以 `server` 子命令运行（`com.sun.net.httpserver`，绑定 127.0.0.1），由 Rust 作为**本地子进程**拉起（不走 Tauri sidecar 命名约定，直接 `std::process::Command` 调 `resources/jre/bin/java.exe`）。前端 SPA 由该 Java 服务**同源托管**（`--webroot resources/webui`，`base:'./'`），WebView2 窗口直接指向 `http://127.0.0.1:<随机端口>/`，因此 `/api` 天然同域免 CORS —— **前端 `client.js` 的 `BASE=''` 无需修改**。
- **窗口策略**：不在 `tauri.conf.json` 预定义窗口；release 由 Rust 在后端就绪后动态建 `WebviewWindow` 指向 Java 服务；`beforeBuildCommand` 设为空操作（真正的前端构建与镜像由 `build_tauri_app.ps1` 完成，避免重复构建）。`debug`（`tauri dev`）分支**不**拉起内置 JRE，沿用浏览器开发态（Tauri 开 vite@5173，后端手动 `java ... server --port 18765`，vite 已代理 `/api`）。
- **原生对话框**：`tauri.conf.json` 开 `withGlobalTauri:true` + `tauri-plugin-dialog`；前端 `src/lib/tauri.js` 用 `window.__TAURI__.dialog.open({directory})` 直接拿绝对路径，**无需引入 `@tauri-apps/plugin-dialog` npm 包**（规避离线/缓存受限环境无法装包的问题），浏览器态自动退化为手动输入。
- **退出清理**：`RunEvent::ExitRequested` 时 `child.kill()` 回收 Java 进程，避免孤儿。

### 9.2 jlink 最小 JRE 模块集（jdeps 实测）
- 静态最小：`java.base,java.logging,jdk.httpserver`。
- 显式追加：`jdk.crypto.ec,jdk.crypto.mscapi`（HTTPS 调 AI 的 TLS 提供方，原 jpackage 事故根因）、`jdk.jdeps`（javap 降级保底）。
- 结论：瘦 JRE 体积远小于原 JavaFX 全量 runtime。

### 9.3 交付文件
| 文件 | 作用 |
|---|---|
| `src-tauri/Cargo.toml` / `build.rs` | Rust 工程（Tauri 2 + dialog 插件 + url） |
| `src-tauri/tauri.conf.json` | 产品名/标识符/window(空)/`withGlobalTauri`/bundle.resources（app/jre/webui）+ NSIS |
| `src-tauri/capabilities/default.json` | 核心 + `dialog:allow-open/save` 权限 |
| `src-tauri/src/main.rs` / `src/lib.rs` | 拉起 Java sidecar、等端口就绪、建窗口、退出杀进程 |
| `webui/src/lib/tauri.js` | Tauri 环境探测 + 原生对话框封装 |
| `webui/src/components/ToolBar.vue` | `browse` 改走 `pickPath`（原生对话框） |
| `webui/.tauri-placeholder/index.html` | 占位 frontendDist（运行时被 Java 服务覆盖，避免重复打包前端） |
| `package.json`（prototype 根） | `dev/build/tauri` 脚本 + `@tauri-apps/cli` |
| `scripts/build_tauri_app.ps1` | 一键编排：编译 java_core→jar、复制 cfr.jar、jlink JRE、构建+镜像前端、生成图标、`cargo tauri build` |

### 9.4 本机打包步骤（沙箱无 Rust/WebView2/显示，须本机执行）
1. 装前置：Rust 工具链 + Tauri 2 前置（MSVC Build Tools、WebView2 运行时）+ Node 22。
2. `cd prototype && npm install`（装 `@tauri-apps/cli`）与 `cd webui && npm install`（装 vue/vite）。
3. `powershell -File scripts/build_tauri_app.ps1`（自动 assemble + `cargo tauri icon` + `cargo tauri build`）。
   - 仅想验证桌面壳不打包：`.\build_tauri_app.ps1 -AssembleOnly`，再 `npm run tauri dev`。
4. 产物：`src-tauri/target/release/bundle/nsis/*.exe` 安装包。
5. 安装后双击 `BempDiff.exe`：Rust 拉起内置 JRE 跑 `server`，窗口自动打开比对界面；文件/目录选择走原生对话框（免上传）。

### 9.5 已知限制与验证状态
- ✅ **P5 回归通过**：`java_core` 零 `javafx` 导入，重编译 + headless `compare` 在样本 war 产出正确差异（stats + 树），核心 CLI 完全独立于已退役的 `javafx_ui`（见 P5-1）。
- Rust/WebView2 在沙箱无法冒烟，仅做代码级审查 + 前端构建验证（`vite build` 27 模块通过）；**桌面壳须本机 `cargo tauri build` 实跑确认**（窗口导航、dialog 权限、JRE 路径解析、TLS 调 AI）。
- `dist/` 此前在沙箱被锁未刷新（见 P3 记录），本机请用 `npm run build` 重建后验收，勿用旧 dist。
