// T06230 CSV 公式注入防护：= + - @/Tab/CR 开头必须加单引号，随后按 RFC 4180 转义。
import { describe, expect, it } from 'vitest'
import { safeCsvCell, buildDiffCsv } from '../lib/csv_export'

describe('csv export hardening', () => {
  it('prefixes every formula trigger', () => {
    expect(safeCsvCell('=SUM(A1)')).toBe("'=SUM(A1)")
    expect(safeCsvCell('+cmd')).toBe("'+cmd")
    expect(safeCsvCell('-cmd')).toBe("'-cmd")
    expect(safeCsvCell('@cmd')).toBe("'@cmd")
    expect(safeCsvCell('\tcmd')).toBe("'\tcmd")
    expect(safeCsvCell('\rcmd')).toBe("\"'\rcmd\"")
  })

  it('quotes commas/quotes/newlines and keeps ordinary text intact', () => {
    expect(safeCsvCell('a,b')).toBe('"a,b"')
    expect(safeCsvCell('a"b')).toBe('"a""b"')
    expect(safeCsvCell('line1\nline2')).toBe('"line1\nline2"')
    expect(safeCsvCell('normal/path.txt')).toBe('normal/path.txt')
    expect(safeCsvCell(null)).toBe('')
  })

  it('builds a header plus leaf rows and always escapes paths/status', () => {
    const csv = buildDiffCsv([
      { key: 'src/App.java', status: 'MODIFIED', type: 'java' },
      { key: 'src', status: 'MODIFIED', type: 'folder' },
      { key: '=evil.csv', status: 'ADDED', type: 'csv' }
    ])
    const lines = csv.split('\r\n')
    expect(lines[0]).toBe('path,status,type')
    expect(lines).toHaveLength(3)
    expect(lines[1]).toBe('src/App.java,MODIFIED,java')
    expect(lines[2]).toBe("'=evil.csv,ADDED,csv")
  })
})
