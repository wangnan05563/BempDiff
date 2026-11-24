// T06242 / REQ-020 / REQ-026：旧 localStorage 行级审阅 → 服务端审阅文档的迁移契约。
// 守护：①旧结构按文件 key 保留全部合法行号；②非法/越界行号被丢弃；③无可迁移条目返回 null（调用方保留旧数据）；
// ④迁移产物 schema/jobId/user/migratedFrom 标注正确，便于失败时定位与回滚。
import { describe, it, expect } from 'vitest'
import { migrateLegacyReviews, REVIEW_SCHEMA_VERSION, REVIEW_STATUS } from '../lib/review'

describe('旧审阅数据迁移', () => {
  it('按文件保留合法行号并标注来源', () => {
    const doc = migrateLegacyReviews({ 'src/A.java': [0, 2, 2, 5], 'src/B.java': [] }, 'job-1', 'alice')
    expect(doc).not.toBeNull()
    expect(doc.schemaVersion).toBe(REVIEW_SCHEMA_VERSION)
    expect(doc.jobId).toBe('job-1')
    expect(doc.user).toBe('alice')
    expect(Object.keys(doc.files)).toEqual(['src/A.java'])
    expect(doc.files['src/A.java'].lines).toEqual([0, 2, 5])
    expect(doc.files['src/A.java'].migratedFrom).toBe('localStorage')
    expect(doc.files['src/A.java'].status).toBe(REVIEW_STATUS.IN_PROGRESS)
  })

  it('过滤负数/非整数/越界行号，避免污染新文档', () => {
    const doc = migrateLegacyReviews({ 'src/A.java': [-1, 1.5, 2_000_001, '7', null] }, 'job-2')
    expect(doc.files['src/A.java'].lines).toEqual([7])
  })

  it('没有可迁移条目返回 null，调用方据此保留旧 localStorage 原文', () => {
    expect(migrateLegacyReviews({}, 'job-3')).toBeNull()
    expect(migrateLegacyReviews({ 'src/A.java': [] }, 'job-4')).toBeNull()
    expect(migrateLegacyReviews(null, 'job-5')).toBeNull()
  })
})
