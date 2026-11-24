// T06220 审阅树纯逻辑校验：统计、AC-07 组合筛选、下一未审选择。
// 运行：node scripts/check_review_tree.mjs
import {
  reviewTreeStats, passesReviewTreeFilter, nextUnreviewedNode, isReviewableFile,
  normalizeReviewTreeFilter
} from '../src/lib/review_tree.js'

let passed = 0
let failed = 0
function assert(name, cond, detail = '') {
  if (cond) { passed++; console.log(`  PASS ${name}`) }
  else { failed++; console.error(`  FAIL ${name}${detail ? ' — ' + detail : ''}`) }
}

const tree = [
  { key: 'src', fileClass: 'FOLDER', status: 'UNCHANGED' },
  { key: 'src/Foo.java', fileClass: 'CLASS', status: 'MODIFIED', formattedOnly: false },
  { key: 'src/Bar.java', fileClass: 'CLASS', status: 'MODIFIED' },
  { key: 'src/Baz.java', fileClass: 'CLASS', status: 'MODIFIED' },
  { key: 'README.md', fileClass: 'STATIC', status: 'MODIFIED', formattedOnly: true }
]
const review = {
  jobId: 'job-test',
  files: {
    'src/Bar.java': { status: 'reviewed', notes: [{ id: 'n1', text: 'looks good' }], bookmarked: true },
    'src/Foo.java': { status: 'unreviewed' },
    'src/Baz.java': { status: 'in_progress', bookmarks: [{ id: 'b1' }, { id: 'b2' }] }
  }
}
const ai = {
  'src/Foo.java': { risk: 'HIGH', category: '业务逻辑' },
  'src/Bar.java': { risk: 'HIGH', category: '业务逻辑' },
  'src/Baz.java': { risk: 'MEDIUM', category: '配置' },
  'README.md': { risk: 'LOW', category: '文档' }
}

console.log('1) 统计头')
const s = reviewTreeStats(tree, review, ai)
assert('目录不计入文件总数', s.totalFiles === 4, `totalFiles=${s.totalFiles}`)
assert('已审 1/4', s.reviewed === 1 && s.filePercent === 25, `reviewed=${s.reviewed} pct=${s.filePercent}`)
assert('未审 2', s.unreviewed === 2, `unreviewed=${s.unreviewed}`)
assert('进行中 1', s.inProgress === 1, `inProgress=${s.inProgress}`)
assert('高风险 2/4', s.highRisk === 2 && s.classified === 4, `hi=${s.highRisk} cls=${s.classified}`)
assert('备注 1', s.notes === 1, `notes=${s.notes}`)
assert('书签 3', s.bookmarks === 3, `bookmarks=${s.bookmarks}`)
assert('无审阅文档时全部未审', reviewTreeStats(tree, { files: {} }, {}).unreviewed === 4)

console.log('2) AC-07：未审 + 高风险 组合筛选')
const f = normalizeReviewTreeFilter({ status: 'unreviewed', highRisk: true })
const hits = tree.filter(n => isReviewableFile(n) && passesReviewTreeFilter(n, f, review, ai))
assert('仅同时满足两个条件的 Foo.java', hits.length === 1 && hits[0].key === 'src/Foo.java', hits.map(n => n.key).join(','))
const hiOnly = tree.filter(n => isReviewableFile(n) && passesReviewTreeFilter(n, { status: 'all', highRisk: true }, review, ai))
assert('仅高风险 = Foo/Bar', hiOnly.length === 2, hiOnly.map(n => n.key).join(','))
const fmt = tree.filter(n => isReviewableFile(n) && passesReviewTreeFilter(n, { onlyFormatted: true }, review, ai))
assert('仅格式变化 = README.md', fmt.length === 1 && fmt[0].key === 'README.md')
const bmOnly = tree.filter(n => isReviewableFile(n) && passesReviewTreeFilter(n, { status: 'all', onlyBookmarks: true }, review, ai))
assert('只看书签 = Bar/Baz', bmOnly.length === 2 && bmOnly.map(n => n.key).join(',') === 'src/Bar.java,src/Baz.java')
assert('只看书签排除无书签文件', !bmOnly.some(n => n.key === 'src/Foo.java' || n.key === 'README.md'))
assert('无 AI 分类时高风险筛不出任何文件', tree.filter(n => isReviewableFile(n) && passesReviewTreeFilter(n, { highRisk: true }, review, {})).length === 0)
assert('非法/缺省筛选归一为全部', normalizeReviewTreeFilter({ status: 'bogus' }).status === 'all')
assert('onlyBookmarks 缺省 false', normalizeReviewTreeFilter({}).onlyBookmarks === false)

console.log('3) 标记后跳下一未审')
const nextAll = nextUnreviewedNode(tree, 'src/Foo.java', review)
assert('从 Foo 往后 = README.md（Bar 已审、Baz 进行中）', nextAll && nextAll.key === 'README.md', nextAll && nextAll.key)
const wrap = nextUnreviewedNode(tree, 'README.md', review)
assert('尾部回绕 = Foo.java', wrap && wrap.key === 'src/Foo.java', wrap && wrap.key)
const nextHi = nextUnreviewedNode(tree, 'src/Foo.java', review, n => passesReviewTreeFilter(n, { highRisk: true }, review, ai))
assert('高风险组合下无下一未审 => null', nextHi === null, nextHi && nextHi.key)
const nextNobody = nextUnreviewedNode(tree, 'README.md', review, () => false)
assert('无候选返回 null', nextNobody === null)

console.log(`\n结果：PASS=${passed} FAIL=${failed}`)
process.exit(failed ? 1 : 0)
