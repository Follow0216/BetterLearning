const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

// Execute the production AuthApi, AuthModels and AuthValidation. Only OS transports are doubled.
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const base = path.join(__dirname, '../src/main/ets/features/auth');
const pending = [];
const calls = [];
let httpDestroyed = 0;
let sessionsCreated = 0;
let sessionsClosed = 0;
const http = {
  // API 24 NetworkKit has no PATCH member. An accidental use fails this harness.
  RequestMethod: { GET: 'GET', POST: 'POST', PUT: 'PUT', DELETE: 'DELETE' },
  HttpDataType: { STRING: 'string', ARRAY_BUFFER: 'arraybuffer' },
  createHttp: () => ({
    request: async (url, options) => {
      calls.push({ transport: 'http', url, options });
      assert(pending.length > 0, 'unexpected NetworkKit request');
      const response = pending.shift();
      if (response.error) throw response.error;
      return { responseCode: response.status, result: response.body };
    },
    destroy: () => { httpDestroyed++; }
  })
};
const rcp = {
  Request: class {
    constructor(url, method, headers, content) {
      this.url = url;
      this.method = method;
      this.headers = headers;
      this.content = content;
    }
  },
  createSession: () => {
    sessionsCreated++;
    return {
      fetch: async request => {
        calls.push({ transport: 'rcp', request });
        assert(pending.length > 0, 'unexpected RCP request');
        const response = pending.shift();
        if (response.error) throw response.error;
        return {
          statusCode: response.status,
          toString: () => {
            assert.notEqual(response.status, 204, '204 response must not be decoded');
            return response.body;
          }
        };
      },
      close: () => { sessionsClosed++; }
    };
  }
};
const modules = new Map();
function load(name) {
  if (modules.has(name)) return modules.get(name);
  const file = path.join(base, name + '.ets');
  const source = fs.readFileSync(file, 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
    reportDiagnostics: true
  });
  assert.equal(compiled.diagnostics.length, 0, name + ' transpilation');
  const module = { exports: {} };
  modules.set(name, module.exports);
  vm.runInNewContext(compiled.outputText, {
    module, exports: module.exports, Date, ArrayBuffer, Uint8Array,
    require: id => {
      if (id === '@kit.NetworkKit') return { http };
      if (id === '@kit.RemoteCommunicationKit') return { rcp };
      if (id === '@kit.BasicServicesKit') return {};
      if (id === '@kit.ArkTS') return { util: {} };
      assert(id.startsWith('./'), 'unexpected import: ' + id);
      return load(id.substring(2));
    }
  }, { filename: file });
  return module.exports;
}
const { AuthApi } = load('AuthApi');
const { UpdateProfileInput } = load('AuthModels');
const api = new AuthApi('https://isolated.invalid/');
const user = {
  accountId: '42', username: 'transport_test', phone: null, email: 'a@example.com',
  role: 'USER', createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T01:00:00Z'
};
const queue = (status, body) => pending.push({ status, body });
let scenarios = 0;
async function check(name, run) {
  const before = { calls: calls.length, destroyed: httpDestroyed,
    created: sessionsCreated, closed: sessionsClosed };
  await run(before);
  assert.equal(pending.length, 0, name + ': unconsumed fake response');
  scenarios++;
  console.log('PASS ' + name);
}
const last = () => calls[calls.length - 1];
(async () => {
  await check('V2 GET uses NetworkKit, preserves Bearer and accepts only Idempotency-Key', async before => {
    queue(200, '{"ok":true}');
    const result = await api.request_resource('GET', '/api/v2/learning/test', 'synthetic-token',
      '', 200, 15000, {
        'Idempotency-Key': 'synthetic-key', Authorization: 'Bearer attacker',
        'Content-Type': 'text/plain', 'X-Injected': 'bad', authorization: 'Bearer attacker'
      });
    assert.equal(result.isSuccess, true);
    assert.equal(result.data, '{"ok":true}');
    assert.equal(calls.length, before.calls + 1);
    assert.equal(last().transport, 'http');
    assert.equal(last().url, 'https://isolated.invalid/api/v2/learning/test');
    assert.equal(last().options.method, 'GET');
    assert.deepEqual(JSON.parse(JSON.stringify(last().options.header)), {
      'Content-Type': 'application/json', Authorization: 'Bearer synthetic-token',
      'Idempotency-Key': 'synthetic-key'
    });
    assert.equal(httpDestroyed, before.destroyed + 1);
    assert.equal(sessionsCreated, before.created);
  });
  await check('V2 POST keeps JSON bytes and its idempotency key on NetworkKit', async before => {
    const body = '{"requestId":"synthetic-id","prompt":"原文"}';
    queue(201, '{"requestId":"synthetic-id"}');
    const result = await api.request_resource('POST', '/api/v2/learning/questions/1/tutor-requests',
      'synthetic-token', body, 201, 9000, { 'Idempotency-Key': 'synthetic-id', 'X-Injected': 'bad' });
    assert.equal(result.isSuccess, true);
    assert.equal(calls.length, before.calls + 1);
    assert.equal(last().transport, 'http');
    assert.equal(last().options.method, 'POST');
    assert.equal(last().options.extraData, body);
    assert.equal(last().options.readTimeout, 9000);
    assert.equal(last().options.header['Idempotency-Key'], 'synthetic-id');
    assert.equal(last().options.header['X-Injected'], undefined);
    assert.equal(httpDestroyed, before.destroyed + 1);
    assert.equal(sessionsCreated, before.created);
  });
  await check('missing token prevents both transports', async before => {
    const result = await api.request_resource('GET', '/api/v2/learning/test', '', '');
    assert.equal(result.isSuccess, false);
    assert.equal(result.error.kind, 'validation');
    assert.equal(calls.length, before.calls);
    assert.equal(httpDestroyed, before.destroyed);
    assert.equal(sessionsCreated, before.created);
  });
  await check('updateProfile uses one RCP PATCH and closes its session', async before => {
    const input = new UpdateProfileInput();
    input.phone = null;
    queue(200, JSON.stringify(user));
    const result = await api.updateProfile('account-token', input);
    assert.equal(result.isSuccess, true);
    assert.equal(result.data.accountId, '42');
    assert.equal(calls.length, before.calls + 1);
    assert.equal(last().transport, 'rcp');
    assert.equal(last().request.method, 'PATCH');
    assert.equal(last().request.url, 'https://isolated.invalid/api/v1/users/me');
    assert.deepEqual(JSON.parse(last().request.content), { phone: null });
    assert.equal(last().request.headers.authorization, 'Bearer account-token');
    assert.equal(last().request.headers['content-type'], 'application/json');
    assert.equal(last().request.configuration.transfer.autoRedirect, false);
    assert.equal(sessionsCreated, before.created + 1);
    assert.equal(sessionsClosed, before.closed + 1);
    assert.equal(httpDestroyed, before.destroyed);
  });
  await check('changePassword uses one RCP PATCH, accepts 204 without decoding', async before => {
    queue(204, '');
    const result = await api.changePassword('account-token', 'x', 'new-pass', 'new-pass');
    assert.equal(result.isSuccess, true);
    assert.equal(result.data, true);
    assert.equal(calls.length, before.calls + 1);
    assert.equal(last().transport, 'rcp');
    assert.equal(last().request.method, 'PATCH');
    assert.equal(last().request.url, 'https://isolated.invalid/api/v1/users/me/password');
    assert.deepEqual(JSON.parse(last().request.content), {
      oldPassword: 'x', newPassword: 'new-pass', confirmPassword: 'new-pass'
    });
    assert.equal(last().request.headers.authorization, 'Bearer account-token');
    assert.equal(sessionsCreated, before.created + 1);
    assert.equal(sessionsClosed, before.closed + 1);
    assert.equal(httpDestroyed, before.destroyed);
  });
  await check('RCP fetch failure closes once and does not fall back to NetworkKit', async before => {
    pending.push({ error: { code: 1007900999 } });
    const result = await api.updateProfile('account-token', {});
    assert.equal(result.isSuccess, false);
    assert.equal(result.error.kind, 'network');
    assert.equal(calls.length, before.calls + 1);
    assert.equal(last().transport, 'rcp');
    assert.equal(sessionsCreated, before.created + 1);
    assert.equal(sessionsClosed, before.closed + 1);
    assert.equal(httpDestroyed, before.destroyed);
  });
  assert.equal(calls.filter(call => call.transport === 'http' && call.options.method === undefined).length, 0);
  console.log(`AuthApi V2/account transport: ${scenarios} scenarios passed (OS transport doubles; no network/device)`);
})().catch(error => { console.error(error); process.exitCode = 1; });
