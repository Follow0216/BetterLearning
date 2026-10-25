#!/usr/bin/env node
// Execute the production whitelist helper and Tablet history citation gate.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader,'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const root = path.resolve(__dirname,'../../../../');
const markersFile=path.join(root,'client/common/src/main/ets/features/multiagent/CitationMarkers.ets');
const markerCode=ts.transpileModule(fs.readFileSync(markersFile,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText;
const markerSandbox={exports:{}};vm.createContext(markerSandbox);vm.runInContext(markerCode,markerSandbox);
const citation_marker_ids=markerSandbox.exports.citation_marker_ids;
const file=path.join(root,'client/apps/tablet/src/main/ets/features/tutor/TabletV2HistoryView.ets');
const sf=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
assert.equal(sf.parseDiagnostics.length,0);
const cls=sf.statements.find(s=>s.name?.text==='TabletV2HistoryView');
const wanted=new Set(['allowedCitationIds','visibleCitations','openCitation','restoreCitationFocus']);
const methods=cls.members.filter(m=>ts.isMethodDeclaration(m)&&wanted.delete(m.name.getText(sf))).map(m=>m.getText(sf)).join('\n');
assert.equal(wanted.size,0);
const code=ts.transpileModule('class Harness {'+methods+'}\nglobalThis.Harness=Harness;',
  {compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
const a='12345678-1234-1234-1234-123456789abc';
const b='12345678-1234-1234-1234-123456789abd';
const c='12345678-1234-1234-1234-123456789abe';
const citations=[{citationId:a,label:'来源一',sourceType:'NOTE'},{citationId:b,label:'未在正文',sourceType:'NOTE'}];
const body='步骤 [[citation:'+a+']] 重复 [[citation:'+a+']] 虚构 [[citation:'+c+']]';
const opened=[];const restored=[];let fallback=0;
const sandbox={citation_marker_ids,focusControl:{requestFocus:()=>{fallback++;}},
  citationFocusController:{restore:id=>{restored.push(id);return true;}},
  CitationModal:args=>args,
  CustomDialogController:class {constructor(options){this.options=options;}open(){opened.push(this.options.builder.citationId);}close(){}}};
vm.createContext(sandbox);vm.runInContext(code,sandbox);
const h=new sandbox.Harness();h.detail={result:{bodyMarkdown:body,citations}};h.citationDialog=null;
assert.deepEqual([...h.allowedCitationIds()],[a]);
assert.deepEqual([...h.visibleCitations()].map(x=>x.citationId),[a]);
h.openCitation(b);h.openCitation(c);assert.equal(opened.length,0);
h.openCitation(a);assert.deepEqual(opened,[a]);
h.restoreCitationFocus(a);assert.deepEqual(restored,[a]);assert.equal(fallback,0);
sandbox.citationFocusController.restore=()=>false;h.restoreCitationFocus(a);assert.equal(fallback,1);
console.log(JSON.stringify({scenarios:5,result:'PASS',boundary:'actual marker whitelist and Tablet citation gate; WebView/native dialog unexercised'}));
