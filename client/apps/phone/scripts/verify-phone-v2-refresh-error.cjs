#!/usr/bin/env node
// Execute the actual PhoneTutorV2Page methods with controlled API completions.
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
  return 'class ' + name + ' {\n' + component.members
    .filter((node) => !exclude.includes(node.name?.getText(source)))
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
  moduleCode(path.join(phone, 'model/PhoneTutorV2Logic.ets')),
  componentCode(path.join(phone, 'components/PhoneTutorV2Page.ets'), 'PhoneTutorV2Page',
    ['build', 'top_bar', 'embedded_toolbar', 'compose_card', 'notice', 'request_section',
      'request_card', 'selected_detail']),
  'globalThis.types = { PhoneTutorV2Page, V2TutorMode, V2TutorRequestStatus };'
].join('\n');
const emitted = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 },
  reportDiagnostics: true });
assert.equal(emitted.diagnostics.length, 0,
  'actual component method transpilation failed: ' + emitted.diagnostics.map((d) => d.messageText));
let token = 'one';
let account = 'account';
let api = {};
let nextUuid = 0;
const sandbox = {
  LearningV2Api: { create_default: () => api },
  LearningApi: { create_default: () => ({ getQuestion: async () => ({ isSuccess: false }) }) },
  PhoneBackController: class { constructor() { this.handleBack = () => false; } },
  TextAreaController: class { stopEditing() {} },
  authSession: {
    read_access_token: () => token,
    has_valid_session: () => !!token,
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
const { PhoneTutorV2Page, V2TutorMode, V2TutorRequestStatus: status } = sandbox.types;
const ok = (data) => ({ isSuccess: true, data, error: null });
const pageData = (items = []) => ({ items, nextCursor: null });
const network = (message) => ({ isSuccess: false, data: null,
  error: { kind: 'network', message, httpStatus: 0, is_unauthorized: () => false, is_network: () => true } });
const httpError = (message, httpStatus) => ({ isSuccess: false, data: null,
  error: { kind: 'http', message, httpStatus, is_unauthorized: () => false, is_network: () => false } });
const unauthorized = () => ({ isSuccess: false, data: null,
  error: { kind: 'http', message: '登录已过期', httpStatus: 401,
    is_unauthorized: () => true, is_network: () => false } });
const deferred = () => { let resolve; let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject }; };
const flush = () => new Promise((done) => setImmediate(done));
const request = (id, revision, runId, state) => ({
  requestId: id, questionId: '12', prompt: '问题', mode: V2TutorMode.HINT,
  snapshotInput: { kind: 'NEW_CAPTURE' }, snapshotOriginRequestId: null,
  snapshotFileRef: runId === null ? null : 'snapshot', currentRunId: runId,
  status: state, revision, createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z'
});
const detail = (req) => {
  const run = req.currentRunId === null ? null : {
    runId: req.currentRunId, requestId: req.requestId, runNo: 1,
    retryOfRunId: null, status: req.status, revision: req.revision, steps: []
  };
  return { request: req, run, runs: run === null ? [] : [run], result: null };
};
function page() {
  const value = new PhoneTutorV2Page();
  value.questionId = '12'; value.accountId = account; value.alive = true;
  value.visible = true; value.foreground = true;
  return value;
}
let cases = 0;
async function check(name, run) {
  token = 'one'; account = 'account'; api = {};
  try { await run(); cases++; } catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}
(async () => {
  await check('list network failure then same-scope success clears error', async () => {
    const value = page(); api.listRequests = async () => network('列表断网');
    await value.refresh_list(); assert.equal(value.errorMessage, '列表断网');
    assert.equal(value.errorSource, 'list');
    api.listRequests = async () => ok(pageData());
    await value.refresh_list(); assert.equal(value.errorMessage, '');
  });
  await check('list throw then same-scope success clears error', async () => {
    const value = page(); api.listRequests = async () => { throw new Error('offline'); };
    await value.refresh_list(); assert.ok(value.errorMessage.length > 0);
    api.listRequests = async () => ok(pageData());
    await value.refresh_list(); assert.equal(value.errorMessage, '');
  });
  await check('load-more cursor error clears only after same cursor succeeds', async () => {
    const value = page(); value.nextCursor = 'cursor-one';
    api.listRequests = async (_, __, cursor) => cursor === 'cursor-one' ?
      network('分页断网') : ok(pageData());
    await value.load_more(); assert.equal(value.errorMessage, '分页断网');
    assert.equal(value.errorSource, 'list'); assert.equal(value.errorRequestId, 'cursor-one');
    api.listRequests = async () => ok({ items: [], nextCursor: 'cursor-one' });
    await value.refresh_list(); assert.equal(value.errorMessage, '分页断网');
    value.nextCursor = 'cursor-two';
    await value.load_more(); assert.equal(value.errorMessage, '分页断网');
    value.nextCursor = 'cursor-one';
    await value.load_more(); assert.equal(value.errorMessage, '');
  });
  await check('question changed during list response cannot clear its prior error', async () => {
    const value = page(); const late = deferred();
    api.listRequests = async () => network('旧题列表断网');
    await value.refresh_list(); assert.equal(value.errorMessage, '旧题列表断网');
    api.listRequests = () => late.promise;
    const pending = value.refresh_list(); value.questionId = '13';
    late.resolve(ok(pageData())); await pending;
    assert.equal(value.errorMessage, '旧题列表断网');
  });
  await check('question changed during detail response cannot clear its prior error', async () => {
    const value = page(); const late = deferred(); value.selectedRequestId = 'A';
    api.getRequest = async () => network('旧题详情断网');
    await value.refresh_detail('A'); assert.equal(value.errorMessage, '旧题详情断网');
    api.getRequest = () => late.promise;
    const pending = value.refresh_detail('A'); value.questionId = '13';
    late.resolve(ok(detail(request('A', 1, 'runA', status.RUNNING)))); await pending;
    assert.equal(value.errorMessage, '旧题详情断网');
  });
  await check('detail network failure and throw recover on same request only', async () => {
    const value = page(); value.selectedRequestId = 'A';
    api.getRequest = async () => network('A断网');
    await value.refresh_detail('A'); assert.equal(value.errorMessage, 'A断网');
    assert.equal(value.errorSource, 'detail'); assert.equal(value.errorRequestId, 'A');
    api.listRequests = async () => ok(pageData());
    await value.refresh_list(); assert.equal(value.errorMessage, 'A断网');
    api.getRequest = async () => { throw new Error('offline'); };
    await value.refresh_detail('A'); assert.ok(value.errorMessage.length > 0);
    api.getRequest = async () => ok(detail(request('A', 1, 'runA', status.RUNNING)));
    await value.refresh_detail('A'); assert.equal(value.errorMessage, '');
  });
  await check('later same-scope failure survives an earlier successful response', async () => {
    const value = page(); const old = deferred();
    api.getRequest = () => old.promise;
    value.selectedRequestId = 'A'; const pending = value.refresh_detail('A');
    api.getRequest = async () => network('较新失败');
    await value.refresh_detail('A');
    old.resolve(ok(detail(request('A', 1, 'runA', status.RUNNING))));
    await pending; assert.equal(value.errorMessage, '较新失败');
  });
  await check('list and detail success preserve action errors', async () => {
    const value = page(); value.selectedRequestId = 'A';
    value.set_error('动作失败');
    api.listRequests = async () => ok(pageData());
    api.getRequest = async () => ok(detail(request('A', 1, 'runA', status.RUNNING)));
    await value.refresh_list(); await value.refresh_detail('A');
    assert.equal(value.errorMessage, '动作失败'); assert.equal(value.errorSource, 'action');
  });
  await check('cancel error survives automatic detail refresh and poll', async () => {
    const value = page(); const running = request('A', 1, 'runA', status.RUNNING);
    value.requests = [running]; value.selectedRequestId = 'A'; value.detail = detail(running);
    api.cancelRequest = async () => httpError('取消失败', 409);
    api.getRequest = async () => ok(detail(running));
    await value.cancel(running); await flush();
    value.poll_selected(); await flush(); await flush();
    assert.equal(value.errorMessage, '取消失败'); assert.equal(value.errorSource, 'action');
  });
  await check('retry error survives automatic detail refresh', async () => {
    const value = page(); const failed = request('A', 2, 'runA', status.FAILED);
    value.requests = [failed]; value.selectedRequestId = 'A'; value.detail = detail(failed);
    api.retryRequest = async () => httpError('重试失败', 409);
    api.getRequest = async () => ok(detail(failed));
    await value.retry(failed); await flush();
    assert.equal(value.errorMessage, '重试失败'); assert.equal(value.errorSource, 'action');
  });
  await check('create conflict error survives unrelated successful reads', async () => {
    const value = page(); value.loadedOnce = true; value.prompt = '问题';
    api.createRequest = async () => httpError('创建冲突', 409);
    api.listRequests = async () => ok(pageData());
    await value.submit(); await value.refresh_list();
    assert.equal(value.errorMessage, '创建冲突'); assert.equal(value.errorSource, 'action');
  });
  await check('same cancel target succeeds after failure and clears its action error', async () => {
    const value = page(); const running = request('A', 1, 'runA', status.RUNNING);
    value.requests = [running]; value.selectedRequestId = 'A'; value.detail = detail(running);
    api.cancelRequest = async () => httpError('取消失败', 409);
    api.getRequest = async () => ok(detail(running));
    await value.cancel(running); await flush();
    assert.equal(value.errorMessage, '取消失败');
    assert.equal(value.errorActionKind, 'cancel');
    assert.equal(value.errorRequestId, 'A');
    assert.equal(value.errorTargetRunId, 'runA');
    api.cancelRequest = async () => ok(detail(request('A', 2, 'runA', status.CANCELLING)));
    await value.cancel(running);
    assert.equal(value.errorMessage, '');
  });
  await check('same retry target succeeds after failure and clears its action error', async () => {
    const value = page(); const failed = request('A', 2, 'runA', status.FAILED);
    value.requests = [failed]; value.selectedRequestId = 'A'; value.detail = detail(failed);
    api.retryRequest = async () => httpError('重试失败', 409);
    api.getRequest = async () => ok(detail(failed));
    await value.retry(failed); await flush();
    assert.equal(value.errorMessage, '重试失败');
    assert.equal(value.errorActionKind, 'retry');
    assert.equal(value.errorRequestId, 'A');
    assert.equal(value.errorTargetRunId, 'runA');
    const queued = detail(request('A', 3, 'runB', status.QUEUED));
    queued.run.runNo = 2; queued.run.retryOfRunId = 'runA';
    queued.runs = [detail(failed).run, queued.run];
    api.retryRequest = async () => ok(queued);
    await value.retry(failed);
    assert.equal(value.errorMessage, '');
  });
  await check('different request, action kind or target run cannot clear cancel error', async () => {
    for (const variant of ['request', 'kind', 'run']) {
      const value = page(); const running = request('A', 1, 'runA', status.RUNNING);
      value.requests = [running]; value.selectedRequestId = 'A'; value.detail = detail(running);
      api.cancelRequest = async () => httpError('A取消失败', 409);
      api.getRequest = async () => ok(detail(running));
      await value.cancel(running); await flush();
      if (variant === 'kind') {
        const failed = request('A', 2, 'runA', status.FAILED);
        value.requests = [failed]; value.detail = detail(failed);
        api.retryRequest = async () => ok(detail(request('A', 3, 'runB', status.QUEUED)));
        await value.retry(failed);
      } else {
        const other = variant === 'request' ? request('B', 1, 'runB', status.RUNNING) :
          request('A', 2, 'runB', status.RUNNING);
        api.cancelRequest = async () => ok(detail(request(other.requestId, other.revision + 1,
          other.currentRunId, status.CANCELLING)));
        await value.cancel(other);
      }
      assert.equal(value.errorMessage, 'A取消失败', variant);
      assert.equal(value.errorActionKind, 'cancel', variant);
    }
  });
  await check('late same-scope cancel success cannot erase a newer action error', async () => {
    const value = page(); const running = request('A', 1, 'runA', status.RUNNING);
    value.requests = [running]; value.selectedRequestId = 'A'; value.detail = detail(running);
    api.cancelRequest = async () => httpError('先前失败', 409);
    api.getRequest = async () => ok(detail(running));
    await value.cancel(running); await flush();
    const late = deferred(); api.cancelRequest = () => late.promise;
    const pending = value.cancel(running);
    // Model a newer action error while the old HTTP response is pending; preserve its scope.
    value.errorMessage = '较新失败'; value.errorVersion++;
    late.resolve(ok(detail(request('A', 2, 'runA', status.CANCELLING))));
    await pending;
    assert.equal(value.errorMessage, '较新失败');
  });
  await check('create conflict records its request scope and another create success preserves it', async () => {
    const value = page(); value.loadedOnce = true; value.prompt = '问题';
    let firstId = ''; let secondId = '';
    api.createRequest = async (_, __, id) => { firstId = id; return httpError('创建冲突', 409); };
    await value.submit();
    assert.equal(value.errorSource, 'action');
    assert.equal(value.errorActionKind, 'create');
    assert.equal(value.errorRequestId, firstId);
    assert.equal(value.errorTargetRunId, null);
    api.createRequest = async (_, __, id) => { secondId = id;
      return ok(request(id, 0, null, status.WAITING_INPUT)); };
    api.getRequest = async (_, __, id) => ok(detail(request(id, 0, null, status.WAITING_INPUT)));
    await value.submit(); await flush();
    assert.notEqual(secondId, firstId);
    assert.equal(value.errorMessage, '创建冲突');
    assert.equal(value.errorRequestId, firstId);
  });
  await check('waiting-input cancel null target clears only its own action error', async () => {
    const value = page(); const waiting = request('A', 0, null, status.WAITING_INPUT);
    value.requests = [waiting]; value.selectedRequestId = 'A'; value.detail = detail(waiting);
    api.cancelRequest = async () => httpError('等待中取消失败', 409);
    api.getRequest = async () => ok(detail(waiting));
    await value.cancel(waiting); await flush();
    assert.equal(value.errorTargetRunId, null);
    api.cancelRequest = async () => ok(detail(request('A', 1, null, status.CANCELLED)));
    await value.cancel(waiting);
    assert.equal(value.errorMessage, '');
    const running = request('A', 2, 'runA', status.RUNNING);
    api.cancelRequest = async () => httpError('运行中取消失败', 409);
    api.getRequest = async () => ok(detail(running));
    await value.cancel(running); await flush();
    api.cancelRequest = async () => ok(detail(request('A', 3, null, status.CANCELLED)));
    await value.cancel(waiting);
    assert.equal(value.errorMessage, '运行中取消失败');
    assert.equal(value.errorTargetRunId, 'runA');
  });
  await check('A to B to A rejects old detail and error', async () => {
    const value = page(); const oldA = deferred(); let aReads = 0;
    value.requests = [request('A', 1, 'runA', status.RUNNING),
      request('B', 1, 'runB', status.SUCCEEDED)];
    value.selectedRequestId = 'A';
    api.getRequest = async (_, __, id) => {
      if (id === 'A' && ++aReads === 1) return oldA.promise;
      return ok(detail(request(id, id === 'A' ? 3 : 1, 'run' + id,
        id === 'A' ? status.SUCCEEDED : status.SUCCEEDED)));
    };
    const pending = value.refresh_detail('A');
    value.select_request('B'); await flush();
    value.select_request('A'); await flush();
    const current = value.detail;
    assert.equal(current.request.requestId, 'A'); assert.equal(current.request.revision, 3);
    oldA.resolve(network('旧A失败')); await pending;
    assert.equal(value.errorMessage, ''); assert.equal(value.detail.request.revision, 3);
  });
  await check('A to B to A rejects old success even if old revision is higher', async () => {
    const value = page(); const oldA = deferred(); let aReads = 0;
    value.requests = [request('A', 1, 'runA', status.RUNNING), request('B', 1, 'runB', status.SUCCEEDED)];
    value.selectedRequestId = 'A';
    api.getRequest = async (_, __, id) => id === 'A' && ++aReads === 1 ? oldA.promise :
      ok(detail(request(id, id === 'A' ? 2 : 1, 'run' + id, status.SUCCEEDED)));
    const pending = value.refresh_detail('A');
    value.select_request('B'); await flush(); value.select_request('A'); await flush();
    oldA.resolve(ok(detail(request('A', 4, 'runA', status.SUCCEEDED)))); await pending;
    assert.equal(value.selectedRequestId, 'A');
    assert.equal(value.detail.request.revision, 2, 'old selection must not replace current detail');
  });
  await check('nonselected active poll advances summary without changing selected detail or action error', async () => {
    const value = page(); const active = request('A', 1, 'runA', status.RUNNING);
    const history = detail(request('B', 1, 'runB', status.SUCCEEDED));
    value.requests = [active, history.request]; value.selectedRequestId = 'B'; value.detail = history;
    value.set_error('动作失败');
    api.getRequest = async () => ok(detail(request('A', 2, 'runA', status.SUCCEEDED)));
    value.poll_selected(); await flush(); await flush();
    assert.equal(value.requests.find((item) => item.requestId === 'A').status, status.SUCCEEDED);
    assert.equal(value.detail.request.requestId, 'B'); assert.equal(value.errorMessage, '动作失败');
  });
  await check('background active poll failures do not paint selected history error', async () => {
    for (const failure of ['network', 'throw', 'http']) {
      const value = page(); const active = request('A', 1, 'runA', status.RUNNING);
      const history = detail(request('B', 1, 'runB', status.SUCCEEDED));
      value.requests = [active, history.request]; value.selectedRequestId = 'B'; value.detail = history;
      api.getRequest = async (_, __, id) => {
        if (id === 'B') return ok(history);
        if (failure === 'throw') throw new Error('离线');
        return failure === 'network' ? network('A轮询断网') : httpError('A轮询失败', 503);
      };
      value.poll_selected(); await flush(); await flush();
      assert.equal(value.errorMessage, '', failure);
      assert.equal(value.selectedRequestId, 'B', failure);
      assert.equal(value.detail.request.requestId, 'B', failure);
      await value.refresh_detail('B');
      assert.equal(value.errorMessage, '', failure + '/B success');
    }
  });
  await check('background active poll 401 still revokes the current session', async () => {
    const value = page(); const active = request('A', 1, 'runA', status.RUNNING);
    const history = detail(request('B', 1, 'runB', status.SUCCEEDED));
    value.requests = [active, history.request]; value.selectedRequestId = 'B'; value.detail = history;
    let unauthorizedToken = '';
    value.onUnauthorized = (received) => { unauthorizedToken = received; };
    api.getRequest = async () => unauthorized();
    value.poll_selected(); await flush(); await flush();
    assert.equal(unauthorizedToken, 'one');
    assert.equal(token, '');
    assert.equal(value.errorMessage, '');
    assert.equal(value.detail.request.requestId, 'B');
  });
  await check('token, account, question and disappear reject late list and detail success or failure', async () => {
    for (const change of ['token', 'account', 'question', 'disappear']) {
      for (const method of ['list', 'detail']) {
        for (const outcome of ['success', 'failure']) {
          const value = page(); const late = deferred();
          value.requests = [request('A', 1, 'runA', status.RUNNING)];
          value.selectedRequestId = 'A';
          if (method === 'list') api.listRequests = () => late.promise;
          else api.getRequest = () => late.promise;
          const pending = method === 'list' ? value.refresh_list() : value.refresh_detail('A');
          if (change === 'token') token = 'new';
          if (change === 'account') account = 'other';
          if (change === 'question') { value.questionId = '13'; value.seq++; }
          if (change === 'disappear') value.aboutToDisappear();
          const result = outcome === 'failure' ? network('旧读取失败') : method === 'list' ?
            ok(pageData([request('B', 2, 'runB', status.SUCCEEDED)])) :
            ok(detail(request('A', 2, 'runA', status.SUCCEEDED)));
          late.resolve(result); await pending;
          const label = change + '/' + method + '/' + outcome;
          assert.equal(value.errorMessage, '', label);
          assert.equal(value.message, '', label);
          assert.equal(value.detail, null, label);
          assert.equal(value.requests.length, 1, label);
          assert.equal(value.requests[0].requestId, 'A', label);
          assert.equal(value.requests[0].revision, 1, label);
          token = 'one'; account = 'account';
        }
      }
    }
  });
  await check('mismatched detail question or request cannot clear error or fill detail', async () => {
    const value = page(); value.selectedRequestId = 'A';
    api.getRequest = async () => network('A详情断网');
    await value.refresh_detail('A');
    assert.equal(value.errorMessage, 'A详情断网');
    for (const mismatch of ['question', 'request']) {
      const wrong = request(mismatch === 'request' ? 'B' : 'A', 2, 'runA', status.SUCCEEDED);
      if (mismatch === 'question') wrong.questionId = '13';
      api.getRequest = async () => ok(detail(wrong));
      await value.refresh_detail('A');
      assert.equal(value.errorMessage, 'A详情断网', mismatch);
      assert.equal(value.detail, null, mismatch);
      assert.equal(value.errorSource, 'detail', mismatch);
    }
  });
  await check('UNKNOWN create keeps original id through unrelated reads and confirms by original id', async () => {
    const value = page(); value.loadedOnce = true; value.prompt = '问题';
    const confirmation = deferred(); let postId = '';
    api.createRequest = async (_, __, id) => { postId = id; return network('回执丢失'); };
    api.getRequest = (_, __, id) => id === postId ? confirmation.promise :
      Promise.resolve(ok(detail(request(id, 1, 'runB', status.SUCCEEDED))));
    api.listRequests = async () => ok(pageData());
    await value.submit(); assert.equal(value.createIntent.requestId, postId);
    await value.refresh_list(); assert.equal(value.createIntent.requestId, postId);
    await value.refresh_detail('other'); assert.equal(value.createIntent.requestId, postId);
    confirmation.resolve(ok(detail(request(postId, 0, null, status.WAITING_INPUT))));
    await flush(); assert.equal(value.createIntent.requestId, '');
    assert.equal(value.requests.some((item) => item.requestId === postId), true);
  });
  await check('late old confirmation cannot clear a newer create intent', async () => {
    const value = page(); const old = deferred();
    value.adopt_intent();
    value.createIntent.requestId = 'old'; value.createIntent.prompt = '旧问题';
    api.getRequest = () => old.promise;
    const pending = value.confirm_create('old');
    value.createIntent.requestId = 'new'; value.createIntent.prompt = '新问题';
    old.resolve(ok(detail(request('old', 0, null, status.WAITING_INPUT))));
    await pending;
    assert.equal(value.createIntent.requestId, 'new');
    assert.equal(value.createIntent.prompt, '新问题');
  });
  console.log(JSON.stringify({ cases, result: 'PASS',
    boundary: 'actual PhoneTutorV2Page methods; fake API/session/ArkUI, no network, server, model, build or device' }));
})().catch((error) => { console.error(error); process.exitCode = 1; });
