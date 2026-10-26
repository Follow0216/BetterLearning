const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(process.argv[2] || process.cwd());
const loader = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = JSON.parse(fs.readFileSync(path.join(loader, 'tsconfig.json'))).compilerOptions;
const auth = path.join(root, 'client/common/src/main/ets/features/auth');
const cache = new Map();
function moduleAt(file) {
  file = path.resolve(file);
  if (cache.has(file)) return cache.get(file);
  const source = fs.readFileSync(file, 'utf8');
  const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(parsed.parseDiagnostics.length, 0, file);
  const exports = {};
  cache.set(file, exports);
  const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText;
  vm.runInNewContext(js, { exports, require: name => moduleAt(path.resolve(path.dirname(file), name + '.ets')), Date }, { filename: file });
  return exports;
}
const models = moduleAt(path.join(auth, 'AuthModels.ets'));
const validation = moduleAt(path.join(auth, 'AuthValidation.ets'));
const session = moduleAt(path.join(auth, 'AuthSession.ets'));
const file = path.join(root, 'client/apps/phone/src/main/ets/components/AccountPage.ets');
const ast = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
assert.equal(ast.parseDiagnostics.length, 0, file);
const struct = ast.statements.find(n => n.kind === ts.SyntaxKind.StructDeclaration);
const members = struct.members.filter(n => n.name && n.name.getText(ast) !== 'build' &&
  !(ts.getDecorators(n) || []).some(d => d.expression.getText(ast) === 'Builder'));
const source = 'class AccountForm {\n' + members.map(n => n.getText(ast).replace(/@(State|Prop)\s*/g, '').replace(/@Watch\([^)]*\)\s*/g, '')).join('\n') + '\n}\nglobalThis.AccountForm = AccountForm;';
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText;
const sandbox = { ...models, ...validation, ...session, AuthApi: { create_default: () => ({}) } };
vm.createContext(sandbox);
vm.runInContext(js, sandbox);
const { UserProfile, AuthSessionData, AuthError, AuthFailureKind, AuthResult } = models;
const authSession = session.authSession;
function profile(phone = '+8613800000001', email = 'own@example.test', id = 'account-own') {
  return new UserProfile(id, '临时测试用户', phone, email, 'USER', '2026-10-25T01:00:00Z', '2026-10-25T02:00:00Z');
}
const ok = value => AuthResult.success(value);
const failure = (fields = {}, code = 'A0400', status = 400, kind = AuthFailureKind.HTTP) =>
  AuthResult.failure(new AuthError(kind, '测试请求失败', status, code, '', fields));
function pending() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function form() {
  const user = profile();
  authSession.save_session(new AuthSessionData('own-token', 'Bearer', 3600, user));
  const page = new sandbox.AccountForm();
  page.user = user; page.alive = true; page.activeSection = 'contact'; page.reset_contact_inputs(user);
  const notifications = [], signedOut = [], changed = [];
  let scope = 0;
  page.toast = { captureScope: () => scope, show: (options, token) => { if (token === undefined || token === scope) notifications.push(options); } };
  page.onAccountSectionChange = () => { scope++; notifications.length = 0; };
  page.onSignedOut = token => signedOut.push(token);
  page.onPasswordChanged = token => changed.push(token);
  page.api = { getCurrentUser: async () => ok(page.user), logout: async () => ok(true) };
  return { page, notifications, signedOut, changed };
}
function password(page) {
  page.activeSection = 'password'; page.oldPassword = 'Old-own-pass1';
  page.newPassword = 'New-own-pass2'; page.confirmPassword = 'New-own-pass2';
}
let scenarios = 0;
async function test(name, body) { await body(); scenarios++; console.log('PASS ' + name); }
(async () => {
  await test('未修改及直接清空不会发保存请求', async () => {
    const { page } = form(); let calls = 0; page.api.updateProfile = async () => { calls++; return ok(profile()); };
    await page.save_contact(); assert.equal(calls, 0); assert.match(page.contactError, /先修改/);
    page.phoneInput = ''; await page.save_contact(); assert.equal(calls, 0); assert(page.phoneError.length > 0);
  });
  await test('未绑定手机输入纯空白且修改合法邮箱时原位报错，不发请求', async () => {
    const { page, notifications } = form(); page.user = profile(null); page.reset_contact_inputs(page.user);
    page.phoneInput = '   '; page.emailInput = 'changed@example.test'; let calls = 0;
    page.api.updateProfile = async () => { calls++; return ok(profile(null, 'changed@example.test')); };
    await page.save_contact(); assert.equal(calls, 0); assert(page.phoneError.length > 0);
    assert.equal(page.phoneInput, '   '); assert.equal(page.emailInput, 'changed@example.test'); assert.equal(notifications.length, 0);
  });
  await test('未绑定邮箱输入纯空白且修改合法手机时原位报错，不发请求', async () => {
    const { page, notifications } = form(); page.user = profile('+8613800000001', null); page.reset_contact_inputs(page.user);
    page.emailInput = '   '; page.phoneInput = '13900000002'; let calls = 0;
    page.api.updateProfile = async () => { calls++; return ok(profile('+8613900000002', null)); };
    await page.save_contact(); assert.equal(calls, 0); assert(page.emailError.length > 0);
    assert.equal(page.emailInput, '   '); assert.equal(page.phoneInput, '13900000002'); assert.equal(notifications.length, 0);
  });
  await test('未绑定项目保持初始空串时缺省，仅发送另一修改项', async () => {
    const { page } = form(); page.user = profile(null); page.reset_contact_inputs(page.user);
    page.emailInput = 'changed@example.test'; let calls = 0;
    page.api.updateProfile = async (token, input) => {
      calls++; assert.equal(input.phone, undefined); assert.equal(input.email, 'changed@example.test');
      return ok(profile(null, 'changed@example.test'));
    };
    await page.save_contact(); assert.equal(calls, 1); assert.equal(page.user.email, 'changed@example.test');
  });
  await test('明确解绑提交null并防重复，PATCH后GET回读真实资料', async () => {
    const { page, notifications } = form(); const request = pending(); let calls = 0, reads = 0;
    page.unbindPhone = true;
    page.api.updateProfile = async (token, input) => { calls++; assert.equal(token, 'own-token'); assert.equal(input.phone, null); assert.equal(input.email, undefined); return request.promise; };
    const canonical = profile(null, 'real@example.test');
    page.api.getCurrentUser = async () => { reads++; return ok(canonical); };
    const first = page.save_contact(); await page.save_contact(); assert.equal(calls, 1); assert(page.savingContact);
    request.resolve(ok(profile(null))); await first;
    assert.equal(reads, 1); assert.equal(authSession.read_user(), canonical); assert.equal(page.phoneInput, '');
    assert.equal(page.emailInput, canonical.email); assert(!page.unbindPhone); assert(!page.savingContact);
    assert.equal(notifications.at(-1).type, 'success');
  });
  await test('联系方式占用字段错误保留输入与原真实资料', async () => {
    const { page, notifications } = form(); const original = page.user; page.emailInput = 'taken@example.test';
    page.api.updateProfile = async () => failure({}, 'A0155', 409);
    await page.save_contact(); assert.equal(page.emailInput, 'taken@example.test'); assert.equal(page.user, original);
    assert.match(page.emailError, /其他账号/); assert.equal(notifications.at(-1).type, 'error');
  });
  await test('切分区后保存成功仍同步表单，不弹过时通知', async () => {
    const { page, notifications } = form(); const request = pending(); page.unbindEmail = true;
    page.api.updateProfile = async () => request.promise; page.api.getCurrentUser = async () => ok(profile(null, null));
    const operation = page.save_contact(); page.select_section('account'); request.resolve(ok(profile(null, null))); await operation;
    assert.equal(page.emailInput, ''); assert.equal(page.phoneInput, ''); assert(!page.unbindEmail); assert(!page.contactTouched);
    assert.equal(notifications.length, 0); assert.equal(authSession.read_user().email, null);
  });
  await test('PATCH已保存但GET失败不误报完整成功', async () => {
    const { page, notifications } = form(); page.unbindEmail = true;
    page.api.updateProfile = async () => ok(profile('+8613800000001', null)); page.api.getCurrentUser = async () => failure({}, 'C0001', 503);
    await page.save_contact(); assert.equal(authSession.read_user().email, null); assert.match(page.contactError, /修改已保存/);
    assert.equal(notifications.at(-1).type, 'error');
  });
  await test('密码字段校验原位，不调用API', async () => {
    const { page, notifications } = form(); let calls = 0; password(page); page.confirmPassword = 'different';
    page.api.changePassword = async () => { calls++; return ok(true); }; await page.change_password();
    assert.equal(calls, 0); assert(page.confirmPasswordError); assert.equal(notifications.length, 0);
  });
  await test('原密码错误及网络未知保留三项输入，不自动重试', async () => {
    const { page, notifications } = form(); password(page); let calls = 0;
    page.api.changePassword = async () => { calls++; return failure({ oldPassword: '原密码错误' }); };
    await page.change_password(); assert.equal(page.oldPasswordError, '原密码错误'); assert.equal(page.oldPassword, 'Old-own-pass1'); assert.equal(page.newPassword, page.confirmPassword);
    page.api.changePassword = async () => { calls++; return failure({}, '', 0, AuthFailureKind.NETWORK); };
    await page.change_password(); assert.equal(calls, 2); assert.match(page.passwordError, /可能已提交/); assert(authSession.has_valid_session());
    assert.equal(notifications.at(-1).type, 'error');
  });
  await test('修改密码使在途资料读取失效，204只走专用成功回调', async () => {
    const { page, changed, signedOut } = form(); const read = pending(), change = pending();
    page.api.getCurrentUser = async () => read.promise; const loading = page.load_profile(); password(page);
    page.api.changePassword = async () => change.promise; const saving = page.change_password();
    read.resolve(failure({}, 'A0200', 401)); await loading; assert(authSession.has_valid_session());
    change.resolve(ok(true)); await saving; assert.equal(authSession.read_access_token(), null); assert.equal(changed.length, 1); assert.equal(signedOut.length, 0);
    assert.equal(page.newPassword, ''); assert(!page.changingPassword); assert(!page.profileLoading);
  });
  await test('离页后的改密204仍清匹配旧令牌，关闭不提交', async () => {
    const { page, changed } = form(); const request = pending(); password(page); let calls = 0;
    page.api.changePassword = async () => { calls++; return request.promise; };
    const operation = page.change_password(); await page.change_password(); page.aboutToDisappear();
    assert.equal(page.oldPassword, ''); request.resolve(ok(true)); await operation; assert.equal(calls, 1);
    assert.equal(authSession.read_access_token(), null); assert.equal(changed.length, 1);
  });
  await test('旧改密成功迟到不清新登录，也不发送旧账号成功回调', async () => {
    const { page, changed } = form(); const request = pending(); password(page); page.api.changePassword = async () => request.promise;
    const operation = page.change_password(); authSession.save_session(new AuthSessionData('new-own-token', 'Bearer', 3600, profile(null, null, 'new-own-account')));
    request.resolve(ok(true)); await operation; assert.equal(authSession.read_access_token(), 'new-own-token'); assert.equal(changed.length, 0);
  });
  await test('409 A0444要求当前账号重新登录', async () => {
    const { page, signedOut } = form(); password(page); page.api.changePassword = async () => failure({}, 'A0444', 409);
    await page.change_password(); assert.equal(authSession.read_access_token(), null); assert.equal(signedOut.length, 1); assert.equal(page.confirmPassword, '');
  });
  await test('旧密码409 A0444迟到不清新账号', async () => {
    const { page, signedOut } = form(); const request = pending(); password(page); page.api.changePassword = async () => request.promise;
    const operation = page.change_password(); authSession.save_session(new AuthSessionData('next-token', 'Bearer', 3600, profile(null, null, 'next-account')));
    request.resolve(failure({}, 'A0444', 409)); await operation; assert.equal(authSession.read_access_token(), 'next-token'); assert.equal(signedOut.length, 0);
  });
  await test('旧资料401迟到不清新账号', async () => {
    const { page, signedOut } = form(); const read = pending(); page.api.getCurrentUser = async () => read.promise;
    const operation = page.load_profile(); authSession.save_session(new AuthSessionData('next-token', 'Bearer', 3600, profile(null, null, 'next-account')));
    read.resolve(failure({}, 'A0200', 401)); await operation; assert.equal(authSession.read_access_token(), 'next-token'); assert.equal(signedOut.length, 0);
  });
  await test('退出204即使已离页也清理旧会话，失败保留会话', async () => {
    let state = form(); const request = pending(); state.page.api.logout = async () => request.promise;
    const operation = state.page.logout(); state.page.aboutToDisappear(); request.resolve(ok(true)); await operation;
    assert.equal(authSession.read_access_token(), null); assert.equal(state.signedOut.length, 1); assert.equal(state.notifications.at(-1).type, 'success');
    state = form(); state.page.api.logout = async () => failure({}, 'C0001', 503); await state.page.logout();
    assert(authSession.has_valid_session()); assert.match(state.page.errorText, /无法确认/); assert.equal(state.notifications.at(-1).type, 'error');
  });
  await test('关闭和切区只清密码，不提交任何请求', async () => {
    const { page } = form(); password(page); let calls = 0; page.api.changePassword = async () => { calls++; return ok(true); };
    page.select_section('password'); assert.equal(page.oldPassword, ''); assert.equal(page.activeSection, ''); assert.equal(calls, 0);
  });
  await test('常驻账号页隐藏时清密码且拦截隐藏提交，返回不抢占其他路由', async () => {
    const { page } = form(); password(page); let calls = 0;
    page.api.changePassword = async () => { calls++; return ok(true); };
    page.active = false; page.on_active_changed(); await page.change_password();
    assert.equal(page.oldPassword, ''); assert.equal(calls, 0);
    page.onAccountSectionChange = () => { calls++; };
    page.aboutToAppear(); assert.equal(calls, 0);
  });
  await test('局部Builder不按值捕获State或Prop，避免错误和回读冻结', async () => {
    const reactive = new Set(struct.members.filter(member =>
      (ts.getDecorators(member) || []).some(d => /^(State|Prop)$/.test(d.expression.getText(ast))))
      .map(member => member.name?.getText(ast)));
    const builders = new Set(struct.members.filter(member =>
      (ts.getDecorators(member) || []).some(d => d.expression.getText(ast) === 'Builder'))
      .map(member => member.name?.getText(ast)));
    const bad = [];
    function visit(node) {
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && builders.has(node.expression.name.text)) {
        for (const argument of node.arguments) {
          function inspect(child) {
            if (ts.isPropertyAccessExpression(child) && child.expression.kind === ts.SyntaxKind.ThisKeyword && reactive.has(child.name.text)) {
              bad.push(node.expression.name.text + '(' + child.getText(ast) + ')');
            }
            ts.forEachChild(child, inspect);
          }
          inspect(argument);
        }
      }
      ts.forEachChild(node, visit);
    }
    visit(struct); assert.deepEqual(bad, []);
  });
  await test('字段错误及密码显示读取当前状态，关闭后读为空', async () => {
    const { page } = form();
    page.phoneError = '手机号错误'; assert.equal(page.field_message('phone'), '手机号错误');
    page.phoneError = ''; assert.equal(page.field_message('phone'), '');
    password(page); page.oldPasswordError = '原密码错误';
    assert.equal(page.field_message('oldPassword'), '原密码错误'); assert.equal(page.password_value('newPassword'), 'New-own-pass2');
    page.active = false; page.on_active_changed();
    assert.equal(page.password_value('oldPassword'), ''); assert.equal(page.password_value('newPassword'), '');
    assert.equal(page.password_value('confirmPassword'), ''); assert.equal(page.field_message('oldPassword'), '');
  });
  await test('资料行显示读取新的规范化快照，解绑立即读未绑定', async () => {
    const { page } = form(); assert.equal(page.profile_value('phone'), '+8613800000001');
    page.user = profile('+8613900000002', 'fresh@example.test');
    assert.equal(page.profile_value('phone'), '+8613900000002'); assert.equal(page.profile_value('email'), 'fresh@example.test');
    page.user = profile(null, null); assert.equal(page.profile_value('phone'), '未绑定'); assert.equal(page.profile_value('email'), '未绑定');
  });
  console.log(JSON.stringify({ result: 'PASS', scenarios, syntaxFiles: cache.size + 1, limits: 'Actual AccountPage methods plus real common validators/session; isolated network-result doubles. No ArkUI render, native keyboard, HTTP provider or device acceptance.' }));
})().catch(error => { console.error(error); process.exitCode = 1; });
