#!/usr/bin/env node
// Actual native-side Markdown bridge/render methods; ArkWeb is replaced, no device claim.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const loader=process.env.BETTERLEARNING_ETS_LOADER||'/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts=require(path.join(loader,'node_modules/typescript')),options=ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const root=path.join(__dirname,'../src/main/ets/features');
function parse(relative){const file=path.join(root,relative);const s=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);assert.equal(s.parseDiagnostics.length,0);return s;}
function code(relative){const s=parse(relative);return s.statements.filter(n=>!ts.isImportDeclaration(n)).map(n=>n.getText(s).replace(/^export\s+/, '')).join('\n');}
const s=parse('learning/QuestionMarkdownView.ets'),component=s.statements.find(n=>n.name?.text==='QuestionMarkdownView');
const top=s.statements.filter(n=>!ts.isImportDeclaration(n)&&n!==component).map(n=>n.getText(s).replace(/^export\s+/, '')).join('\n');
const members=component.members.filter(n=>n.name?.getText(s)!=='build').map(n=>ts.isPropertyDeclaration(n)?n.name.getText(s)+(n.initializer?' = '+n.initializer.getText(s):'')+';':n.getText(s));
const source=code('multiagent/CitationMarkers.ets')+'\n'+code('multiagent/CitationFocusController.ets')+'\n'+top+'\nclass QuestionMarkdownView {'+members.join('\n')+'}\nglobalThis.types={QuestionMarkdownView,citationFocusController};';
const emitted=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020},reportDiagnostics:true});assert.equal(emitted.diagnostics.length,0);
class WebviewController{constructor(){this.calls=[];}async runJavaScript(script){this.calls.push(script);return 'true';}}
const context=vm.createContext({webview:{WebviewController},MAX_QUESTION_MARKDOWN_LENGTH:20000});vm.runInContext(emitted.outputText,context);
const {QuestionMarkdownView,citationFocusController}=context.types;
const id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';let cases=0;
async function check(name,run){await run();cases++;}
(async()=>{
await check('native bridge rejects metadata-only and nonwhitelist body ids',async()=>{const h=new QuestionMarkdownView();h.pageReady=true;h.citations=[{citationId:id,label:'1'}];let opened=0;h.onCitation=()=>opened++;h.bodyMarkdown='普通正文';h.citationBridge.open(id);h.bodyMarkdown='[[citation:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb]]';h.citationBridge.open(id);assert.equal(opened,0);h.bodyMarkdown='[[citation:'+id+']]';h.citationBridge.open(id);assert.equal(opened,1);});
await check('unchanged body/result preserves Web reading position',async()=>{const h=new QuestionMarkdownView();h.pageReady=true;h.bodyMarkdown='[[citation:'+id+']]';h.citations=[{citationId:id,label:'1'}];await h.render_content();await h.render_content();assert.equal(h.controller.calls.length,1);h.citations=[{citationId:id,label:'更新标签'}];await h.render_content();assert.equal(h.controller.calls.length,2);});
await check('render failure can retry unchanged content',async()=>{const h=new QuestionMarkdownView();h.pageReady=true;h.bodyMarkdown='公式';await h.render_content();h.hasRenderError=true;await h.render_content();assert.equal(h.controller.calls.length,2);assert.equal(h.hasRenderError,false);});
await check('native focus restores original Web then detaches on disappearance',async()=>{const h=new QuestionMarkdownView();h.pageReady=true;h.bodyMarkdown='[[citation:'+id+']]';h.citations=[{citationId:id,label:'1'}];h.citationBridge.open(id);assert.equal(citationFocusController.restore(id),true);assert.ok(h.controller.calls[0].includes('restoreCitationFocus'));h.citationBridge.open(id);h.aboutToDisappear();assert.equal(citationFocusController.restore(id),false);});
console.log(JSON.stringify({cases,result:'PASS',boundary:'actual native-side bridge/render lifecycle with ArkWeb stub; no real WebView/device'}));
})().catch(e=>{console.error(e);process.exitCode=1;});
