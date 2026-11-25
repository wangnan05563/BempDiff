import { describe, expect, it } from 'vitest'
import { configKindOf, diffConfig, configDiffMarkdown, parseConfig } from '../lib/config_semantic'

function changes(result) {
  return result.changes.map(c => `${c.change}:${c.path}`)
}

describe('config semantic diff (FN-04)', () => {
  it('properties: key order / comments / whitespace do not create diffs', () => {
    const oldText = '# c\na=1\nb=two\n'
    const newText = 'b = two\n\n# c2\na=1\n'
    const r = diffConfig('properties', oldText, newText)
    expect(r.ok).toBe(true)
    expect(r.changes).toHaveLength(0)
  })

  it('properties: duplicate key is classified without changing other keys', () => {
    const oldText = 'port=8080\nname=x\n'
    const newText = 'port=8080\nname=x\nname=y\n'
    const r = diffConfig('properties', oldText, newText)
    expect(r.ok).toBe(true)
    expect(changes(r)).toContain('duplicate:name')
    expect(r.stats.duplicate).toBe(1)
  })

  it('yaml: nested keys and arrays align, secret is masked', () => {
    const oldText = 'server:\n  port: 8080\n  password: old\nfeatures:\n  - a\n  - b\n'
    const newText = 'server:\n  password: new\n  port: 9090\nfeatures:\n  - a\n  - c\n'
    const r = diffConfig('yaml', oldText, newText)
    expect(r.ok).toBe(true)
    expect(changes(r)).toEqual(expect.arrayContaining(['change:server.port', 'change:server.password', 'change:features[1]']))
    const secret = r.changes.find(c => c.path === 'server.password')
    expect(secret.secret).toBe(true)
    expect(secret.newValue).not.toContain('new')
    expect(r.stats.secret).toBe(1)
  })

  it('json: object order ignored and type changes flagged', () => {
    const oldText = '{"a":1,"b":{"x":true}}'
    const newText = '{"b":{"x":"true"},"a":1}'
    const r = diffConfig('json', oldText, newText)
    expect(r.ok).toBe(true)
    expect(changes(r)).toEqual(['type:b.x'])
  })

  it('xml: attributes/namespaces align; DOCTYPE is rejected', () => {
    const oldText = '<cfg xmlns:p="urn:x"><p:server port="1" p:name="a"/></cfg>'
    const newText = '<cfg xmlns:q="urn:x"><q:server q:name="a" port="2"/></cfg>'
    const r = diffConfig('xml', oldText, newText)
    expect(r.ok).toBe(true)
    expect(changes(r)).toContain('change:cfg.server.@port')
    const bad = parseConfig('xml', '<!DOCTYPE x [<!ENTITY e "boom">]><x/>')
    expect(bad.ok).toBe(false)
    expect(bad.error).toContain('DOCTYPE')
  })

  it('ini/toml sections keep distinct same-name keys', () => {
    const text = '[db]\nhost=old\n[replica]\nhost=new\n'
    const r = parseConfig('properties', text)
    expect(r.ok).toBe(true)
    expect(r.entries.has('db.host')).toBe(true)
    expect(r.entries.has('replica.host')).toBe(true)
    expect(r.duplicates).toHaveLength(0)
  })

  it('yaml sequence map keeps nested keys under the mapping', () => {
    const r = parseConfig('yaml', 'items:\n  - config:\n      host: a\n      port: 1\n')
    expect(r.ok).toBe(true)
    expect(r.entries.has('items[0].config.host')).toBe(true)
    expect(r.entries.has('items[0].config.port')).toBe(true)
  })

  it('xml CDATA is preserved as text instead of dropped', () => {
    const r = parseConfig('xml', '<x><msg><![CDATA[<b>&amp;</b>]]></msg></x>')
    expect(r.ok).toBe(true)
    const text = r.entries.get('x.msg.#text')
    expect(text && text.value).toBe('<b>&amp;</b>')
  })

  it('json5 comments / single quotes / bare keys / trailing commas parse', () => {
    const r = parseConfig('json', "{ // c\n unquoted: 'v', list: [1, 2,], }")
    expect(r.ok).toBe(true)
    expect(r.entries.get('unquoted').value).toBe('v')
    expect(r.entries.get('list[1]').value).toBe(2)
  })

  it('yaml block scalar honors explicit indentation indicator', () => {
    const r = parseConfig('yaml', 'msg: |2\n  line1\n  line2\n')
    expect(r.ok).toBe(true)
    expect(r.entries.get('msg').value).toBe('line1\nline2')
  })

  it('yaml rejects tab used as indentation after spaces', () => {
    const r = parseConfig('yaml', '  \tkey: 1\n')
    expect(r.ok).toBe(false)
    expect(r.error).toContain('Tab')
  })

  it('toml array table is rejected explicitly instead of merging sections', () => {
    const r = parseConfig('properties', '[[items]]\nname=a\n')
    expect(r.ok).toBe(false)
    expect(r.error).toContain('数组表')
  })

  it('mixed formats are detected by extension', () => {
    expect(configKindOf('a.properties')).toBe('properties')
    expect(configKindOf('a.yml')).toBe('yaml')
    expect(configKindOf('a.XML')).toBe('xml')
    expect(configKindOf('a.json')).toBe('json')
    expect(configKindOf('a.txt')).toBe(null)
  })

  it('parse failure degrades with an error and no crash', () => {
    const r = diffConfig('json', '{bad', '{"a":1}')
    expect(r.ok).toBe(false)
    expect(r.error).toBeTruthy()
    expect(r.changes).toHaveLength(0)
  })

  it('markdown copy never contains unmasked secrets', () => {
    const r = diffConfig('properties', 'password=old\n', 'password=new\n')
    const md = configDiffMarkdown(r, 'app.properties')
    expect(md).toContain('app.properties')
    expect(md).not.toContain('new')
    expect(md).not.toContain('old')
  })

  it('secret masking covers nested value and api.key path', () => {
    const oldText = '{"datasource":{"password":{"value":"old-secret"}},"api":{"key":"old-key"}}'
    const newText = '{"datasource":{"password":{"value":"new-secret"}},"api":{"key":"new-key"}}'
    const r = diffConfig('json', oldText, newText)
    expect(r.ok).toBe(true)
    const nested = r.changes.find(c => c.path === 'datasource.password.value')
    const apiKey = r.changes.find(c => c.path === 'api.key')
    expect(nested && nested.secret).toBe(true)
    expect(apiKey && apiKey.secret).toBe(true)
    expect(nested.newValue).not.toContain('new-secret')
    expect(apiKey.newValue).not.toContain('new-key')
  })

  it('yaml nesting over 64 levels is rejected before parsing', () => {
    let deep = ''
    for (let i = 0; i < 80; i++) deep += ' '.repeat(i * 2) + 'k' + i + ':\n'
    deep += ' '.repeat(160) + 'leaf: 1\n'
    const r = parseConfig('yaml', deep)
    expect(r.ok).toBe(false)
    expect(r.error).toContain('64')
  })

  it('1000-key properties parse/diff stays under 500ms', () => {
    const lines = Array.from({ length: 1000 }, (_, i) => `key.${i}=value-${i}`)
    const oldText = lines.join('\n')
    const newText = lines.map((line, i) => (i % 100 === 0 ? `key.${i}=changed-${i}` : line)).join('\n')
    const t0 = performance.now()
    const r = diffConfig('properties', oldText, newText)
    const elapsed = performance.now() - t0
    expect(r.ok).toBe(true)
    expect(r.changes).toHaveLength(10)
    expect(elapsed).toBeLessThan(500)
  })
})
