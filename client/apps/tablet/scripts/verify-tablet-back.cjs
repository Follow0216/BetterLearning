#!/usr/bin/env node
// Execute actual Tablet back handlers and common note back logic with a shared controller.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader,'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const root = path.resolve(__dirname,'../../../../');
function parsed(relative) {
  const file=path.join(root,relative);
  const ast=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
  assert.equal(ast.parseDiagnostics.length,0,relative);return ast;
}
function member(relative,name,kind) {
  const ast=parsed(relative),cls=ast.statements.find(s=>s.name?.text===name);
  const target=cls.members.find(m=>m.name?.getText(ast)===kind);
  assert.ok(target,relative+' '+kind);return target.getText(ast);
}
const backFile='client/apps/tablet/src/main/ets/components/TabletBackController.ets';
const backAst=parsed(backFile);
const controller=backAst.statements.find(s=>s.name?.text==='TabletBackController').getText(backAst).replace(/^export\s+/, '');
const shell='client/apps/tablet/src/main/ets/components/TabletShell.ets';
const answer='client/apps/tablet/src/main/ets/components/AnswerWorkspace.ets';
const history='client/apps/tablet/src/main/ets/features/tutor/TabletV2HistoryView.ets';
const notes='client/common/src/main/ets/features/multiagent/LearningNotesView.ets';
const index='client/apps/tablet/src/main/ets/pages/Index.ets';
const shellAst=parsed(shell);
const tabEnum=shellAst.statements.find(s=>s.name?.text==='TabletTab').getText(shellAst);
const code=tabEnum+'\n'+controller+
  '\nclass Shell {'+member(shell,'TabletShell','backHandler')+'}\n'+
  'class Answer {'+member(answer,'AnswerWorkspace','backHandler')+'}\n'+
  'class History {'+member(history,'TabletV2HistoryView','backHandler')+member(history,'TabletV2HistoryView','goBack')+
    member(history,'TabletV2HistoryView','stopDetailPolling')+'}\n'+
  'class Notes {'+member(notes,'LearningNotesView','goBack')+'}\n'+
  'class Page {'+member(index,'Index','onBackPress')+'}\n'+
  'globalThis.Classes={TabletBackController,Shell,Answer,History,Notes,Page};';
const js=ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
const sandbox={focusControl:{requestFocus:()=>{}},clearInterval:()=>{}};vm.createContext(sandbox);vm.runInContext(js,sandbox);
const {TabletBackController,Shell,Answer,History,Notes,Page}=sandbox.Classes;
const controllerInstance=new TabletBackController(),page=new Page();page.backController=controllerInstance;
const shellInstance=new Shell();shellInstance.foreground=true;shellInstance.active_tab='library';shellInstance.notesBackToken=0;
controllerInstance.attach(shellInstance.backHandler);
const answerInstance=new Answer();answerInstance.active=true;answerInstance.show_history=true;
let answerBack=0;answerInstance.on_back=()=>{answerBack++;};
controllerInstance.attach(answerInstance.backHandler);
const historyInstance=new History();historyInstance.active=true;historyInstance.selectedRequestId='request-A';
historyInstance.detail={request:{requestId:'request-A'}};historyInstance.detailEpoch=0;historyInstance.loading=false;
historyInstance.citationDialog=null;let historyBack=0;historyInstance.onBack=()=>{historyBack++;};
controllerInstance.attach(historyInstance.backHandler);
assert.equal(page.onBackPress(),true);assert.equal(historyInstance.detail,null);assert.equal(historyInstance.selectedRequestId,'');
assert.equal(historyBack,0);assert.equal(answerInstance.show_history,true);
assert.equal(page.onBackPress(),true);assert.equal(historyBack,1);
controllerInstance.detach(historyInstance.backHandler);
assert.equal(page.onBackPress(),true);assert.equal(answerInstance.show_history,false);
assert.equal(page.onBackPress(),true);assert.equal(answerBack,1);
controllerInstance.detach(answerInstance.backHandler);
shellInstance.active_tab='notes';
const notesInstance=new Notes();notesInstance.generation=0;notesInstance.loading=true;notesInstance.selected={noteId:'note'};
let notesBack=0;notesInstance.onBack=()=>{notesBack++;shellInstance.active_tab='library';};
assert.equal(page.onBackPress(),true);notesInstance.goBack();assert.equal(notesInstance.selected,null);
assert.equal(notesInstance.loading,false);assert.equal(notesBack,0);
assert.equal(page.onBackPress(),true);notesInstance.goBack();assert.equal(notesBack,1);
assert.equal(shellInstance.active_tab,'library');
shellInstance.foreground=false;assert.equal(page.onBackPress(),false);
controllerInstance.detach(shellInstance.backHandler);assert.equal(page.onBackPress(),false);
console.log(JSON.stringify({scenarios:8,result:'PASS',boundary:'actual system-back handlers and note logic; no native back event/dialog runtime'}));
