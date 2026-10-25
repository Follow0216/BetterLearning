#!/usr/bin/env node
// Execute the actual V2 bridge class with controlled async API/canvas ports.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const file = path.resolve(__dirname, '../src/main/ets/features/tutor/TabletV2TutorBridge.ets');
const source = fs.readFileSync(file, 'utf8');
const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS);
assert.equal(ast.parseDiagnostics.length, 0);
const actual = ast.statements.find(s => s.name?.text === 'TabletV2TutorBridge').getText(ast).replace(/^export\s+/, '');
const code = ts.transpileModule(actual + '\nglobalThis.Bridge = TabletV2TutorBridge;',
  { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText;
const good = data => ({ isSuccess: true, data, error: null });
const bad = message => ({ isSuccess: false, data: null, error: { message, is_unauthorized: () => false } });
const uuid = '12345678-1234-1234-1234-123456789abc';
const req = (qid = '11', status = 'WAITING_INPUT', file = null) =>
  ({ questionId: qid, requestId: uuid, status, snapshotFileRef: file, snapshotInput: {kind:'NEW_CAPTURE'}, currentRunId:null });
let token = 'token-A'; let account = 'account-A'; let interval;
const sandbox = {
  authSession: { read_access_token: () => token, read_user: () => ({ accountId: account }),
    matches_access_token: value => value === token, clear_session_if_token: value => { if (value !== token) return false; token = null; return true; } },
  AuthError: { protocol_failure: message => ({ message, is_unauthorized: () => false }),
    network_failure: message => ({ message, is_unauthorized: () => false }) },
  TabletTutorPhase: Object.fromEntries(['STOPPED','READY','FREEZING','UPLOADING','GENERATING','SUCCEEDED','FAILED','ERROR']
    .map(value => [value,value])),
  TabletTutorState: class { constructor(questionId, turnId, phase, message, error) { Object.assign(this,{questionId,turnId,phase,message,error}); } },
  setInterval: fn => { interval = fn; return 1; }, clearInterval: () => { interval = null; }, console
};
vm.createContext(sandbox); vm.runInContext(code,sandbox);
const settle = () => new Promise(resolve => setImmediate(resolve));
let scenarios = 0;
async function test(name, run) { token = 'token-A'; account = 'account-A'; await run(); scenarios++; }
async function main() {
  await test('pending freezes requestId once and uploads the exact independent package', async () => {
    let freezes = 0; let uploads = 0; const events = [];
    const package1 = { manifestJson: JSON.stringify({ saveRequestId: uuid, baseFileRef: null }), files: [] };
    const canvas = { freezeV2AtPenUp: async (q,id) => { assert.equal(q,'11'); assert.equal(id,uuid); freezes++; return good(package1); },
      cancelV2Snapshot: () => {} };
    const api = { pending: async () => good({request:req()}), get: async () => good({request:req()}),
      upload: async (t,q,id,upload,active) => { assert.equal(upload,package1); assert.equal(id,uuid); assert.equal(active(),true);
        uploads++; return good({request:req('11','QUEUED','file-ref')}); } };
    const bridge = new sandbox.Bridge(canvas,api,state => events.push(state));
    bridge.start('11'); await settle();
    assert.equal(freezes,1); assert.equal(uploads,1);
    assert.ok(events.some(state => state.phase === 'GENERATING'));
    bridge.stop();
  });
  await test('unknown upload reuses original package after detail confirms waiting', async () => {
    let freezes = 0; let uploads = 0;
    const package1 = { manifestJson: JSON.stringify({ saveRequestId: uuid, baseFileRef: null }), files: [] };
    const canvas = { freezeV2AtPenUp: async () => { freezes++; return good(package1); }, cancelV2Snapshot: () => {} };
    const api = { pending: async () => good({request:req()}), get: async () => good({request:req()}),
      upload: async () => { uploads++; return uploads === 1 ? bad('network') : good({request:req('11','QUEUED','file-ref')}); } };
    const bridge = new sandbox.Bridge(canvas,api,() => {});
    bridge.start('11'); await settle(); interval(); await settle();
    assert.equal(freezes,1); assert.equal(uploads,2); bridge.stop();
  });
  await test('question switch rejects late frozen result and upload', async () => {
    let resolveFreeze; let uploads = 0;
    const canvas = { freezeV2AtPenUp: () => new Promise(resolve => { resolveFreeze = resolve; }), cancelV2Snapshot: () => {} };
    const api = { pending: async () => good({request:req()}), get: async () => good({request:req()}),
      upload: async () => { uploads++; return good({request:req('11','QUEUED','file-ref')}); } };
    const bridge = new sandbox.Bridge(canvas,api,() => {});
    bridge.start('11'); await settle(); bridge.stop();
    bridge.start('12'); resolveFreeze(good({manifestJson:JSON.stringify({saveRequestId:uuid,baseFileRef:null}),files:[]}));
    await settle(); assert.equal(uploads,0); bridge.stop();
  });
  await test('account switch rejects late frozen result', async () => {
    let resolveFreeze; let uploads = 0;
    const canvas = { freezeV2AtPenUp: () => new Promise(resolve => { resolveFreeze = resolve; }), cancelV2Snapshot: () => {} };
    const api = { pending: async () => good({request:req()}), get: async () => good({request:req()}),
      upload: async () => { uploads++; return good({request:req('11','QUEUED','file-ref')}); } };
    const bridge = new sandbox.Bridge(canvas,api,() => {});
    bridge.start('11'); await settle(); account = 'account-B';
    resolveFreeze(good({manifestJson:JSON.stringify({saveRequestId:uuid,baseFileRef:null}),files:[]}));
    await settle(); assert.equal(uploads,0); bridge.stop();
  });
  console.log(JSON.stringify({scenarios,result:'PASS',boundary:'actual V2 bridge async behavior with mock canvas/API; no native filesystem/network/device'}));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
