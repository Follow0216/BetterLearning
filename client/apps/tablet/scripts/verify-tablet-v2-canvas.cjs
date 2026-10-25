#!/usr/bin/env node
// Execute the new workspace freeze methods with an in-memory exporter and disk.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader,'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader,'tsconfig.json'),ts.sys.readFile).config.compilerOptions;
const file = path.resolve(__dirname,'../src/main/ets/features/canvas/TabletCanvasWorkspace.ets');
const sf = ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.ETS,options);
assert.equal(sf.parseDiagnostics.length,0);
const context = sf.statements.find(s => s.name?.text === 'V2FreezeContext').getText(sf);
const cls = sf.statements.find(s => s.name?.text === 'TabletCanvasWorkspace');
const wanted = new Set(['v2_current','cancel_v2_context','cancel_v2_snapshot','read_v2_frozen','freeze_v2_at_pen_up',
  'export_v2','begin_upload','finish_upload']);
const methods = cls.members.filter(m => ts.isMethodDeclaration(m) && wanted.delete(m.name.getText(sf))).map(m => m.getText(sf)).join('\n');
assert.equal(wanted.size,0);
const code = ts.transpileModule(context + '\nlet activeCanvasUpload = null; class Harness {\n' + methods + '\n}\nglobalThis.Harness=Harness;',
  {compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
const good = data => ({isSuccess:true,data,error:null});
const fail = message => ({isSuccess:false,data:null,error:{message}});
const uuid = '12345678-1234-1234-1234-123456789abc';
const doc = {schemaVersion:1,questionLayout:{questionBodySha256:'body-hash',rasterBlocks:[
  {partName:'q',sha256:'raster',pixelWidth:2000,pixelHeight:1000}]},strokes:[{penKind:'fountain',points:[{x:-120,y:800,pressure:0.7}]}]};
const disk = new Map(); let account = 'account-A'; let token = 'token-A'; let exportCount = 0;
const sandbox = {
  authSession:{read_user:()=>({accountId:account}),matches_access_token:t=>t===token,has_valid_session:()=>true},
  AuthResult:{success:good,failure:error=>({isSuccess:false,data:null,error})},
  AuthError:{local_validation:message=>({message})},
  canvas_exists:path=>disk.has(path), canvas_read:path=>disk.get(path), canvas_text:bytes=>bytes,
  validate_answer_manifest:manifest=>{assert.equal(manifest.schemaVersion,1);},
  export_canvas:async (captured,files,base,directory,active,id)=>{
    assert.equal(captured,doc); assert.equal(captured.strokes[0].points[0].x,-120);
    assert.equal(captured.strokes[0].points[0].pressure,0.7); assert.equal(captured.strokes[0].penKind,'fountain');
    assert.equal(captured.questionLayout.rasterBlocks[0].pixelWidth,2000);
    assert.equal(base,null); assert.match(id,/^[0-9a-f-]{36}$/); assert.equal(active(),true);
    assert.ok(directory.includes('/tutor-v2/'+id)); exportCount++;
    const manifest = {schemaVersion:1,saveRequestId:id,baseFileRef:null,document:captured,blocks:[]};
    disk.set(directory+'/uploads/'+id+'/manifest.json',JSON.stringify(manifest));
    disk.set(directory+'/uploads/'+id+'/q.png','image');
    return {manifestJson:JSON.stringify(manifest),files:[{partName:'q',filePath:directory+'/uploads/'+id+'/q.png'}]};
  },
  console
};
vm.createContext(sandbox);vm.runInContext(code,sandbox);
function harness() {
  const h = new sandbox.Harness();
  Object.assign(h,{alive:true,questionId:'11',accountId:'account-A',token:'token-A',generation:1,mountKey:'mount',
    document:doc,store:{directory:'/private/app/account-A/11'},files:[{partName:'q',filePath:'/source/q.png'}],
    loading:false,rasterizing:false,bodyHash:'body-hash',v2Context:null,emit:()=>{}});
  return h;
}
async function main() {
  let captures = 0; const h = harness();
  h.freezeHandle = {captureAtPenUp:()=>{captures++;return Promise.resolve(good(doc));},cancel:()=>{}};
  const first = await h.freeze_v2_at_pen_up('11',uuid);
  assert.equal(first.isSuccess,true); assert.equal(captures,1); assert.equal(exportCount,1);
  const second = await h.freeze_v2_at_pen_up('11',uuid);
  assert.equal(second.data.manifestJson,first.data.manifestJson); assert.equal(captures,1); assert.equal(exportCount,1);
  h.cancel_v2_snapshot('11',uuid);
  const resumed = await h.freeze_v2_at_pen_up('11',uuid);
  assert.equal(resumed.isSuccess,true); assert.equal(resumed.data.manifestJson,first.data.manifestJson);
  assert.equal(captures,1); assert.equal(exportCount,1);
  account='account-B'; const blocked = await h.freeze_v2_at_pen_up('11',uuid);
  assert.equal(blocked.isSuccess,false); account='account-A';
  const wrong = await h.freeze_v2_at_pen_up('11','12345678-1234-1234-1234-123456789abd');
  assert.equal(wrong.isSuccess,true); assert.equal(exportCount,2);
  console.log(JSON.stringify({scenarios:5,result:'PASS',boundary:'actual TabletCanvasWorkspace freeze methods with mock exporter/disk; existing canvas tests cover geometry and pen data'}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
