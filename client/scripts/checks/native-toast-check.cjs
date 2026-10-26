/* Isolated actual-source method checks. No Hvigor, HDC, network, or user data. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const cp = require('node:child_process');
const root = path.resolve(process.argv[2] || path.join(__dirname, '../../../'));
const loader = process.env.BL_ETS_LOADER_ROOT || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const parseOptions = JSON.parse(fs.readFileSync(path.join(loader, 'tsconfig.json'), 'utf8')).compilerOptions;
const files = [
  'phone/components/PhoneApp.ets', 'phone/components/PhoneHomePage.ets', 'phone/components/PhoneTutorPage.ets',
  'phone/components/ManualEntry.ets', 'phone/components/QuestionLibrary.ets', 'phone/components/PhoneAnswerPage.ets',
  'tablet/pages/Index.ets', 'tablet/components/TabletShell.ets', 'tablet/components/LibraryPane.ets',
  'tablet/components/AccountPane.ets', 'tablet/components/AnswerWorkspace.ets', 'tablet/features/canvas/TabletCanvasWorkspace.ets'
];
const parsed = new Map();
for (const name of files) {
  const filename = path.join(root, 'client/apps', name.replace('/','/src/main/ets/'));
  const source = fs.readFileSync(filename, 'utf8');
  const sf = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, parseOptions);
  const errors = sf.parseDiagnostics.map(d => {
    const p = sf.getLineAndCharacterOfPosition(d.start);
    return `${name}:${p.line + 1}:${p.character + 1} ${ts.flattenDiagnosticMessageText(d.messageText, ' ')}`;
  });
  assert.equal(errors.length, 0, errors.join('\n'));
  parsed.set(name, sf);
}
let token = 'session-a', accountId = 'account-a', uuid = 0, groups = 0;
const timers = new Map(); let nextTimer = 0;
const status = { WAITING_ANSWER: 'WAITING_ANSWER', GENERATING: 'GENERATING', SUCCEEDED: 'SUCCEEDED', FAILED: 'FAILED' };
const answerState = { NOT_STARTED: 'NOT_STARTED', IN_PROGRESS: 'IN_PROGRESS', COMPLETED: 'COMPLETED' };
const success = data => ({ isSuccess: true, data, error: null });
const failure = error => ({ isSuccess: false, data: null, error });
const common = {
  AnswerState: answerState, TutorTurnStatus: status, AuthFailureKind: { PROTOCOL: 'PROTOCOL' },
  LearningSubject: { HIGHER_MATHEMATICS: 'HIGHER_MATHEMATICS' }, INBOX_COLLECTION_ID: 'inbox',
  LearningApi: { create_default: () => ({}) }, AuthApi: { create_default: () => ({}) },
  OcrApi: { create_default: () => ({}) }, TutorApi: { create_default: () => ({}) }, SolutionApi: { create_default: () => ({}) },
  AuthResult: { success, failure: (_, e) => failure(e) },
  citationFocusController: { clear: () => {}, restore: () => false },
  authSession: {
    read_access_token: () => token,
    read_user: () => accountId === null ? null : ({ accountId, username: 'learner' }),
    matches_access_token: value => value === token,
    has_valid_session: () => token !== null,
    clear_session: () => { token = null; },
    clear_session_if_token: value => { if (token !== value) return false; token = null; return true; }
  }
};
class Scroller { isAtEnd() { return true; } currentOffset() { return { xOffset: 0, yOffset: 0 }; } scrollEdge() {} scrollTo() {} }
let homeState;
function requireMock(name) {
  if (name === 'common') return common;
  if (name === '../model/PhoneHomeState') return homeState;
  if (name.includes('PhoneTutorV2Logic')) return { phone_v2_clear_all_intents: () => {} };
  if (name.includes('TabletBackController')) return { TabletBackController: class { attach() {} detach() {} handle() { return false; } } };
  if (name === '@kit.ArkTS') return { util: { generateRandomUUID: () => 'request-' + ++uuid } };
  if (name === '@kit.ArkUI') return { KeyboardAvoidMode: { OFFSET: 0, RESIZE: 1 } };
  if (name.includes('TabletCanvasWorkspace') || name.includes('CanvasWorkspaceController')) return { TabletCanvasController: class {} };
  if (name.includes('TabletTutorBridge')) return { TabletTutorPhase: { STOPPED: 'STOPPED' } };
  if (name.includes('QuestionFormat')) return new Proxy({}, { get: () => () => '请稍后重试' });
  return {};
}
function evaluate(source) {
  const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  vm.runInNewContext(js, { exports, require: requireMock, Scroller, Edge: { Bottom: 1 },
    getContext: () => ({}), TextAreaController: class { stopEditing() {} },
    setTimeout: fn => { timers.set(++nextTimer, fn); return nextTimer; }, clearTimeout: id => timers.delete(id),
    setInterval: fn => { timers.set(++nextTimer, fn); return nextTimer; }, clearInterval: id => timers.delete(id) });
  return exports;
}
common.ToastStore = evaluate(fs.readFileSync(path.join(root, 'client/common/src/main/ets/components/feedback/ToastStore.ets'), 'utf8')).ToastStore;
homeState = evaluate(fs.readFileSync(path.join(root, 'client/apps/phone/src/main/ets/model/PhoneHomeState.ets'), 'utf8'));
function component(name, className) {
  const source = parsed.get(name);
  const struct = source.statements.find(n => n.kind === ts.SyntaxKind.StructDeclaration);
  const members = struct.members.filter(m => !ts.isConstructorDeclaration(m) && (!ts.isMethodDeclaration(m) ||
    (m.name.getText(source) !== 'build' && !(m.modifiers || []).some(x => x.kind === ts.SyntaxKind.Decorator)))).map(m => {
      if (!ts.isPropertyDeclaration(m)) return m;
      return ts.factory.updatePropertyDeclaration(m, (m.modifiers || []).filter(x => x.kind !== ts.SyntaxKind.Decorator),
        m.name, m.questionToken || m.exclamationToken, m.type, m.initializer);
    });
  const cls = ts.factory.createClassDeclaration([ts.factory.createModifier(ts.SyntaxKind.ExportKeyword)], className, undefined, undefined, members);
  const printer = ts.createPrinter();
  const imports = source.statements.filter(n => ts.isImportDeclaration(n) || ts.isVariableStatement(n) || ts.isEnumDeclaration(n) || ts.isClassDeclaration(n))
    .map(n => printer.printNode(ts.EmitHint.Unspecified, n, source)).join('\n');
  return evaluate(imports + '\n' + printer.printNode(ts.EmitHint.Unspecified, cls, source))[className];
}
function watch(store = new common.ToastStore()) {
  const view = { store, items: [] };
  store.attachHost(items => { view.items = items; });
  return view;
}
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function check(value, message) { assert.ok(value, message); }
function done() { groups++; }
const turn = (id, state = status.GENERATING) => ({ turnId: id, requestId: 'request-' + id, questionId: '101', prompt: '求积分', status: state,
  fileRef: state === status.WAITING_ANSWER ? null : 'frozen-1', answerMarkdown: state === status.SUCCEEDED ? '积分结果' : null,
  createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z', failureCode: null, failureMessage: null });
const App = component('phone/components/PhoneApp.ets', 'PhoneApp');
const Manual = component('phone/components/ManualEntry.ets', 'ManualEntry');
const Tutor = component('phone/components/PhoneTutorPage.ets', 'PhoneTutorPage');
const Library = component('tablet/components/LibraryPane.ets', 'LibraryPane');
const Canvas = component('tablet/features/canvas/TabletCanvasWorkspace.ets', 'TabletCanvasWorkspace');
function app() { token = 'session-a'; accountId = 'account-a'; const p = new App(); p.aboutToAppear(); return p; }
function tutor() {
  token = 'session-a'; accountId = 'account-a'; const p = new Tutor(); p.questionId = '101'; p.restore_session();
  p.loading = false; p.load_question = () => {}; p.load_turns = () => {}; p.toast = new common.ToastStore(); return p;
}
function library() {
  token = 'session-a'; accountId = 'account-a'; const p = new Library(); p.alive = true; p.open_question_id = '101'; p.detail = { questionId: '101' };
  p.toast = new common.ToastStore(); p.canvas_local_draft_saved = true; p.refresh_answer_views = async () => {}; return p;
}
(async () => {
  const nav = app(); check(nav.activeTab === 'home' && nav.show_bottom_nav(), 'frozen AI home remains default');
  nav.homeComposing = true; check(!nav.show_bottom_nav(), 'home composer keeps navigation hidden');
  nav.select_tab('library'); check(nav.show_bottom_nav(), 'hidden home composer cannot hide library navigation'); done();
  nav.open_entry(); check(nav.entryVisible && !nav.entryNavigationVisible && !nav.show_bottom_nav(), 'capture entry does not reuse prior navigation state');
  nav.entryNavigationVisible = true; check(nav.show_bottom_nav(), 'capture source retains v1 bottom navigation'); done();
  nav.return_home(); nav.activeTab = 'library'; nav.detailQuestionId = '101'; nav.answerQuestionId = '101'; nav.answerTurnId = 'turn-1';
  check(!nav.show_bottom_nav() && nav.handle_back() && nav.answerQuestionId === '' && nav.detailQuestionId === '101', 'frozen answer returns to detail');
  check(nav.handle_back() && nav.detailQuestionId === '' && nav.activeTab === 'library', 'detail returns to frozen library'); done();
  const inactiveEntry = new Manual(); inactiveEntry.active = false; inactiveEntry.bodyMarkdown = '本轮积分草稿'; inactiveEntry.pendingInput = { requestId: 'same-save' };
  inactiveEntry.on_active_changed(); check(inactiveEntry.bodyMarkdown === '本轮积分草稿' && inactiveEntry.pendingInput.requestId === 'same-save', 'leaving capture preserves unsaved body and request identity'); done();
  const rootPage = app(), rootView = watch(rootPage.toast);
  const oldToken = rootPage.sessionOwnerToken; token = null; rootPage.handle_password_changed(oldToken);
  check(!rootPage.isAuthenticated && rootView.items[0].message.includes('密码已修改'), 'password success clears session before auth-scope success');
  rootPage.sync_toast_scope(); check(rootView.items.length === 1, 'same auth scope does not clear cross-login success'); done();
  const staleRoot = app(), staleView = watch(staleRoot.toast); token = 'session-b'; accountId = 'account-b'; staleRoot.enter_session();
  staleRoot.handle_password_changed('session-a'); staleRoot.handle_signed_out('session-a'); staleRoot.handle_signed_out('');
  check(staleRoot.isAuthenticated && token === 'session-b' && staleView.items.length === 0, 'old or empty-token callbacks cannot sign out new session'); done();
  const saveRoot = app(), saveView = watch(saveRoot.toast); saveRoot.entryVisible = true; saveRoot.sync_toast_scope();
  const saveScope = saveRoot.toast.captureScope(); saveRoot.handle_question_saved({ questionId: '501' }, saveScope);
  check(saveRoot.detailQuestionId === '501' && !saveRoot.entryVisible && saveView.items.length === 1, 'current saved question enters detail and shows one new-scope result'); done();
  const lateRoot = app(), lateView = watch(lateRoot.toast); lateRoot.entryVisible = true; lateRoot.sync_toast_scope(); const lateScope = lateRoot.toast.captureScope();
  lateRoot.return_home(); lateRoot.sync_toast_scope(); const refreshBefore = lateRoot.libraryRefreshToken;
  lateRoot.handle_question_saved({ questionId: '502' }, lateScope);
  check(lateRoot.detailQuestionId === '' && lateRoot.libraryRefreshToken === refreshBefore && lateView.items.length === 0, 'late save cannot navigate, refresh, or notify a changed route'); done();
  const manualRoot = app(), manualView = watch(manualRoot.toast); manualRoot.entryVisible = true; manualRoot.entryContext = 'REVIEW'; manualRoot.sync_toast_scope();
  const manual = new Manual(); manual.toast = manualRoot.toast; manual.bodyMarkdown = '计算二重积分'; manual.pendingInput = { subject: manual.subject, bodyMarkdown: manual.bodyMarkdown };
  manual.screen = 'REVIEW'; manual.abandon_capture = () => {}; let callbackScreen;
  manual.onSaved = (detail, scope) => { callbackScreen = manual.screen; manualRoot.handle_question_saved(detail, scope); };
  manual.api = { confirmManualQuestion: async () => success({ questionId: '503' }) };
  await manual.confirm(); check(callbackScreen === 'REVIEW' && manualRoot.detailQuestionId === '503' && manualView.items.length === 1, 'save callback validates original scope before screen reset'); done();
  const failManual = new Manual(), failView = watch(); failManual.toast = failView.store; failManual.bodyMarkdown = '原题正文';
  failManual.pendingInput = { subject: failManual.subject, bodyMarkdown: failManual.bodyMarkdown }; failManual.api = { confirmManualQuestion: async () => failure(null) };
  await failManual.confirm(); check(failManual.bodyMarkdown === '原题正文' && failManual.errorText.length > 0 && failView.items.length === 1, 'failed confirm preserves body and inline error');
  failView.store.dismiss('phone-entry-save'); check(failManual.errorText.length > 0 && failManual.bodyMarkdown === '原题正文', 'closing toast cannot clear business error or draft'); done();
  const ocr = new Manual(), ocrView = watch(); ocr.toast = ocrView.store; ocr.cropPath = '/isolated-image'; ocr.bodyMarkdown = '';
  ocr.ocrApi = { recognize: async () => success({ bodyMarkdown: '积分题' }) }; await ocr.start_recognition();
  check(ocr.bodyMarkdown === '积分题' && ocrView.items[0].type === 'success' && ocr.cropPath === '/isolated-image', 'OCR result is input aid, retains image and does not save'); done();
  const lateOcr = new Manual(), lateOcrView = watch(), ocrReply = deferred(); lateOcr.toast = lateOcrView.store; lateOcr.cropPath = '/isolated-image';
  lateOcr.ocrApi = { recognize: () => ocrReply.promise }; const ocrPending = lateOcr.start_recognition(); lateOcrView.store.onScopeChange('other-route');
  lateOcr.imageGeneration++; ocrReply.resolve(success({ bodyMarkdown: '旧内容' })); await ocrPending;
  check(lateOcrView.items.length === 0 && lateOcr.bodyMarkdown === '', 'late OCR has no mutation or notification'); done();
  const save = library(), saveToast = watch(save.toast); save.canvas_controller = { save: async () => success({ questionId: '101', fileRef: 'file-1', state: answerState.IN_PROGRESS }) };
  await save.run_answer_action('save'); check(saveToast.items.length === 1 && saveToast.items[0].type === 'success', 'tablet save waits for real matching receipt'); done();
  const localFail = library(), localView = watch(localFail.toast); localFail.canvas_local_draft_saved = false; localFail.canvas_controller = save.canvas_controller;
  await localFail.run_answer_action('save'); check(localView.items[0].type === 'error' && localFail.feedback_error && localFail.feedback_text.includes('本机草稿'), 'cloud success does not conceal local draft failure'); done();
  const bad = library(), badView = watch(bad.toast); bad.canvas_controller = { save: async () => success({ questionId: 'different', fileRef: 'file-1', state: answerState.IN_PROGRESS }) };
  await bad.run_answer_action('save'); check(badView.items[0].type === 'error' && bad.feedback_error, 'mismatched receipt cannot generate save success'); done();
  const staleSave = library(), staleSaveView = watch(staleSave.toast), saveReply = deferred(); staleSave.canvas_controller = { save: () => saveReply.promise };
  const savePending = staleSave.run_answer_action('save'); staleSave.open_question_id = '102'; staleSave.toast.onScopeChange('question:102');
  saveReply.resolve(success({ questionId: '101', fileRef: 'file-1', state: answerState.IN_PROGRESS })); await savePending;
  check(staleSaveView.items.length === 0, 'old question receipt cannot notify'); done();
  const refresh = library(), refreshView = watch(refresh.toast); refresh.load_collections = async () => {}; refresh.load_questions = async () => { refresh.error_text = '断网'; };
  await refresh.refresh_all(); check(refreshView.items.length === 0, 'automatic refresh is quiet');
  await refresh.refresh_all(true); check(refreshView.items.length === 1 && refresh.error_text === '断网', 'manual refresh failure notifies and preserves inline failure'); done();
  const canvas = new Canvas(), canvasView = watch(); canvas.toast = canvasView.store; canvas.alive = true; canvas.token = token;
  canvas.document = {}; canvas.store = { preserve: () => { throw new Error('disk'); } }; canvas.draft = () => ({}); canvas.emit = () => {};
  await canvas.refresh(); check(canvasView.items[0].type === 'error' && canvas.message.includes('未刷新'), 'failed local preservation prevents manual cloud refresh and gives accurate error'); done();
  const terminal = tutor(), terminalView = watch(terminal.toast); terminal.track_requested_turn(turn('live'), terminal.toast.captureScope());
  terminal.notify_pending_turn(turn('live', status.SUCCEEDED)); terminal.notify_pending_turn(turn('live', status.SUCCEEDED));
  check(terminalView.items.length === 1 && terminalView.items[0].revision === 0, 'terminal poll result notifies exactly once'); done();
  const wrongQuestion = tutor(), wrongQuestionView = watch(wrongQuestion.toast); wrongQuestion.track_requested_turn(turn('wrong'), wrongQuestion.toast.captureScope());
  wrongQuestion.notify_pending_turn({ ...turn('wrong', status.SUCCEEDED), questionId: '102' });
  check(wrongQuestionView.items.length === 0, 'mismatched question cannot notify a successful result'); done();
  const historical = tutor(), historyView = watch(historical.toast); historical.notify_pending_turn(turn('old', status.FAILED));
  check(historyView.items.length === 0, 'historical or unrequested turns do not emit terminal notifications'); done();
  const terminalLate = tutor(), terminalLateView = watch(terminalLate.toast); terminalLate.track_requested_turn(turn('late'), terminalLate.toast.captureScope());
  terminalLate.toast.onScopeChange('other-question'); terminalLate.notify_pending_turn(turn('late', status.SUCCEEDED));
  check(terminalLateView.items.length === 0 && terminalLate.requestedToastScopes.size === 0, 'late terminal outcome is rejected and removed'); done();
  const retry = tutor(), retryView = watch(retry.toast); retry.api = { retryTurn: async () => failure({ is_unauthorized: () => false, is_network: () => true }), getTurn: async () => success(turn('retry', status.SUCCEEDED)) };
  await retry.retry_turn(turn('retry', status.FAILED)); check(retryView.items.length === 1 && retryView.items[0].type === 'success', 'unknown retry followed by confirmed success cannot emit misleading error'); done();
  const generating = tutor(), generatingView = watch(generating.toast); generating.api = { retryTurn: retry.api.retryTurn, getTurn: async () => success(turn('retry-later')) };
  await generating.retry_turn(turn('retry-later', status.FAILED)); check(generatingView.items.length === 0 && generating.requestedToastScopes.size === 1, 'confirmed processing retry registers future terminal result');
  generating.notify_pending_turn(turn('retry-later', status.SUCCEEDED)); check(generatingView.items[0].type === 'success', 'later retry terminal result notifies'); done();
  const recovered = tutor(), recoveredView = watch(recovered.toast); const found = turn('found', status.SUCCEEDED); found.requestId = 'unknown-request';
  recovered.pendingRequestId = 'unknown-request'; recovered.pendingPrompt = '求积分'; recovered.prompt = '求积分'; recovered.api = { listTurns: async () => success([found]) };
  await Tutor.prototype.load_turns.call(recovered, true); check(recoveredView.items.length === 1 && recoveredView.items[0].type === 'success' && recovered.pendingRequestId === '', 'manual recovery of unknown create registers its confirmed terminal result'); done();
  check(parsed.get('phone/components/PhoneApp.ets').text.match(/ToastHost\(/g).length === 1, 'one phone root host');
  check(parsed.get('tablet/pages/Index.ets').text.match(/ToastHost\(/g).length === 1, 'one tablet root host'); done();
  for (const name of ['tablet/components/LibraryPane.ets', 'tablet/features/canvas/TabletCanvasWorkspace.ets']) {
    const sf = parsed.get(name); const struct = sf.statements.find(n => n.kind === ts.SyntaxKind.StructDeclaration);
    const forbidden = name.includes('LibraryPane') ? ['apply_canvas_state'] : ['document_changed', 'view_changed', 'persist'];
    for (const m of struct.members) if (ts.isMethodDeclaration(m) && forbidden.includes(m.name.getText(sf))) {
      check(!m.getText(sf).includes('.show('), 'automatic/pointer state path must not notify: ' + m.name.getText(sf));
    }
  } done();
  console.log(`PASS: ${files.length} ArkTS files parsed; ${groups} actual-source integration groups.`);
})().catch(error => { console.error(error); process.exitCode = 1; });
