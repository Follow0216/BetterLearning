#!/usr/bin/env node
// Extracts actual page methods; bridge/network and native UI are test doubles.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const root = path.resolve(__dirname, '../../../..');
const base = path.join(root, 'client/apps/tablet/src/main/ets');
function parse(name) {
  const file = path.join(base, name);
  const sf = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(sf.parseDiagnostics.length, 0, name + ': ArkTS parse diagnostics');
  return sf;
}
const library = parse('components/LibraryPane.ets');
const index = parse('pages/Index.ets');
const answer = parse('components/AnswerWorkspace.ets');
parse('components/TabletShell.ets');
const bridge = parse('features/tutor/TabletTutorBridge.ets');
const phase = bridge.statements.find(s => s.name?.text === 'TabletTutorPhase').getText(bridge).replace(/^export\s+/, '');
function extract(sf, name, wanted) {
  const st = sf.statements.find(s => s.name?.text === name);
  const names = new Set(wanted);
  const result = st.members.filter(m => ts.isMethodDeclaration(m) && names.delete(m.name.getText(sf)))
    .map(m => m.getText(sf)).join('\n');
  assert.equal(names.size, 0, 'Missing actual page methods: ' + [...names]);
  return result;
}
const methods = extract(library, 'LibraryPane', ['tutor_eligible', 'sync_tutor', 'tutor_context_current',
  'stop_tutor', 'dispose_tutor', 'reset_answer_view', 'apply_canvas_state', 'close_detail',
  'aboutToDisappear', 'expire_session', 'answer_callback_current', 'flush_then_close', 'is_detail_request_current', 'run_answer_action']);
const indexMethods = extract(index, 'Index', ['onPageShow', 'onPageHide', 'aboutToDisappear', 'handle_signed_out']);
const answerMethods = extract(answer, 'AnswerWorkspace', ['tutor_hint', 'tutor_hint_color']);
const code = `${phase}
class LibraryHarness {
  constructor() {
    this.alive=true; this.active=true; this.detail_loading=false; this.detail_error='';
    this.canvas_ready=true; this.detail={questionId:'11'}; this.open_question_id='11';
    this.tutor_phase=TabletTutorPhase.STOPPED; this.tutor_bridge=null; this.tutor_revision=0;
    this.tutor_question_id=''; this.tutor_token=''; this.tutor_started=false;
    this.detail_seq=1; this.answer_mount_revision=1; this.list_seq=0; this.pending_action=''; this.canvas_busy=false;
    this.expirations=0; this.answer_modes=[]; this.canvas_feedback_error=''; this.feedback_text=''; this.feedback_error=false;
    this.on_session_expired=()=>{this.expirations++;};
    this.on_answer_mode_changed=active=>this.answer_modes.push(active);
    this.canvas_controller={flushLocal:async()=>({isSuccess:true,data:true})};
    this.backController={attach:()=>{},detach:()=>{}};
  }
  ${methods}
}
class IndexHarness { ${indexMethods} }
class AnswerHarness { ${answerMethods} }
globalThis.LibraryHarness=LibraryHarness; globalThis.IndexHarness=IndexHarness;
globalThis.AnswerHarness=AnswerHarness; globalThis.Phase=TabletTutorPhase;
`;
let token = 'account-A-token';
const instances = [];
class FakeBridge {
  constructor(controller, api, onChange, onExpired) {
    this.controller=controller; this.onChange=onChange; this.onExpired=onExpired;
    this.starts=[]; this.stops=0; this.disposals=0; instances.push(this);
  }
  start(qid) { this.starts.push(qid); this.qid=qid; this.publish('READY'); }
  stop() { this.stops++; this.publish('STOPPED'); }
  dispose() { this.disposals++; }
  publish(phase, questionId=this.qid) { this.onChange({phase,questionId,message:'unsafe provider detail'}); }
}
const sandbox = { TabletV2TutorBridge:FakeBridge, SharedTabletV2TutorApi:class {},
  TABLET_COLOR_SUCCESS:'success', TABLET_COLOR_MUTED:'muted', TABLET_COLOR_PRIMARY:'primary',
  AnswerState:{NOT_STARTED:'NOT_STARTED'},
  authSession:{read_access_token:()=>token,has_valid_session:()=>token!==null,
    matches_access_token:t=>t===token,clear_session:()=>{token=null;},
    clear_session_if_token:t=>{if(t!==token)return false;token=null;return true;}} };
vm.createContext(sandbox);
const compiled = ts.transpileModule(code, {compilerOptions:{target:ts.ScriptTarget.ES2020},reportDiagnostics:true});
assert.equal(compiled.diagnostics.length,0);
vm.runInContext(compiled.outputText,sandbox);
const {LibraryHarness,IndexHarness,AnswerHarness,Phase}=sandbox;
let scenarios=0;
async function check(name,run) { token='account-A-token'; try{await run();scenarios++;}catch(e){throw new Error(name+': '+e.message,{cause:e});} }
async function main() {
  for (const field of ['alive','active','canvas_ready']) {
    await check('no start when '+field+' false',()=>{const h=new LibraryHarness();h[field]=false;h.sync_tutor();assert.equal(h.tutor_bridge,null);});
  }
  for (const [field,value] of [['detail_loading',true],['detail_error','failed'],['detail',null],['open_question_id','12']]) {
    await check('no start for invalid detail '+field,()=>{const h=new LibraryHarness();h[field]=value;h.sync_tutor();assert.equal(h.tutor_bridge,null);});
  }
  await check('no start without token',()=>{const h=new LibraryHarness();token=null;h.sync_tutor();assert.equal(h.tutor_bridge,null);});
  await check('same context starts once',()=>{const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;h.sync_tutor();assert.equal(b.starts.length,1);assert.equal(h.tutor_phase,Phase.READY);});
  await check('background stop retains instance, clears UI, resumes same instance',()=>{
    const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;b.publish(Phase.UPLOADING);
    h.active=false;h.sync_tutor();assert.equal(b.stops,1);assert.equal(h.tutor_phase,Phase.STOPPED);
    b.publish(Phase.SUCCEEDED);assert.equal(h.tutor_phase,Phase.STOPPED);
    h.active=true;h.sync_tutor();assert.equal(h.tutor_bridge,b);assert.equal(b.starts.length,2);
  });
  await check('question change disposes old, late callbacks ignored',()=>{
    const h=new LibraryHarness();h.sync_tutor();const old=h.tutor_bridge;
    h.open_question_id='12';h.detail={questionId:'12'};h.sync_tutor();const next=h.tutor_bridge;
    assert.notEqual(next,old);assert.equal(old.disposals,1);
    old.publish(Phase.SUCCEEDED,'11');old.onExpired();assert.equal(h.expirations,0);assert.equal(h.tutor_phase,Phase.READY);
    next.publish(Phase.UPLOADING,'11');assert.equal(h.tutor_phase,Phase.READY);
  });
  await check('token replacement rebuilds and rejects old state',()=>{
    const h=new LibraryHarness();h.sync_tutor();const old=h.tutor_bridge;token='account-B-token';
    old.publish(Phase.SUCCEEDED);old.onExpired();assert.equal(h.expirations,0);
    h.sync_tutor();assert.notEqual(h.tutor_bridge,old);assert.equal(old.disposals,1);
  });
  await check('401 after conditional token clear follows existing logout',()=>{
    const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;token=null;b.onExpired();
    assert.equal(h.expirations,1);assert.equal(h.alive,false);assert.equal(h.tutor_bridge,null);assert.equal(b.disposals,1);
  });
  await check('return clears and permits new instance',()=>{
    const h=new LibraryHarness();h.sync_tutor();const old=h.tutor_bridge;h.close_detail();
    assert.equal(old.disposals,1);assert.equal(h.tutor_phase,Phase.STOPPED);assert.equal(h.open_question_id,'');
    old.publish(Phase.SUCCEEDED);assert.equal(h.tutor_phase,Phase.STOPPED);
    h.open_question_id='11';h.detail={questionId:'11'};h.canvas_ready=true;h.sync_tutor();assert.notEqual(h.tutor_bridge,old);
  });
  await check('unmount disposes and rejects stale callbacks',()=>{const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;h.aboutToDisappear();b.publish(Phase.SUCCEEDED);b.onExpired();assert.equal(h.expirations,0);assert.equal(h.tutor_phase,Phase.STOPPED);});
  await check('flush failure stays on current detail and retains bridge',async()=>{
    const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;h.closing=true;
    h.canvas_controller.flushLocal=async()=>({isSuccess:false,data:null});
    await h.flush_then_close(token,'11',1);assert.equal(h.tutor_bridge,b);assert.equal(b.disposals,0);assert.equal(h.open_question_id,'11');assert.equal(h.feedback_error,true);
  });
  await check('successful flush closes and disposes',async()=>{const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;h.closing=true;await h.flush_then_close(token,'11',1);assert.equal(b.disposals,1);assert.equal(h.open_question_id,'');});
  await check('page show/hide and logout state',()=>{const h=new IndexHarness();h.onPageShow();assert.equal(h.page_visible,true);h.onPageHide();assert.equal(h.page_visible,false);h.onPageShow();h.aboutToDisappear();assert.equal(h.page_visible,false);h.handle_signed_out();assert.equal(h.signed_in,false);assert.equal(token,null);});
  await check('canvas readiness controls startup',()=>{const h=new LibraryHarness();h.canvas_ready=false;h.sync_tutor();assert.equal(h.tutor_bridge,null);h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false});assert.ok(h.tutor_bridge);});
  await check('old answer callbacks fail revision/detail ownership gate',()=>{const h=new LibraryHarness();assert.equal(h.answer_callback_current(1),true);assert.equal(h.answer_callback_current(0),false);h.detail={questionId:'12'};assert.equal(h.answer_callback_current(1),false);h.detail={questionId:'11'};h.detail_loading=true;assert.equal(h.answer_callback_current(1),false);});
  await check('save request revision does not remount answer or invalidate canvas callbacks',async()=>{
    const h=new LibraryHarness();h.sync_tutor();const b=h.tutor_bridge;const mount=h.answer_mount_revision;
    h.canvas_controller.save=async()=>null;
    await h.run_answer_action('save');
    assert.equal(h.detail_seq,2);assert.equal(h.answer_mount_revision,mount);
    assert.equal(h.answer_callback_current(mount),true);assert.equal(h.tutor_bridge,b);assert.equal(b.disposals,0);
    h.reset_answer_view();assert.equal(h.answer_callback_current(mount),false);
  });
  for (const phase of Object.values(Phase)) {
    await check('safe status text '+phase,()=>{const h=new AnswerHarness();h.tutor_phase=phase;const text=h.tutor_hint();assert.equal(text.includes('unsafe'),false);if([Phase.READY,Phase.STOPPED].includes(phase))assert.equal(text,'');else assert.ok(text.length>0);});
  }
  await check('canvas warning clears when public refresh recovers',()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'cloud conflict',isBusy:false});
    assert.equal(h.feedback_text,'cloud conflict'); assert.equal(h.feedback_error,true);
    h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false});
    assert.equal(h.feedback_text,''); assert.equal(h.feedback_error,false); assert.equal(h.canvas_feedback_error,'');
  });
  await check('failed refresh retains its current canvas failure',()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'cloud conflict',isBusy:false});
    h.apply_canvas_state({isReady:false,errorMessage:'download failed',isBusy:false});
    assert.equal(h.feedback_text,'download failed'); assert.equal(h.feedback_error,true);
  });
  await check('empty canvas state cannot clear an independent error',()=>{
    const h=new LibraryHarness(); h.feedback_text='save failed'; h.feedback_error=true;
    h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false}); assert.equal(h.feedback_text,'save failed'); assert.equal(h.feedback_error,true);
  });
  await check('independent replacement survives clearing an old canvas warning',()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'cloud conflict',isBusy:false});
    h.feedback_text='close failed'; h.feedback_error=true; h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false});
    assert.equal(h.feedback_text,'close failed'); assert.equal(h.feedback_error,true); assert.equal(h.canvas_feedback_error,'');
  });
  await check('pending action preserves existing warning ownership semantics',()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'cloud conflict',isBusy:false});
    h.pending_action='save'; h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false});
    assert.equal(h.feedback_text,'cloud conflict'); assert.equal(h.canvas_feedback_error,'cloud conflict');
  });
  await check('answer reset clears tracked canvas warning',()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'cloud conflict',isBusy:false}); h.reset_answer_view();
    assert.equal(h.canvas_feedback_error,''); assert.equal(h.feedback_text,''); assert.equal(h.feedback_error,false);
  });
  await check('actual independent action failure is not cleared by empty canvas state',async()=>{
    const h=new LibraryHarness(); h.apply_canvas_state({isReady:true,errorMessage:'操作未完成，请重试',isBusy:false});
    h.canvas_controller.save=async()=>null; await h.run_answer_action('save');
    assert.equal(h.canvas_feedback_error,''); assert.equal(h.feedback_text,'操作未完成，请重试');
    h.apply_canvas_state({isReady:true,errorMessage:'',isBusy:false}); assert.equal(h.feedback_text,'操作未完成，请重试'); assert.equal(h.feedback_error,true);
  });
  const shell=fs.readFileSync(path.join(base,'components/TabletShell.ets'),'utf8');
  assert.ok(shell.includes('active: this.foreground && this.active_tab === TabletTab.LIBRARY'));
  assert.ok(fs.readFileSync(path.join(base,'pages/Index.ets'),'utf8').includes('foreground: this.page_visible'));
  assert.equal((fs.readFileSync(path.join(base,'components/LibraryPane.ets'),'utf8').match(/if \(this.answer_callback_current\(revision\)\)/g)||[]).length,5);
  console.log(JSON.stringify({scenarios,result:'PASS',parseDiagnostics:0,
    boundary:'actual page methods with Bridge/network/native UI doubles; not a device/build/E2E result'}));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
