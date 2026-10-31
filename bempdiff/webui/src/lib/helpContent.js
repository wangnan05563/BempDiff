// 帮助文档内容数据模型（设置页「帮助文档」tab 的数据源）。
//
// 设计目标：
//  - 层级化结构：功能分类(category) → 说明条目(entry) → 结构化内容块(block)。
//  - 纯数据 + 纯函数，便于单测（结构校验、检索、版本同步全部可离线断言）。
//  - 内容随版本维护：DOC_VERSION 记录「本文档对应内容版本」，与产品 APP_VERSION
//    通过 contentMatchesVersion() 判定是否滞后，避免文档与产品功能脱节。
//
// 维护约定（修改本文档时注意）：
//  - 新增/调整功能后：新增条目或更新条目内容，并在需要时递增 DOC_VERSION，
//    使「帮助内容版本」与「产品版本」的对照保持可信。
//  - entry.id 全局唯一且稳定（作为导航/检索/测试锚点），不要随意改名。

/** 产品名称（展示用）。 */
export const PRODUCT_NAME = 'BempDiff'

/** 产品版本（Web UI 版本；桌面壳如暴露 appVersion 则运行时优先取壳层版本）。 */
export const APP_VERSION = '0.1.0'

/** 帮助内容版本：与 APP_VERSION 保持同套主·次版本口径（内容随产品演进同步递增），
 *  仅当主版本不一致时提示「内容可能滞后」。 */
export const DOC_VERSION = '0.1'

/**
 * 运行时解析「当前产品版本」：优先取桌面壳暴露的 appVersion（版本最贴近安装包），
 * 否则回落到内置 APP_VERSION。这样即使壳层暂未暴露版本号，也能给出可用的版本标注。
 */
export function resolveAppVersion() {
  try {
    const v = (typeof window !== 'undefined') && window.bempdiff && window.bempdiff.appVersion
    if (v) return String(v)
  } catch (_) { /* 环境无 window（SSR/测例）时忽略，回落内置常量 */ }
  return APP_VERSION
}

/** 内容块 type 白名单（见 buildHelp 校验）。 */
export const BLOCK_TYPES = ['para', 'list', 'steps', 'note', 'warn']

/**
 * 判定「帮助内容是否与当前产品版本同步」。
 * 用「主.次」两段版本号相同作为同步标准（0.1.0 与 0.1 视为同步；0.1 与 0.2 视为滞后提示更新）。
 * 取版本号前两段：避免 patch 级频繁递增造成误报，同时次版本不对齐时能及早提醒文档滞后。
 * 纯逻辑，测例直接断言。
 */
function versionBase2(v) {
  const m = String(v || '').match(/\d+(?:\.\d+)?/)
  return m ? m[0] : String(v || '')
}
export function contentMatchesVersion(docVersion = DOC_VERSION, appVersion = resolveAppVersion()) {
  return versionBase2(docVersion) === versionBase2(appVersion)
}

// ---------------------------------------------------------------------------
// 内容块辅助构造（让书写更紧凑、可读）
// ---------------------------------------------------------------------------
const para = (text) => ({ type: 'para', text })
const list = (items) => ({ type: 'list', items })
const steps = (items) => ({ type: 'steps', items })
const note = (text) => ({ type: 'note', text })
const warn = (text) => ({ type: 'warn', text })

/**
 * 文档条目（entry）：一个可独立导航/检索的最小主题单元。
 * id 全局唯一；title 为导航与标题；intro 为一段导语；blocks 为正文结构块；
 * keywords 用于增强检索（用户习惯用词，可不完全出现在正文）。
 */
function entry(o) {
  return {
    id: o.id,
    title: o.title,
    intro: o.intro || '',
    blocks: o.blocks || [],
    keywords: o.keywords || [],
    // 英文字段（T01508 批次11）：缺失时渲染与检索自动回退中文原文
    titleEn: o.titleEn || '',
    introEn: o.introEn || '',
    blocksEn: o.blocksEn || [],
    keywordsEn: o.keywordsEn || []
  }
}

/** 按当前语言取条目标题（渲染期调用，随 i18n.locale 响应）。 */
export function entryTitle(e) {
  return (e && i18n.locale === 'en-US' && e.titleEn) ? e.titleEn : (e ? e.title : '')
}
/** 按当前语言取条目导语。 */
export function entryIntro(e) {
  return (e && i18n.locale === 'en-US' && e.introEn) ? e.introEn : (e ? e.intro : '')
}
/** 按当前语言取条目正文块。 */
export function entryBlocks(e) {
  if (e && i18n.locale === 'en-US' && e.blocksEn && e.blocksEn.length) return e.blocksEn
  return (e && e.blocks) || []
}
/** 分类标题（渲染期按语言取）。 */
export function categoryTitle(c) {
  if (c && c.titleKey) return t(c.titleKey)
  return (c && i18n.locale === 'en-US' && c.titleEn) ? c.titleEn : (c ? c.title : '')
}

// ---------------------------------------------------------------------------
// 内容：功能分类 → 条目
// ---------------------------------------------------------------------------
import { TERM_TABLE, i18n, t } from './i18n'

export const CATEGORIES = [
  {
    key: 'basics',
    title: '基础功能',
    titleKey: 'hd.cat.basics',
    icon: 'bi-stack',
    entries: [
      entry({
        id: 'glossary',
        title: '术语中英对照',
        intro: '界面与报告中的核心术语中英对照（术语首次出现处附英文，供对外沟通与审计引用）。',
        keywords: ['术语', '对照', '英文', 'glossary', 'i18n', '翻译'],
        titleEn: 'Glossary: Chinese - English',
        introEn: 'A Chinese-English glossary of the core terms used in the UI and reports (English is appended where a term first appears, for communication and audit references).',
        keywordsEn: ['glossary', 'terms', 'terminology', 'english', 'i18n', 'translation'],
        blocksEn: [
          para('Term list (Chinese - English: description):'),
          list(['Diff Tree \\u2014 \\u5dee\\u5f02\\u6811: a navigation view of changed files organized by directory hierarchy', 'Compare \\u2014 \\u6bd4\\u5bf9: loading two packages/directories and computing file-level differences', 'Decompile \\u2014 \\u53cd\\u7f16\\u8bd1: turning .class bytecode back into readable Java source', 'Smart Classification \\u2014 \\u667a\\u80fd\\u5206\\u7c7b: AI tagging of changed files plus a risk rating', 'AI Analysis \\u2014 \\u667a\\u80fd\\u5206\\u6790: AI interpretation of the changes (risk / impact / test points)', 'Report \\u2014 \\u62a5\\u544a: a Markdown/HTML summary of the diff and AI results', 'Export \\u2014 \\u5bfc\\u51fa: packaging diff assets (sources / jars) for download', 'Layer \\u2014 \\u5206\\u5c42: the L0 top-level / L1 in-archive / L2 nested-archive level marker']),
          para('The interface language can be switched with the "EN / \\u4e2d" button in the toolbar; core UI text switches immediately, and the rest migrates batch by batch across releases.'),
        ],
        blocks: [
          para('术语对照清单（中文 — English：说明）：'),
          list(TERM_TABLE.map((r) => `${r[0]} — ${r[1]}：${r[2]}`)),
          para('界面语言可在工具栏「EN / 中」按钮切换；切换后核心界面文案即时生效，其余界面将随版本迭代逐步迁移。')
        ]
      }),
      entry({
        id: 'compare',
        title: '如何开始一次对比',
        intro: '选择「老包」与「新包」即可开始比对。支持文件夹、WAR、JAR、ZIP、EAR 等常见格式，一次比对得到文件级差异总览。',
        keywords: ['开始对比', '导入', '选择', 'war', 'jar', 'zip', '文件夹'],
        titleEn: 'How to start a compare',
        introEn: 'Pick the old package and the new package, then compare. Folders, WAR, JAR, ZIP, EAR and other common formats are supported, and one compare yields a file-level overview of the differences.',
        keywordsEn: ['start compare', 'import', 'select', 'war', 'jar', 'zip', 'folder'],
        blocksEn: [
          para('The old and new inputs can be two archives/folders, or two snapshots of the same folder.'),
          steps(['Switch the input type at the top left: package (war/jar/zip/ear) or folder; both sides share the same type.', 'Enter the two paths in "Old path" / "New path" (use the browse button, or drop them in from the desktop shell).', 'The path bar supports breadcrumb navigation, "up one level" and back/forward history; you can also move both sides up at once.', 'Click Compare (or press Ctrl+Enter) and wait for parse, unpack and diff to finish, then review the stats and the diff tree.']),
          note('Paths may point at archives or folders; when comparing folders, nested ZIP/WAR/JAR files are unpacked layer by layer automatically (see "Auto Unpack Nested Archives").'),
          note('Same-name packages with different versions (e.g. app-1.0.zip vs app-2.0.zip) are ordered by version automatically.'),
          warn('Make sure the two paths really are "old -> new"; swapping them inverts every added/deleted verdict.'),
        ],
        blocks: [
          para('老包与新包既可以是两个压缩包/文件夹，也可以是同一个文件夹的两次快照。'),
          steps([
            '在顶部左侧切换输入类型：文件包（war/jar/zip/ear）或文件夹；老包与新包保持同一类型。',
            '在「老包路径」/「新包路径」填入两个待比对的路径（也可点文件夹图标浏览选择，或从桌面壳直接拖入）。',
            '路径栏支持面包屑逐级点击导航，配合「向上一层」「后退 / 前进」在目录间来回切换；也可一键同时上移两侧。',
            '点击「开始对比」（或按 Ctrl+Enter），等待解析、解包、比对完成后查看差异统计与差异文件树。',
          ]),
          note('路径既支持压缩包也支持文件夹；比对文件夹时，内部嵌套的 ZIP/WAR/JAR 也会被自动逐层解包（见「自动逐层解包」）。'),
          note('同名不同版本的包（如 app-1.0.zip 与 app-2.0.zip）会自动按版本排序，无需手动区分新旧。'),
          warn('请确认两个路径中的内容确实是「旧版→新版」关系，顺序颠倒会导致新增/删除判定颠倒。'),
        ]
      }),
      entry({
        id: 'drop',
        title: '用拖拽快速填入老包/新包',
        intro: '把文件直接拖进应用窗口，即可自动交替填入老包与新包路径，无需逐个手动选择。',
        keywords: ['拖拽', '拖放', '拖入', '自动填入', '循环', '遮罩'],
        titleEn: 'Fill the old/new paths by drag & drop',
        introEn: 'Drop files straight into the app window: they fill the old and new paths alternately, so you never have to pick them one by one.',
        keywordsEn: ['drag', 'drop', 'auto fill', 'cycle', 'overlay'],
        blocksEn: [
          para('In the desktop shell you can drop any archive or folder into the window; the tool fills the old/new paths in turn.'),
          list(['First drop -> fills the old path.', 'Second drop -> fills the new path.', 'Third drop and beyond -> they alternate: odd drops go to old, even drops to new.']),
          para('A full-screen overlay appears while dragging, telling you which side the next drop fills ("Drop the old package" / "Drop the new package"); releasing the mouse completes the fill and the overlay closes.'),
          steps(['Drop the old package into the window; it fills the old path.', 'Drop the new package in; it fills the new path and the compare starts.', 'For the next pair: the third drop returns to old, the fourth fills new, and so on.']),
          note('The compare starts automatically once both paths are set; if the dropped file path cannot be read (browser mode), the tool asks you to use the desktop shell or type the path manually.'),
        ],
        blocks: [
          para('桌面壳下把任意压缩包或文件夹直接拖进窗口即可，系统会按顺序循环填入老包/新包路径。'),
          list([
            '第一次拖入 → 自动填入「老包路径」。',
            '第二次拖入 → 自动填入「新包路径」。',
            '第三次及以后 → 循环交替：奇数次填老包、偶数次填新包。',
          ]),
          para('拖拽过程中会弹出全屏遮罩，中央提示本次应填入的类型（「请拖入老包 / 请拖入新包」）；松开鼠标即完成填充，遮罩随之关闭、恢复页面展示。'),
          steps([
            '把旧版包拖进窗口，松开后自动填入老包路径。',
            '再把新版包拖进窗口，松开后自动填入新包路径并开始对比。',
            '要比较新的一对时：第三次拖入回到老包、第四次填新包，依此类推。',
          ]),
          note('当两个路径齐备时系统会自动开始对比；若读取不到拖入文件的路径（浏览器模式），会提示改用桌面壳或手动填写。'),
        ]
      }),
      entry({
        id: 'tree',
        title: '差异文件树与视图',
        intro: '差异树以层级方式展示所有变化文件，支持按状态过滤、搜索、切换视图，并可通过右键快速处理条目。',
        keywords: ['差异树', '过滤', '搜索', '树视图', '列表视图', '忽略'],
        titleEn: 'Diff tree and views',
        introEn: 'The diff tree shows all changed files hierarchically, with status filters, search, view switching and a right-click menu for quick actions.',
        keywordsEn: ['diff tree', 'filter', 'search', 'tree view', 'list view', 'ignore'],
        blocksEn: [
          para('The diff tree groups results by Modified / Added / Deleted / Unchanged, with live counts in the pane header.'),
          list(['Status filters sit at the top: show only Modified / Added / Deleted; hiding Unchanged shortens the list dramatically.', 'The search box supports fuzzy matching and can switch to regex mode; with filters active the header shows "hits / total".', 'Switch between the directory tree and a flat list, and sort by path / name / status (sorting by name or status applies to the list view).', 'After AI Smart Classification you can sort by risk and filter by High / Medium / Low, so risky files surface first.', 'Right-click an entry to: open the compare, reveal in folder, set as base folder, rename / copy / delete (folder mode), copy name / path / relative path, exclude (hide temporarily), add an ignore rule, view properties.']),
          note('"Exclude" only hides the entry for the current session and can be undone via Restore at the bottom of the tree; "Ignore" writes a rule and persists.'),
          note('If the compare fails, the failure reason stays in this pane and in the status bar - it is never shown as "no differences".'),
        ],
        blocks: [
          para('差异树把结果按「修改 / 新增 / 删除 / 未变」归类，面板顶部实时显示各类数量。'),
          list([
            '顶部可勾选状态过滤：只显示「修改」「新增」「删除」等，默认隐藏「未变」可显著缩短列表；过滤生效时标题显示「命中 / 总数」。',
            '搜索框支持模糊匹配，也可切换为正则模式做精确匹配。',
            '视图可在「树结构」与「列表」间切换，并按路径 / 名称 / 状态排序（名称与状态排序作用于列表视图）。',
            '完成「AI 智能分类」后，可按风险排序，并按「高 / 中 / 低」过滤，高风险文件优先浮出。',
            '右键条目可执行：打开对比、在文件夹中显示、设为基准目录、重命名 / 复制 / 删除（文件夹模式）、复制名称 / 路径 / 相对路径、排除（临时隐藏）、加入忽略规则、查看属性。',
          ]),
          note('「排除」仅当前会话临时隐藏，可在树底部「恢复」；「忽略」会写入规则并持久化生效。'),
          note('比对失败时，失败原因会常驻在差异树栏与状态栏，不会被误显示为「两个包没有差异」。'),
        ]
      }),
      entry({
        id: 'diff',
        title: '内容比对（双栏差异）',
        intro: '点击差异树中的任一文件，即可在中间区域打开左右双栏对比，逐行查看两种版本的内容差异。',
        keywords: ['diff', '对比', '内容', '双栏', '源码', '高亮'],
        titleEn: 'Content compare (two columns)',
        introEn: 'Click any file in the diff tree to open a left/right two-column compare in the middle pane and inspect the differences line by line.',
        keywordsEn: ['diff', 'compare', 'content', 'two column', 'source', 'highlight'],
        blocksEn: [
          para('Source files (Java/JS/HTML/CSS ...), text files, Office documents (Word/Excel/PPT), bytecode (decompiled automatically) and binaries (summary card) are all supported.'),
          steps(['Click a Modified or Added/Deleted file in the diff tree; it opens in a tab (multiple files can stay open, pin the ones you need).', 'The two columns (split) or the unified Git-style view show the old and new content, with changed lines highlighted.', 'Switch line wrapping, character/word/line highlight granularity, collapse unchanged regions, or show diff-only content.', 'Use Find (Ctrl+F), previous/next diff (j / k), row selection copy (Ctrl+A/C) and gutter actions (copy to the other side, mark reviewed).', 'Focus mode hides both side panes for projection; Esc exits.']),
          note('Unchanged files can be opened too - handy to confirm that nothing substantial changed. Binary entries show type, size on both sides and a content verdict instead of a line diff.'),
        ],
        blocks: [
          para('支持源码类（Java/JS/HTML/CSS 等）、文本类、Office 文档（Word/Excel/PPT）、字节码（自动反编译）与二进制文件（摘要卡）。'),
          steps([
            '在差异树中点击一个「修改」或「新增 / 删除」文件；文件会在标签页中打开，可同时保留多个标签并「固定」常用文件。',
            '用双栏（split）或 Git 风格统一视图（unified）查看老版 / 新版内容，差异行高亮。',
            '可切换自动换行、字符 / 词 / 行级高亮粒度、折叠未变更区，或进入「仅差异内容」视图。',
            '用差异内查找（Ctrl+F）、上一处 / 下一处差异（j / k）、行选择复制（Ctrl+A/C）、行侧操作（复制到对侧、标记已审阅）聚焦具体改动。',
            '专注模式会隐藏左右两栏放大对比视野，适合投屏评审，Esc 退出。',
          ]),
          note('「未变」文件也可打开查看；二进制条目展示两侧大小与内容是否一致（SHA/CRC 判定），不提供行级差异。'),
        ]
      }),
      entry({
        id: 'export',
        title: '导出差异资产',
        intro: '把本次差异以压缩包形式导出，便于交付或归档。导出包内按「增量」与「删除」分类组织。',
        keywords: ['导出', '差异资产', '增量', '删除', 'zip'],
        titleEn: 'Export diff assets',
        introEn: 'Export the diff as an archive for delivery or archiving; the archive is organized into increment and deleted sections.',
        keywordsEn: ['export', 'diff assets', 'increment', 'deleted', 'zip'],
        blocksEn: [
          para('Exports are routed by size: small packages are exported synchronously (with live progress), large ones are generated in the background (you get an "export started, about X minutes" notice) and downloaded later from the Downloads panel. The archive is organized into increment and deleted sections.'),
          list(['Choose the granularity first: all / changed JARs only / changed CLASS files only.', 'increment/: added and modified files (the increment you need to ship).', 'deleted/: removed files (kept for reference/audit).', 'The Downloads panel keeps sync exports and async export jobs, supports keyword and time filtering, download, delete and cancel.']),
          steps(['After a compare finishes, open the Export menu in the toolbar.', 'Pick the granularity (all / JAR / CLASS), then click "Export diff assets".', 'If a layer-by-layer unpack compare is still running, the export waits for it to finish.', 'Small packages download directly; for large ones pick the job in the Downloads panel and click Download; you can cancel a running export from the status bar.']),
        ],
        blocks: [
          para('导出按规模自动分流：小包同步导出（实时显示进度），大包异步后台生成（提示“导出已启动，预计X分钟完成”），完成后到「下载管理」页下载。导出包内按「增量」与「删除」分类组织。'),
          list([
            '导出前可选择粒度：全量 / 仅变化 JAR / 仅变化 CLASS。',
            'increment/：新增与修改的文件（需要带走的增量）。',
            'deleted/：被删除的文件（便于留存对照/审计）。',
            '「下载管理」集中展示同步导出与异步导出记录，支持关键字与时间筛选、下载、删除与取消。',
          ]),
          steps([
            '完成一次对比后，点击工具栏「导出」下拉。',
            '选择导出粒度（全量 / JAR / CLASS），再点「导出差异资产」。',
            '若正在自动逐层解包比对，需等待其完成后才能导出（导出会自动等待）。',
            '小包会直接下载；大包在「下载管理」页选择本次任务后点击「下载」获取，进行中的导出可在状态栏取消。',
          ]),
        ]
      }),
      entry({
        id: 'history',
        title: '比对历史与常用会话',
        intro: '最近比对过的路径对会自动留存，也可把常用组合命名保存，下次一键回填。',
        keywords: ['历史', '会话', '最近', '保存', '重放', '常用'],
        titleEn: 'Compare history and saved sessions',
        introEn: 'Recent path pairs are remembered automatically, and frequently used pairs can be named and restored in one click.',
        keywordsEn: ['history', 'session', 'recent', 'save', 'replay'],
        blocksEn: [
          para('The clock icon at the top left opens the history panel, which holds two lists:'),
          list(['Auto history: the last 20 path pairs (deduplicated, persisted across restarts), only path metadata is stored. Click an entry to fill the paths back (the compare does not start automatically).', 'Named sessions: type a name and press + to save a pair you use often; click to apply, or remove it.']),
          note('You can delete a single history entry or clear the whole auto history; nothing is uploaded - all records stay on this machine.'),
        ],
        blocks: [
          para('点击顶部左侧的时钟图标打开历史面板，其中包含两个列表：'),
          list([
            '自动历史：最近 20 组对比路径（自动去重、跨启动保留），仅保存路径元数据；点击条目即可回填路径（不会自动重新比对）。',
            '命名会话：输入名称后按「+」保存常用路径组合，点击即可应用，也可单项删除。',
          ]),
          note('自动历史支持单项删除与整体清空；所有记录只存在本机，不会上传。'),
        ]
      }),
    ]
  },
  {
    key: 'advanced',
    title: '高级功能',
    titleKey: 'hd.cat.advanced',
    icon: 'bi-stars',
    entries: [
      entry({
        id: 'unpack',
        title: '自动逐层解包',
        intro: '对比嵌套包（ZIP/WAR/JAR 层层嵌套）时，默认会自动异步多线程逐层解包并展开，无需手动点击每个包。',
        keywords: ['解包', '嵌套', '递归', '展开', '多线程'],
        titleEn: 'Automatic layer-by-layer unpacking',
        introEn: 'When comparing nested packages (ZIP inside WAR inside JAR ...), the tool unpacks them asynchronously with multiple threads by default - you never have to expand each archive by hand.',
        keywordsEn: ['unpack', 'nested', 'recursive', 'expand', 'threads'],
        blocksEn: [
          para('Optimized for folder / WAR / ZIP comparisons: once the compare finishes, files inside nested archives are unpacked automatically and appear in the diff tree, and AI analysis plus diff export cover those nested files too.'),
          list(['"Unpack threads" and "Max unpack depth" can be adjusted under Settings -> Parse & Export.', 'Nested archives that hit the depth limit or time out stay as manually expandable nodes, so no structure is lost.']),
          warn('Auto-unpacking very large packages costs memory and time; if you do not need to look inside nested archives, turn auto-unpacking off in the settings.'),
        ],
        blocks: [
          para('针对「文件夹 / WAR / ZIP」对比优化：比对完成后，嵌套包内部文件会自动解包并出现在差异树中，AI 分析与差异导出也会覆盖到嵌套子文件。'),
          list([
            '在「设置 → 解析与导出」可调整「解包线程数」与「最大解包深度」。',
            '深度接近上限或超时未完成的嵌套归档会保留为可手动展开的节点，不丢失结构。',
          ]),
          warn('过大的包开启自动解包会更占内存与耗时；如不需要查看嵌套内部，可在设置中关闭自动逐层解包。'),
        ]
      }),
      entry({
        id: 'classify',
        title: 'AI 智能分类',
        intro: '让 AI 对差异文件自动打上风险等级与变化类别标签，帮你优先审阅高风险变更。',
        keywords: ['智能分类', '风险', '打标', '分类'],
        titleEn: 'AI Smart Classification',
        introEn: 'Let AI tag every changed file with a risk level and a change category so you can review the risky ones first.',
        keywordsEn: ['smart classification', 'risk', 'tag', 'classify'],
        blocksEn: [
          steps(['After a compare, click "AI Smart Classification" in the toolbar.', 'The tool tags changed files with a High / Medium / Low risk and a change category.', 'Sort or filter the diff tree by risk to handle the high-risk files first.']),
          note('When a model is configured under Settings -> AI Service the model judges the semantics; without a model (or offline) a built-in heuristic based on file type and package path still produces deterministic tags - the feature is never left empty.'),
        ],
        blocks: [
          steps([
            '完成一次对比后，点击工具栏「AI 智能分类」。',
            '系统为差异文件打上「高 / 中 / 低」风险与变化类别。',
            '在差异树上即可按风险排序或过滤，优先处理高风险文件。',
          ]),
          note('已配置模型时由模型结合语义判定；未接入模型或离线时，系统按文件类型与包路径的内置启发式规则给出确定性结果，该能力在任何环境都可用。'),
        ]
      }),
      entry({
        id: 'ai',
        title: 'AI 智能分析',
        intro: '对整体变更执行智能分析，支持多类分析并行发起，结果以控制台流式展示。',
        keywords: ['ai', 'AI分析', '风险', '影响范围', '测试要点', '破坏性'],
        titleEn: 'AI Analysis',
        introEn: 'Run AI analyses over the whole change set; several categories can run in parallel and results stream into the console.',
        keywordsEn: ['ai', 'analysis', 'risk', 'impact', 'test points', 'breaking'],
        blocksEn: [
          para('Supported categories (they can run in parallel without blocking each other):'),
          list(['Overall Risk Analysis: an overview of the risk in this change set.', 'Breaking Changes: finds changes that may break existing behavior.', 'Impact Scope: assesses which modules and areas the change touches.', 'Test Points: suggests what to regression-test.', 'Custom Question: ask anything in your own words.']),
          steps(['Start an analysis from the toolbar (one-click overall risk) or from the AI console; results stream into the console.', 'Thinking steps can be collapsed, and a running task can be aborted or retried.', 'Copy the Markdown result with one click; the file tab shows the per-file conclusion next to the source diff.']),
          note('AI analysis can only start after the auto-unpack compare finishes; it consumes model quota, so you are asked to confirm when the estimated cost exceeds the threshold.'),
          note('If no API Key is configured or the model is unreachable, compare / decompile / report still work; AI failures are classified (auth / model / network / rate limit) with a suggested action.'),
        ],
        blocks: [
          para('支持的分析类别（可并行、互不阻塞）：'),
          list([
            '整体风险分析：总览这次变化的风险情况。',
            '破坏性变更专项：识别可能破坏现有功能的变更。',
            '影响范围分析：评估变化波及的模块与范围。',
            '测试要点分析：给出应重点回归的测试建议。',
            '自定义问题：按你的自由表述发起分析。',
          ]),
          steps([
            '从工具栏一键发起整体风险分析，或在右侧「智能分析 → 控制台」选择类别后发起；结果以流式输出到控制台。',
            '思考过程可折叠；进行中的任务可中止，失败后可重试。',
            '结果可一键复制 Markdown；「单文件」页签与源码比对同屏展示该文件的 AI 结论。',
          ]),
          note('AI 分析需待自动解包比对完成后方可发起；分析会占用模型额度，超成本阈值时会先给你确认。'),
          note('未配置 Key 或模型不可达时，比对 / 反编译 / 报告仍照常工作；AI 失败会按「鉴权 / 模型 / 网络 / 限流」四类归因并给出建议动作。'),
        ]
      }),
      entry({
        id: 'report',
        title: '报告生成',
        intro: '把比对与 AI 分析结果整理成一份结构化报告，也支持针对单一主题的聚焦报告。',
        keywords: ['报告', '生成', '导出', 'markdown'],
        titleEn: 'Report generation',
        introEn: 'Turn the compare and AI results into a structured report, or generate a focused report for a single topic.',
        keywordsEn: ['report', 'generate', 'export', 'markdown'],
        blocksEn: [
          list(['Overall report: diff stats, changed file list, source diff summary and (when AI is enabled) the AI chapters.', 'Focused report: a slim report for one selected analysis category (e.g. Breaking Changes or Audit summary).', 'The preview dialog highlights the overall risk at the top; download the Markdown or export a self-contained HTML file.', 'HTML export supports a custom template (title, subtitle, author, risk note) and is print-friendly with no scripts.']),
          note('Enable "Auto AI report after compare" in the settings to reduce manual steps. The Markdown report can be generated with or without AI.'),
        ],
        blocks: [
          list([
            '整体报告：包含差异统计、变更文件清单、源码 diff 摘要，以及启用 AI 时的 AI 章节。',
            '聚焦报告：仅针对你选择的某一具体分析项（如「破坏性变更」或「审计摘要」）生成精简报告。',
            '生成后可在报告预览中查看，顶部会高亮「整体风险」结论；支持下载 Markdown 文件。',
            '还可导出为自包含单文件 HTML（无脚本、打印友好），并自定义标题、副标题、落款与风险口径。',
          ]),
          note('可在「设置」开启「比对后自动生成 AI 报告」，减少手动操作；Markdown 报告也可在不启用 AI 时生成基础版。'),
        ]
      }),
      entry({
        id: 'context',
        title: '项目级上下文增强',
        intro: '把本机项目源码/文档作为背景上下文注入 AI，让分析结果更贴合你的业务。',
        keywords: ['上下文', '项目', '增强', '注入'],
        titleEn: 'Project-level context',
        introEn: 'Inject local project sources/docs as background context into the AI so the analysis fits your codebase.',
        keywordsEn: ['context', 'project', 'inject'],
        blocksEn: [
          steps(['Enable "Project-level Context" under Settings -> UI & Advanced and pick the project source/doc directory.', 'Saving the config scans that directory structure automatically.', 'Later AI analyses inject a project overview into the request, so results match the real code.']),
        ],
        blocks: [
          steps([
            '在「设置 → 界面与高级」启用「项目级上下文增强」，并选择项目源码/文档目录。',
            '保存配置后自动扫描该项目目录结构。',
            '此后发起 AI 分析时，会把项目概览注入分析请求，结果更贴合实际代码。',
          ]),
        ]
      }),
      entry({
        id: 'decompile',
        title: '反编译查看',
        intro: '对于只有字节码（.class）的变更，自动反编译为源码后对比，避免直接看不可读的字节码。',
        keywords: ['反编译', 'class', '字节码', 'cfr'],
        titleEn: 'Decompiled view',
        introEn: 'For changes that only exist as bytecode (.class), the tool decompiles them to source before comparing, so you never stare at unreadable bytecode.',
        keywordsEn: ['decompile', 'class', 'bytecode', 'cfr'],
        blocksEn: [
          para('A decompiler is bundled (CFR first, javap as fallback); you can also point Settings -> Parse & Export at an external CFR jar. Classes matching the internal package prefixes are expanded by business code by default.'),
          note('If decompilation fails, the view shows the failure reason instead of an empty diff; binaries fall back to a summary card (type, size, content verdict).'),
          warn('Treat decompiled output as reference for understanding only - never as the sole basis for production decisions.'),
        ],
        blocks: [
          para('内置反编译器（CFR 为主、javap 降级），也可在「设置 → 解析与导出」指定外部 CFR jar。命中内部业务前缀的类默认按业务码展开。'),
          note('反编译失败时会提示失败原因并保留文件信息；纯二进制文件改用摘要卡展示类型、两侧大小与内容判定。'),
          warn('反编译结果仅作理解参考，切勿作为生产的唯一依据。'),
        ]
      }),
      entry({
        id: 'appearance',
        title: '主题、强调色与界面语言',
        intro: '外观可在工具栏即时切换：明暗主题、强调色、语法高亮主题与中英界面。',
        keywords: ['主题', '暗色', '亮色', '强调色', '配色', '语言', '英文', '外观'],
        titleEn: 'Theme, accent color and language',
        introEn: 'Switch the look instantly from the toolbar: light/dark theme, accent color, syntax highlight theme and Chinese/English UI.',
        keywordsEn: ['theme', 'dark', 'light', 'accent', 'color', 'language', 'english', 'appearance'],
        blocksEn: [
          list(['Theme cycles through light -> dark -> follow system with the sun / moon / half-circle button.', 'The palette button offers six accent colors; accent and light/dark combine independently and persist.', 'The syntax highlight theme can be switched independently for long review sessions.', 'The EN / 中 button switches the interface language; core UI updates immediately and the rest migrates batch by batch.']),
          note('All appearance preferences are stored locally and restored on the next start.'),
        ],
        blocks: [
          list([
            '工具栏的太阳 / 月亮 / 半圆按钮循环切换主题：亮 → 暗 → 跟随系统。',
            '调色盘按钮提供六种预设强调色，与明暗主题正交叠加并持久化。',
            '语法高亮主题可单独切换，适配长时间审阅与投屏。',
            '「EN / 中」按钮切换界面语言；核心文案即时生效，其余界面随版本逐步迁移。',
          ]),
          note('所有外观偏好都保存在本机，下次启动自动恢复。'),
        ]
      }),
      entry({
        id: 'shortcuts',
        title: '快捷键与速查面板',
        intro: '常用操作都有快捷键；按「?」可随时打开速查面板。',
        keywords: ['快捷键', '键盘', '速查', '热键', 'ctrl', 'esc'],
        titleEn: 'Shortcuts and cheat sheet',
        introEn: 'Common actions have shortcuts, and pressing "?" opens the cheat sheet anytime.',
        keywordsEn: ['shortcut', 'keyboard', 'cheat sheet', 'hotkey', 'ctrl', 'esc'],
        blocksEn: [
          list(['? - open/close the shortcut cheat sheet', 'Ctrl+Enter - start a compare', 'Ctrl+K - focus the diff tree filter box', 'Ctrl+F - find inside the diff; n / p - next / previous match', 'j / k - next / previous diff', 'Ctrl/Alt + Up/Down - jump to previous / next diff', 'Ctrl+A / Ctrl+C - select all diff rows / copy selected rows (when there is no native text selection)', 'Esc - close find, clear row selection or exit focus mode']),
          note('Global shortcuts are not captured while typing in an input box, so editing text is never disturbed.'),
        ],
        blocks: [
          list([
            '「?」：打开 / 关闭快捷键速查面板。',
            'Ctrl+Enter：开始比对。',
            'Ctrl+K：定位到差异树过滤框。',
            'Ctrl+F：在差异内容内查找；n / p：下一条 / 上一条匹配。',
            'j / k：下一处 / 上一处差异（IDE 惯例）。',
            'Ctrl/Alt + ↑/↓：跳转上一处 / 下一处差异。',
            'Ctrl+A / Ctrl+C：全选差异行 / 复制选中行（无原生文本选区时接管）。',
            'Esc：关闭查找、清除行选择或退出专注模式。',
          ]),
          note('在输入框内输入时不会劫持全局快捷键，不影响正常编辑。'),
        ]
      }),
      entry({
        id: 'settings',
        title: '设置中心总览',
        intro: '所有配置都在「设置」界面完成：AI 服务、解析与导出、差异树过滤、界面与高级、帮助文档、关于。',
        keywords: ['设置', '配置', 'ai 服务', '解析', '导出', '过滤', '关于'],
        titleEn: 'Settings overview',
        introEn: 'Everything is configured in the Settings dialog: AI service, parse & export, diff tree filter, UI & advanced, help, about.',
        keywordsEn: ['settings', 'config', 'ai service', 'parse', 'export', 'filter', 'about'],
        blocksEn: [
          list(['AI Service: provider preset, Base URL, model (fetch the list), API Key (not persisted by default), proxy, strict SSRF, connection test, token caps and cost gate.', 'Parse & Export: internal package prefixes, expand-all, unpack threads / max depth, Top-K, external CFR jar, project context, config migration, temp cleanup and sanitized diagnostic bundle.', 'Diff Tree Filter: search key / regex, which statuses to show, ignored file types and custom extensions.', 'UI & Advanced: auto AI report after compare, project-level context, appearance-related defaults.', 'Help: this document and the guide button; About: version and update check.']),
          note('The API Key stays in backend memory unless you tick "Remember API Key"; config export excludes the plaintext key by default.'),
        ],
        blocks: [
          list([
            'AI 服务：厂商预设、Base URL、模型（可一键拉取列表）、API Key（默认不落盘）、HTTP/HTTPS 代理、严格 SSRF、连接测试、输入/输出 Token 上限与成本闸门。',
            '解析与导出：内部包前缀、展开全部 class、解包线程数与最大深度、Top-K、自定义 CFR jar、项目级上下文、配置迁移、临时文件清理与脱敏诊断包导出。',
            '差异树过滤：搜索关键字与正则、显示哪些状态、忽略的文件类型与自定义后缀。',
            '界面与高级：比对后自动生成 AI 报告、项目级上下文增强等。',
            '帮助文档：本文档与引导按钮；关于：版本信息与更新检查。',
          ]),
          note('API Key 默认只存在后端内存，勾选「记住 API Key」才落盘；导出配置默认不包含明文密钥。'),
        ]
      }),
      entry({
        id: 'update',
        title: '版本检查与更新',
        intro: '启动后自动静默检查新版本；也可在「设置 → 关于」手动检查并更新。',
        keywords: ['更新', '版本', '升级', 'release', 'github', '检查更新'],
        titleEn: 'Version check and update',
        introEn: 'A silent update check runs after start; you can also check manually under Settings -> About.',
        keywordsEn: ['update', 'version', 'upgrade', 'release', 'github', 'check update'],
        blocksEn: [
          list(['About -> Check Update queries the latest GitHub Release and compares it with the current version (a cached result is reused within 5 minutes).', 'When a new version is found, a hint appears in the lower right corner: open the download page or, in the desktop shell, download the installer and start the setup wizard directly.', 'A GitHub read-only token can be saved to avoid anonymous rate limits or to access a private repository; it is kept in memory by default.', 'The check degrades gracefully on network errors or rate limiting and never blocks other features.']),
          note('The startup check is throttled to once every 24 hours.'),
        ],
        blocks: [
          list([
            '「设置 → 关于 → 检查更新」会查询 GitHub 最新 Release 并与当前版本对比（5 分钟内复用缓存结果，可点按钮强制刷新）。',
            '发现新版本时右下角出现提示条：可前往下载页；桌面壳下还可直接下载安装包并启动安装向导。',
            '可填写 GitHub 只读访问令牌，避免匿名限流（60 次/小时）或访问私有仓库；令牌默认只存后端内存。',
            '网络异常或触发限流时优雅降级并给出排查提示，不影响比对等其他功能。',
          ]),
          note('启动时的静默检查有 24 小时节流，不会频繁联网。'),
        ]
      }),
    ]
  },
  {
    key: 'faq',
    title: '常见问题（FAQ）',
    titleKey: 'hd.cat.faq',
    icon: 'bi-question-circle',
    entries: [
      entry({
        id: 'faq_compare',
        title: '为什么没有看到任何差异？',
        intro: '分别排查「路径是否真正可比」「过滤是否掩盖了结果」「是否处于解包/分析中」。',
        keywords: ['无差异', '空白', '空树'],
        titleEn: 'Why do I see no differences at all?',
        introEn: 'Check three things: whether the paths are really comparable, whether filters hide the results, and whether unpacking/analysis is still running.',
        keywordsEn: ['no differences', 'empty', 'blank tree'],
        blocksEn: [
          list(['Confirm the left side is the old version and the right side the new one, and that the content really differs.', 'Look at the status filters at the top of the diff tree: a checked filter may be hiding everything.', 'The search box may still carry a previous keyword/regex that filters everything out.', 'Unpacking or AI analysis may still be running - results only stabilize once those finish.', 'If the compare failed, the reason stays in the diff tree and the status bar; a long-running compare shows a timeout hint instead of looking "identical".']),
        ],
        blocks: [
          list([
            '确认左侧为旧版、右侧为新版，且内容确实存在差异。',
            '看差异树顶部的状态过滤：若勾选了状态过滤把某项隐藏，结果可能被“藏起来”。',
            '搜索框是否带有上一次的关键字/正则，把结果过滤掉了。',
            '是否仍在「自动逐层解包」或 AI 分析中——这些阶段完成后结果才会稳定。',
            '若比对失败，失败原因会常驻差异树与状态栏；运行过久时会显示超时提示，不会被误认为「两个包一致」。',
          ]),
        ]
      }),
      entry({
        id: 'faq_ai',
        title: '点了 AI 分析没反应或报错？',
        intro: 'AI 依赖模型连通性与任务状态，按下面的顺序检查。',
        keywords: ['AI', '没反应', '报错', '模型', 'key'],
        titleEn: 'AI analysis does nothing or reports an error?',
        introEn: 'AI depends on model connectivity and job state - check in this order.',
        keywordsEn: ['ai', 'no response', 'error', 'model', 'key'],
        blocksEn: [
          list(['First confirm under Settings -> AI Service that AI is enabled and the right model is selected; use "Test Connection" to verify.', 'Make sure at least one compare has finished - AI analyses the compare result.', 'AI is disabled while auto-unpacking runs; wait until unpacking finishes.', 'Public models may need a proxy, or "Strict SSRF" turned off (e.g. for local models).', 'Errors are classified (auth / model / network / rate limit) with a suggested action; without a key the compare, smart classification (heuristic) and reports still work.']),
        ],
        blocks: [
          list([
            '先在「设置 → AI 服务」确认已启用并在模型厂商下拉选择正确模型，可点「连接测试」验证连通。',
            '确认已至少完成一次「开始对比」——AI 需基于比对结果分析。',
            '在自动逐层解包期间 AI 会被禁用，请等解包完成后再发起。',
            '公网模型可能需要配置代理或关闭「严格 SSRF」限制（如使用本地模型时）。',
            '失败会按「鉴权 / 模型 / 网络 / 限流」四类归因并给出建议动作；未配置 Key 时比对、智能分类（启发式）与报告仍可正常工作。',
          ]),
        ]
      }),
      entry({
        id: 'faq_export',
        title: '导出差异资产失败/为空？',
        intro: '导出依赖已完成、解包已结束，且至少存在新增或修改文件。',
        keywords: ['导出失败', '导出为空', '资产'],
        titleEn: 'Export of diff assets fails or is empty?',
        introEn: 'Export needs a finished compare, finished unpacking, and at least one added or modified file.',
        keywordsEn: ['export failed', 'empty export', 'assets'],
        blocksEn: [
          list(['Confirm the compare has finished and auto-unpacking has ended.', 'If all differences are "unchanged" or "deleted without increment", an export without added/modified files is normal.', 'Very large packages are generated asynchronously - find the job in the Downloads panel and click Download; a running export can be cancelled from the status bar.', 'If the export job shows an error, the message in the Downloads panel is the first thing to read; the sanitized diagnostic bundle in Settings helps support.']),
        ],
        blocks: [
          list([
            '确认对比已完成（状态为完成），且自动解包已结束。',
            '若差异全部为「未变」或「删除但无增量」，导出包中没有新增/修改项是正常的。',
            '超大包会异步生成：到「下载管理」找到该任务点「下载」；进行中的导出可在状态栏取消。',
            '若导出任务显示错误，先看「下载管理」里的错误信息；必要时在设置中导出脱敏诊断包反馈。',
          ]),
        ]
      }),
      entry({
        id: 'faq_unpack',
        title: '自动解包卡住或很慢？',
        intro: '大包 + 深嵌套 + 高并发，可能受深度/字节上限/机器性能影响。',
        keywords: ['解包慢', '解包卡', '嵌套', '内存'],
        titleEn: 'Auto-unpacking is stuck or slow?',
        introEn: 'Large packages, deep nesting and high concurrency can hit depth/size limits or machine limits.',
        keywordsEn: ['slow unpack', 'stuck', 'nested', 'memory'],
        blocksEn: [
          list(['For large packages, turn auto-unpacking off first (compare the top level only) and expand manually when needed.', 'Lower "Max unpack depth" or "Unpack threads" under Settings -> Parse & Export to reduce resource use.', 'Archives that time out or hit the limit stay as manually expandable nodes - no structure is lost.']),
        ],
        blocks: [
          list([
            '大包建议先关闭自动逐层解包（仅比对顶层），需要时再单独展开。',
            '在「设置 → 解析与导出」降低「最大解包深度」或「解包线程数」可减少资源占用。',
            '超时或触顶的归档会保留为可手动展开节点，不会丢失结构。',
          ]),
        ]
      }),
      entry({
        id: 'faq_setup',
        title: '桌面版如何配置 AI / 代理？',
        intro: '所有配置都在「配置中心」界面完成，无需手工改文件。',
        keywords: ['配置', '代理', '安装', '设置'],
        titleEn: 'How do I configure AI / proxy in the desktop app?',
        introEn: 'Everything is configured in the Settings UI - no manual file editing needed.',
        keywordsEn: ['config', 'proxy', 'setup', 'settings'],
        blocksEn: [
          list(['Model service and key: Settings -> AI Service.', 'Proxy (for public models): Settings -> AI Service -> HTTP/HTTPS proxy.', 'Local/private models usually need no API Key and require "Strict SSRF" to be off.', 'The API Key is kept in memory by default; tick "Remember API Key" if you want it persisted. In browser mode (server subcommand), paths refer to the server machine - type absolute paths manually.', 'GitHub token (optional, for update checks): Settings -> About.']),
        ],
        blocks: [
          list([
            '模型服务与密钥：设置 → AI 服务。',
            '代理（访问公网模型）：设置 → AI 服务 → HTTP/HTTPS 代理。',
            '本地/私有化模型通常无需 API Key，并需关闭「严格 SSRF」。',
            'API Key 默认只存内存，需要重启后保留时勾选「记住 API Key」；浏览器模式下路径指服务器本机，请手动填写绝对路径。',
            'GitHub 访问令牌（可选，供更新检查）：设置 → 关于。',
          ]),
        ]
      }),
      entry({
        id: 'faq_update',
        title: '检查更新失败或提示限流？',
        intro: '更新检查依赖 GitHub 可达性与访问额度，按网络、令牌、缓存的顺序排查。',
        keywords: ['检查更新', '更新失败', '限流', 'github', '令牌', '网络'],
        titleEn: 'Update check fails or reports rate limiting?',
        introEn: 'Update checks depend on GitHub reachability and quota - check network, token and cache in this order.',
        keywordsEn: ['update failed', 'rate limit', 'github', 'token', 'network'],
        blocksEn: [
          list(['Confirm the machine can reach github.com (proxy/company network may block it).', 'Anonymous requests are limited to 60 per hour; save a read-only GitHub token in Settings -> About to raise the quota.', 'A result checked within the last 5 minutes is reused from cache; click Check Update again to force a refresh.', 'The check never affects compare or AI features - failures only show an explanatory message.']),
        ],
        blocks: [
          list([
            '确认本机可访问 github.com（代理或公司网络可能拦截）。',
            '匿名请求限 60 次/小时；在「设置 → 关于」填写只读 GitHub 令牌可提升额度，私有仓库也必须配置令牌。',
            '5 分钟内会复用缓存结果，需要最新结果时再点一次「检查更新」强制刷新。',
            '检查更新失败不影响比对与 AI 功能，只会给出排查提示。',
          ]),
        ]
      }),
    ]
  },
  {
    key: 'tips',
    title: '使用技巧',
    titleKey: 'hd.cat.tips',
    icon: 'bi-lightbulb',
    entries: [
      entry({
        id: 'tips_ignore',
        title: '用“忽略”减少噪声',
        intro: '日志、临时文件、图片等高频噪声可通过忽略规则快速排除，让差异树更干净。',
        keywords: ['忽略', '排除', '噪声', '过滤'],
        titleEn: 'Use "Ignore" to cut noise',
        introEn: 'Logs, temp files and images are common noise; ignore rules remove them quickly and keep the diff tree clean.',
        keywordsEn: ['ignore', 'exclude', 'noise', 'filter'],
        blocksEn: [
          list(['Right-click an entry -> "Ignore" to add it to the ignore rules (persisted).', 'Under Settings -> Parse & Export -> Compare filter, check the file types to ignore entirely (e.g. .log/.tmp), and add custom extensions.', 'Engine-side ignoring of whitespace, whole-line comments or a custom regex removes pure-format noise from line matching.', 'Ignore rules can be reviewed and cleared in one place via "Diff Tree -> Properties".']),
        ],
        blocks: [
          list([
            '右键条目 →「忽略」：把该文件加入忽略规则，持久化生效。',
            '在「设置 → 解析与导出 → 比对过滤」勾选要整体忽略的文件类型（如 .log/.tmp），并可添加自定义后缀。',
            '引擎侧还可忽略空白、整行注释或按自定义正则剔除噪声，只影响行匹配、不改显示内容。',
            '忽略规则可在「差异文件树 → 属性弹窗」统一查看与清空。',
          ]),
        ]
      }),
      entry({
        id: 'tips_perf',
        title: '大包对比更快的姿势',
        intro: '同一份数据，合并忽略与关闭无关层可明显提速。',
        keywords: ['性能', '大包', '优化', '卡'],
        titleEn: 'Faster compares on large packages',
        introEn: 'On the same data set, combining ignores with turning off irrelevant levels speeds things up noticeably.',
        keywordsEn: ['performance', 'large package', 'optimize', 'slow'],
        blocksEn: [
          list(['Turn off "Unchanged" to reduce rendering and scrolling work.', 'If not needed for business review, ignore .class / .jar build outputs and keep sources only.', 'Lower the unpack depth and thread count to avoid wasted resources.']),
        ],
        blocks: [
          list([
            '关闭「未变」显示，减少渲染与浏览量。',
            '若非业务必要，忽略 .class / .jar 等编译产物只保留源码。',
            '调低解包深度与线程数，避免无谓资源消耗。',
          ]),
        ]
      }),
      entry({
        id: 'tips_cost',
        title: '控制 AI 分析与成本',
        intro: 'AI 调用会按 token 计费，配置成本闸门可避免意外高额。',
        keywords: ['成本', 'token', '闸门', '预算'],
        titleEn: 'Control AI usage and cost',
        introEn: 'AI calls are billed per token; the cost gate avoids surprise bills.',
        keywordsEn: ['cost', 'token', 'gate', 'budget'],
        blocksEn: [
          list(['Set the "Cost Gate Warning Tokens" threshold under Settings -> AI Service.', 'Above the threshold, AI analyses ask for confirmation before calling the model.', 'Cap the input and output tokens per request to avoid oversized prompts or truncated reports (raise the output cap if test-point lists get cut off).', 'Prefer local/private models to reduce both compliance pressure and cost.']),
        ],
        blocks: [
          list([
            '在「设置 → AI 服务」设置「成本闸门告警 token」阈值。',
            '超过阈值时，AI 分析会先确认再调用，避免误触发高额账单。',
            '可设置「单次请求最大输入 / 输出 Token」：防止 prompt 过大被模型拒收，也避免长报告被硬切（测试要点不完整时可调大输出上限）。',
            '优先用本地/私有化模型，可降低合规与成本压力。',
          ]),
        ]
      }),
      entry({
        id: 'tips_flow',
        title: '发布前评审的标准流程',
        intro: '从两个包到一份可交付的评审结论，按下面五步走即可。',
        keywords: ['流程', '发布', '评审', '最佳实践', '工作流'],
        titleEn: 'Standard pre-release review flow',
        introEn: 'From two packages to a deliverable review conclusion in five steps.',
        keywordsEn: ['workflow', 'release', 'review', 'best practice'],
        blocksEn: [
          steps(['Compare the old and new packages (or folders) and wait for unpacking to finish.', 'Run Smart Classification, then filter / sort by risk and review the high-risk files first.', 'Start the overall risk analysis (and breaking-change / test-point analyses as needed) in the console.', 'Generate the report, review the overall risk banner, then download Markdown or export the HTML report.', 'Export the diff assets (all / JAR / CLASS) and hand them over together with the report.']),
          note('Save the path pair as a named session if you repeat this release pair every iteration.'),
        ],
        blocks: [
          steps([
            '选择老包与新包开始对比，等待自动逐层解包结束。',
            '执行「AI 智能分类」，按风险过滤 / 排序，优先审阅高风险文件。',
            '在控制台发起整体风险分析，按需追加破坏性变更、影响范围、测试要点分析。',
            '生成报告，检查顶部「整体风险」结论，下载 Markdown 或导出 HTML 报告。',
            '导出差异资产（全量 / JAR / CLASS），与报告一起交付留档。',
          ]),
          note('若每轮发布都对比同一对路径，可保存为「命名会话」，下次一键回填。'),
        ]
      }),
      entry({
        id: 'tips_review',
        title: '高效审阅技巧',
        intro: '用过滤、筛选与行级操作，把注意力集中在真正需要看的改动上。',
        keywords: ['审阅', '效率', '已审阅', '过滤', '专注', '标签页'],
        titleEn: 'Efficient review tips',
        introEn: 'Use filters and row-level actions to focus on the changes that really matter.',
        keywordsEn: ['review', 'efficiency', 'reviewed', 'filter', 'focus', 'tabs'],
        blocksEn: [
          list(['Hide Unchanged and switch to diff-only view to remove noise.', 'After classification, filter by high / medium risk and open files one by one; mark a reviewed file with the gutter check to keep progress visible.', 'Pin the files you keep revisiting so they stay in the tab bar; use j / k to jump between diffs instead of scrolling.', 'Use focus mode when reviewing with others, and Esc to quickly return.']),
        ],
        blocks: [
          list([
            '关闭「未变」并切换到「仅差异内容」视图，先消除噪声。',
            '完成智能分类后按「高 / 中」风险过滤，逐个打开文件；用行侧「标记已审阅」记录进度。',
            '把反复查看的文件「固定」在标签栏；用 j / k 在差异间跳转，比滚动更快。',
            '与他人一起评审时开启专注模式，Esc 可快速退出。',
          ]),
        ]
      }),
    ]
  }
]

/** 扁平化的全部条目（带 categoryKey/categoryTitle），供检索与导航使用。 */
export const HELP_ENTRIES = (() => {
  const out = []
  for (const c of CATEGORIES) {
    for (const e of c.entries) {
      out.push({ ...e, categoryKey: c.key, categoryTitle: c.title })
    }
  }
  return out
})()

/** 检索用的拼装文本（标题 + 导语 + 块文本 + 关键词），命中即纳入结果。 */
function blocksText(blocks) {
  return (blocks || [])
    .map((b) => [b.text, ...(b.items || [])].filter(Boolean).join(' '))
    .join(' ')
}
/** 检索文本：中英双语拼装（EN 界面按英文关键词、ZH 界面按中文关键词均可命中）。 */
function entrySearchText(e) {
  return [
    e.title, e.titleEn, e.intro, e.introEn,
    blocksText(e.blocks), blocksText(e.blocksEn),
    ...(e.keywords || []), ...(e.keywordsEn || [])
  ].filter(Boolean).join(' ').toLowerCase()
}

/**
 * 帮助检索：返回命中的条目（按分类分组后可导航）。query 为空返回 []。
 * 命中规则：条目的标题/导语/正文/关键词任意一处包含 query（不区分大小写）。
 */
export function searchHelp(query, entries = HELP_ENTRIES) {
  const q = String(query || '').trim().toLowerCase()
  if (!q) return []
  return entries.filter((e) => entrySearchText(e).includes(q))
}

/**
 * 结构自检：确保内容模型可被组件/测试放心消费。
 * 返回错误列表（为空即合法）。主要校验：
 *  - 分类/条目/块层级齐全；entry.id 唯一；
 *  - 每个块 type 合法、steps/note/warn 有文本、list/steps 有 items。
 * 供单测调用，也便于未来维护时快速定位内容缺陷。
 */
export function validateHelp(entries = HELP_ENTRIES) {
  const errors = []
  const seen = new Set()
  for (const e of entries) {
    if (!e.id) errors.push(`条目缺少 id: ${e.title || '(无标题)'}`)
    if (e.id) {
      if (seen.has(e.id)) errors.push(`条目 id 重复: ${e.id}`)
      seen.add(e.id)
    }
    if (!e.title) errors.push(`条目缺少 title: id=${e.id || '(无)'}`)
    if (!Array.isArray(e.blocks)) errors.push(`条目 blocks 非数组: ${e.id}`)
    for (const b of e.blocks || []) {
      if (!BLOCK_TYPES.includes(b.type)) errors.push(`${e.id}.blocks 含非法 type=${b.type}`)
      if ((b.type === 'steps' || b.type === 'list') && !Array.isArray(b.items)) errors.push(`${e.id} 的 ${b.type} 块缺少 items`)
      if ((b.type === 'para' || b.type === 'note' || b.type === 'warn') && !b.text) errors.push(`${e.id} 的 ${b.type} 块缺少 text`)
    }
  }
  return errors
}