#!/usr/bin/env node
// Execute actual Phone V2 and Home component methods with ArkUI and HTTP boundaries stubbed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const client = path.resolve(__dirname, '../../..');
function parse(file) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest,
    true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, file + ': ' + source.parseDiagnostics.map((d) => d.messageText));
  return source;
}
function moduleCode(file) {
  const source = parse(file);
  return source.statements.filter((node) => !ts.isImportDeclaration(node))
    .map((node) => node.getText(source).replace(/^export\s+/, '')).join('\n');
}
function componentCode(file, name, exclude) {
  const source = parse(file);
  const component = source.statements.find((node) => node.name?.text === name);
  assert.ok(component, name + ' missing');
  const supporting = source.statements.filter((node) => ts.isClassDeclaration(node))
    .map((node) => node.getText(source)).join('\n');
  return supporting + '\nclass ' + name + ' {\n' + component.members
    .filter((node) => !exclude.includes(node.name?.getText(source)) &&
      !node.getText(source).trimStart().startsWith('@Builder'))
    .map((node) => ts.isPropertyDeclaration(node) ?
      node.name.getText(source) + (node.initializer ? ' = ' + node.initializer.getText(source) : '') + ';' :
      node.getText(source)).join('\n') + '\n}';
}
const multi = path.join(client, 'common/src/main/ets/features/multiagent');
const phone = path.join(client, 'apps/phone/src/main/ets');
const code = [
  'const V2_PROMPT_MAX = 4000; const V2_POLL_MS = 3000;',
  moduleCode(path.join(multi, 'LearningV2Models.ets')),
  moduleCode(path.join(multi, 'LearningV2State.ets')),
  moduleCode(path.join(multi, 'CitationMarkers.ets')),
  moduleCode(path.join(client, 'common/src/main/ets/components/feedback/ToastModels.ets')),
  moduleCode(path.join(client, 'common/src/main/ets/components/feedback/ToastStore.ets')),
  moduleCode(path.join(phone, 'model/PhoneHomeState.ets')),
  moduleCode(path.join(phone, 'model/PhoneTutorV2Logic.ets')),
  componentCode(path.join(phone, 'components/PhoneTutorV2Page.ets'), 'PhoneTutorV2Page',
    ['build', 'top_bar', 'embedded_toolbar', 'compose_card', 'notice', 'request_section',
      'request_card', 'selected_detail']),
  componentCode(path.join(phone, 'components/PhoneHomePage.ets'), 'PhoneHomePage',
    ['build', 'header', 'no_tablet_question', 'single_device', 'image_action',
      'disabled_send', 'info', 'mode_picker', 'mode_option', 'history_page']),
  'globalThis.types = { PhoneTutorV2Page, PhoneHomePage, ToastStore, V2TutorMode, V2TutorRequestStatus, phone_v2_draft_for, phone_v2_clear_all_intents };'
].join('\n');
const emitted = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 },
  reportDiagnostics: true });
assert.equal(emitted.diagnostics.length, 0,
  'actual component method transpilation failed: ' + emitted.diagnostics.map((d) => d.messageText));
let token = 'one';
let account = 'account';
let api = {};
let nextUuid = 0;
let stoppedEditors = 0;
const sandbox = {
  LearningV2Api: { create_default: () => api },
  LearningApi: { create_default: () => ({ getQuestion: async () => ({ isSuccess: false }) }) },
  TextAreaController: class { stopEditing() { stoppedEditors++; } },
  ListScroller: class { getItemRect() { return { y: 0, height: 0 }; } isAtEnd() { return false; }
    scrollToIndex() {} },
  LengthMetrics: { vp: (value) => value }, ScrollAlign: { START: 0 },
  setTimeout: () => 1, clearTimeout: () => {},
  authSession: {
    has_valid_session: () => token.length > 0,
    read_access_token: () => token,
    read_user: () => token ? { accountId: account } : null,
    matches_access_token: (value) => !!value && token === value,
    clear_session_if_token: (value) => { if (value !== token) return false; token = ''; return true; }
  },
  util: { generateRandomUUID: () => 'uuid-' + (++nextUuid) },
  setInterval: () => 1,
  clearInterval: () => {}
};
vm.createContext(sandbox);
vm.runInContext(emitted.outputText, sandbox);
const { PhoneTutorV2Page, PhoneHomePage, ToastStore, V2TutorMode,
  V2TutorRequestStatus: status } = sandbox.types;
const { phone_v2_draft_for, phone_v2_clear_all_intents } = sandbox.types;
const ok = (data) => ({ isSuccess: true, data, error: null });
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve }; };
const flush = () => new Promise((done) => setImmediate(done));
const request = (id, revision, runId, state) => ({
  requestId: id, questionId: '12', prompt: '问题', mode: V2TutorMode.HINT,
  snapshotInput: { kind: 'NEW_CAPTURE' }, snapshotOriginRequestId: null,
  snapshotFileRef: runId === null ? null : 'snapshot', currentRunId: runId,
  status: state, revision, createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z'
});
const detail = (req, runNo = 1, runRevision = 1, retryOfRunId = null, priorRuns = []) => {
  const run = req.currentRunId === null ? null : {
    runId: req.currentRunId, requestId: req.requestId, runNo, retryOfRunId,
    status: req.status, revision: runRevision, steps: []
  };
  return { request: req, run, runs: run === null ? [] : [...priorRuns, run], result: null };
};
function page() {
  const value = new PhoneTutorV2Page();
  value.questionId = '12'; value.accountId = account; value.alive = true;
  value.visible = true; value.foreground = true;
  return value;
}
let cases = 0;
async function check(name, run) {
  token = 'one'; account = 'account'; api = {}; stoppedEditors = 0;
  phone_v2_clear_all_intents();
  try { await run(); cases++; } catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}
(async () => {
  await check('retry while user opens history updates active summary', async () => {
    const failed = request('r1', 2, 'run1', status.FAILED);
    const history = detail(request('history', 1, 'old', status.SUCCEEDED));
    const value = page(); value.requests = [failed, history.request];
    value.selectedRequestId = 'r1'; value.detail = detail(failed, 1, 2);
    const retry = deferred();
    api.retryRequest = () => retry.promise;
    api.getRequest = async (_, __, id) => ok(id === 'history' ? history : value.detail);
    const pending = value.retry(failed);
    value.select_request('history'); await flush();
    retry.resolve(ok(detail(request('r1', 3, 'run2', status.QUEUED), 2, 1, 'run1',
      [detail(failed, 1, 2).run])));
    await pending;
    assert.equal(value.selectedRequestId, 'history');
    assert.equal(value.detail.request.requestId, 'history');
    assert.equal(value.requests.find((item) => item.requestId === 'r1').currentRunId, 'run2');
  });
  await check('cancel while user opens history updates active summary', async () => {
    const running = request('r1', 1, 'run1', status.RUNNING);
    const history = detail(request('history', 1, 'old', status.SUCCEEDED));
    const value = page(); value.requests = [running, history.request];
    value.selectedRequestId = 'r1'; value.detail = detail(running);
    const cancel = deferred(); api.cancelRequest = () => cancel.promise;
    api.getRequest = async () => ok(history);
    const pending = value.cancel(running);
    value.select_request('history'); await flush();
    cancel.resolve(ok(detail(request('r1', 2, 'run1', status.CANCELLED), 1, 2)));
    await pending;
    assert.equal(value.selectedRequestId, 'history');
    assert.equal(value.detail.request.requestId, 'history');
    assert.equal(value.requests.find((item) => item.requestId === 'r1').status, status.CANCELLED);
  });
  await check('history stays selected while active polling completes', async () => {
    const active = request('r1', 1, 'run1', status.RUNNING);
    const history = detail(request('history', 1, 'old', status.SUCCEEDED));
    const value = page(); value.requests = [active, history.request];
    value.selectedRequestId = 'history'; value.detail = history;
    const poll = deferred(); api.getRequest = () => poll.promise;
    value.poll_selected();
    poll.resolve(ok(detail(request('r1', 2, 'run1', status.SUCCEEDED), 1, 2)));
    await flush(); await flush();
    assert.equal(value.detail.request.requestId, 'history');
    assert.equal(value.requests.find((item) => item.requestId === 'r1').status, status.SUCCEEDED);
    assert.equal(value.has_active_request(), false);
  });
  await check('late low revision create confirmation cannot replace poll or retry', async () => {
    const value = page();
    const oldFailed = detail(request('r1', 2, 'run1', status.FAILED), 1, 2);
    const newer = detail(request('r1', 3, 'run2', status.QUEUED), 2, 1, 'run1', [oldFailed.run]);
    value.requests = [newer.request]; value.selectedRequestId = 'r1'; value.detail = newer;
    const confirm = deferred(); api.getRequest = () => confirm.promise;
    const pending = value.confirm_create('r1');
    confirm.resolve(ok(detail(request('r1', 1, 'run1', status.RUNNING))));
    await pending;
    assert.equal(value.detail.request.currentRunId, 'run2');
    assert.equal(value.requests[0].revision, 3);
  });
  await check('complete runs chain recovers missed failure and retry', async () => {
    const value = page();
    const running = detail(request('r1', 1, 'run1', status.RUNNING));
    const failedRun = { ...running.run, status: status.FAILED, revision: 2 };
    const recovered = detail(request('r1', 3, 'run2', status.QUEUED), 2, 1, 'run1', [failedRun]);
    value.requests = [running.request]; value.selectedRequestId = 'r1'; value.detail = running;
    api.getRequest = async () => ok(recovered);
    await value.refresh_detail('r1');
    assert.equal(value.detail.request.currentRunId, 'run2');
    const invalid = page(); invalid.requests = [running.request];
    invalid.selectedRequestId = 'r1'; invalid.detail = running;
    api.getRequest = async () => ok(detail(request('r1', 3, 'run2', status.QUEUED), 2, 1, 'run1'));
    await invalid.refresh_detail('r1');
    assert.equal(invalid.detail.request.currentRunId, 'run1');
  });
  await check('account switch discards late detail and keeps new binding', async () => {
    const value = page(); value.selectedRequestId = 'r1';
    const response = deferred(); api.getRequest = () => response.promise;
    const pending = value.refresh_detail('r1');
    token = 'two'; account = 'other';
    response.resolve(ok(detail(request('r1', 1, 'run1', status.RUNNING))));
    await pending;
    assert.equal(value.detail, null);
    assert.equal(value.requests.length, 0);
  });
  await check('refresh racing create releases loading without clearing mutation busy', async () => {
    const value = page(); value.loadedOnce = true; value.prompt = '问题';
    const list = deferred(); const post = deferred();
    api.listRequests = () => list.promise; api.createRequest = () => post.promise;
    api.getRequest = async () => ok(detail(request('r1', 0, null, status.WAITING_INPUT)));
    const pendingList = value.refresh_list();
    const pendingPost = value.submit();
    assert.equal(value.loading, true); assert.equal(value.busy, true);
    list.resolve(ok({ items: [], nextCursor: null })); await pendingList;
    assert.equal(value.loading, false); assert.equal(value.busy, true);
    post.resolve(ok(request('r1', 0, null, status.WAITING_INPUT))); await pendingPost;
    assert.equal(value.busy, false);
  });
  await check('only published citation markers have native backup buttons', async () => {
    const value = page();
    const cited = '11111111-1111-1111-1111-111111111111';
    const uncited = '22222222-2222-2222-2222-222222222222';
    value.detail = detail(request('r1', 2, 'run1', status.SUCCEEDED));
    value.detail.result = { bodyMarkdown: '见 [[citation:' + cited + ']]', citations: [
      { citationId: cited, label: '引用1' }, { citationId: uncited, label: '引用2' }
    ] };
    assert.equal(value.visible_citations().length, 1);
    assert.equal(value.visible_citations()[0].citationId, cited);
  });
  await check('question draft and summary retain original question and isolate accounts', async () => {
    const summaries = [];
    const value = page(); value.loadedQuestionId = '12';
    value.onSessionSummary = (id, label, prompt) => summaries.push({ id, label, prompt });
    api.listRequests = async () => ok({ items: [], nextCursor: null });
    value.prompt = '第一题草稿'; value.fullSolution = true;
    value.questionId = '13'; value.on_question_changed(); await flush();
    assert.equal(summaries[0].id, '12');
    assert.equal(summaries[0].prompt, '第一题草稿');
    value.prompt = '第二题草稿'; value.save_draft();
    value.questionId = '12'; value.on_question_changed(); await flush();
    assert.equal(value.prompt, '第一题草稿');
    assert.equal(value.fullSolution, true);
    assert.equal(phone_v2_draft_for('account', '13').prompt, '第二题草稿');
    const other = page(); other.accountId = 'different'; other.questionId = '12';
    other.adopt_draft(); assert.equal(other.prompt, '');
  });
  await check('Home system back stops V2 editor and restores nav composing state', async () => {
    const value = page(); const home = new PhoneHomePage();
    const composing = []; home.onComposingChanged = (state) => composing.push(state);
    value.editorNavigation = home.tutorEditorNavigation;
    value.onComposingChanged = (state) => { home.tutorComposing = state; home.report_composing(); };
    value.aboutToAppear(); await flush();
    value.editorFocused = true; home.tutorComposing = true;
    assert.equal(home.handle_back(), true);
    assert.equal(value.editorFocused, false);
    assert.equal(home.tutorComposing, false);
    assert.equal(composing.at(-1), false);
    assert.ok(stoppedEditors > 0);
    value.aboutToDisappear();
  });
  await check('Home reading preserves citations and back clears reading content', async () => {
    const home = new PhoneHomePage(); const fullscreen = [];
    home.onFullscreenChanged = (state) => fullscreen.push(state);
    const citations = [{ citationId: '11111111-1111-1111-1111-111111111111', label: '来源' }];
    home.open_reading('解答', '正文', false, citations);
    assert.equal(home.readingVisible, true);
    assert.equal(home.readingCitations[0].citationId, citations[0].citationId);
    assert.equal(home.handle_back(), true);
    assert.equal(home.readingVisible, false);
    assert.equal(home.readingBody, '');
    assert.equal(home.readingCitations.length, 0);
    assert.equal(fullscreen.join(','), 'true,false');
  });
  await check('create retry cancel capture Toast scope and polling stays silent', async () => {
    const toast = new ToastStore(); let visible = [];
    toast.attachHost((items) => { visible = items; });
    const value = page(); value.toast = toast; value.loadedQuestionId = '12'; value.loadedOnce = true;
    value.prompt = '问题';
    toast.onScopeChange('question:12');
    const create = deferred(); api.createRequest = () => create.promise;
    api.getRequest = async () => ok(detail(request('create', 0, null, status.WAITING_INPUT)));
    const creating = value.submit(); toast.onScopeChange('question:13');
    create.resolve(ok(request('create', 0, null, status.WAITING_INPUT))); await creating;
    assert.equal(visible.length, 0, 'old create result must not toast in next scope');
    const running = request('run', 1, 'run1', status.RUNNING);
    value.requests = [running]; value.selectedRequestId = 'run'; value.detail = detail(running);
    const cancel = deferred(); api.cancelRequest = () => cancel.promise;
    const cancelling = value.cancel(running); toast.onScopeChange('question:14');
    cancel.resolve(ok(detail(request('run', 2, 'run1', status.CANCELLED), 1, 2))); await cancelling;
    assert.equal(visible.length, 0, 'old cancel result must not toast in next scope');
    const failed = request('retry', 2, 'failed-run', status.FAILED);
    value.requests = [failed]; value.selectedRequestId = 'retry'; value.detail = detail(failed, 1, 2);
    const retry = deferred(); api.retryRequest = () => retry.promise;
    const retrying = value.retry(failed); toast.onScopeChange('question:15');
    retry.resolve(ok(detail(request('retry', 3, 'run2', status.QUEUED), 2, 1, 'failed-run',
      [detail(failed, 1, 2).run]))); await retrying;
    assert.equal(visible.length, 0, 'old retry result must not toast in next scope');
    const current = request('retry', 4, 'run2', status.SUCCEEDED);
    api.getRequest = async () => ok(detail(current, 2, 2, 'failed-run', [detail(failed, 1, 2).run]));
    await value.poll_selected();
    assert.equal(visible.length, 0, 'poll must not toast');
    const latest = request('new', 1, 'run1', status.RUNNING);
    value.requests = [latest]; value.selectedRequestId = 'new'; value.detail = detail(latest);
    api.cancelRequest = async () => ok(detail(request('new', 2, 'run1', status.CANCELLED), 1, 2));
    await value.cancel(latest);
    assert.equal(visible.length, 1, 'current scope user action should toast');
    assert.equal(visible[0].key, 'phone-v2-cancel');
    toast.dispose();
  });
  await check('logout disappearance cannot restore cleared account draft', async () => {
    const value = page(); value.loadedQuestionId = '12'; value.prompt = '退出前草稿';
    value.save_draft(); assert.equal(phone_v2_draft_for('account', '12').prompt, '退出前草稿');
    phone_v2_clear_all_intents(); token = ''; account = 'other';
    value.aboutToDisappear();
    assert.equal(phone_v2_draft_for('account', '12').prompt, '');
  });
  console.log(JSON.stringify({ cases, result: 'PASS', boundary: 'actual Phone V2 and Home methods plus ToastStore, with HTTP and ArkUI stubs; no server, device or model' }));
})().catch((error) => { console.error(error); process.exitCode = 1; });
