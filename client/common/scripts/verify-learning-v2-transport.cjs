const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));

const file = path.join(__dirname, '../src/main/ets/features/multiagent/LearningV2Api.ets');
const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
const U = (n) => String(n).padStart(8, '0') + '-0000-0000-0000-000000000000';
const requests = [];
class FakeAuthApi {
  static next = null;
  constructor(baseUrl) { this.baseUrl = baseUrl; }
  async request_resource(...args) {
    requests.push({ kind: 'resource', args });
    return { isSuccess: true, data: JSON.stringify(FakeAuthApi.next), error: null };
  }
  async request_multipart(...args) {
    requests.push({ kind: 'multipart', args });
    return { isSuccess: true, data: JSON.stringify(FakeAuthApi.next), error: null };
  }
  async request_binary(...args) {
    requests.push({ kind: 'binary', args });
    return { isSuccess: true, data: Uint8Array.from([137,80,78,71,13,10,26,10]).buffer, error: null };
  }
}
const Result = {
  success: (data) => ({ isSuccess: true, data, error: null }),
  failure: (error) => ({ isSuccess: false, data: null, error })
};
const ErrorModel = {
  protocol_failure: (message) => ({ kind: 'protocol', message }),
  local_validation: (message) => ({ kind: 'local', message })
};
const hash = 'a'.repeat(64);
const moduleScope = { exports: {} };
vm.runInNewContext(code, {
  exports: moduleScope.exports,
  require: (name) => {
    if (name === '@kit.NetworkKit') return { http: { RequestMethod: { GET: 'GET', POST: 'POST' } } };
    if (name === '../auth/AuthApi') return { AuthApi: FakeAuthApi, DEFAULT_DEV_BASE_URL: 'https://example.invalid' };
    if (name === '../auth/AuthModels') return { AuthError: ErrorModel, AuthResult: Result };
    if (name === '../canvas/CanvasFiles') return {
      canvas_utf8: (s) => new TextEncoder().encode(s),
      canvas_read: () => Uint8Array.from([137,80,78,71,13,10,26,10]),
      canvas_sha256: async () => hash
    };
    if (name === '../canvas/CanvasGeometry') return { validate_answer_manifest: () => {} };
    if (name === './LearningV2Models') return {
      V2TutorMode: { HINT: 'HINT', FULL_SOLUTION: 'FULL_SOLUTION' },
      V2TutorRequestStatus: { WAITING_INPUT: 'WAITING_INPUT', QUEUED: 'QUEUED', RUNNING: 'RUNNING',
        CANCELLING: 'CANCELLING', SUCCEEDED: 'SUCCEEDED', FAILED: 'FAILED', CANCELLED: 'CANCELLED', TIMED_OUT: 'TIMED_OUT' },
      V2RunStatus: { QUEUED: 'QUEUED', RUNNING: 'RUNNING', CANCELLING: 'CANCELLING',
        SUCCEEDED: 'SUCCEEDED', FAILED: 'FAILED', CANCELLED: 'CANCELLED', TIMED_OUT: 'TIMED_OUT' }
    };
    return {};
  },
  TextEncoder, ArrayBuffer, Uint8Array, Set, Object, Number, Date, JSON, RegExp, String, encodeURIComponent
}, { filename: file });
const { LearningV2Api } = moduleScope.exports;
const api = LearningV2Api.create_default();
const requestId = U(1), runId = U(2), retryKey = U(3), citationId = U(4);
const stamp = '2026-10-25T12:00:00Z';
const request = (status = 'WAITING_INPUT', run = null) => ({
  requestId, questionId: '1', prompt: '提示', mode: 'HINT', snapshotInput: { kind: 'NEW_CAPTURE' },
  snapshotOriginRequestId: null, snapshotFileRef: run ? U(9) : null, currentRunId: run ? runId : null,
  status, revision: run ? 2 : 1, createdAt: stamp, updatedAt: stamp
});
const detail = (status = 'WAITING_INPUT', run = null) => ({
  request: request(status, run), run, runs: run ? [run] : [], result: null
});
(async () => {
  FakeAuthApi.next = request();
  let result = await api.createRequest('token', '1', requestId, '提示', 'HINT', { kind: 'NEW_CAPTURE' });
  assert.equal(result.isSuccess, true);
  assert.equal(requests.at(-1).args[0], 'POST');
  assert.equal(requests.at(-1).args[1], '/api/v2/learning/questions/1/tutor-requests');
  assert.equal(requests.at(-1).args[2], 'token');
  assert.equal(JSON.parse(requests.at(-1).args[3]).requestId, requestId);
  assert.equal(requests.at(-1).args[6]['Idempotency-Key'], requestId);

  FakeAuthApi.next = detail('CANCELLED');
  result = await api.cancelRequest('token', '1', requestId, null);
  assert.equal(result.isSuccess, true);
  assert.equal(JSON.parse(requests.at(-1).args[3]).expectedRunId, null);
  assert.equal(requests.at(-1).args[1].endsWith('/cancel'), true);

  const run = { runId, requestId, runNo: 1, status: 'QUEUED', revision: 0, steps: [] };
  FakeAuthApi.next = detail('QUEUED', run);
  result = await api.retryRequest('token', '1', requestId, runId, retryKey);
  assert.equal(result.isSuccess, true);
  assert.equal(JSON.parse(requests.at(-1).args[3]).targetRunId, runId);
  assert.equal(requests.at(-1).args[6]['Idempotency-Key'], retryKey);

  const manifest = { schemaVersion: 1, saveRequestId: requestId, baseFileRef: null,
    blocks: [{ partName: 'chunk_0000', sha256: hash }], document: { questionLayout: { rasterBlocks: [] } } };
  FakeAuthApi.next = detail('QUEUED', run);
  result = await api.uploadSnapshot('token', '1', requestId, {
    manifestJson: JSON.stringify(manifest), files: [{ partName: 'chunk_0000', filePath: '/tmp/frozen.png' }]
  });
  assert.equal(result.isSuccess, true);
  assert.equal(requests.at(-1).kind, 'multipart');
  assert.equal(requests.at(-1).args[2].length, 2);
  assert.equal(requests.at(-1).args[2][0].name, 'manifest');
  assert.equal(requests.at(-1).args[2][1].name, 'chunk_0000');
  assert.equal(requests.at(-1).args[1], 'token');

  const beforeBadUpload = requests.length;
  const damaged = { ...manifest, blocks: [{ partName: 'chunk_0000', sha256: 'b'.repeat(64) }] };
  result = await api.uploadSnapshot('token', '1', requestId, {
    manifestJson: JSON.stringify(damaged), files: [{ partName: 'chunk_0000', filePath: '/tmp/frozen.png' }]
  });
  assert.equal(result.isSuccess, false);
  assert.equal(requests.length, beforeBadUpload);

  result = await api.downloadCitationAsset('token', citationId, 0);
  assert.equal(result.isSuccess, true);
  assert.equal(requests.at(-1).args[0], '/api/v2/learning/citations/' + citationId + '/assets/0');
  assert.equal(requests.at(-1).args[1], 'token');
  const count = requests.length;
  result = await api.downloadCitationAsset('token', citationId, -1);
  assert.equal(result.isSuccess, false);
  assert.equal(requests.length, count);

  FakeAuthApi.next = { request: request(), run: null, runs: [], result: { fake: true } };
  result = await api.getRequest('token', '1', requestId);
  assert.equal(result.isSuccess, false);
  FakeAuthApi.next = detail('TIMED_OUT');
  result = await api.getRequest('token','1',requestId);
  assert.equal(result.isSuccess,true);
  FakeAuthApi.next = { ...detail('TIMED_OUT'), runs: [run] };
  result = await api.getRequest('token','1',requestId);
  assert.equal(result.isSuccess,false);
  FakeAuthApi.next = { ...detail('TIMED_OUT'), request: { ...request('TIMED_OUT'), snapshotFileRef: U(9) } };
  result = await api.getRequest('token','1',requestId);
  assert.equal(result.isSuccess,false);
  console.log('LearningV2Api: 29 个替身传输层与协议断言通过（未连接服务端）');
})().catch((error) => { console.error(error); process.exitCode = 1; });
