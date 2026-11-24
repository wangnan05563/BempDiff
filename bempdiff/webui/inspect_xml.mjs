import { diffConfig } from './src/lib/config_semantic.js'
const oldText = '<cfg xmlns:p="urn:x"><p:server port="1" p:name="a"/></cfg>'
const newText = '<cfg xmlns:q="urn:x"><q:server q:name="a" port="2"/></cfg>'
const r = diffConfig('xml', oldText, newText)
console.log(r.changes.map(c => c.change + ':' + c.path + ' = ' + c.oldValue + ' -> ' + c.newValue).join('\n'))
