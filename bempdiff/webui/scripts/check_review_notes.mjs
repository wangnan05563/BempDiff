// T06221 备注/书签/证据纯逻辑校验（AC-12 + XSS + 导出排除）。
// 运行：node scripts/check_review_notes.mjs
import {
  validateReviewNote, normalizeReviewNote, normalizeFileNotes, normalizeFileBookmarks,
  upsertReviewNote, removeReviewNote, toggleReviewBookmark, notesForTarget, bookmarkForTarget,
  noteTargetKey, buildEvidenceMarkdown, buildReviewAppendix, appendReviewAppendix, buildReviewJsonExport,
  REVIEW_NOTE_MAX
} from '../src/lib/review_notes.js'
import { normalizeReviewDoc, summarizeReview } from '../src/lib/review.js'
import { passesReviewTreeFilter, countFileNotes, countFileBookmarks, reviewTreeStats } from '../src/lib/review_tree.js'
import { renderMarkdown } from '../src/lib/markdown.js'
import { buildHtmlReport } from '../src/lib/html_report.js'

let passed = 0
let failed = 0
function assert(name, cond, detail = '') {
  if (cond) { passed++; console.log(`  PASS ${name}`) }
  else { failed++; console.error(`  FAIL ${name}${detail ? ' — ' + detail : ''}`) }
}

const fileKey = 'src/Foo.java'
const lineTarget = {
  fileKey, path: fileKey, scope: 'line', hunkId: 'h1', line: 12,
  targetKey: noteTargetKey('line', { line: 12 }),
  oldLine: '41', newLine: '42', change: 'rep', hunkHeader: '@@ -40,2 +40,2 @@',
  before: 'int oldValue = 1;', after: 'int newValue = 2;'
}
const hunkTarget = {
  fileKey, path: fileKey, scope: 'hunk', hunkId: 'h1', line: null,
  targetKey: noteTargetKey('hunk', { hunkId: 'h1' }),
  oldLine: '', newLine: '', change: 'hunk', hunkHeader: '@@ -40,4 +40,5 @@',
  before: 'int oldValue = 1;', after: 'int newValue = 2;'
}
const fileTarget = {
  fileKey, path: fileKey, scope: 'file', hunkId: null, line: null,
  targetKey: noteTargetKey('file'), change: 'file', hunkHeader: '', before: '', after: ''
}

console.log('1) 备注校验：空 / 超长 / 边界')
assert('空备注拒绝', validateReviewNote('   \n ').errorKey === 'rv.note.empty')
assert('2000 字通过', validateReviewNote('风'.repeat(REVIEW_NOTE_MAX)).ok === true)
assert('2001 字拒绝', validateReviewNote('风'.repeat(REVIEW_NOTE_MAX + 1)).errorKey === 'rv.note.tooLong')
assert('CRLF 归一为 LF', normalizeReviewNote({ text: 'a\r\nb' }).text === 'a\nb')
assert('非法标签回退 other', normalizeReviewNote({ text: 'x', tag: 'bad' }).tag === 'other')
assert('非法 scope 回退 file', normalizeReviewNote({ text: 'x', scope: 'bad' }).scope === 'file')

console.log('2) 备注/书签持久化结构')
const note = normalizeReviewNote({
  ...lineTarget, id: 'n1', text: '这里需要确认空指针', tag: 'risk', user: '本地审阅人',
  createdAt: '2026-11-23T01:00:00.000Z'
})
assert('行备注保留目标/快照', note && note.targetKey === 'l:12' && note.oldLine === '41' && note.before.includes('oldValue'))
const notes = upsertReviewNote({ notes: [] }, note)
assert('新增备注后 1 条', notes.length === 1 && notes[0].id === 'n1')
const edited = upsertReviewNote({ notes }, { ...note, text: '已确认，低风险', updatedAt: '2026-11-23T02:00:00.000Z' })
assert('按 id 编辑不新增', edited.length === 1 && edited[0].text === '已确认，低风险')
assert('删除备注', removeReviewNote({ notes: edited }, 'n1').length === 0)
assert('notesForTarget 按 targetKey 命中', notesForTarget({ notes: edited }, lineTarget).length === 1)
assert('notesForTarget 不命中 hunk', notesForTarget({ notes: edited }, hunkTarget).length === 0)

const bm1 = toggleReviewBookmark({ bookmarks: [] }, lineTarget, '本地审阅人')
assert('一键加书签', bm1.active === true && bm1.bookmarks.length === 1 && bm1.bookmarks[0].targetKey === 'l:12')
const bm2 = toggleReviewBookmark({ bookmarks: bm1.bookmarks }, lineTarget, '本地审阅人')
assert('再点取消书签', bm2.active === false && bm2.bookmarks.length === 0)
const bmFile = toggleReviewBookmark({ bookmarks: [] }, fileTarget, '本地审阅人')
assert('文件级书签 targetKey=f', bmFile.bookmarks[0].targetKey === 'f')
const bmHunk = toggleReviewBookmark({ bookmarks: bmFile.bookmarks }, hunkTarget, '本地审阅人')
assert('Hunk 书签可并列', bmHunk.bookmarks.length === 2 && !!bookmarkForTarget({ bookmarks: bmHunk.bookmarks }, hunkTarget))
assert('书签数组兼容旧对象映射', normalizeFileBookmarks({ a: { targetKey: 'f' }, b: { targetKey: 'h:h1', hunkId: 'h1', scope: 'hunk' } }).length === 2)

console.log('3) AC-12 证据 Markdown')
const md = buildEvidenceMarkdown(fileKey, lineTarget, { notes: [note] })
assert('含文件路径', md.includes('`src/Foo.java`'))
assert('含 Hunk 头', md.includes('@@ -40,2 +40,2 @@'))
assert('含旧/新行号', md.includes('旧 41 / 新 42'))
assert('含变更类型', md.includes('修改'))
assert('含变更前摘要', md.includes('int oldValue = 1;'))
assert('含变更后摘要', md.includes('int newValue = 2;'))
assert('含备注全文', md.includes('这里需要确认空指针'))
assert('Markdown 结构可直接粘贴', md.startsWith('## 证据摘录：src/Foo.java') && md.includes('**文件**：'))
const hunkMd = buildEvidenceMarkdown(fileKey, hunkTarget, {})
assert('Hunk 级证据含范围头', hunkMd.includes('@@ -40,4 +40,5 @@') && hunkMd.includes('Hunk 范围'))
const multi = buildEvidenceMarkdown(fileKey, { targets: [lineTarget, { ...lineTarget, line: 13, targetKey: 'l:13', oldLine: '42', newLine: '43' }] }, {})
assert('多行证据含两个小节', multi.includes('### 1.') && multi.includes('### 2.') && multi.includes('旧 42 / 新 43'))

console.log('4) 报告附录与导出排除（XSS）')
const review = {
  jobId: 'job-test', user: '本地审阅人',
  files: {
    [fileKey]: { status: 'reviewed', hunkTotal: 1, hunks: {}, lines: [], notes: [note, { ...note, id: 'n2', scope: 'file', targetKey: 'f', line: null }], bookmarks: bmHunk.bookmarks }
  }
}
const appendix = buildReviewAppendix(review, { generatedAt: '2026-11-23T03:00:00.000Z' })
assert('附录含标题', appendix.includes('## 证据与备注'))
assert('附录含备注/书签计数', appendix.includes('备注：2 条；书签：2 个'))
assert('附录含行号证据', appendix.includes('旧 41 / 新 42'))
assert('附录含备注正文', appendix.includes('这里需要确认空指针'))
const excluded = buildReviewAppendix(review, { excludeNotes: true, generatedAt: '2026-11-23T03:00:00.000Z' })
assert('排除备注正文', !excluded.includes('这里需要确认空指针') && excluded.includes('备注正文已排除'))
assert('排除后仍保留标签/计数/证据', excluded.includes('风险') && excluded.includes('备注：2 条'))
assert('无数据不生成空章节', buildReviewAppendix({ files: {} }) === '')
const appended = appendReviewAppendix('# 报告\n\n正文', review, {})
assert('附录拼接到报告末尾', appended.includes('# 报告') && appended.includes('## 证据与备注') && appended.includes('隐私范围。'))
assert('重复拼接不重复章节', appendReviewAppendix(appended, review, {}) === appended)

const json = buildReviewJsonExport(review, { excludeNotes: true, generatedAt: '2026-11-23T03:00:00.000Z' })
assert('JSON 含备注计数', json.files[fileKey].notes.length === 2)
assert('JSON 排除备注正文', !JSON.stringify(json).includes('这里需要确认空指针'))
assert('JSON 标记排除', json.exportMeta.noteTextExcluded === true)
const jsonFull = buildReviewJsonExport(review, { excludeNotes: false })
assert('JSON 默认含备注正文', JSON.stringify(jsonFull).includes('这里需要确认空指针'))

const xss = buildReviewAppendix({
  files: { [fileKey]: { status: 'reviewed', notes: [{ ...note, id: 'x1', text: '<img src=x onerror="window.__xss=1">' }], bookmarks: [] } }
}, { generatedAt: '2026-11-23T03:00:00.000Z' })
const rendered = renderMarkdown(xss)
assert('XSS 标签不进入 HTML', !rendered.includes('<img src=x') && !rendered.includes('onerror="'))
assert('XSS 文本被实体转义', rendered.includes('&lt;img src=x') || rendered.includes('&amp;lt;img'))
assert('Markdown 原始文本保留可读性', xss.includes('<img src=x'))
const htmlReport = buildHtmlReport({ md: xss, title: 'XSS 测试' })
assert('HTML 报告转义 XSS', !htmlReport.includes('<img src=x') && !htmlReport.includes('onerror="'))
assert('HTML 报告保留证据章节', htmlReport.includes('证据与备注'))

console.log('5) 审阅文档 round-trip / 树筛选')
const doc = normalizeReviewDoc({
  jobId: 'job-test', user: '本地审阅人', files: { [fileKey]: { hunkTotal: 1, lines: [], hunks: {}, notes: [note], bookmarks: bmHunk.bookmarks } }
})
assert('normalizeReviewDoc 保留 notes', doc.files[fileKey].notes.length === 1)
assert('normalizeReviewDoc 保留 bookmarks', doc.files[fileKey].bookmarks.length === 2)
assert('summarizeReview 不因扩展字段报错', summarizeReview(doc.files).totalFiles === 1)
assert('树统计读到备注', reviewTreeStats([{ key: fileKey, fileClass: 'CLASS', status: 'MODIFIED' }], review).notes === 2)
assert('countFileNotes 兼容数组', countFileNotes({ notes: [note, { ...note, id: 'n3' }] }) === 2)
assert('countFileBookmarks 兼容对象映射', countFileBookmarks({ bookmarks: { a: { targetKey: 'f' } } }) === 1)
const onlyBm = { ...normalizeReviewDoc({ jobId: 'j', files: {} }), files: { [fileKey]: { notes: [], bookmarks: bmHunk.bookmarks } } }
assert('只看书签命中文件', passesReviewTreeFilter({ key: fileKey, fileClass: 'CLASS', status: 'MODIFIED' }, { status: 'all', onlyBookmarks: true }, onlyBm, {}))
assert('无书签文件被只看书签过滤', !passesReviewTreeFilter({ key: 'src/Bar.java', fileClass: 'CLASS', status: 'MODIFIED' }, { status: 'all', onlyBookmarks: true }, onlyBm, {}))

console.log(`\n结果：PASS=${passed} FAIL=${failed}`)
process.exit(failed ? 1 : 0)
