#!/usr/bin/env node
// Execute production transport, Phone methods and Tablet bridge with controlled boundaries.
// No model provider, full Hvigor build, signing, network or physical device is used.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const client = path.resolve(__dirname, '..');
const multi = path.join(client, 'common/src/main/ets/features/multiagent');
const phone = path.join(client, 'apps/phone/src/main/ets');
const tablet = path.join(client, 'apps/tablet/src/main/ets');
function parse(file) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest,
    true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, file + ' must parse');
  return source;
}
function moduleCode(file) {
  const source = parse(file);
  return source.statements.filter(node => !ts.isImportDeclaration(node))
    .map(node => node.getText(source).replace(/^export\s+/, '')).join('\n');
}
const pageSource = parse(path.join(phone, 'components/PhoneTutorV2Page.ets'));
const component = pageSource.statements.find(node => node.name?.text === 'PhoneTutorV2Page');
const selectedMethods = ['submit', 'dispatch_create', 'save_draft', 'adopt_draft'];
const phoneCode = pageSource.statements.filter(node => ts.isClassDeclaration(node))
  .map(node => node.getText(pageSource)).join('\n') + '\nclass PhoneTutorV2Page {\n' + component.members
  .filter(node => ts.isPropertyDeclaration(node) || selectedMethods.includes(node.name?.getText(pageSource)))
  .map(node => ts.isPropertyDeclaration(node) ? node.name.getText(pageSource) +
    (node.initializer ? ' = ' + node.initializer.getText(pageSource) : '') + ';' : node.getText(pageSource))
  .join('\n') + '\n}';
const historySource = parse(path.join(tablet, 'features/tutor/TabletV2HistoryView.ets'));
const historyInputText = historySource.statements.find(node => node.name?.text === 'tablet_v2_input_text').getText(historySource);
const source = [
  'const V2_PROMPT_MAX = 4000; const V2_POLL_MS = 3000;',
  moduleCode(path.join(multi, 'LearningV2Models.ets')),
  moduleCode(path.join(multi, 'LearningV2State.ets')),
  moduleCode(path.join(multi, 'LearningV2Api.ets')),
  moduleCode(path.join(phone, 'model/PhoneTutorV2Logic.ets')),
  moduleCode(path.join(tablet, 'features/tutor/TabletV2TutorBridge.ets')),
  historyInputText,
  phoneCode,
  'globalThis.tested = { LearningV2Api, PhoneTutorV2Page, TabletV2TutorBridge, phone_v2_draft_for, phone_v2_retryable, phone_v2_input_text, tablet_v2_input_text };'
].join('\n');
const emitted = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020 }, reportDiagnostics: true });
assert.equal(emitted.diagnostics.length, 0);
const U = n => String(n).padStart(8, '0') + '-0000-0000-0000-000000000000';
const ok = data => ({ isSuccess: true, data, error: null });
const failure = kind => ({ isSuccess: false, data: null,
  error: { kind, message: kind, httpStatus: kind === 'auth' ? 401 : 0, is_unauthorized: () => kind === 'auth' } });
let token = 'token-A'; let account = 'account-A'; let foreground = true; let uuid = 50;
let response = null; let transportFailure = null; const writes = [];
class FakeAuthApi {
  async request_resource(...args) { writes.push(args); return transportFailure || ok(JSON.stringify(response)); }
  async request_multipart(...args) { writes.push(args); return transportFailure || ok(JSON.stringify(response)); }
}
const intervals = new Map(); let timer = 0;
const sandbox = {
  http: { RequestMethod: { GET: 'GET', POST: 'POST' } }, AuthApi: FakeAuthApi,
  DEFAULT_DEV_BASE_URL: 'https://invalid.test', AuthResult: { success: ok, failure: error => ({ isSuccess: false, data: null, error }) },
  AuthError: { protocol_failure: message => ({ kind: 'protocol', message, is_unauthorized: () => false }),
    local_validation: message => ({ kind: 'local', message, is_unauthorized: () => false }),
    network_failure: message => ({ kind: 'network', message, is_unauthorized: () => false }) },
  canvas_utf8: value => new TextEncoder().encode(value),
  canvas_read: () => Uint8Array.from([137,80,78,71,13,10,26,10]), canvas_sha256: async () => 'a'.repeat(64),
  validate_answer_manifest: () => {},
  util: { generateRandomUUID: () => U(++uuid) },
  LearningApi: { create_default: () => ({}) }, PhoneBackController: class {}, TextAreaController: class {}, ListScroller: class {},
  authSession: { read_access_token: () => token, read_user: () => token ? ({ accountId: account }) : null,
    has_valid_session: () => !!token, matches_access_token: value => !!token && value === token,
    clear_session_if_token: value => { if (value !== token) return false; token = ''; return true; } },
  TabletTutorPhase: Object.fromEntries(['STOPPED','READY','FREEZING','UPLOADING','GENERATING','SUCCEEDED','FAILED','ERROR'].map(v => [v,v])),
  TabletTutorState: class { constructor(questionId, requestId, phase, message, error) { Object.assign(this, { questionId, requestId, phase, message, error }); } },
  AppStorage: { get: () => foreground },
  setInterval: (fn, ms) => { const id = ++timer; intervals.set(id, { fn, ms }); return id; },
  clearInterval: id => intervals.delete(id), console
};
vm.createContext(sandbox); vm.runInContext(emitted.outputText, sandbox);
const { LearningV2Api, PhoneTutorV2Page, TabletV2TutorBridge, phone_v2_draft_for,
  phone_v2_retryable, phone_v2_input_text, tablet_v2_input_text } = sandbox.tested;
const stamp = '2026-10-26T00:00:00Z';
function request(kind = 'NO_ANSWER', reason = 'NO_DEVICE') {
  return { requestId: U(1), questionId: '12', prompt: '为什么', mode: 'HINT', snapshotInput: { kind },
    inputSelectionReason: reason, snapshotOriginRequestId: null, snapshotFileRef: null,
    currentRunId: kind === 'NO_ANSWER' ? U(2) : null, status: kind === 'NO_ANSWER' ? 'QUEUED' : 'WAITING_INPUT',
    revision: 1, createdAt: stamp, updatedAt: stamp };
}
function detail(req) {
  const run = req.currentRunId === null ? null : { runId: req.currentRunId, requestId: req.requestId,
    runNo: 1, status: req.status, revision: 0, steps: [] };
  return { request: req, run, runs: run ? [run] : [], result: null };
}
const settle = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); };
const defer = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function tick(ms) { for (const item of [...intervals.values()]) if (item.ms === ms) item.fn(); await settle(); }
function presenceReceipt(value) { return ok({ ...value, leaseExpiresAt: stamp }); }
async function main() {
  const api = LearningV2Api.create_default();
  for (const reason of ['NO_DEVICE', 'OTHER_QUESTION', 'OFFLINE']) {
    response = request('NO_ANSWER', reason);
    const result = await api.createRequest(token, '12', U(1), '为什么');
    assert.equal(result.isSuccess, true, reason);
    assert.equal(JSON.parse(writes.at(-1)[3]).snapshotInput.kind, 'AUTO');
    response = detail(response);
    assert.equal((await api.getRequest(token, '12', U(1))).isSuccess, true, 'no-answer run needs no snapshot ref');
  }
  response = request('NEW_CAPTURE', 'TABLET_AVAILABLE');
  assert.equal((await api.createRequest(token, '12', U(1), '为什么')).isSuccess, true);
  response = request('NO_ANSWER', 'TABLET_AVAILABLE');
  assert.equal((await api.createRequest(token, '12', U(1), '为什么')).isSuccess, false, 'mismatched resolution rejected');
  for (const kind of ['network', 'auth']) {
    transportFailure = failure(kind);
    const result = await api.createRequest(token, '12', U(1), '为什么');
    assert.equal(result.isSuccess, false); assert.equal(result.error.kind, kind, 'transport failure never means NO_DEVICE');
  }
  transportFailure = null;
  const presence = { sessionId: U(20), sequence: 4, questionId: '12', active: true };
  response = { ...presence, leaseExpiresAt: stamp };
  assert.equal((await api.updateTabletPresence(token, presence)).isSuccess, true);
  assert.equal(writes.at(-1)[1], '/api/v2/learning/tablet-presence');
  response = { ...presence, sequence: 5, questionId: null, active: false, leaseExpiresAt: stamp };
  assert.equal((await api.updateTabletPresence(token, presence)).isSuccess, false, 'released/tombstoned lease cannot confirm stale heartbeat');
  response = { available: false, reason: 'OTHER_QUESTION', leaseExpiresAt: stamp };
  assert.equal((await api.getTabletAvailability(token, '12')).isSuccess, true);
  response = { ...request(), inputSelectionReason: undefined };
  assert.equal((await api.createRequest(token, '12', U(1), '为什么')).isSuccess, false, 'no-answer requires explicit selection reason');
  const blank = { manifestJson: JSON.stringify({ saveRequestId: U(1), baseFileRef: null,
    document: { strokes: [], questionLayout: { rasterBlocks: [] } }, blocks: [] }), files: [] };
  const captured = { ...request('NEW_CAPTURE', 'TABLET_AVAILABLE'), snapshotFileRef: U(3), currentRunId: U(2), status: 'QUEUED' };
  response = detail(captured);
  assert.equal((await api.uploadSnapshot(token, '12', U(1), blank)).isSuccess, true, 'zero strokes still uses snapshot multipart');
  assert.ok(phone_v2_input_text(captured).includes('新冻结'));
  assert.ok(phone_v2_input_text(request()).includes('未检测'));
  assert.ok(tablet_v2_input_text(request()).includes('题面、问题'));
  assert.ok(tablet_v2_input_text(captured).includes('新冻结'));
  assert.equal(phone_v2_retryable({ ...request(), status: 'FAILED' }), true);

  const page = new PhoneTutorV2Page();
  Object.assign(page, { alive: true, accountId: account, questionId: '12', loadedQuestionId: '12', loadedOnce: true,
    session: () => token, has_active_request: () => false, context_unconfirmed: () => false,
    capture_anchor: () => {}, clear_error: () => {}, valid_context: () => true, clear_action_error: () => {},
    on_created: value => { page.created = value; page.createIntent.clear(); } });
  const sent = [];
  page.api = { createRequest: async (...args) => { sent.push(args); return ok({ ...request(), requestId: args[2], snapshotInput: args[5] }); } };
  page.reuseRequestId = U(8); page.prompt = '下一轮沿用'; await page.submit();
  assert.equal(sent[0][5].kind, 'REUSE_REQUEST'); assert.equal(sent[0][5].sourceRequestId, U(8));
  assert.equal(page.reuseRequestId, '', 'explicit reuse is consumed for one request');
  page.prompt = '再问一轮'; await page.submit(); assert.equal(sent[1][5].kind, 'AUTO');
  const draft = phone_v2_draft_for(account, '12'); draft.reuseRequestId = U(8); draft.prompt = '旧草稿';
  page.adopt_draft(); assert.equal(page.prompt, '旧草稿'); assert.equal(page.reuseRequestId, '');
  page.prompt = '未知发送'; let confirms = 0; page.confirm_create = () => { confirms++; };
  page.api.createRequest = async () => failure('network'); await page.submit();
  const unknownKey = page.createIntent.requestId;
  assert.equal(page.createIntent.snapshotKind, 'AUTO'); assert.ok(unknownKey); assert.equal(confirms, 1);
  await page.submit(); assert.equal(page.createIntent.requestId, unknownKey, 'unknown POST retains idempotency key');

  const leaseWrites = []; const freezeCalls = []; const uploads = []; let pending = null;
  const bridgeApi = { presence: async (_token, value) => { leaseWrites.push({ token: _token, ...value }); return presenceReceipt(value); },
    pending: async () => ok({ request: pending }), get: async () => ok(detail(captured)),
    upload: async (_t, _q, _r, value) => { uploads.push(value); return ok(detail(captured)); } };
  const canvas = { freezeV2AtPenUp: async (q, r) => { freezeCalls.push([q,r]); return ok(blank); }, cancelV2Snapshot: () => {} };
  const states = [];
  const bridge = new TabletV2TutorBridge(canvas, bridgeApi, state => states.push(state));
  bridge.display(request());
  assert.ok(states.at(-1).message.includes('仅使用题面'));
  assert.equal(states.at(-1).message.includes('完整画布已接收'), false);
  bridge.start('12'); await settle(); assert.equal(leaseWrites.at(-1).active, true);
  pending = request('NEW_CAPTURE', 'TABLET_AVAILABLE'); await tick(2000);
  assert.equal(freezeCalls.length, 1); assert.equal(uploads[0], blank, 'bridge uploads original zero-stroke package');
  const delayed = defer(); bridgeApi.presence = async (_t, value) => {
    leaseWrites.push({ token: _t, ...value }); return value.active ? delayed.promise : presenceReceipt(value); };
  await tick(8000); const oldBeat = leaseWrites.at(-1);
  bridge.start('13'); const release = leaseWrites.find(value => !value.active && value.sequence > oldBeat.sequence);
  assert.ok(release); assert.equal(release.sessionId, oldBeat.sessionId);
  assert.ok(leaseWrites.at(-1).sequence > release.sequence, 'new question uses higher sequence');
  delayed.resolve(presenceReceipt(oldBeat)); await settle();
  assert.equal(bridge.leaseConfirmed, false, 'late old-question heartbeat cannot confirm new epoch');
  bridge.dispose();
  bridgeApi.presence = async (_t, value) => { leaseWrites.push({ token: _t, ...value }); return presenceReceipt(value); };
  pending = null;
  const next = new TabletV2TutorBridge(canvas, bridgeApi, () => {}); next.start('12'); await settle();
  assert.equal(leaseWrites.at(-1).sessionId, oldBeat.sessionId, 'recreated bridge shares process session');
  assert.ok(leaseWrites.at(-1).sequence > release.sequence);
  foreground = false; await tick(2000); assert.equal(leaseWrites.at(-1).active, false, 'background immediately fences and releases');
  foreground = true; next.start('12'); await settle();
  account = 'account-B'; token = 'token-B'; await tick(2000);
  assert.equal(leaseWrites.at(-1).active, false); assert.equal(leaseWrites.at(-1).token, 'token-A', 'account switch releases old account only');
  next.dispose();
  const library = fs.readFileSync(path.join(tablet, 'components/LibraryPane.ets'), 'utf8');
  const ability = fs.readFileSync(path.join(tablet, 'entryability/EntryAbility.ets'), 'utf8');
  assert.ok(library.includes('this.foreground && this.canvas_can_freeze_v2'));
  assert.ok(ability.includes("AppStorage.setOrCreate('tabletForeground', false)"));
  console.log('PASS: AUTO/no-device/other-question/offline, new snapshot/zero strokes, network/auth errors, one-shot reuse, unknown POST, lease question/account/background fences');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
