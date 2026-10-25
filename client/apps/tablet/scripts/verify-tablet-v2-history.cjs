#!/usr/bin/env node
// Execute actual history request methods with delayed responses and session changes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader,'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const file = path.resolve(__dirname,'../src/main/ets/features/tutor/TabletV2HistoryView.ets');
const sf = ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
assert.equal(sf.parseDiagnostics.length,0);
const cls = sf.statements.find(s => s.name?.text === 'TabletV2HistoryView');
const wanted = new Set(['current','fail','reload','loadPage','openRequest']);
const methods = cls.members.filter(m => ts.isMethodDeclaration(m) && wanted.delete(m.name.getText(sf))).map(m => m.getText(sf)).join('\n');
assert.equal(wanted.size,0);
const code = ts.transpileModule('class Harness {'+methods+'}\nglobalThis.Harness=Harness;',
  {compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
const good = data => ({isSuccess:true,data,error:null});
let account = 'account-A'; let token = 'token-A'; let cleared = 0;
const sandbox = {authSession:{read_access_token:()=>token,read_user:()=>({accountId:account}),
  clear_session_if_token:value=>{if(value!==token)return false;token=null;cleared++;return true;}},
  AuthError:{protocol_failure:message=>({message,is_unauthorized:()=>false})}};
vm.createContext(sandbox);vm.runInContext(code,sandbox);
function deferred() { let resolve; const promise = new Promise(r=>{resolve=r;});return {promise,resolve}; }
function harness(api) { const h=new sandbox.Harness(); Object.assign(h,{alive:true,epoch:0,detailEpoch:0,
  selectedRequestId:'',token:'token-A',accountId:'account-A',questionId:'11',requests:[],detail:null,
  nextCursor:null,loading:false,message:'',api,onUnauthorized:()=>{h.signedOut=true;}}); return h; }
async function main() {
  const old = deferred(), fresh = deferred(); let calls=0;
  const h=harness({listRequests:()=>++calls===1?old.promise:fresh.promise});
  const first=h.reload(); const second=h.reload();
  fresh.resolve(good({items:[{requestId:'new',questionId:'11'}],nextCursor:null})); await second;
  old.resolve(good({items:[{requestId:'old',questionId:'11'}],nextCursor:null})); await first;
  assert.equal(h.loading,false);assert.equal(h.requests.length,1);assert.equal(h.requests[0].requestId,'new');
  const a=deferred(),b=deferred();
  h.api.getRequest=(_t,_q,id)=>id==='A'?a.promise:b.promise;
  const openA=h.openRequest('A'),openB=h.openRequest('B');
  b.resolve(good({request:{requestId:'B',questionId:'11'}}));await openB;
  a.resolve(good({request:{requestId:'A',questionId:'11'}}));await openA;
  assert.equal(h.detail.request.requestId,'B');assert.equal(h.loading,false);
  const late=deferred(); h.api.getRequest=()=>late.promise;
  const oldDetail=h.openRequest('C');account='account-B';
  late.resolve(good({request:{requestId:'C',questionId:'11'}}));await oldDetail;
  assert.equal(h.detail,null);account='account-A';
  h.requests=[{requestId:'old'}];h.detail={request:{requestId:'old'}};
  h.fail({message:'unauthorized',is_unauthorized:()=>true});
  assert.equal(h.requests.length,0);assert.equal(h.detail,null);assert.equal(cleared,1);assert.equal(h.signedOut,true);
  console.log(JSON.stringify({scenarios:4,result:'PASS',boundary:'actual history async methods with delayed API/session doubles; no native ArkUI'}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
