#!/usr/bin/env node
// Execute the actual PhoneTutorV2Page async methods; replace only ArkUI and HTTP boundaries.
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
const phonePagePath = process.env.BETTERLEARNING_PHONE_PAGE_UNDER_TEST || path.join(phone, 'components/PhoneTutorV2Page.ets');
function rowBindingCode(file) {
  const source=parse(file),component=source.statements.find(node=>node.name?.text==='PhoneTutorV2Page');
  function expressions(name){const result=[];const member=component.members.find(node=>node.name?.text===name);
    function visit(node){if((ts.isCallExpression(node)||node.kind===ts.SyntaxKind.EtsComponentExpression)&&node.expression.getText(source)==='Text')result.push({kind:'text',value:node.arguments[0].getText(source)});
      if(ts.isIfStatement(node))result.push({kind:'if',value:node.expression.getText(source)});ts.forEachChild(node,visit);}visit(member);return result;}
  const row=expressions('request_card'),actions=expressions('selected_detail');
  const stage=row.find(item=>item.kind==='text'&&(item.value.includes('phone_v2_stage_text(')||item.value.includes('this.request_stage('))).value;
  const loading=row.find(item=>item.kind==='if'&&(item.value.includes('phone_v2_active(')||item.value.includes('this.request_active('))).value;
  const cancel=actions.find(item=>item.kind==='if'&&item.value.includes('CANCELLING')).value;
  return 'globalThis.rowBindings={stage:function(request,requestId){return '+stage+';},loading:function(request,requestId){return '+loading+';},cancel:function(request,requestId){return '+cancel+';}};';
}
const code = [
  'const V2_PROMPT_MAX = 4000; const V2_POLL_MS = 3000;',
  moduleCode(path.join(multi, 'LearningV2Models.ets')),
  moduleCode(path.join(multi, 'LearningV2State.ets')),
  moduleCode(path.join(multi, 'CitationMarkers.ets')),
  moduleCode(path.join(phone, 'model/PhoneTutorV2Logic.ets')),
  componentCode(phonePagePath, 'PhoneTutorV2Page',
    ['build', 'top_bar', 'embedded_toolbar', 'compose_card', 'notice', 'request_section',
      'request_card', 'selected_detail']),
  rowBindingCode(phonePagePath),
  'globalThis.types = { PhoneTutorV2Page, PhoneV2MessageDataSource, V2TutorMode, V2TutorRequestStatus };'
].join('\n');
const emitted = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 },
  reportDiagnostics: true });
assert.equal(emitted.diagnostics.length, 0,
  'actual component method transpilation failed: ' + emitted.diagnostics.map((d) => d.messageText));
let token = 'one';
let account = 'account';
let api = {};
let nextUuid = 0;
let frameCallbacks = new Map(); let nextFrame = 0;
const sandbox = {
  LearningV2Api: { create_default: () => api },
  LearningApi: { create_default: () => ({ getQuestion: async () => ({ isSuccess: false }) }) },
  PhoneBackController: class { constructor() { this.handleBack = () => false; } },
  TextAreaController: class { stopEditing() {} },
  ListScroller: class { constructor() { this.rects = new Map(); this.scrolls = []; this.atEnd = false; }
    getItemRect(index) { return this.rects.get(index) || { y: 0, height: 0 }; }
    isAtEnd() { return this.atEnd; }
    scrollToIndex(...args) { this.scrolls.push(args); } },
  LengthMetrics: { vp: (value) => value },
  ScrollAlign: { START: 0 },
  setTimeout: (fn) => { const id = ++nextFrame; frameCallbacks.set(id, fn); return id; },
  clearTimeout: (id) => frameCallbacks.delete(id),
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
  token = 'one'; account = 'account'; api = {}; frameCallbacks.clear();
  sandbox.phone_v2_clear_all_intents();
  try { await run(); cases++; } catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}

function runFrames() { const pending = Array.from(frameCallbacks.values()); frameCallbacks.clear(); pending.forEach((fn) => fn()); }
function published(id, time = '2026-10-25T00:00:00Z') {
  const req = request(id, 2, 'run-' + id, status.SUCCEEDED); req.createdAt = time;
  const value = detail(req, 1, 2); value.result = { resultId: 'result-' + id, status: 'VERIFIED',
    mode: 'HINT', bodyMarkdown: '完整讲解'.repeat(3000), citations: [], createdAt: time }; return value;
}
const net = () => ({ isSuccess: false, data: null, error: { httpStatus: 0, message: '离线',
  is_unauthorized: () => false, is_network: () => true } });
(async () => {
  await check('all published details remain independently expanded and cached', async () => {
    const value = page(); const a = published('a'), b = published('b'), c = published('c');
    value.requests = [a.request, b.request, c.request]; value.selectedRequestId = 'a';
    api.getRequest = async (_, __, id) => ok({ a, b, c }[id]);
    await value.hydrate_details();
    assert.equal(value.details.length, 3); assert.equal(value.answer_markdown('a').length, 12000);
    assert.equal(value.answer_markdown('c').length, 12000);
    assert.equal(value.is_collapsed('a'), false); assert.equal(value.is_collapsed('b'), false);
    value.toggle_answer('b'); assert.equal(value.is_collapsed('b'), true);
    assert.equal(value.is_collapsed('a'), false); assert.equal(value.is_collapsed('c'), false);
    assert.equal(value.newAnswerRequestId, '', 'loading historical answers must not announce new results');
  });
  await check('older page hydrates every body and preserves stable visible anchor and relative offset', async () => {
    const value = page(); const b = published('b', '2026-10-25T02:00:00Z');
    const c = published('c', '2026-10-25T03:00:00Z'); const a = published('a', '2026-10-25T01:00:00Z');
    value.requests = [b.request, c.request]; value.details = [b, c]; value.nextCursor = 'older';
    value.firstVisibleIndex = 2; value.followLatest = false;
    value.conversationScroller.rects.set(2, { y: -37, height: 600 });
    api.listRequests = async () => ok({ items: [a.request], nextCursor: null });
    api.getRequest = async () => ok(a);
    await value.load_more(); await flush(); runFrames();
    assert.equal(value.requests.map((req) => req.requestId).join(','), 'a,b,c');
    assert.equal(value.details.length, 3); assert.equal(value.anchorRequestId, 'c');
    assert.equal(value.anchorOffset, -45);
    assert.equal(value.conversationScroller.scrolls.at(-1)[0], 3);
    assert.equal(value.conversationScroller.scrolls.at(-1)[3].extraOffset, 45);
    assert.equal(value.answer_markdown('a').length, 12000);
  });
  await check('new answer does not steal history reading position; explicit reveal targets its start', async () => {
    const value = page(); const history = published('history', '2026-10-24T00:00:00Z');
    const active = request('active', 1, 'run-active', status.RUNNING);
    value.requests = [history.request, active]; value.details = [history, detail(active)];
    value.followLatest = false; value.anchorRequestId = 'history'; value.anchorOffset = -51;
    value.receive_detail(published('active')); runFrames();
    assert.equal(value.newAnswerRequestId, 'active'); assert.equal(value.anchorRequestId, 'history');
    assert.equal(value.conversationScroller.scrolls.at(-1)[0], 1);
    value.reveal_answer('active'); runFrames();
    assert.equal(value.conversationScroller.scrolls.at(-1)[0], 2);
    assert.equal(value.conversationScroller.scrolls.at(-1)[3].extraOffset, 0);
    assert.equal(value.newAnswerRequestId, '');
  });
  await check('account plus question restores draft, body, collapse, question state and anchor', async () => {
    const value = page(); value.loadedQuestionId = '12'; value.prompt = '半句草稿';
    const a = published('a'); value.requests = [a.request]; value.details = [a]; value.loadedOnce = true;
    value.questionBody = '完整题面'; value.questionLabel = '关于抛物线的题'; value.questionExpanded = true;
    value.collapsedRequestIds = ['a']; value.anchorRequestId = 'a'; value.anchorOffset = -22;
    value.contextParentRequestId = 'a'; value.reuseRequestId = 'snapshot-source'; value.save_draft();
    const other = page(); other.questionId = '13'; other.adopt_draft(); assert.equal(other.prompt, '');
    const restored = page(); restored.adopt_draft(); assert.equal(restored.prompt, '半句草稿');
    assert.equal(restored.questionBody, '完整题面'); assert.equal(restored.details.length, 1);
    assert.equal(restored.is_collapsed('a'), true); assert.equal(restored.questionExpanded, true);
    assert.equal(restored.anchorOffset, -22); assert.equal(restored.reuseRequestId, 'snapshot-source');
    account = 'other'; const isolated = page(); isolated.adopt_draft(); assert.equal(isolated.prompt, '');
    sandbox.phone_v2_clear_all_intents(); account = 'account';
    const cleared = page(); cleared.adopt_draft(); assert.equal(cleared.details.length, 0);
    assert.equal(sandbox.phone_v2_recent_questions('account').every((q) => !q.hasAnswer), true);
  });
  await check('question expand-collapse restores the original conversation anchor', async () => {
    const value = page(); value.requests = [published('a').request];
    value.anchorRequestId = 'a'; value.anchorOffset = -63; value.toggle_question();
    assert.equal(value.questionExpanded, true); value.anchorOffset = 100;
    value.toggle_question(); runFrames(); assert.equal(value.questionExpanded, false);
    assert.equal(value.anchorOffset, -63); assert.equal(value.conversationScroller.scrolls.at(-1)[3].extraOffset, 63);
  });
  await check('real stage is unique current-run RUNNING role, independent of unrelated revisions', async () => {
    const req = request('a', 1, 'current', status.RUNNING); const data = detail(req);
    data.run.steps = [{ stepId: 'solve', role: 'SOLVE', status: 'RUNNING', revision: 1 },
      { stepId: 'teach', role: 'TEACH', status: 'SUCCEEDED', revision: 999 }];
    assert.equal(sandbox.phone_v2_stage_text(req, data), '正在分析题目');
    data.run.steps[1].status = 'RUNNING'; assert.equal(sandbox.phone_v2_stage_text(req, data), '正在生成讲解');
    data.run.runId = 'old'; assert.equal(sandbox.phone_v2_stage_text(req, data), '正在生成讲解');
    for (const [state, text] of [[status.WAITING_INPUT,'等待平板同步作答'],[status.QUEUED,'正在排队'],
      [status.CANCELLING,'正在取消'],[status.CANCELLED,'已取消'],[status.TIMED_OUT,'等待超时']]) {
      assert.equal(sandbox.phone_v2_stage_text({ ...req, status: state }, null), text);
    }
  });
  await check('semantic parent and snapshot source freeze separately; editing during POST preserves next draft', async () => {
    const value = page(); const a = published('a'); value.requests = [a.request]; value.details = [a];
    value.loadedOnce = true; value.prompt = '你刚才第二步为什么这样做';
    const post = deferred(); let sent;
    api.createRequest = (...args) => { sent = args; return post.promise; };
    api.getRequest = async (_, __, id) => ok(detail({ ...request(id, 0, null, status.WAITING_INPUT), contextParentRequestId: 'a' }));
    const pending = value.submit(); assert.equal(sent[6], 'a'); assert.equal(sent[5].kind, 'NEW_CAPTURE');
    value.prompt = '下一条草稿'; value.fullSolution = true; value.reuseRequestId = 'different-snapshot';
    post.resolve(ok({ ...request(sent[2], 0, null, status.WAITING_INPUT), contextParentRequestId: 'a' }));
    await pending; await flush(); assert.equal(value.prompt, '下一条草稿'); assert.equal(value.fullSolution, true);
    assert.equal(value.reuseRequestId, 'different-snapshot'); assert.equal(value.createIntent.requestId, '');
  });
  await check('unknown POST never resends or changes frozen parent when draft and history change', async () => {
    const value = page(); const a = published('a'); value.requests = [a.request]; value.details = [a];
    value.loadedOnce = true; value.prompt = '原提问'; let posts = 0, gets = 0;
    api.createRequest = async () => { posts++; return net(); }; api.getRequest = async () => { gets++; return net(); };
    await value.submit(); await flush(); const original = value.createIntent.requestId;
    assert.equal(value.createIntent.contextParentRequestId, 'a');
    const b = published('b', '2026-10-26T00:00:00Z'); value.requests.push(b.request); value.details.push(b);
    value.prompt = '下一问题'; value.contextParentRequestId = 'b'; value.reuseRequestId = 'b';
    await value.submit(); await flush(); assert.equal(posts, 1); assert.ok(gets >= 2);
    assert.equal(value.createIntent.requestId, original); assert.equal(value.createIntent.contextParentRequestId, 'a');
    assert.equal(value.createIntent.prompt, '原提问'); assert.equal(value.prompt, '下一问题');
  });
  await check('parent acknowledgement mismatch cannot publish a continuous turn', async () => {
    const value = page(); const a = published('a'); value.requests = [a.request]; value.details = [a];
    value.loadedOnce = true; value.prompt = '继续';
    api.createRequest = async (_, __, id) => ok(request(id, 0, null, status.WAITING_INPUT));
    api.getRequest = async (_, __, id) => ok(detail(request(id, 0, null, status.WAITING_INPUT)));
    await value.submit(); await flush(); assert.ok(value.createIntent.requestId.length > 0);
    assert.equal(value.requests.length, 1); assert.match(value.message, /未确认/);
  });
  await check('definite 4xx rejection keeps question text and ends unknown confirmation', async () => {
    for (const httpStatus of [400, 404, 409, 413]) {
      const value = page(); value.loadedOnce = true; value.prompt = '保留未发送问题'; let gets = 0;
      api.createRequest = async () => ({ isSuccess: false, data: null, error: { httpStatus,
        message: '请重新选择上下文', is_unauthorized: () => false, is_network: () => false } });
      api.getRequest = async () => { gets++; return net(); };
      await value.submit(); assert.equal(value.prompt, '保留未发送问题');
      assert.equal(value.createIntent.requestId, ''); assert.equal(gets, 0);
    }
  });
  await check('unconfirmed paginated history blocks silent independent prompt until explicitly selected', async () => {
    const value = page(); value.loadedOnce = true; value.prompt = '继续问'; value.nextCursor = 'more';
    let posts = 0; api.createRequest = async () => { posts++; return net(); };
    assert.equal(value.context_unconfirmed(), true); assert.equal(value.can_send(), false);
    await value.submit(); assert.equal(posts, 0); value.independentQuestion = true;
    assert.equal(value.can_send(), true); await value.submit(); assert.equal(posts, 1);
  });
  await check('terminal detail removes creation waiting notice and current run cannot regress', async () => {
    const value = page(); const active = request('a', 1, 'run-a', status.RUNNING);
    value.requests = [active]; value.details = [detail(active)]; value.message = '请让平板打开本题';
    value.receive_detail(published('a')); assert.equal(value.message, '');
    assert.equal(value.details[0].request.status, status.SUCCEEDED);
    value.receive_detail(detail(active)); assert.equal(value.details[0].request.status, status.SUCCEEDED);
  });
  await check('explicit GET 404 enables manual resend of same frozen operation without automatic POST', async () => {
    const value = page(); const a = published('a'); value.requests = [a.request]; value.details = [a];
    value.loadedOnce = true; value.prompt = '原问题'; value.reuseRequestId = 'snapshot-a'; let posts = [];
    api.createRequest = async (...args) => { posts.push(args); return net(); };
    api.getRequest = async () => ({ isSuccess: false, data: null, error: { httpStatus: 404,
      is_unauthorized: () => false, is_network: () => false } });
    await value.submit(); await flush(); assert.equal(posts.length, 1); assert.equal(value.createIntent.confirmedMissing, true);
    value.prompt = '下一草稿'; value.fullSolution = true; value.contextParentRequestId = 'changed'; value.reuseRequestId = '';
    await value.resend_create(); await flush(); assert.equal(posts.length, 2);
    assert.equal(JSON.stringify(posts[0]), JSON.stringify(posts[1])); assert.equal(value.prompt, '下一草稿');
  });
  await check('cancel during detail hydration cannot strand its busy flag or prevent future body hydration', async () => {
    const value = page(); const active = request('active', 1, 'run-active', status.RUNNING);
    const old = published('old'); value.requests = [active, old.request];
    const pendingDetail = deferred(); api.getRequest = () => pendingDetail.promise;
    const hydration = value.hydrate_details(); assert.equal(value.hydrating, true);
    api.cancelRequest = async () => ok(detail({ ...active, revision: 2, status: status.CANCELLED }));
    await value.cancel(active); assert.equal(value.hydrating, false);
    pendingDetail.resolve(ok(detail(active))); await hydration;
    api.getRequest = async (_, __, id) => ok(id === 'old' ? old : detail({ ...active, revision: 2, status: status.CANCELLED }));
    await value.hydrate_details(); assert.equal(value.hydrating, false); assert.equal(value.answer_markdown('old').length, 12000);
  });
  await check('retry during hydration releases it and OUTCOME_UNKNOWN stays explicit', async () => {
    const value = page(); const failed = request('failed', 2, 'run-failed', status.FAILED);
    const failedDetail = detail(failed, 1, 2); failedDetail.run.reason = 'OUTCOME_UNKNOWN';
    const old = published('old'); value.requests = [failed, old.request]; value.details = [failedDetail];
    assert.equal(value.outcome_unknown('failed'), true);
    const pendingDetail = deferred(); api.getRequest = () => pendingDetail.promise;
    const hydration = value.hydrate_details(); assert.equal(value.hydrating, true);
    const retried = detail({ ...failed, revision: 3, currentRunId: 'retry-run', status: status.QUEUED }, 2, 1,
      'run-failed', [failedDetail.run]);
    api.retryRequest = async () => ok(retried); await value.retry(failed); assert.equal(value.hydrating, false);
    pendingDetail.resolve(ok(old)); await hydration; api.getRequest = async () => ok(old);
    await value.hydrate_details(); assert.equal(value.answer_markdown('old').length, 12000);
    assert.equal(value.outcome_unknown('failed'), false);
  });
  await check('RUNNING summary without prior detail still announces completion without taking old anchor', async () => {
    const value = page(); const active = request('active', 1, 'run-active', status.RUNNING);
    value.requests = [published('history').request, active]; value.followLatest = false;
    value.anchorRequestId = 'history'; value.anchorOffset = -17; value.receive_detail(published('active'));
    assert.equal(value.newAnswerRequestId, 'active'); assert.equal(value.anchorRequestId, 'history');
  });
  await check('ISO fractional and timezone variants sort by epoch and select the actual latest parent', async () => {
    const older=published('older','2026-10-25T00:00:00Z');
    const newer=published('newer','2026-10-25T00:00:00.100Z');
    assert.equal(sandbox.phone_v2_chronological([newer.request,older.request]).at(-1).requestId,'newer');
    assert.equal(sandbox.phone_v2_context_parent([older.request,newer.request],[older,newer]),'newer');
    const offset=published('offset','2026-10-25T08:00:00.200+08:00');
    assert.equal(sandbox.phone_v2_context_parent([offset.request,newer.request],[newer,offset]),'offset');
    const same=published('a','2026-10-25T08:00:00.100+08:00');
    assert.equal(sandbox.phone_v2_chronological([newer.request,same.request]).map(r=>r.requestId).join(','),'a,newer');
  });
  await check('context rejection exposes explicit independent action without silently changing semantic parent', async () => {
    for (const [httpStatus,code] of [[413,'A0431'],[409,'A0456']]) {
      const value=page();const a=published('a');value.requests=[a.request];value.details=[a];
      value.loadedOnce=true;value.prompt='继续追问';const parents=[];
      api.createRequest=async(...args)=>{parents.push(args[6]);return {isSuccess:false,data:null,error:{httpStatus,
        response:{code},message:'父轮不可用',is_unauthorized:()=>false,is_network:()=>false}};};
      await value.submit();assert.equal(parents[0],'a');assert.equal(value.independentQuestion,false);
      assert.equal(value.prompt,'继续追问');assert.equal(value.can_choose_independent(),true);
      value.choose_independent();await value.submit();assert.equal(parents[1],null);
    }
  });
  await check('slow valid question body survives a submit sequence change', async () => {
    const value=page();value.loadedOnce=true;value.prompt='问题';const pendingBody=deferred();
    value.learningApi={getQuestion:()=>pendingBody.promise};const loadingBody=value.load_question();
    api.createRequest=async()=>net();api.getRequest=async()=>net();await value.submit();
    pendingBody.resolve(ok({questionId:'12',summary:'慢返回题目',bodyMarkdown:'完整慢题面'}));await loadingBody;
    assert.equal(value.questionBody,'完整慢题面');assert.equal(value.questionLabel,'慢返回题目');
  });
  await check('refresh recovers failed body fetch and stale body fetch cannot overwrite new body', async () => {
    const value=page();let bodyCalls=0;value.learningApi={getQuestion:async()=>{bodyCalls++;return net();}};
    await value.load_question();assert.equal(value.questionBody,'');
    const oldBody=deferred();value.learningApi={getQuestion:()=>oldBody.promise};const old=value.load_question();
    value.learningApi={getQuestion:async()=>{bodyCalls++;return ok({questionId:'12',summary:'恢复题面',bodyMarkdown:'已恢复'});}};
    api.listRequests=async()=>ok({items:[],nextCursor:null});value.refresh_conversation();await flush();
    oldBody.resolve(ok({questionId:'12',summary:'旧题面',bodyMarkdown:'旧正文'}));await old;
    assert.equal(bodyCalls,2);assert.equal(value.questionBody,'已恢复');assert.equal(value.questionLabel,'恢复题面');
  });
  await check('long user prompt reveals assistant start after measured native layout, preserving answer origin', async () => {
    const value=page();const active=request('active',1,'run-active',status.RUNNING);value.requests=[active];
    value.reveal_answer('active');runFrames();value.remember_user_height('active',900);runFrames();
    assert.equal(value.anchorOffset,-910);assert.equal(value.conversationScroller.scrolls.at(-1)[3].extraOffset,910);
    value.details=[detail(active)];value.followLatest=false;value.receive_detail(published('active'));runFrames();
    assert.equal(value.newAnswerRequestId,'');assert.equal(value.anchorOffset,-910);
  });
  await check('actual LazyForEach data source preserves request identity and notifies only changed messages', async () => {
    const source=new sandbox.types.PhoneV2MessageDataSource();const events=[];
    const listener={onDataReloaded:()=>events.push('reload'),onDataChange:(index)=>events.push('change:'+index)};
    source.registerDataChangeListener(listener);source.registerDataChangeListener(listener);
    const a=request('a',1,'run-a',status.RUNNING);const b=published('b').request;
    source.replace([a,b]);assert.equal(source.totalCount(),2);assert.equal(events.join(','),'reload');events.length=0;
    source.replace([{...a}, {...b}]);assert.equal(events.length,0,'unchanged poll must preserve mounted bodies');
    source.replace([{...a,revision:2,status:status.SUCCEEDED},b]);assert.equal(events.join(','),'change:0');
    assert.equal(source.getData(0).requestId,'a');events.length=0;source.changed('b');assert.equal(events[0],'change:1');
    events.length=0;const older=published('older').request;source.replace([older,source.getData(0),b]);
    assert.equal(events.join(','),'reload');assert.equal(source.getData(1).requestId,'a');
    source.unregisterDataChangeListener(listener);events.length=0;source.changed('a');assert.equal(events.length,0);
  });
  await check('padded geometry: repeated question expand-collapse keeps measured marker at same physical coordinate', async () => {
    // Contract measured on device: getItemRect uses the List origin, while START uses its padded content origin.
    const padding=8, density=3, listScreenTop=152, markerWithinItem=480;
    const geometry={itemY:-315, getItemRect(index){return {y:index===1?this.itemY:padding,height:1400};},
      isAtEnd(){return false;}, scrollToIndex(index,_smooth,_align,options){
        this.itemY=index===1?padding-(options?.extraOffset??0):padding+600;
      }};
    const value=page();value.loadedQuestionId='12';value.requests=[published('a').request];
    value.conversationScroller=geometry;value.firstVisibleIndex=1;value.followLatest=false;
    const marker=()=>Math.round((listScreenTop+geometry.itemY+markerWithinItem)*density);
    const original=marker();assert.equal(original,951);
    for(let cycle=0;cycle<10;cycle++){
      value.toggle_question();value.toggle_question();runFrames();value.capture_anchor();
      assert.equal(marker(),original,'cycle '+cycle+' must not accumulate content padding into the reading position');
      assert.equal(value.anchorRequestId,'a');
    }
  });
  await check('padded geometry: repeated account-question cache restoration preserves each independent reading position', async () => {
    const padding=8,density=3,listScreenTop=152,markerWithinItem=480;
    function geometry(y){return {itemY:y,getItemRect(){return {y:this.itemY,height:1400};},isAtEnd(){return false;},
      scrollToIndex(_index,_smooth,_align,options){this.itemY=padding-(options?.extraOffset??0);}};}
    const first=page();first.loadedQuestionId='12';first.requests=[published('a').request];
    first.conversationScroller=geometry(-315);first.firstVisibleIndex=1;first.followLatest=false;first.capture_anchor();first.save_draft();
    const second=page();second.questionId='13';second.loadedQuestionId='13';
    second.requests=[{...published('b').request,questionId:'13'}];second.conversationScroller=geometry(-105);
    second.firstVisibleIndex=1;second.followLatest=false;second.capture_anchor();second.save_draft();
    for(let cycle=0;cycle<5;cycle++){
      for(const [questionId,requestId,expectedY]of [['12','a',-315],['13','b',-105]]){
        const restored=page();restored.questionId=questionId;restored.loadedQuestionId=questionId;
        restored.conversationScroller=geometry(padding);restored.adopt_draft();restored.schedule_restore();runFrames();
        const actual=Math.round((listScreenTop+restored.conversationScroller.itemY+markerWithinItem)*density);
        assert.equal(actual,Math.round((listScreenTop+expectedY+markerWithinItem)*density));
        assert.equal(restored.anchorRequestId,requestId);restored.firstVisibleIndex=1;restored.capture_anchor();restored.save_draft();
      }
    }
  });
  await check('row binding: captured initial QUEUED item advances through current steps and terminal actions without remount', async () => {
    const value=page(),initial=request('bound',2,'run-bound',status.QUEUED),captured=initial,id=initial.requestId;
    value.requests=[initial];value.details=[detail(initial,1,0)];value.on_requests_changed();
    const events=[];value.messageDataSource.registerDataChangeListener({onDataReloaded:()=>events.push('reload'),onDataChange:index=>events.push('change:'+index)});
    // Evaluate expressions taken from the actual UI Builder AST, keeping the original row closure item forever.
    const stage=()=>sandbox.rowBindings.stage.call(value,captured,id);
    const loading=()=>sandbox.rowBindings.loading.call(value,captured,id),cancel=()=>sandbox.rowBindings.cancel.call(value,captured,id);
    assert.equal(stage(),'正在排队');assert.equal(loading(),true);assert.equal(cancel(),true);
    let previous=[];
    for(const [role,text]of [['SOLVE','正在分析题目'],['DIAGNOSE','正在检查作答思路'],['TEACH','正在整理讲解'],['REVIEW','正在核对讲解']]){
      const running=detail({...initial,status:status.RUNNING,revision:3},1,1);
      running.run.steps=[...previous,{stepId:role,role,status:'RUNNING',revision:0}];
      const before=events.length;value.receive_detail(running);value.on_requests_changed();
      assert.equal(stage(),text,'same row must render '+role+' from current state');assert.equal(loading(),true);
      assert.ok(events.length>before,'step-only progress with run revision 1 must notify the same item');
      previous=running.run.steps.map(step=>({...step,status:'SUCCEEDED',revision:1}));
    }
    const done=published('bound');done.request.revision=4;done.run.revision=2;done.run.steps=previous;
    value.receive_detail(done);value.on_requests_changed();assert.equal(stage(),'讲解已完成');
    assert.equal(loading(),false);assert.equal(cancel(),false);assert.equal(value.is_collapsed(id),false);
    value.toggle_answer(id);assert.equal(stage(),'讲解已完成');assert.equal(loading(),false);assert.equal(cancel(),false);
    assert.equal(captured.status,status.QUEUED,'test must retain the original DTO and stable request identity');
    assert.equal(value.messageDataSource.getData(0).requestId,id);
  });
  await check('row actions: click resolves latest currentRun and does not cancel or retry a terminal success', async () => {
    const value=page(),id='action';const running=request(id,3,'latest-run',status.RUNNING);
    value.requests=[running];let target=null,cancels=0,retries=0;
    api.cancelRequest=async(_token,_question,_id,run)=>{cancels++;target=run;return ok(detail({...running,status:status.CANCELLING,revision:4}));};
    value.cancel_current(id);await flush();assert.equal(target,'latest-run');assert.equal(cancels,1);
    const done=published(id);done.request.revision=5;value.requests=[done.request];value.details=[done];
    api.retryRequest=async()=>{retries++;return net();};value.cancel_current(id);value.retry_current(id);await flush();
    assert.equal(cancels,1);assert.equal(retries,0);
    value.requests=[];assert.equal(value.request_stage(id),'');assert.equal(value.request_active(id),false);
  });
  console.log(JSON.stringify({ cases, result: 'PASS', boundary: 'actual Phone production methods and reducers with controlled API, native layout/scroll callbacks stubbed; no ArkUI/device/model claim' }));
})().catch((error) => { console.error(error); process.exitCode = 1; });
