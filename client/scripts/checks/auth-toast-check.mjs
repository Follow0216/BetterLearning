import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const loader = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = JSON.parse(fs.readFileSync(path.join(loader, 'tsconfig.json'), 'utf8')).compilerOptions;
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../');
const feedback = path.join(root, 'client/common/src/main/ets/components/feedback');
const authFile = path.join(root, 'client/common/src/main/ets/features/auth/AuthScreen.ets');
const hostFile = path.join(feedback, 'ToastHost.ets');
const parse = file => {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, file + ' ETS parse');
  return source;
};
for (const file of [authFile, hostFile, path.join(feedback, 'ToastModels.ets'), path.join(feedback, 'ToastStore.ets'), path.join(root, 'client/common/Index.ets')]) parse(file);
const authSource = parse(authFile);
const authStruct = authSource.statements.find(node => node.name?.text === 'AuthScreen');
const enums = authSource.statements.filter(node => ['AuthMode', 'AuthStage', 'AuthField'].includes(node.name?.text)).map(node => node.getText(authSource)).join('\n');
const methodNames = new Set(['aboutToAppear', 'aboutToDisappear', 'submit_login', 'submit_register', 'switch_mode',
  'apply_local_errors', 'apply_auth_error', 'load_profile', 'handle_profile_failure', 'profile_failure_message',
  'perform_logout', 'return_to_login', 'is_current_scope', 'notify_form_error', 'notify_result', 'clear_errors']);
const methods = authStruct.members.filter(node => methodNames.has(node.name?.text)).map(node => node.getText(authSource));
assert.equal(methods.length, methodNames.size, 'all production methods extracted');
const compiled = ts.transpileModule(enums + '\nexport class ScreenHarness {\n' + methods.join('\n') + '\n}', {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
const storeJs = ts.transpileModule(fs.readFileSync(path.join(feedback, 'ToastStore.ets'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;
function harness() {
  const storeContext = vm.createContext({ exports: {}, Date, setTimeout, clearTimeout }); vm.runInContext(storeJs, storeContext);
  const store = new storeContext.exports.ToastStore(); let snapshot = [];
  store.attachHost(items => { snapshot = items; });
  let token = ''; const session = {
    save_session: data => { token = data.accessToken; }, matches_access_token: value => token === value,
    has_valid_session: () => token.length > 0, read_access_token: () => token || null,
    clear_session: () => { token = ''; }, clear_session_if_token: value => { if (value !== token) return false; token = ''; return true; }
  };
  let valid = true;
  const errors = () => ({ has_error: () => !valid, username: valid ? '' : '用户名不能为空', password: '', confirmPassword: '', form: '' });
  const context = vm.createContext({ exports: {}, Date, authSession: session,
    validate_login_form: errors, validate_register_form: errors,
    AuthFailureKind: { PROTOCOL: 'PROTOCOL' }, AUTH_WIDE_QUERY: 'wide', AUTH_SHORT_QUERY: 'short'
  }); vm.runInContext(compiled, context);
  const screen = new context.exports.ScreenHarness();
  Object.assign(screen, { toast: store, active: true, mode: 'login', stage: 'form', username: 'test-user',
    password: 'test-password', confirmPassword: 'test-password', formError: '', formNotice: '',
    usernameError: '', passwordError: '', confirmPasswordError: '', isSubmitting: false,
    profileError: '', profile: null, profileLoading: false, isLoggingOut: false, wideQuery: null, shortQuery: null,
    onAuthenticated: () => store.onScopeChange('signed-in'), onSignedOut: () => store.onScopeChange('login'),
    api: {}, getUIContext: () => ({ getMediaQuery: () => ({ matchMediaSync: () => ({ matches: false, on() {}, off() {} }) }) })
  });
  return { screen, store, session, get snapshot() { return snapshot; }, setValid: value => { valid = value; } };
}
const failure = (extra = {}) => ({ field_error: () => null, is_network: () => false, is_rate_limited: () => false,
  is_service_unavailable: () => false, is_username_conflict: () => false, is_unauthorized: () => false, kind: 'OTHER',
  message: '请求失败', ...extra });
let passed = 0;
async function check(name, test) { await test(); passed++; console.log('PASS ' + name); }
await check('登录页初次挂载保留新scope成功提示，内部模式切换清理', () => {
  const h = harness(); h.store.onScopeChange('login'); h.store.show({ key: 'password', type: 'success', message: '密码已修改，请重新登录' });
  const scope = h.store.captureScope(); h.screen.aboutToAppear(); assert.equal(h.store.captureScope(), scope);
  assert.equal(h.snapshot[0].key, 'password'); h.screen.switch_mode(); assert.notEqual(h.store.captureScope(), scope);
  assert.equal(h.snapshot.length, 0); assert.equal(h.screen.mode, 'register'); assert.equal(h.screen.password, ''); h.store.dispose();
});
await check('注册成功切回登录后通知，关闭不清表单事实', async () => {
  const h = harness(); h.screen.mode = 'register'; h.screen.api.register = async () => ({ isSuccess: true, data: {} });
  const scope = h.store.captureScope(); await h.screen.submit_register(); assert.notEqual(h.store.captureScope(), scope);
  assert.equal(h.screen.mode, 'login'); assert.equal(h.snapshot[0].message, '注册成功，请登录');
  h.store.dismiss('auth-register'); assert.equal(h.screen.formNotice, '注册成功，请登录'); h.store.dispose();
});
await check('迟到注册成功不能清除新scope或改动新页面状态', async () => {
  const h = harness(); h.screen.mode = 'register'; let resolve;
  h.screen.api.register = () => new Promise(done => { resolve = done; }); const pending = h.screen.submit_register();
  h.store.onScopeChange('another-account'); h.store.show({ key: 'new', type: 'info', message: '当前页面消息' });
  resolve({ isSuccess: true, data: {} }); await pending; assert.equal(h.screen.mode, 'register');
  assert.equal(h.snapshot[0].key, 'new'); h.store.dispose();
});
await check('字段校验不发通知，操作级错误关闭后仍原位保留', async () => {
  const h = harness(); h.setValid(false); h.screen.api.login = () => assert.fail('local validation must not call API');
  await h.screen.submit_login(); assert.equal(h.snapshot.length, 0); assert.equal(h.screen.usernameError, '用户名不能为空');
  h.setValid(true); h.screen.api.login = async () => ({ isSuccess: false, data: null, error: failure({ is_network: () => true }) });
  await h.screen.submit_login(); assert.equal(h.snapshot[0].type, 'error'); h.store.dismiss('auth-submit');
  assert.equal(h.screen.formError, '网络连接失败，请检查网络后重试'); h.store.dispose();
});
await check('登录完成先由根切scope再显示结果，新登录页mount不清理', async () => {
  const h = harness(); h.screen.api.login = async () => ({ isSuccess: true, data: { accessToken: 'isolated-test-session' } });
  h.screen.api.getCurrentUser = async () => ({ isSuccess: true, data: { accountId: 'isolated-account' } });
  await h.screen.submit_login(); assert.equal(h.snapshot[0].key, 'auth-login');
  const scope = h.store.captureScope(); h.screen.aboutToAppear(); assert.equal(h.store.captureScope(), scope); h.store.dispose();
});
await check('确认退出在新scope显示成功，失败保留会话与持续错误', async () => {
  const h = harness(); h.session.save_session({ accessToken: 'isolated-test-session' });
  h.screen.api.logout = async () => ({ isSuccess: false, error: failure() }); await h.screen.perform_logout();
  assert.equal(h.session.read_access_token(), 'isolated-test-session'); h.store.dismiss('auth-logout');
  assert.equal(h.screen.profileError, '无法确认退出结果，请重试');
  h.screen.api.logout = async () => ({ isSuccess: true }); await h.screen.perform_logout();
  assert.equal(h.session.read_access_token(), null); assert.equal(h.snapshot[0].key, 'auth-session');
  assert.equal(h.snapshot[0].message, '已退出登录'); h.store.dispose();
});
await check('失效scope及已卸载页面不通知', () => {
  const h = harness(); const scope = h.store.captureScope(); h.store.clearScope(); h.screen.notify_result('old', 'error', '过时', scope);
  assert.equal(h.snapshot.length, 0); h.screen.aboutToDisappear(); h.screen.notify_result('gone', 'info', '卸载后', h.store.captureScope());
  assert.equal(h.snapshot.length, 0); h.store.dispose();
});
await check('迟到拒绝及卸载finally不改新scope/已销毁组件状态', async () => {
  const h = harness(); let reject;
  h.screen.api.register = () => new Promise((_, fail) => { reject = fail; });
  const pending = h.screen.submit_register(); h.screen.aboutToDisappear();
  h.store.onScopeChange('new-window'); h.store.show({ key: 'new', type: 'info', message: '当前页面' });
  reject(new Error('isolated failure')); await pending;
  assert.equal(h.screen.formError, ''); assert.equal(h.screen.isSubmitting, true);
  assert.equal(h.snapshot[0].key, 'new'); h.store.dispose();
});
await check('Native仅播报：普通非打断、error告警，不请求焦点', () => {
  const source = parse(hostFile); const bubble = source.statements.find(node => node.name?.text === 'ToastBubble');
  const method = bubble.members.find(node => node.name?.text === 'announce'); const sent = [];
  const context = vm.createContext({ exports: {}, accessibility: {
    EventInfo: class { constructor(type, bundleName, triggerAction) { Object.assign(this, { type, bundleName, triggerAction }); } },
    sendAccessibilityEvent: event => { sent.push(event); return Promise.resolve(); }
  }});
  vm.runInContext(ts.transpileModule('export class BubbleHarness { ' + method.getText(source) + ' }', {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText, context);
  const bubbleHarness = new context.exports.BubbleHarness();
  bubbleHarness.getUIContext = () => ({ getHostContext: () => ({ applicationInfo: { name: 'isolated.test.bundle' } }) });
  bubbleHarness.item = { type: 'info', message: '完成', description: '说明' }; bubbleHarness.announce();
  bubbleHarness.item = { type: 'error', message: '失败', description: '' }; bubbleHarness.announce();
  assert.equal(sent[0].type, 'announceForAccessibilityNotInterrupt'); assert.equal(sent[1].type, 'announceForAccessibility');
  assert.equal(sent[0].textAnnouncedForAccessibility, '完成，说明'); assert.ok(sent.every(event => !event.type.includes('Focus')));
});
console.log(JSON.stringify({ suite: 'auth-toast-v1', passed, etsParsedFiles: 5, validation: 'production methods with isolated API/session/accessibility doubles', nativeBuild: 'not verified' }));
