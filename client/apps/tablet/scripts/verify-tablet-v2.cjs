#!/usr/bin/env node
// Run the real ArkTS bridge and state gate with controlled asynchronous ports.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const bridgeFile = path.resolve(__dirname, '../src/main/ets/features/tutor/TabletV2TutorBridge.ets');
const stateFile = path.resolve(__dirname, '../../../common/src/main/ets/features/multiagent/LearningV2State.ets');
function statement(file, name) {
  const ast = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS);
  assert.equal(ast.parseDiagnostics.length, 0, `${file} must parse`);
  const found = ast.statements.find(item => item.name?.text === name);
  assert.ok(found, `${name} missing from ${file}`);
  return found.getText(ast).replace(/^export\s+/, '');
}
const source = [statement(stateFile, 'terminal'), statement(stateFile, 'accept_v2_detail'),
  statement(bridgeFile, 'TabletV2TutorBridge'),
  'globalThis.Bridge = TabletV2TutorBridge; globalThis.acceptDetail = accept_v2_detail;'].join('\n');
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText;
const good = data => ({ isSuccess: true, data, error: null });
const bad = message => ({ isSuccess: false, data: null, error: { message, is_unauthorized: () => false } });
const ids = {
  old: '12345678-1234-1234-1234-123456789abc',
  next: '22345678-1234-1234-1234-123456789abc',
  run1: '32345678-1234-1234-1234-123456789abc',
  run2: '42345678-1234-1234-1234-123456789abc'
};
const statuses = ['WAITING_INPUT','QUEUED','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'];
const enums = Object.fromEntries(statuses.map(value => [value,value]));
let token = 'token-A'; let account = 'account-A'; let interval = null; let intervalMs = 0;
const sandbox = {
  V2TutorRequestStatus: enums, V2RunStatus: enums,
  authSession: { read_access_token: () => token, read_user: () => token === null ? null : ({ accountId: account }),
    matches_access_token: value => value === token,
    clear_session_if_token: value => { if (value !== token) return false; token = null; return true; } },
  AuthError: { protocol_failure: message => ({ message, is_unauthorized: () => false }),
    network_failure: message => ({ message, is_unauthorized: () => false }) },
  TabletTutorPhase: Object.fromEntries(['STOPPED','READY','FREEZING','UPLOADING','GENERATING','SUCCEEDED','FAILED','ERROR']
    .map(value => [value,value])),
  TabletTutorState: class { constructor(questionId, turnId, phase, message, error) {
    Object.assign(this,{questionId,turnId,phase,message,error}); } },
  setInterval: (fn, ms) => { interval = fn; intervalMs = ms; return 1; },
  clearInterval: () => { interval = null; }, console
};
vm.createContext(sandbox); vm.runInContext(code,sandbox);
const pkg = id => ({ manifestJson: JSON.stringify({ saveRequestId: id, baseFileRef: null }), files: [] });
function request({ id = ids.old, qid = '11', status = 'WAITING_INPUT', revision = 0,
  runId = null, file = null } = {}) {
  return { requestId: id, questionId: qid, prompt: '如何解题', mode: 'HINT',
    snapshotInput: { kind: 'NEW_CAPTURE' }, snapshotOriginRequestId: null,
    snapshotFileRef: file, currentRunId: runId, status, revision,
    createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z' };
}
function run(runId, status, revision, runNo, retryOfRunId = null, id = ids.old) {
  return { runId, requestId: id, status, revision, runNo, retryOfRunId, steps: [] };
}
function detail(status, revision, currentRun, runs = currentRun ? [currentRun] : [], id = ids.old) {
  return { request: request({ id, status, revision, runId: currentRun?.runId ?? null,
    file: currentRun ? ids.next : null }), run: currentRun, runs,
    result: status === 'SUCCEEDED' ? { resultId: ids.next, mode: 'HINT', status: 'VERIFIED',
      bodyMarkdown: '解答', citations: [], createdAt: '2026-10-25T00:00:00Z' } : null };
}
const pending = value => good({ request: value });
const tick = async () => { assert.equal(typeof interval, 'function'); interval(); await settle(); };
const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };
const defer = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
let scenarios = 0;
async function test(name, fn) {
  token = 'token-A'; account = 'account-A'; interval = null; intervalMs = 0;
  await fn(); scenarios++; console.log('PASS ' + name);
}
async function main() {
  await test('freeze once; same pending first and GET before reusing package', async () => {
    let freezes = 0; let uploads = 0; let gets = 0; let polls = 0;
    const packet = pkg(ids.old); const events = [];
    const canvas = { freezeV2AtPenUp: async () => { freezes++; return good(packet); }, cancelV2Snapshot: () => {} };
    const queued = detail('QUEUED',1,run(ids.run1,'QUEUED',0,1));
    const api = { pending: async () => { polls++; return pending(request()); },
      get: async () => { gets++; return good(detail('WAITING_INPUT',0,null)); },
      upload: async (_t,_q,id,upload) => { assert.equal(id,ids.old); assert.equal(upload,packet);
        uploads++; return uploads === 1 ? bad('unknown upload') : good(queued); } };
    const bridge = new sandbox.Bridge(canvas,api,state => events.push(state));
    bridge.start('11'); await settle(); await tick();
    assert.equal(intervalMs,2000); assert.equal(polls,2); assert.equal(gets,1);
    assert.equal(freezes,1); assert.equal(uploads,2);
    assert.equal(events.at(-1).phase,'GENERATING'); bridge.stop();
  });
  await test('FAILED run1 becomes SUCCEEDED run2 on same request without writes', async () => {
    let freezes = 0; let uploads = 0; let gets = 0; let polls = 0; const events = [];
    const first = run(ids.run1,'FAILED',3,1);
    const second = run(ids.run2,'SUCCEEDED',1,2,ids.run1);
    const api = { pending: async () => pending(polls++ === 0 ? request() : null),
      upload: async () => { uploads++; return good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))); },
      get: async () => good(gets++ === 0 ? detail('FAILED',3,first) : detail('SUCCEEDED',5,second,[first,second])) };
    const canvas = { freezeV2AtPenUp: async () => { freezes++; return good(pkg(ids.old)); }, cancelV2Snapshot: () => {} };
    const bridge = new sandbox.Bridge(canvas,api,state => events.push(state));
    bridge.start('11'); await settle(); await tick(); await tick();
    assert.equal(events.at(-1).phase,'SUCCEEDED');
    assert.equal(freezes,1); assert.equal(uploads,1); assert.equal(gets,2); assert.equal(polls,3);
    assert.equal(intervalMs,2000); bridge.stop();
  });
  await test('queued and running retry advances; old run and revision cannot overwrite', async () => {
    const oneQueued = run(ids.run1,'QUEUED',0,1);
    const oneRunning = run(ids.run1,'RUNNING',1,1);
    const oneFailed = run(ids.run1,'FAILED',2,1);
    const twoQueued = run(ids.run2,'QUEUED',0,2,ids.run1);
    const twoRunning = run(ids.run2,'RUNNING',1,2,ids.run1);
    const twoSuccess = run(ids.run2,'SUCCEEDED',2,2,ids.run1);
    const responses = [detail('RUNNING',2,oneRunning), detail('FAILED',3,oneFailed),
      detail('QUEUED',4,twoQueued,[oneFailed,twoQueued]), detail('FAILED',3,oneFailed),
      detail('RUNNING',5,twoRunning,[oneFailed,twoRunning]), detail('QUEUED',4,twoQueued,[oneFailed,twoQueued]),
      detail('SUCCEEDED',6,twoSuccess,[oneFailed,twoSuccess])];
    let calls = 0; const phases = [];
    const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)), cancelV2Snapshot: () => {} },
      { pending: async () => pending(calls === 0 ? request() : null),
        upload: async () => good(detail('QUEUED',1,oneQueued)),
        get: async () => good(responses[calls++]) }, state => phases.push(state.phase));
    bridge.start('11'); await settle();
    for (let i = 0; i < responses.length; i++) await tick();
    assert.equal(calls,responses.length); assert.equal(phases.filter(value => value === 'FAILED').length,1);
    assert.equal(phases.at(-1),'SUCCEEDED'); bridge.stop();
  });
  await test('new pending wins over old GET and keeps packets separate', async () => {
    let pendingCalls = 0; const frozen = []; const uploaded = []; const cancelled = []; const got = [];
    const oldQueued = detail('QUEUED',1,run(ids.run1,'QUEUED',0,1));
    const newQueued = detail('QUEUED',1,run(ids.run1,'QUEUED',0,1,null,ids.next),[run(ids.run1,'QUEUED',0,1,null,ids.next)],ids.next);
    const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async (_q,id) => { frozen.push(id); return good(pkg(id)); },
      cancelV2Snapshot: (_q,id) => cancelled.push(id) },
      { pending: async () => pending(request({ id: pendingCalls++ === 0 ? ids.old : ids.next })),
        get: async (_t,_q,id) => { got.push(id); return good(oldQueued); },
        upload: async (_t,_q,id,packet) => { uploaded.push(id); assert.equal(JSON.parse(packet.manifestJson).saveRequestId,id);
          return good(id === ids.old ? oldQueued : newQueued); } }, () => {});
    bridge.start('11'); await settle(); await tick();
    assert.deepEqual(frozen,[ids.old,ids.next]); assert.deepEqual(uploaded,[ids.old,ids.next]);
    assert.deepEqual(got,[]); assert.ok(cancelled.includes(ids.old)); bridge.stop();
  });
  await test('terminal snapshots release; SUCCEEDED and CANCELLED stay terminal; TIMED_OUT retry only with run', async () => {
    let cancelled = 0; let uploads = 0; const phases = [];
    const first = run(ids.run1,'SUCCEEDED',1,1);
    const second = run(ids.run2,'QUEUED',0,2,ids.run1);
    const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)),
      cancelV2Snapshot: () => { cancelled++; } },
      { pending: async () => pending(uploads === 0 ? request() : null),
        upload: async () => { uploads++; return good(detail('SUCCEEDED',2,first)); },
        get: async () => good(detail('QUEUED',3,second,[first,second])) }, state => phases.push(state.phase));
    bridge.start('11'); await settle(); assert.equal(cancelled,1);
    assert.equal(bridge.frozen,null); await tick();
    assert.equal(phases.at(-1),'SUCCEEDED'); assert.equal(uploads,1); bridge.stop();
    const cancelledNoRun = detail('CANCELLED',1,null);
    const timeoutNoRun = detail('TIMED_OUT',1,null);
    const failed = detail('FAILED',2,run(ids.run1,'FAILED',1,1));
    assert.equal(sandbox.acceptDetail(cancelledNoRun,failed),false);
    assert.equal(sandbox.acceptDetail(timeoutNoRun,failed),false);
    const timeoutRun = detail('TIMED_OUT',2,run(ids.run1,'TIMED_OUT',1,1));
    const retry = detail('QUEUED',3,run(ids.run2,'QUEUED',0,2,ids.run1),
      [run(ids.run1,'TIMED_OUT',1,1),run(ids.run2,'QUEUED',0,2,ids.run1)]);
    assert.equal(sandbox.acceptDetail(timeoutRun,retry),true);
  });
  await test('late pending after stop and dispose cannot freeze', async () => {
    for (const end of ['stop','dispose']) {
      const gate = defer(); let freezes = 0;
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => { freezes++; return good(pkg(ids.old)); },
        cancelV2Snapshot: () => {} },
        { pending: () => gate.promise, get: async () => { throw Error('unexpected GET'); },
          upload: async () => { throw Error('unexpected upload'); } }, () => {});
      bridge.start('11'); bridge[end](); gate.resolve(pending(request())); await settle(); assert.equal(freezes,0);
    }
  });
  await test('question, account and token changes reject late freeze', async () => {
    for (const change of ['question','account','token']) {
      token = 'token-A'; account = 'account-A'; const gate = defer(); let uploads = 0;
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: () => gate.promise, cancelV2Snapshot: () => {} },
        { pending: async (_t,q) => pending(q === '11' ? request() : null), get: async () => { throw Error('unexpected GET'); },
          upload: async () => { uploads++; return good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))); } }, () => {});
      bridge.start('11'); await settle();
      if (change === 'question') { bridge.stop(); bridge.start('12'); }
      if (change === 'account') account = 'account-B';
      if (change === 'token') token = 'token-B';
      gate.resolve(good(pkg(ids.old))); await settle(); assert.equal(uploads,0); bridge.stop();
    }
  });
  await test('late GET and upload cannot overwrite stopped or changed session', async () => {
    for (const pendingUpload of [false,true]) {
      const gate = defer(); const phases = [];
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)), cancelV2Snapshot: () => {} },
        { pending: async () => pending(pendingUpload ? request() : null),
          get: () => gate.promise,
          upload: pendingUpload ? () => gate.promise : async () => good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))) },
        state => phases.push(state.phase));
      if (!pendingUpload) {
        // Seed an observed request through the real first upload, then hold the next GET.
        let calls = 0;
        bridge.api.pending = async () => pending(calls++ === 0 ? request() : null);
        bridge.api.upload = async () => good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1)));
      }
      bridge.start('11'); await settle(); if (!pendingUpload) await tick();
      const count = phases.length; bridge.stop(); gate.resolve(good(detail('SUCCEEDED',2,run(ids.run1,'SUCCEEDED',1,1))));
      await settle(); assert.equal(phases.length,count+1); assert.equal(phases.at(-1),'STOPPED');
    }
  });
  await test('single flight holds one pending while interval ticks', async () => {
    const gate = defer(); let pendingCalls = 0; let uploads = 0;
    const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)), cancelV2Snapshot: () => {} },
      { pending: () => { pendingCalls++; return gate.promise; },
        get: async () => { throw Error('unexpected GET'); }, upload: async () => { uploads++; return good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))); } }, () => {});
    bridge.start('11'); await tick(); await tick(); assert.equal(pendingCalls,1); assert.equal(intervalMs,2000);
    gate.resolve(pending(request())); await settle(); assert.equal(uploads,1); bridge.stop();
  });
  await test('pause preserves same binding observation; late GET is ignored and retry is read after resume', async () => {
    const gate = defer(); const phases = []; const got = [];
    let pendingCalls = 0; let getCalls = 0; let freezes = 0; let uploads = 0;
    const failed = run(ids.run1,'FAILED',1,1);
    const success = run(ids.run2,'SUCCEEDED',1,2,ids.run1);
    const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => { freezes++; return good(pkg(ids.old)); },
      cancelV2Snapshot: () => {} },
      { pending: async () => pending(pendingCalls++ === 0 ? request() : null),
        upload: async () => { uploads++; return good(detail('FAILED',2,failed)); },
        get: (_t,_q,id) => { got.push(id); return getCalls++ === 0 ? gate.promise :
          Promise.resolve(good(detail('SUCCEEDED',4,success,[failed,success]))); } },
      state => phases.push(state.phase));
    bridge.start('11'); await settle(); assert.equal(phases.at(-1),'FAILED');
    await tick(); assert.equal(getCalls,1);
    bridge.stop(); const stoppedCount = phases.length;
    assert.equal(phases.at(-1),'STOPPED');
    bridge.start('11'); await settle();
    assert.equal(phases.at(-1),'SUCCEEDED'); assert.deepEqual(got,[ids.old,ids.old]);
    const resumedCount = phases.length;
    gate.resolve(good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))));
    await settle(); assert.equal(phases.length,resumedCount); assert.ok(resumedCount > stoppedCount);
    assert.equal(phases.at(-1),'SUCCEEDED');
    assert.equal(freezes,1); assert.equal(uploads,1); bridge.stop();
  });
  await test('question, account and token rebinding discard paused observation', async () => {
    for (const change of ['question','account','token']) {
      token = 'token-A'; account = 'account-A'; let pendingCalls = 0; let gets = 0;
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)),
        cancelV2Snapshot: () => {} },
        { pending: async () => pending(pendingCalls++ === 0 ? request() : null),
          upload: async () => good(detail('FAILED',2,run(ids.run1,'FAILED',1,1))),
          get: async () => { gets++; throw Error('old request must not be read'); } }, () => {});
      bridge.start('11'); await settle(); bridge.stop();
      if (change === 'account') account = 'account-B';
      if (change === 'token') token = 'token-B';
      bridge.start(change === 'question' ? '12' : '11'); await settle();
      assert.equal(gets,0,change); bridge.stop();
    }
  });
  await test('CANCELLED and TIMED_OUT detail paths release packet and gate run resurrection', async () => {
    for (const terminal of ['CANCELLED_RUN','CANCELLED_EMPTY','TIMED_OUT_RUN','TIMED_OUT_EMPTY']) {
      const status = terminal.startsWith('CANCELLED') ? 'CANCELLED' : 'TIMED_OUT';
      const hasRun = terminal.endsWith('RUN');
      const first = hasRun ? run(ids.run1,status,1,1) : null;
      const terminalDetail = detail(status,2,first);
      const retryRun = run(ids.run2,'SUCCEEDED',1,2,ids.run1);
      const attempted = hasRun ? detail('SUCCEEDED',4,retryRun,[first,retryRun]) :
        detail('QUEUED',3,run(ids.run1,'QUEUED',0,1));
      let pendingCalls = 0; let uploads = 0; let freezes = 0; let cancelled = 0;
      const phases = [];
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => { freezes++; return good(pkg(ids.old)); },
        cancelV2Snapshot: () => { cancelled++; } },
        { pending: async () => pending(pendingCalls++ === 0 ? request() : null),
          upload: async () => { uploads++; return good(terminalDetail); },
          get: async () => good(attempted) }, state => phases.push(state.phase));
      bridge.start('11'); await settle();
      assert.equal(phases.at(-1),'FAILED',terminal); assert.equal(bridge.frozen,null,terminal);
      assert.equal(cancelled,1,terminal); await tick();
      assert.equal(phases.at(-1),status === 'TIMED_OUT' && hasRun ? 'SUCCEEDED' : 'FAILED',terminal);
      assert.equal(freezes,1,terminal); assert.equal(uploads,1,terminal); bridge.stop();
    }
  });
  await test('account and token changes reject late GET and upload results', async () => {
    for (const operation of ['GET','upload']) for (const change of ['account','token']) {
      token = 'token-A'; account = 'account-A';
      const gate = defer(); const phases = []; let pendingCalls = 0; let uploads = 0;
      const bridge = new sandbox.Bridge({ freezeV2AtPenUp: async () => good(pkg(ids.old)), cancelV2Snapshot: () => {} },
        { pending: async () => pending(pendingCalls++ === 0 ? request() : null),
          upload: async () => { uploads++; return operation === 'upload' ? gate.promise :
            good(detail('QUEUED',1,run(ids.run1,'QUEUED',0,1))); },
          get: () => gate.promise }, state => phases.push(state.phase));
      bridge.start('11'); await settle(); if (operation === 'GET') await tick();
      if (change === 'account') account = 'account-B'; else token = 'token-B';
      gate.resolve(good(detail('SUCCEEDED',2,run(ids.run1,'SUCCEEDED',1,1))));
      await settle(); assert.equal(phases.at(-1),'STOPPED',`${operation}/${change}`);
      assert.equal(phases.includes('SUCCEEDED'),false,`${operation}/${change}`);
      assert.equal(uploads,1,`${operation}/${change}`); bridge.dispose();
    }
  });
  console.log(JSON.stringify({ scenarios, result:'PASS', boundary:'actual Bridge and accept_v2_detail; fake ports only; no filesystem/network/device writes' }));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
