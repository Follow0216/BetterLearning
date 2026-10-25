#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const file = path.join(__dirname, '../src/main/ets/features/multiagent/CitationMarkers.ets');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
const testModule = { exports: {} };
vm.runInNewContext(js, { exports: testModule.exports, require: () => ({}) }, { filename: file });
const { citation_marker_ids, citation_marker_metadata } = testModule.exports;
const a = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const b = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const unknown = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const citations = [
  { citationId: a, label: '<img src=x onerror=alert(1)>', sourceId: 'private-path' },
  { citationId: b, label: '方法二' }
];
const markdown = `先看 [[citation:${b}]]，再看 [[citation:${a}]] 与重复的 [[citation:${b}]]；未知 [[citation:${unknown}]]。`;
assert.deepEqual(Array.from(citation_marker_ids(markdown, citations)), [b, a]);
const metadata = Array.from(citation_marker_metadata(markdown, citations));
assert.deepEqual(metadata.map((item) => Object.keys(item)), [
  ['citationId', 'label'], ['citationId', 'label']
]);
assert.equal(metadata[0].label, '方法二');
assert.equal(metadata[1].label, '<img src=x onerror=alert(1)>');
assert.equal(JSON.stringify(metadata).includes('private-path'), false);
assert.deepEqual(Array.from(citation_marker_ids('[[citation:' + a.toUpperCase() + ']]', citations)), []);
assert.deepEqual(Array.from(citation_marker_ids('[[citation:' + a.slice(0, -1) + ']]', citations)), []);
assert.deepEqual(Array.from(citation_marker_ids('[[citation:' + unknown + ']]', citations)), []);
assert.deepEqual(Array.from(citation_marker_ids('', citations)), []);
assert.deepEqual(Array.from(citation_marker_ids('[[citation:' + a + ']]', [])), []);
console.log('CitationMarkers: 10 个白名单、顺序与纯文本元数据断言通过');
