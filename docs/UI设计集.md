# BempDiff UI 设计集（合并集）

> 合并三份 UI / 视觉设计文档：
> - 第一部分：Diff Gutter 差异化对比栏 + 语法高亮配色方案（原《diff-highlight-design.md》）
> - 第二部分：启动图（Splash）与加载进度方案（原《启动图与加载进度方案.md》）
> - 第三部分：按钮图标优化（去边框扁平风格）（原《按钮图标优化-去边框扁平.md》；样张见 按钮图标优化-去边框扁平风格.svg）

## 第一部分：Diff Gutter 差异化对比栏 + 语法高亮配色方案
> 实现日期：2026-08-21 ｜ 涉及文件：`webui/src/lib/syntax_highlight.js`（新增）、`webui/src/components/DiffView.vue`（改造）、`webui/src/__tests__/syntax_highlight.spec.js`（新增）

#### 一、需求与目标

1. **diff gutter**：双栏差异视图中，每一行左侧（对比栏）清晰标识 新增/删除/修改 状态，用差异化颜色 + 图标。
2. **语法高亮**：按文件类型（JS/TS、Java、Python、HTML/CSS、Markdown 等）自动匹配语法着色，代码层次一目了然。
3. **颜色不冲突**：语法高亮的字体色与差异标记（增/删/改）的背景色/边框色互不混淆。
4. **视觉层次**：关键字、函数、类型、字符串、数字、注释等语法元素有明显色彩区分；差异标记醒目不刺眼。
5. **可维护**：统一色板（CSS 变量）、统一 token 类型表、语言扩展低成本。

#### 二、整体架构

```
源码行（old/new）
   │  alignLines 行级 LCS（已有）
   ▼
差异行 rows（ctx/rep/del/add）── 虚拟滚动只渲染可见行
   │  getInline()（已有，改为并入 token）
   ▼
每个 diff 片段（im-del/im-add/普通）内部再 tokenizeLine()
   │  syntax_highlight.js（新增）
   ▼
双层 DOM：<span class="im-del">（背景=差异语义）> <span class="tok tok-kw">（前景=语法层次）
   │
   ▼
渲染：diff gutter 列（图标+色条）｜行号列 ｜代码列
```

**分层原则**：背景管「差异语义」（红=删、绿=增、橙=改），前景管「语法层次」（token 语义色）。两层互不干扰，这是冲突规避的根本。

#### 三、diff gutter 规范

| 行类型 | 左侧栏（旧） | 右侧栏（新） | 整行底色 | gutter 色条 |
|---|---|---|---|---|
| ctx 未变 | — | — | 无 | 无 |
| add 新增 | — | ➕ plus | 淡绿 `--bs-success-bg-subtle` | 绿 20% |
| del 删除 | ➖ dash | — | 淡红 `--bs-danger-bg-subtle` | 红 20% |
| rep 修改 | ✏️ pencil | ✏️ pencil | 淡橙 `--bs-warning-bg-subtle` | 橙 26% |

- 图标只在「该侧存在该类型内容」时出现（`gtIcon(v, side)`：左栏 del/rep，右栏 add/rep），空侧 gutter 保持空白占位，维持列对齐。
- gutter 自带 `border-right` 与行号列分隔；图标色：add=`--bs-success`、del=`--bs-danger`、rep=`#b45309`（深琥珀，保证淡橙底上可读）。
- 网格列：换行模式 `1.15rem 3.2rem 1fr 1.15rem 3.2rem 1fr`（左 gutter｜左行号｜左代码｜右 gutter｜右行号｜右代码）；分栏模式 `1.15rem 3.2rem 1fr`。

#### 四、语法高亮：token 类型与色板

**一套语义色板跨语言统一**（视觉统一 + 维护简单），语言差异只体现在产出哪些 token 类型。

##### token 类型表

| token | CSS 类 | 语义 | 覆盖语言 |
|---|---|---|---|
| keyword | `.tok-keyword` | 关键字/控制流 | 全部代码语言 |
| string | `.tok-string` | 字符串/引号值 | 全部 + HTML 属性值 |
| comment | `.tok-comment` | 注释/引用 | 全部 |
| number | `.tok-number` | 数字/颜色值 | 全部 + CSS |
| ident | `.tok-ident` | 标识符/变量/选择器 | 全部 |
| func | `.tok-func` | 函数调用（后跟 `(`） | 全部代码语言 |
| type | `.tok-type` | 类型/类名（首字母大写） | Java/TS/Python |
| tag | `.tok-tag` | HTML 标签 | HTML/JSP |
| attr | `.tok-attr` | HTML/CSS 属性名 | HTML/CSS |
| heading | `.tok-heading` | 标题 | Markdown |
| link | `.tok-link` | 链接文本 | Markdown |
| emph | `.tok-emph` | 粗体/斜体 | Markdown |
| inlinecode | `.tok-inlinecode` | 行内代码 | Markdown |
| op | `.tok-op` | 运算符/分隔符 | 全部 |
| ws / plain | 无 span | 空白/普通文本（不包裹，省 DOM） | 全部 |

##### 色板（CSS 变量，定义在 `.col-center`）

| 变量 | 色值 | 用途 |
|---|---|---|
| `--tok-kw` | `#7c3aed` 紫 | 关键字 |
| `--tok-str` | `#b45309` 琥珀 | 字符串 |
| `--tok-com` | `#64748b` 蓝灰 | 注释 |
| `--tok-num` | `#0891b2` 青 | 数字 |
| `--tok-id` | `#475569` 深蓝灰 | 标识符 |
| `--tok-fn` | `#2563eb` 蓝 | 函数调用 |
| `--tok-type` | `#0891b2` 青 | 类型 |
| `--tok-tag` | `#be185d` 玫红 | HTML 标签 |
| `--tok-attr` | `#0284c7` 蓝 | 属性名 |
| `--tok-hd` | `#7c3aed` 紫 | 标题/强调 |
| `--tok-link` | `#2563eb` 蓝 | 链接 |
| `--tok-code` | `#b45309` 琥珀 | 行内代码 |
| `--tok-op` | `#64748b` 蓝灰 | 运算符 |

##### 冲突规避矩阵（核心）

| diff 语义色（保留区） | 语法色（避开区） |
|---|---|
| 红 `#dc3545` 系（删除、`--bs-danger`） | ❌ 语法色不用纯红/亮红 |
| 绿 `#198754` 系（新增、`--bs-success`） | ❌ 语法色不用纯绿/亮绿 |
| 橙 `#fd7e14` 系（修改、`--bs-warning`） | ❌ 语法色不用纯橙/亮橙 |

规避手段（三层兜底）：
1. **源头规避**：token 色板全部落在 紫/蓝/青/蓝灰/琥珀褐/玫红 等非红绿橙区系，色相上不与 diff 三系混淆。
2. **分层兜底**：diff 片段内的 token 自动加深 40% —— `.code .im-del .tok, .code .im-add .tok { color: color-mix(in srgb, currentColor 60%, #000); }`，保证红/绿底上对比度足够、且各 token 类型仍保留各自色相层次。
3. **语义兜底**：整行底色（add/del/rep）均为 Bootstrap 的 `*-bg-subtle` 极淡色，与任何前景语法色叠加对比度都充分。

#### 五、语言识别

```js
langOf(key, fc)  // 扩展名优先 → FileClass 兜底 → 'plain'
```

- 扩展名表：`.js/.mjs/.cjs/.jsx → js`、`.ts/.tsx → ts`、`.java → java`、`.py → python`、`.html/.htm/.xhtml → html`、`.css/.scss/.less → css`、`.md/.markdown → markdown`、`.jsp/.jspx/.tag/.tagx → jsp`。
- FileClass 兜底：`CLASS→java`、`JS→js`、`HTML→html`、`CSS→css`、`JSP→jsp`，其余 `→plain`（XML/JSON/properties 等配置类暂不高亮，纯文本显示）。
- 大小写不敏感。`.md/.py/.ts` 后端不细分类型（归 OTHER/CONFIG），必须靠扩展名表命中。

#### 六、性能

- **虚拟滚动 + 惰性计算**：diff 视图只渲染可见行（约 40 行），tokenize 在 `getInline()` 内对可见行逐行执行，结果并入既有 `inlineCache`（key = `行下标|语言`），切文件/粒度/折叠时清空。
- **O(n) 每行**：词法为单遍状态机（字符串转义 / 块注释 / 行注释），不做回溯；HTML/CSS/Markdown 用前缀正则扫描。
- **超大文件无需降级**：tokenize 成本与「行数」无关只与「可见行」有关，`oversized`（>6 万行）降级仅针对行内 LCS，语法高亮不受影响。

#### 七、扩展新语言 / 新 token 类型

1. **新语言**（如 Go）：
   - 关键字加入 `KEYWORDS`（超集，多数语言可复用）；
   - `CODE_CONF` 加 `go: { lineComment: '//', blockComment: ['/*','*/'], quotes: ['"', "'", '`'] }`；
   - 若语法特殊（如 Go 的 struct tag），在 `tokenizeLine()` 分发里加专用函数；
   - `langOf()` 扩展名表加 `.go → go`，`FC_LANG` 无需动。
2. **新 token 类型**（如 XML 标签名）：
   - `TOKEN_TYPES` 加类型名；
   - 某语言 tokenizer 产出该类型；
   - `.col-center` 加一个 `--tok-*` 变量 + 一个 `.tok-*` 色类（若用现成色可复用变量）；
   - `syntax_highlight.spec.js` 补断言。
3. **暗色主题**：色板为明暗通用中调；如需更亮，加 `[data-bs-theme="dark"] .col-center { --tok-*: ... }` 覆盖即可，类名与结构不变。

#### 八、验证

- `vite build`：49 模块，exit 0。
- `vitest run`：10 文件 128 用例全绿（新增 syntax_highlight.spec.js 13 用例：各语言 tokenize 结构、字符串转义、token 合并、langOf 扩展名/兜底/大小写）。
- 独立验证：TipButton.spec.js 存在定时器类 flaky（满载环境偶发），单跑全绿，与本次改动无关。


## 第二部分：启动图（Splash）与加载过渡方案

> 目标：缓解桌面壳冷启动阶段（Java 后端 sidecar 拉起前的数秒）无反馈等待，用本地启动图消除「黑屏/白屏空窗」。启动图**不依赖后端**，秒显。
>
> 现状（2026-11-02，T04092）：Tauri「路径 B」已剔除，原「Rust 按启动里程碑广播 `splash-progress`、进度条显示真实百分比」的实现随之退役。当前 Electron 壳采用**不确定进度动画**（不伪造百分比），主窗口 `ready-to-show` 后关闭启动图。原 Rust 侧设计保留在 §7 作历史记录。

#### 1. 启动图设计 / 配置

| 项目 | 说明 |
| --- | --- |
| 文件 | `webui/public/splash.html`（启动页）+ `webui/public/splash-logo.png`（品牌图，复制自项目根 `bempdiff-logo.png`） |
| 图片尺寸 | `splash-logo.png` 显示为 128×128，圆角 24px、白色半透明底、柔和阴影 + 轻微呼吸动画 |
| 图片格式 | PNG（含 Alpha，透明背景，适配深浅背景） |
| 窗口尺寸 | 400×300（`dev-shell/main.js` 的 `SPLASH_SIZE_W/H`），无边框 `frame:false`、`skipTaskbar`、不可调整 |
| 展示时长 | 启用到主窗口真正显示为止（一般 1~5s）；另有 8s 超时兜底强制收回 |

#### 2. 进度组件实现

- `splash.html` 内一个圆角进度条（.track/.bar，薄荷绿→淡蓝渐变）＋ 阶段文案（#stage）。
- 桌面壳没有向本页推送进度的通道，页面统一使用 `.bar.indeterminate` 滑动动画：**不显示百分比、不伪造进度**。
- 主窗口显示后才关闭本窗，因此启动图只承担「正在启动」的存在感，不承担进度承诺。

#### 3. 生命周期（Electron 实现）

- `dev-shell/main.js:createSplashWindow()` 在后端拉起前先建 splash 窗（无边框 + `skipTaskbar`，`ready-to-show` 后居中显示）。
- 页面来源 `findSplashHtml()`：优先 `dist_input/webui/splash.html`（打包态唯一来源），回退 `webui/dist/splash.html`。
- 主窗口 `ready-to-show` 或 `did-fail-load` → `closeSplash()`；`createSplashWindow()` 内另挂 8s 定时器兜底，防止后端挂起留下无法关闭的僵尸窗。

#### 4. 接入方式（构建链）

vite build 会把 `webui/public/*` 原样复制到 `webui/dist`，再由 `scripts/build_dist_input.ps1` 镜像到 `dist_input/webui`——**无需额外改构建脚本**。替换品牌图只需覆盖 `webui/public/splash-logo.png`。

#### 5. 效果预览

柔和薄荷青→淡蓝渐变背景，居中 128×128 呼吸 logo +「BempDiff / 差异化对比工具 · 正在启动…」标题、渐变进度条与阶段文案。整个准备工作阶段即出现，消除「黑屏/白屏空窗」；主窗口就绪瞬间过渡到主界面，体验连贯、不突兀。

#### 6. 兼容性与注意事项

- 交付以 Windows 为主，其它平台按工作流手动触发；splash 窗 `skipTaskbar` 仅存续于启动期，不影响主窗口。
- 进度为不确定动画，不存在「虚报近满」问题（历史 Rust 方案的 8~100 钳制与 76→90 封顶随之退役）。
- 验证：`splash.html` 为纯静态单页，浏览器直接打开即可预览；打包态由 `dist_input/webui/splash.html` 提供。

#### 7. 历史记录：Tauri 路径 B 的真实进度方案（已于 T04092 退役）

原 `src-tauri/src/lib.rs` 的 `launch_java()` 按阶段调用 `splash(handle, stage, percent)` 广播 `splash-progress`（负载 `{ stage, percent }`），前端 `window.__TAURI__.event.listen` 订阅并驱动百分比：

| stage | percent | 触发时机 |
| --- | --- | --- |
| resolving | 10 | 解析随包 JRE / bempdiff.jar / cfr.jar / webroot |
| spawning | 45 | 资源就绪，即将 `spawn` Java 子进程 |
| waiting | 76 → 90 | `wait_for_server` TCP 探活期间每 ~0.2s +1，封顶 90 |
| ready | 100 | 后端端口监听成功；主线程销毁 splash、`show()` 主窗口 |

该方案要求 `tauri.conf.json` 的 `withGlobalTauri: true`，并需本机 `cargo tauri build` 冒烟；随路径 B 代码一并删除，无遗留依赖。

## 第三部分：按钮图标优化（去边框扁平风格）
> 任务：整体按钮图标视觉优化，去除描边边框，扁平化、简约化，统一一致性。
> 范围：前端 bempdiff/webui（Vue3 + Bootstrap 5.3.3 + bootstrap-icons）。

#### 一、结论摘要

本次优化以低成本、全局生效方式落地：不逐个修改 20 个组件的按钮模板，而是在全局样式 main.css 末尾追加一套扁平无边框按钮主题，用更高特异性的 .btn.btn-outline-* 覆盖 Bootstrap 的 outline 变量组，实现：

- 所有 btn-outline-* 描边按钮 去除 1px 描边，改为无边框扁平样式
- 保留主次语义：靠填充深浅 + 文字/图标颜色 + hover/active 反馈而非描边来区分
- 键盘聚焦保留细主色圆环（无障碍设计，不牺牲可用性）

#### 二、图标清单（按按钮类型分类）

产品中的按钮图标全部使用 bootstrap-icons 字体图标（i.bi.bi-*），本身就是纯填充、无边框的字体图标，无需替换图标字形。优化的核心对象是承载图标的按钮容器边框。

##### 1. 主操作按钮（实底填充，本就无边框，保持原样）
- ToolBar 开始比对 bi-arrow-left-right：btn-primary 实底
- AiConsole 新建分析 bi-plus-lg、AiAnalysisDialog bi-cpu：btn-primary 实底
- ConfigDialog 保存 bi-check2：btn-primary 实底
- CostGateDialog 确认发起 bi-check-circle：btn-primary 实底

##### 2. 次要/图标按钮（btn-outline-secondary，本次主要去边框对象）
- ToolBar：输入类型切换 bi-file-earmark-zip / bi-folder
- ToolBar：浏览老包/新包 bi-folder2-open
- ToolBar：向上一层(单侧) bi-arrow-up-circle
- ToolBar：两侧同时向上 bi-arrow-up-square
- ToolBar：后退/前进 bi-arrow-left / bi-arrow-right
- ToolBar：查看报告 bi-filetype-md
- ToolBar：切换主题 bi-sun / bi-moon-stars
- ToolBar：打开配置 bi-gear
- ToolBar：导出 bi-file-earmark-arrow-down
- TipButton 通用图标按钮 bi-*（树工具栏展开/折叠等）
- DiffTree：搜索过滤切换 bi-regex
- DiffTree：风险排序 bi-sort-down
- AiConsole：中断/重跑/关闭分析 bi-stop-fill / bi-arrow-clockwise / bi-x-lg
- CompareOverlay：取消比对 bi-x-circle

##### 3. 强调按钮（btn-outline-primary，去边框 + 主色语义）
- AiConsole：新窗口预览报告 bi-filetype-md / 导出分析 bi-download
- ConfigDialog：重新扫描上下文 bi-arrow-clockwise
- InfoPanel：生成报告(AI/合规) bi-filetype-md

##### 4. 危险按钮（btn-outline-danger，去边框 + 危险色语义）
- DiffTree：删除忽略规则（文字按钮）

#### 三、优化说明（每类优化思路）

1. 去除边框：--bs-btn-border-color: transparent 移除 outline 按钮 1px 描边，改为纯透明/无边框。
2. 简约化：简化视觉层次，默认态仅保留图标/文字，不设底、不设边，视觉轻盈。
3. 语义保留：
   - hover 浅灰底 --bs-secondary-bg（次级）/ 主色淡底 --bs-primary-bg-subtle（强调）/ 危险淡底 --bs-danger-bg-subtle（危险）；
   - active/选中 主色淡底+主色文字（分段控件、风险过滤、排序等 .active 态），或主色实底（primary active）。
4. 一致性：统一笔画粗细、圆角（沿用 Bootstrap 默认圆角）、尺寸比例不变；全部通过一套主题规则收口，后续新增按钮自动继承。
5. 可用性：键盘聚焦保留细主色圆环（focus-visible，区别于描边），不影响识别与无障碍。

#### 四、视觉参考
- 样张：按钮图标优化-去边框扁平风格.svg（SVG 对比图：优化前后状态 + 覆盖规则配色图例）。

#### 五、风格规范（可复用设计规范）

##### 5.1 按钮层级
- 主操作 btn-primary：主色实底 / hover 略深主色 / active 主色实底，用于最重要动作。
- 强调 btn-outline-primary：透明+主色文字 / hover 主色淡底 / active 主色实底，用于高价值次要动作。
- 次级 btn-outline-secondary：透明+次级色 / hover 浅灰底 / active 主色淡底+主色字，用于图标/一般动作。
- 危险 btn-outline-danger：透明+危险色 / hover 危险淡底 / active 危险实底，用于删除/破坏性动作。

##### 5.2 使用铁律
- 新按钮一律用 btn-outline-secondary / btn-outline-primary / btn-outline-danger，不再新增自绘描边。
- 图标统一用 bootstrap-icons（纯填充字形），不再引入带描边的自定义图标集。
- 圆角/尺寸沿用 Bootstrap 默认，不另设。

##### 5.3 落地文件
- src/styles/main.css 末尾 扁平无边框按钮主题 区块（唯一需要维护的位置）。

#### 六、验证
- npx vitest run：12 files / 153 tests 全部通过。
- npx vite build 成功，新 CSS index-CXc-sSNj.css 已含 .btn.btn-outline-secondary 主题。
- 已同步 webui/dist 至 dist_input/webui（打包固化副本）。
