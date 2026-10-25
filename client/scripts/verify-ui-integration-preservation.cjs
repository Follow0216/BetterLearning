#!/usr/bin/env node
// Read-only preservation guard for the approved incoming UI/account/toast delivery.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..');
const incoming='8944f092e3cada4443c8e18c47709e522ff309dc';
const loader=process.env.BETTERLEARNING_ETS_LOADER||'/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts=require(path.join(loader,'node_modules/typescript'));
const options=ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const exact=[
 'web', '.env.example', 'client/build-profile.json5',
 'client/apps/phone/src/main/ets/components/AccountPage.ets',
 'client/apps/phone/src/main/ets/components/ManualEntry.ets',
 'client/apps/phone/src/main/ets/components/QuestionDetailPage.ets',
 'client/apps/phone/src/main/ets/components/QuestionLibrary.ets',
 'client/apps/phone/src/main/ets/components/PhoneAnswerPage.ets',
 'client/apps/phone/src/main/ets/components/AnswerTileViewer.ets',
 'client/apps/phone/src/main/ets/model/PhoneHomeState.ets',
 'client/apps/phone/src/main/ets/theme',
 'client/apps/phone/src/main/ets/features/ocr',
 'client/common/src/main/ets/components/feedback',
 'client/common/src/main/ets/features/auth/AuthModels.ets',
 'client/common/src/main/ets/features/auth/AuthSession.ets',
 'client/common/src/main/ets/features/auth/AuthValidation.ets',
 'client/common/src/main/ets/features/auth/AuthScreen.ets',
 'docs/testing/toast-account-v1','docs/testing/phone-nav-v1-20261025',
 'docs/testing/phone-account-20261025','docs/testing/user-account-20261025',
 'docs/testing/toast-common-20261025','docs/testing/toast-native-20261025',
 'docs/testing/phone-ux-20261025'
];
let cases=0;
for(const item of exact){const diff=execFileSync('git',['diff','--name-only',incoming,'--',item],{cwd:root,encoding:'utf8'}).trim();assert.equal(diff,'','must preserve incoming bytes: '+item+'\n'+diff);cases++;}
function methods(text,file){const ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);assert.equal(ast.parseDiagnostics.length,0,file);const component=ast.statements.find(n=>n.kind===ts.SyntaxKind.StructDeclaration);assert.ok(component,file);return new Map(component.members.filter(ts.isMethodDeclaration).map(n=>[n.name.getText(ast),n.getText(ast)]));}
const builders={
 'client/apps/phone/src/main/ets/components/PhoneApp.ets':['bottom_nav','nav_item','capture_nav_item'],
 'client/apps/phone/src/main/ets/components/PhoneHomePage.ets':['header','single_device','image_action','disabled_send','mode_picker','mode_option']
};
for(const [file,names] of Object.entries(builders)){const old=methods(execFileSync('git',['show',incoming+':'+file],{cwd:root,encoding:'utf8'}),file);const current=methods(fs.readFileSync(path.join(root,file),'utf8'),file);for(const name of names){assert.ok(old.has(name),name);assert.equal(current.get(name),old.get(name),'incoming UI Builder must remain exact: '+name);cases++;}}
const tag=execFileSync('git',['rev-parse','mvp-m4-toast-account-v1^{}'],{cwd:root,encoding:'utf8'}).trim();assert.equal(tag,incoming);cases++;
console.log(JSON.stringify({cases,result:'PASS',incoming,boundary:'exact incoming file/tree and UI Builder source preservation; no rendering, build, service or device'}));
