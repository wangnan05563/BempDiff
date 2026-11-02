# BempDiff · 差异化对比工具

[![GitHub social preview](assets/social-preview-1280x640.png)](../README.md)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../LICENSE)
[![Release](https://img.shields.io/github/v/release/wangnan05563/bempDiff.svg)](https://github.com/wangnan05563/bempDiff/releases/latest)

> 软件构建包（war / jar / zip / 文件夹）差异化对比与智能分析桌面工具：丢进老包和新包，自动比对差异、反编译看源码级改动、AI 分析风险与测试要点，导出报告与差异资产。双击安装即用，无需安装 Java。

仓库根 [`README.md`](../README.md) 是项目主页（安装、CLI/CI、架构与开发上手），本文是 `bempdiff/` 模块级的详细说明。

## 简介

BempDiff 面向「生产版本 vs 下发版本」的发布前比对场景：把两份构建包拖进来，自动逐层解包（支持嵌套 war/jar/zip）、按目录树展示差异、反编译 class 做源码级 diff，并可通过大模型（可自带 API Key）生成整体风险概览与逐文件深度分析。支持 GUI、headless 命令行与 CI 门禁三种用法。

## 特性

### 比对内核

- **分层差异**：L0 包级清单 / L1 内部业务码（按内部包前缀展开 class）/ L2 第三方依赖（jar 级折叠），变更一眼分层
- **自动逐层解包**：嵌套 war → jar → zip 递归展开为原子条目，内存/磁盘双轨管理（单条目 ≤64 KB 驻留内存，每作业 256 MB 内存预算、全局 3× 硬上限，超额自动落盘）
- **反编译 diff**：CFR 为主、javap 降级；GBK 容错解码；只展示变更行 + 行号区间
- **语法感知归一化**：用 JDK 内置 javac AST 对反编译源码归一化，格式化/成员重排不再报成实质变更，解析失败自动降级行级
- **API 兼容性分级**：新增 / 移除 / 签名变更 / 行为变更四类，不兼容项醒目标注并进报告与 CI JSON
- **前端资源对比**：JS / HTML / CSS 文本差异（自动美化后对比）
- **文件夹对比**：直接对比两个解压目录（名称/类型/大小/mtime/SHA-256）；zip/war/ear 归档做清单级直比，docx/xlsx/pptx 抽取正文文本对比
- **忽略规则**：引擎侧忽略空白/注释/自定义正则，前端侧按精确路径、前缀、文件名、扩展名管理忽略规则（右键「忽略」即加，本机持久化）

### 审阅体验

- **BCompare 风格交互**：目录树视图（折叠/全部展开）、路径导航历史（后退/前进/向上一层）、拖拽即比、文件与目录同一对话框自动识别
- **行内字符级高亮** + **未变更区折叠**（上下文行数可配，另有「仅差异内容」视图）
- **主题**：亮/暗/跟随系统三态 + 多套强调色 + 语法高亮主题包；中英双语界面
- **gutter 快捷操作**：复制到对侧、标记已审阅，`j`/`k` 跳下一处/上一处差异，`n`/`p` 跳查找结果
- **对比会话**：最近比对历史可一键重放；大包导出走下载管理器异步处理

### AI 分析

- **两阶段分析**：整体概览 + 单文件深读（Top-K 限流）；基于反编译调用关系的影响面分析
- **成本透明**：分析前预估 token/费用并设闸门拦截，分析后展示输入/输出实际消耗
- **项目级上下文增强**：分析前离线扫描指定工程目录（构建系统/模块依赖/技术约定），结论更贴合实际工程
- **多任务控制台**：并行分析多类别、流式输出、思考过程可折叠、可中止/重启；AI 打标风险等级与变更类别回灌差异树着色
- **优雅降级**：AI 不可达或无 Key 时比对/反编译/报告全链照常工作，AI 环节走 Mock

### 报告与打包

- **导出**：Markdown 报告（只写差异内容）、自包含 HTML 报告（内联 CSS、打印友好）、CI JSON 摘要、差异 class 资产 zip、反编译源码包、一键脱敏诊断包
- **跨平台安装包**：electron-builder 产出 Windows x64/arm64 NSIS、Linux arm64（AppImage + deb）、macOS arm64（dmg，未签名），内嵌瘦身 JRE

## 安装

从 [Releases](https://github.com/wangnan05563/bempDiff/releases) 下载对应平台安装包（Windows 为 `BempDiff-<版本>-setup.exe`，NSIS 安装包，内嵌瘦 JRE，无需装 Java），双击安装即可。
arm64 / Linux / macOS 产物由 `.github/workflows/` 下的工作流按需构建（`workflow_dispatch`），macOS 包未签名，首次打开需右键 → 打开。构建细节见本文「[开发](#开发)」章节。

## 快速开始

### GUI（推荐）

1. 启动 BempDiff，浏览或拖入「老包（生产）」与「新包（下发）」路径——文件与目录同一对话框，类型自动识别
2. 点击「开始比对」，等待解包与差异树生成
3. 左侧差异树点选文件 → 中间双栏源码 diff（支持 全量/仅差异、unified/split/wrap 视图）
4. 需要时点 ⚙ 配置中心填 AI 厂商 BaseURL + API Key → 工具栏「智能分析」发起 AI 分析（下方控制台流式输出）
5. 导出菜单：生成报告 / 导出差异资产 / 下载管理

### 命令行 headless（CI / 服务器）

`BempDiff.exe` 带子命令参数时直接跑核心比对并退出，不弹窗口：

```bash
BempDiff.exe inspect <pkg>
BempDiff.exe compare <old> <new> [--expand-all]
BempDiff.exe compare-folders <leftDir> <rightDir>          # 文件夹对比（基础版）
BempDiff.exe folderdiff <leftDir> <rightDir> [--report <md>] [--max-depth N]
BempDiff.exe report  <old> <new> [--expand-all] [--top-k N] [--cfr cfr.jar] [--out <md>]
                       [--ai] [--apikey KEY] [--baseurl URL] [--provider P] [--model M]
                       [--project <工程目录>] [--replay <dir>]
BempDiff.exe export  <old> <new> [--expand-all] [--top-k N] [--out outdir]
BempDiff.exe diff-jars <old.jar> <new.jar> [--out <md>]     # 差异 lib jar 的 class 源码对比
BempDiff.exe decompile <old> <new> [--expand-all] [--top-k N] [--cfr cfr.jar]
BempDiff.exe ai <old> <new> [--apikey KEY] [--baseurl URL] [--provider P] [--project <工程目录>]
BempDiff.exe server [--port 18765] [--webroot <dir>]
BempDiff.exe ci --left <old> --right <new> [--report <md>] [--json <out.json>]
                   [--fail-on-diff] [--ai] [--top-k N] [--expand-all]
```

`ci` 子命令是流水线门禁：复用完整报告链路 + 输出 JSON 摘要，退出码 `0`=无差异 / `1`=有差异（需带 `--fail-on-diff`）/ `2`=参数或运行错误。

```yaml
- run: BempDiff.exe ci --left prod.war --right rc.war --json ci.json --report ci.md --fail-on-diff
```

也可直接用 Java 运行核心：`java -cp dist_input/classes com.bempdiff.Main <子命令> ...`

## 配置

服务端配置持久化在 `~/.bempdiff/bempdiff-web.properties`，界面偏好存浏览器 `localStorage`。

- **对比选项**（⚙ 配置中心）：内部包前缀（L1 展开规则）、Top-K、忽略空白/注释/正则/扩展名、嵌套解包线程数与深度
- **AI**：厂商（OpenAI 兼容/Azure/Ollama/通义等）、BaseURL、API Key（掩码显示，默认不落盘）、模型、连接测试、代理、成本闸门阈值、单次输入/输出 token 上限
- **界面**：明暗主题、强调色、语法高亮主题、语言（中/英）、差异树过滤、启动图
- **更新检查**：「关于 → 检查更新」读取 GitHub 最新 Release（默认仓库 `wangnan05563/bempDiff`，可用 `BEMPDIFF_GITHUB_REPO` 覆盖；私有仓库或限流时可在关于页填 GitHub 令牌，或用环境变量 `GITHUB_TOKEN`），详见 [`../docs/GitHub更新手册.md`](../docs/GitHub更新手册.md)

## 开发

技术栈：Electron（壳）+ Vue 3 / Bootstrap / Vite（前端）+ Java 21（核心比对引擎，sidecar 进程）。

```bash
# 前端开发（Vite 热更）与单测
cd webui && npm install && npm run dev
npm run test:unit          # vitest run

# 桌面壳（拉起 Java sidecar + Electron 原生窗口，免浏览器）
tooling/scripts/启动服务.bat        # 停止：tooling/scripts/停止服务.bat

# 构建安装包（编译 Java + jlink 瘦 JRE + 构建前端 + electron-builder → NSIS setup.exe）
tooling/scripts/构建打包.bat

# 一键「构建 + 发布 GitHub Release」（GH_DRY_RUN=1 为演练，不真正发布）
tooling/scripts/一键发布.bat
```

版本号约定 `0.1.<yyyyMMdd><序号>`，由构建脚本自动写入 `package.json`；GitHub Release tag 用裸版本号（与「检查更新」的比较口径一致）。

目录结构：

| 路径 | 说明 |
| --- | --- |
| `java_core/` | Java 核心比对引擎（分层差异 / 解包 / 反编译 / AST / AI 管线 / HTTP 服务） |
| `webui/` | Vue 3 + Bootstrap 前端（Vite 构建，vitest 单测） |
| `dev-shell/` | Electron 壳（sidecar 生命周期 / 原生对话框 / 启动图 / 打包配置） |
| `dist_input/` | 打包落地物（瘦 JRE / 编译产物 / CFR / 前端 dist） |
| `assets/` | logo、图标、GitHub 社交预览图（`social-preview-1280x640.png`） |
| `scripts/` | 构建/清理等开发期脚本 |
| `../tooling/scripts/` | 启动、构建打包、一键发布、性能与质量脚本 |
| `../docs/` | 设计文档、评审报告、打包手册 |

### 测试与质量

```bash
# 前端单测（vitest，用例在 webui/src/__tests__）
cd webui && ./node_modules/.bin/vitest run

# Java 核心（内置轻量 TestRunner，用例在 java_core/test）
java -cp "classes;test;lib/*" com.bempdiff.test.TestRunner com.bempdiff.test.DiffTest
```

静态扫描配置见仓库根 [`sonar-project.properties`](../sonar-project.properties)（project key `bempdiff`，JaCoCo 覆盖率由 `tooling/quality/jacoco/` 生成）。

## 已知限制

- Windows 为主要交付平台；其它平台产物按工作流手动触发构建，macOS 包未签名
- AI 功能需自带 API Key；无 Key 时可完整使用比对/反编译/报告，AI 部分走 Mock 降级
- 超大包（数万条目）首次比对耗时与包大小相关，解包阶段有进度提示
- RAR / 7z / ISO 等格式尚未支持免解压直比（归档目前覆盖 zip/war/ear 清单级）

## 许可证

[MIT](../LICENSE)
