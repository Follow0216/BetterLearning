const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(process.argv[2] || path.join(__dirname, '../../../'));
const loader = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = JSON.parse(fs.readFileSync(path.join(loader, 'tsconfig.json'), 'utf8')).compilerOptions;
const base = path.join(root, 'client/common/src/main/ets/features/auth');
const files = ['AuthModels', 'AuthValidation', 'AuthSession', 'AuthApi'];
for (const name of files) {
  const file = path.join(base, name + '.ets');
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest,
    true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, name + ' ETS syntax');
}
const pending = [];
const calls = [];
let destroyed = 0;
let closed = 0;
let failCreate = false;
let failClose = false;
// API 24 的 NetworkKit 没有 PATCH；不能让替身凭空提供 API 26 枚举。
const http = {RequestMethod: {GET: 'GET', POST: 'POST', PUT: 'PUT', DELETE: 'DELETE'},
  HttpDataType: {STRING: 'string', ARRAY_BUFFER: 'arraybuffer'},
  createHttp: () => ({request: async (url, options) => {
    calls.push({url, options, transport: 'http'});
    assert(pending.length > 0, 'unexpected network attempt');
    const next = pending.shift();
    if (next.error) throw next.error;
    return next;
  }, destroy: () => {destroyed++;}})};
const rcp = {
  Request: class {
    constructor(url, method, headers, content) {
      this.url = url; this.method = method; this.headers = headers; this.content = content;
    }
  },
  createSession: () => {
    if (failCreate) throw {code: 1007900999};
    return {
      fetch: async request => {
        calls.push({url: request.url, transport: 'rcp', options: {
          method: request.method, header: request.headers, extraData: request.content,
          configuration: request.configuration
        }});
        assert(pending.length > 0, 'unexpected RCP attempt');
        const next = pending.shift();
        if (next.error) throw next.error;
        return {statusCode: next.responseCode, toString: () => {
          assert.notEqual(next.responseCode, 204, '204 must not decode body');
          return next.result;
        }};
      },
      close: () => {closed++; if (failClose) throw {code: 1007900993};}
    };
  }
};
const cache = new Map();
function load(name) {
  if (cache.has(name)) return cache.get(name);
  const module = {exports: {}};
  cache.set(name, module.exports);
  const source = fs.readFileSync(path.join(base, name + '.ets'), 'utf8');
  const js = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2020,
    module: ts.ModuleKind.CommonJS}}).outputText;
  const context = {module, exports: module.exports, Date, ArrayBuffer, Uint8Array,
    require: id => {
      if (id === '@kit.NetworkKit') return {http};
      if (id === '@kit.RemoteCommunicationKit') return {rcp};
      if (id === '@kit.BasicServicesKit') return {};
      if (id === '@kit.ArkTS') return {util: {}};
      return load(id.replace('./', ''));
    }};
  vm.runInNewContext(js, context, {filename: name + '.ets'});
  return module.exports;
}
const {UpdateProfileInput, UserProfile, AuthSessionData, AuthFailureKind} = load('AuthModels');
const {validate_profile_update, validate_change_password} = load('AuthValidation');
const {AuthSession, authSession} = load('AuthSession');
const {AuthApi} = load('AuthApi');
const api = new AuthApi('http://isolated.invalid/');
const user = (accountId = '42', phone = '+8613800138000', email = 'a@example.com') => ({
  accountId, username: 'account_test', phone, email, role: 'USER',
  createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T01:00:00Z'});
const profile = id => new UserProfile(id, 'account_test', null, null, 'USER', 'created', 'updated');
const queue = (status, body) => pending.push({responseCode: status,
  result: typeof body === 'string' ? body : JSON.stringify(body)});
const plain = value => JSON.parse(JSON.stringify(value));
const errorBody = (code, fieldErrors = {}) => ({code, message: '安全错误文案', traceId: 'test-trace', fieldErrors});
let scenarios = 0;
async function check(name, run) {
  await run(); scenarios++; console.log('PASS ' + name);
}
(async () => {
  await check('PATCH输入缺省与null序列化保持不同', () => {
    const input = new UpdateProfileInput();
    assert.deepEqual(plain(input), {});
    input.phone = null; assert.deepEqual(plain(input), {phone: null});
    input.email = undefined; assert.deepEqual(plain(input), {phone: null});
  });
  await check('联系方式缺省/null允许，空串和空白不允许', () => {
    assert.deepEqual(plain(validate_profile_update({})), {});
    assert.deepEqual(plain(validate_profile_update({phone: null, email: null})), {});
    for (const value of ['', ' ', '\t\n']) {
      const errors = validate_profile_update({phone: value, email: value});
      assert(errors.phone && errors.email);
    }
  });
  await check('国内与E164号码边界及原样输入不变', () => {
    for (const phone of ['13800138000', ' 13800138000 ', '+12', '+123456789012345', '\u001c13800138000\u3000']) {
      const input = {phone}; assert.equal(validate_profile_update(input).phone, undefined);
      assert.equal(input.phone, phone);
    }
    for (const phone of ['12800138000', '1380013800', '+1', '+012', '+1234567890123456', '138-0013-8000', '\u00a013800138000\u00a0']) {
      assert(validate_profile_update({phone}).phone);
    }
  });
  await check('邮箱保守格式和规范化预检不改原输入', () => {
    for (const email of [' A.Test+tag@Example.COM ', 'a@b.co', '\u3000a@b.co\u001f']) {
      const input = {email}; assert.equal(validate_profile_update(input).email, undefined);
      assert.equal(input.email, email);
    }
    for (const email of ['a..b@example.com', '.a@example.com', 'a@localhost', 'a@-b.com', 'a@b-.com', 'a@例子.com', '\ufeffa@example.com']) {
      assert(validate_profile_update({email}).email);
    }
  });
  await check('邮箱本地字节/域名标签/总长边界', () => {
    assert.equal(validate_profile_update({email: 'a'.repeat(64) + '@b.co'}).email, undefined);
    assert(validate_profile_update({email: 'a'.repeat(65) + '@b.co'}).email);
    assert.equal(validate_profile_update({email: 'a@' + 'b'.repeat(63) + '.co'}).email, undefined);
    assert(validate_profile_update({email: 'a@' + 'b'.repeat(64) + '.co'}).email);
    const max = 'a'.repeat(64) + '@' + ['b'.repeat(63), 'c'.repeat(63), 'd'.repeat(61)].join('.');
    assert.equal(max.length, 254); assert.equal(validate_profile_update({email: max}).email, undefined);
    assert(validate_profile_update({email: max + 'd'}).email);
  });
  await check('新旧密码码点与UTF8边界，旧密码不套8位下限', () => {
    assert.deepEqual(plain(validate_change_password('x', 'new-pass', 'new-pass')), {});
    assert.deepEqual(plain(validate_change_password('x'.repeat(64), 'n'.repeat(64), 'n'.repeat(64))), {});
    assert.deepEqual(plain(validate_change_password('汉'.repeat(24), '汉'.repeat(24), '汉'.repeat(24))), {});
    assert(validate_change_password('汉'.repeat(25), 'new-pass', 'new-pass').oldPassword);
    assert(validate_change_password('x', '汉'.repeat(25), '汉'.repeat(25)).newPassword);
    assert.equal(validate_change_password('x', '😀'.repeat(18), '😀'.repeat(18)).newPassword, undefined);
    assert(validate_change_password('x', '😀'.repeat(19), '😀'.repeat(19)).newPassword);
    assert(validate_change_password('', 'short', '').oldPassword);
    assert(validate_change_password('x'.repeat(65), 'new-pass', 'new-pass').oldPassword);
  });
  await check('密码空白不trim，确认按原始内容比较，相同新旧允许', () => {
    assert.deepEqual(plain(validate_change_password(' ', '        ', '        ')), {});
    assert.deepEqual(plain(validate_change_password('new-pass', 'new-pass', 'new-pass')), {});
    assert(validate_change_password('x', ' new-pass ', 'new-pass').confirmPassword);
    assert(validate_change_password('x', 'new-pass', '').confirmPassword);
  });
  await check('缺token/本地错误不发网络请求', async () => {
    const before = calls.length;
    assert((await api.updateProfile('', {})).error.is_validation());
    assert((await api.changePassword('', 'x', 'new-pass', 'new-pass')).error.is_validation());
    assert((await api.updateProfile('synthetic-a', {phone: ''})).error.field_error('phone'));
    const invalid = await api.changePassword('synthetic-a', '', 'short', 'wrong');
    assert(invalid.error.field_error('oldPassword') && invalid.error.field_error('newPassword') && invalid.error.field_error('confirmPassword'));
    assert.equal(calls.length, before);
  });
  await check('PATCH仅发送指定字段并解析真实规范化资料', async () => {
    const input = new UpdateProfileInput(); input.phone = ' 13800138000 ';
    input.username = 'must-not-send'; queue(200, user());
    const result = await api.updateProfile('synthetic-a', input);
    assert(result.isSuccess); assert.equal(result.data.phone, '+8613800138000');
    const call = calls.at(-1);
    assert.equal(call.url, 'http://isolated.invalid/api/v1/users/me');
    assert.equal(call.options.method, 'PATCH');
    assert.deepEqual(JSON.parse(call.options.extraData), {phone: ' 13800138000 '});
    assert.equal(call.transport, 'rcp');
    assert.equal(call.options.header.authorization, 'Bearer synthetic-a');
    assert.equal(call.options.header['content-type'], 'application/json');
    assert.equal(call.options.configuration.transfer.autoRedirect, false);
    assert.equal(call.options.configuration.transfer.timeout.connectMs, 15000);
    assert.equal(call.options.configuration.transfer.timeout.transferMs, 15000);
  });
  await check('null解绑发送JSON null且接受可空返回，缺省发空对象', async () => {
    queue(200, user('42', null, null));
    const result = await api.updateProfile('synthetic-a', {phone: null, email: null});
    assert(result.isSuccess && result.data.phone === null && result.data.email === null);
    assert.deepEqual(JSON.parse(calls.at(-1).options.extraData), {phone: null, email: null});
    queue(200, user()); await api.updateProfile('synthetic-a', {});
    assert.deepEqual(JSON.parse(calls.at(-1).options.extraData), {});
  });
  await check('用户响应缺字段/类型错误/非法JSON拒绝覆盖', async () => {
    const missing = user(); delete missing.phone;
    for (const body of [missing, {...user(), email: 7}, 'not-json', '']) {
      queue(200, body); const result = await api.updateProfile('synthetic-a', {});
      assert(!result.isSuccess); assert.equal(result.error.kind, AuthFailureKind.PROTOCOL);
    }
  });
  await check('手机号/邮箱冲突保留业务码与追踪字段', async () => {
    for (const code of ['A0154', 'A0155']) {
      queue(409, errorBody(code)); const result = await api.updateProfile('synthetic-a', {});
      assert.equal(result.error.httpStatus, 409); assert.equal(result.error.serverCode, code);
      assert.equal(result.error.traceId, 'test-trace');
    }
  });
  await check('改密使用PATCH准确字段和原样口令，204无需JSON', async () => {
    queue(204, '');
    const result = await api.changePassword('synthetic-a', ' ', '        ', '        ');
    assert(result.isSuccess && result.data === true);
    const call = calls.at(-1);
    assert.equal(call.url, 'http://isolated.invalid/api/v1/users/me/password');
    assert.equal(call.options.method, 'PATCH');
    assert.deepEqual(JSON.parse(call.options.extraData), {oldPassword: ' ', newPassword: '        ', confirmPassword: '        '});
  });
  await check('错误旧密码保留oldPassword字段且不当作退出', async () => {
    queue(400, errorBody('A0400', {oldPassword: '旧密码错误'}));
    const result = await api.changePassword('synthetic-a', 'x', 'new-pass', 'new-pass');
    assert.equal(result.error.field_error('oldPassword'), '旧密码错误');
    assert.equal(result.error.is_unauthorized(), false);
  });
  await check('401/并发409/503有明确区分', async () => {
    for (const [status, code] of [[401, 'A0200'], [409, 'A0444'], [503, 'C0001']]) {
      queue(status, errorBody(code));
      const result = await api.changePassword('synthetic-a', 'x', 'new-pass', 'new-pass');
      assert.equal(result.error.httpStatus, status); assert.equal(result.error.serverCode, code);
      assert.equal(result.error.is_unauthorized(), status === 401);
      assert.equal(result.error.is_service_unavailable(), status === 503);
    }
  });
  await check('错误成功状态不能冒充204改密成功', async () => {
    queue(200, user());
    const result = await api.changePassword('synthetic-a', 'x', 'new-pass', 'new-pass');
    assert.equal(result.isSuccess, false);
    queue(204, ''); assert.equal((await api.updateProfile('synthetic-a', {})).isSuccess, false);
  });
  await check('改密网络未知结果单次返回失败，不自动重试', async () => {
    const before = calls.length; pending.push({error: {code: 2300000, message: 'isolated failure'}});
    const result = await api.changePassword('synthetic-a', 'x', 'new-pass', 'new-pass');
    assert.equal(result.error.kind, AuthFailureKind.NETWORK); assert.equal(calls.length, before + 1);
    assert(!JSON.stringify(result.error).includes('new-pass'));
  });
  await check('API不改全局会话、不延长有效期、不追加logout', async () => {
    authSession.save_session(new AuthSessionData('synthetic-owner', 'Bearer', 60, profile('42')), 1000);
    const before = calls.length;
    queue(200, user()); await api.updateProfile('synthetic-owner', {email: null});
    queue(204, ''); await api.changePassword('synthetic-owner', 'x', 'new-pass', 'new-pass');
    assert.equal(authSession.read_access_token(2000), 'synthetic-owner');
    assert.equal(authSession.read_user(2000).email, null); assert.equal(authSession.expires_at_millis(), 61000);
    assert.equal(calls.length, before + 2); authSession.clear_session();
  });
  await check('资料更新仅当前token/同账号生效，保持原到期时刻', () => {
    const session = new AuthSession(); const updated = profile('42');
    session.save_session(new AuthSessionData('synthetic-a', 'Bearer', 60, profile('42')), 1000);
    assert.equal(session.update_user_if_token('synthetic-old', updated, 2000), false);
    assert.equal(session.update_user_if_token('synthetic-a', profile('43'), 2000), false);
    assert.equal(session.update_user_if_token('synthetic-a', updated, 2000), true);
    assert.equal(session.read_user(2000), updated); assert.equal(session.expires_at_millis(), 61000);
    assert.equal(session.update_user_if_token('synthetic-a', profile('42'), 61000), false);
    assert.equal(session.read_access_token(61000), null);
  });
  await check('迟到改密/资料不能清除或复活新登录', () => {
    const session = new AuthSession();
    session.save_session(new AuthSessionData('synthetic-new', 'Bearer', 60, profile('43')), 1000);
    assert.equal(session.clear_session_if_token('synthetic-old'), false);
    assert.equal(session.update_user_if_token('synthetic-old', profile('42'), 2000), false);
    assert.equal(session.read_user(2000).accountId, '43');
    assert.equal(session.clear_session_if_token('synthetic-new'), true);
    assert.equal(session.update_user_if_token('synthetic-new', profile('43'), 2000), false);
    assert.equal(session.has_valid_session(2000), false);
  });
  await check('API24缺失NetworkKit PATCH仍由RCP发送，既有GET保留原通道', async () => {
    assert.equal(http.RequestMethod.PATCH, undefined);
    queue(200, user()); const result = await api.getCurrentUser('synthetic-a');
    assert(result.isSuccess); assert.equal(calls.at(-1).transport, 'http');
    assert.equal(calls.at(-1).options.method, 'GET');
    assert.equal(calls.at(-1).options.usingCache, false);
    assert.equal(calls.filter(call => call.transport === 'http').length, 1);
    assert(calls.filter(call => call.transport === 'rcp').every(call => call.options.method === 'PATCH'));
  });
  await check('RCP创建失败返回统一失败，关闭失败不覆盖204确认', async () => {
    failCreate = true;
    const failed = await api.updateProfile('synthetic-a', {});
    assert.equal(failed.isSuccess, false); assert.equal(failed.error.kind, AuthFailureKind.NETWORK);
    failCreate = false; failClose = true; queue(204, '');
    const confirmed = await api.changePassword('synthetic-a', 'x', 'new-pass', 'new-pass');
    assert(confirmed.isSuccess && confirmed.data === true); failClose = false;
  });
  assert.equal(pending.length, 0);
  assert.equal(destroyed, calls.filter(call => call.transport === 'http').length);
  assert.equal(closed, calls.filter(call => call.transport === 'rcp').length);
  console.log(JSON.stringify({result: 'PASS', scenarios, syntaxFiles: files.length,
    isolatedRequests: calls.length, httpRequests: destroyed, closedRcpSessions: closed,
    networkKitPatchEnumAvailable: http.RequestMethod.PATCH !== undefined,
    limits: 'Original ETS modules with API24 NetworkKit (no PATCH) and mocked system RCP; no backend, formal ArkTS build, UI or device verification.'}));
})().catch(error => {console.error(error); process.exitCode = 1;});
