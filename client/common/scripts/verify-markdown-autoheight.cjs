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
await check('height bridge opts in and rejects stale render revision',async()=>{
  const h=new QuestionMarkdownView();h.autoHeight=true;h.aboutToAppear();h.pageReady=true;h.bodyMarkdown='第一篇';
  let height=0;h.onContentHeight=value=>height=value;await h.render_content();const first=h.renderRevision;
  h.citationBridge.resize(900,first);assert.equal(height,900);assert.equal(h.contentHeight,900);
  h.bodyMarkdown='第二篇';await h.render_content();h.citationBridge.resize(1800,first);assert.equal(h.contentHeight,900);
  h.citationBridge.resize(41,h.renderRevision);assert.equal(h.contentHeight,41);
  const current=h.renderRevision;await h.render_content();assert.equal(h.renderRevision,current);
  h.citationBridge.resize(45,current);assert.equal(h.contentHeight,45);
});
await check('default Tablet mode ignores height bridge and keeps renderer default mode',async()=>{
  const h=new QuestionMarkdownView();h.aboutToAppear();h.pageReady=true;h.bodyMarkdown='题面';await h.render_content();
  h.citationBridge.resize(400,h.renderRevision);assert.equal(h.contentHeight,48);assert.equal(h.autoHeight,false);
  assert.match(h.controller.calls[0],/,false,[0-9]+\)$/);
});
await check('invalid, detached, and over-surface heights cannot silently crop body',async()=>{
  const h=new QuestionMarkdownView();h.autoHeight=true;h.aboutToAppear();h.pageReady=true;h.bodyMarkdown='完整正文'.repeat(3000);
  await h.render_content();const revision=h.renderRevision;
  h.citationBridge.resize(NaN,revision);h.citationBridge.resize(-5,revision);assert.equal(h.contentHeight,48);
  let failures=0;h.onRenderError=()=>failures++;h.citationBridge.resize(25000,revision);
  assert.equal(h.hasRenderError,true);assert.equal(failures,1);assert.equal(h.bodyMarkdown.length,12000);
  h.aboutToDisappear();h.citationBridge.resize(100,revision);assert.equal(h.contentHeight,48);
});
await check('renderer measures intrinsic body and reports shrink with current revision',async()=>{
  const assets=path.join(__dirname,'../src/main/resources/rawfile/question-markdown');
  let intrinsic=1000,resize;const calls=[];const classes=new Set();
  const content={style:{},innerHTML:'',setAttribute(){},addEventListener(){},getBoundingClientRect:()=>({height:intrinsic})};
  const box={window:{markdownit:require(path.join(assets,'vendor/markdown-it.min.js')),texmath:require(path.join(assets,'vendor/texmath.js')),
    katex:require(path.join(assets,'vendor/katex.min.js')),scrollTo(){},getComputedStyle:()=>({paddingTop:'8px',paddingBottom:'8px'}),
    requestAnimationFrame:fn=>fn(),BetterLearningCitations:{resize:(height,revision)=>calls.push({height,revision})}},
    ResizeObserver:class{constructor(fn){resize=fn;}observe(){}},document:{getElementById:()=>content,body:{},
    documentElement:{style:{},classList:{toggle:(name,on)=>on?classes.add(name):classes.delete(name)}}}};
  vm.runInNewContext(fs.readFileSync(path.join(assets,'renderer.js'),'utf8'),box);
  assert.equal(box.window.BetterLearningQuestion.render('长文',15,false,[],true,8),true);
  assert.equal(calls.at(-1).height,1016);assert.equal(calls.at(-1).revision,8);assert.equal(classes.has('auto-height'),true);
  intrinsic=22;assert.equal(box.window.BetterLearningQuestion.render('短文',15,false,[],true,9),true);
  assert.equal(calls.at(-1).height,38);assert.equal(calls.at(-1).revision,9);
  intrinsic=30;resize();assert.equal(calls.at(-1).height,46);
  const count=calls.length;box.window.BetterLearningQuestion.render('默认',15,false,[]);resize();
  assert.equal(calls.length,count);assert.equal(classes.has('auto-height'),false);
});
console.log(JSON.stringify({cases,result:'PASS',boundary:'actual native bridge and renderer; DOM metrics/ArkWeb stubbed; separate browser evidence covers real layout'}));
})().catch(e=>{console.error(e);process.exitCode=1;});
