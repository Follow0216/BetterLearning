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
const wanted = new Set(['current','fail','reload','loadPage','openRequest','refreshSelectedDetail',
  'activeChanged','stopDetailPolling','startDetailPolling','pollSelectedDetail']);
const methods = cls.members.filter(m => ts.isMethodDeclaration(m) && wanted.delete(m.name.getText(sf))).map(m => m.getText(sf)).join('\n');
assert.equal(wanted.size,0);
const stateFile=path.resolve(__dirname,'../../../../client/common/src/main/ets/features/multiagent/LearningV2State.ets');
const stateAst=ts.createSourceFile(stateFile,fs.readFileSync(stateFile,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
const stateFunctions=stateAst.statements.filter(s=>ts.isFunctionDeclaration(s)).map(s=>s.getText(stateAst).replace(/^export\s+/,'')).join('\n');
const code = ts.transpileModule(stateFunctions+'\nclass Harness {'+methods+'}\nglobalThis.Harness=Harness;'+
  '\nglobalThis.accept_v2_detail=accept_v2_detail;globalThis.v2_detail_is_active=v2_detail_is_active;',
  {compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
const good = data => ({isSuccess:true,data,error:null});
let account = 'account-A'; let token = 'token-A'; let cleared = 0; let timerId=0;
const timers=new Map();
const statuses=['WAITING_INPUT','QUEUED','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'];
const sandbox = {authSession:{read_access_token:()=>token,read_user:()=>({accountId:account}),
  clear_session_if_token:value=>{if(value!==token)return false;token=null;cleared++;return true;}},
  AuthError:{protocol_failure:message=>({message,is_unauthorized:()=>false})},
  V2TutorRequestStatus:Object.fromEntries(statuses.map(s=>[s,s])),
  V2RunStatus:Object.fromEntries(statuses.slice(1).map(s=>[s,s])),
  citationFocusController:{clear:()=>{}},
  setInterval:fn=>{const id=++timerId;timers.set(id,fn);return id;},clearInterval:id=>timers.delete(id)};
vm.createContext(sandbox);vm.runInContext(code,sandbox);
function deferred() { let resolve; const promise = new Promise(r=>{resolve=r;});return {promise,resolve}; }
function harness(api) { const h=new sandbox.Harness(); Object.assign(h,{alive:true,epoch:0,detailEpoch:0,
  selectedRequestId:'',token:'token-A',accountId:'account-A',questionId:'11',requests:[],detail:null,
  nextCursor:null,loading:false,message:'',active:true,detailTimer:-1,detailPollCount:0,detailInFlightEpoch:-1,
  api,onUnauthorized:()=>{h.signedOut=true;}}); return h; }
function detail(id,status='RUNNING',revision=1,runRevision=1) {
  const run={runId:'run-'+id,requestId:id,runNo:1,status,revision:runRevision};
  return {request:{requestId:id,questionId:'11',currentRunId:run.runId,status,revision},
    run,runs:[run],result:null};
}
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
  b.resolve(good(detail('B')));await openB;
  a.resolve(good(detail('A')));await openA;
  assert.equal(h.detail.request.requestId,'B');assert.equal(h.loading,false);
  const late=deferred(); h.api.getRequest=()=>late.promise;
  const oldDetail=h.openRequest('C');account='account-B';
  late.resolve(good(detail('C')));await oldDetail;
  assert.equal(h.detail,null);account='account-A';
  h.requests=[{requestId:'old'}];h.detail={request:{requestId:'old'}};
  h.fail({message:'unauthorized',is_unauthorized:()=>true});
  assert.equal(h.requests.length,0);assert.equal(h.detail,null);assert.equal(cleared,1);assert.equal(h.signedOut,true);
  token='token-A';account='account-A';
  const polling=harness({getRequest:async()=>good(detail('P','SUCCEEDED',2,2))});
  polling.selectedRequestId='P';polling.detail=detail('P');polling.startDetailPolling();
  assert.ok(polling.detailTimer>0);
  polling.pollSelectedDetail();await new Promise(resolve=>setImmediate(resolve));
  assert.equal(polling.detail.request.status,'SUCCEEDED');assert.equal(polling.detailTimer,-1);
  polling.api.getRequest=async()=>good(detail('P','RUNNING',1,1));
  await polling.refreshSelectedDetail(true);assert.equal(polling.detail.request.status,'SUCCEEDED');
  assert.equal(polling.selectedRequestId,'P');
  const oldForeground=deferred();const resume=harness({getRequest:()=>oldForeground.promise});
  resume.selectedRequestId='Q';resume.detail=detail('Q');resume.startDetailPolling();
  const oldPoll=resume.refreshSelectedDetail(false);resume.active=false;resume.activeChanged();
  oldForeground.resolve(good(detail('Q','SUCCEEDED',2,2)));await oldPoll;
  assert.equal(resume.detail.request.status,'RUNNING');assert.equal(resume.detailTimer,-1);
  resume.api.getRequest=async()=>good(detail('Q','SUCCEEDED',2,2));resume.active=true;resume.activeChanged();
  await new Promise(resolve=>setImmediate(resolve));assert.equal(resume.detail.request.status,'SUCCEEDED');
  const bounded=harness({getRequest:async()=>good(detail('R','RUNNING',1,1))});
  bounded.selectedRequestId='R';bounded.detail=detail('R');bounded.startDetailPolling();bounded.detailPollCount=600;
  bounded.pollSelectedDetail();assert.equal(bounded.detailTimer,-1);
  assert.match(bounded.message,/手动刷新/);
  await bounded.reload();assert.equal(bounded.selectedRequestId,'R');assert.equal(bounded.detail.request.status,'RUNNING');
  assert.ok(bounded.detailTimer>0);
  const current=detail('S','FAILED',2,2);
  const badRetry={request:{requestId:'S',questionId:'11',currentRunId:'run-S-2',status:'QUEUED',revision:3},
    run:{runId:'run-S-2',requestId:'S',runNo:2,status:'QUEUED',revision:1},
    runs:[{runId:'run-S-2',requestId:'S',runNo:2,status:'QUEUED',revision:1}],result:null};
  assert.equal(sandbox.accept_v2_detail(current,badRetry),false);
  const loadingOnBackground=harness({getRequest:async()=>good(detail('T'))});
  loadingOnBackground.selectedRequestId='T';loadingOnBackground.requests=[{requestId:'T'}];
  loadingOnBackground.active=false;loadingOnBackground.activeChanged();
  loadingOnBackground.active=true;loadingOnBackground.activeChanged();
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(loadingOnBackground.detail.request.requestId,'T');assert.ok(loadingOnBackground.detailTimer>0);
  console.log(JSON.stringify({scenarios:10,result:'PASS',boundary:'actual history async/foreground/poll methods and production revision guard; no native ArkUI'}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
