#!/usr/bin/env node
// Executes real component methods, draft state and the one-shot handoff with controlled async edges.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const parserOptions = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const base = 'client/apps/phone/src/main/ets/';
const original = { path: '/owned/original.jpg', contentType: 'image/jpeg', width: 3000, height: 4000, byteLength: 12345 };
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function load(relative, dependencies = {}) {
  const module = { exports: {} };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root, relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 }
  }).outputText, { module, exports: module.exports, Observed: value => value,
    require: key => { assert.ok(key in dependencies, 'Unexpected dependency ' + key); return dependencies[key]; } });
  return module.exports;
}
function component(relative, name, edges) {
  const source = fs.readFileSync(path.join(root, base, relative), 'utf8');
  const ast = ts.createSourceFile(relative, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, parserOptions);
  assert.equal(ast.parseDiagnostics.length, 0, relative);
  const declaration = ast.statements.find(node => node.kind === ts.SyntaxKind.StructDeclaration && node.name.text === name);
  assert.ok(declaration, name);
  const methods = declaration.members.filter(member => member.name && member.name.getText(ast) !== 'build' &&
    !(ts.getDecorators(member) || []).some(d => d.expression.getText(ast) === 'Builder'));
  const text = ast.statements.filter(ts.isEnumDeclaration).map(n => n.getText(ast)).join('\n') + '\nclass Harness {\n' +
    methods.map(n => n.getText(ast).replace(/@(State|Prop|ObjectLink)\s*/g, '').replace(/@Watch\([^)]*\)\s*/g, '')).join('\n') +
    '\n}\nglobalThis.Harness = Harness;';
  const context = { ...edges };
  vm.runInNewContext(ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2021 } }).outputText, context);
  return new context.Harness();
}
function harness() {
  let token = 'account-A', serial = 0, timerSerial = 0;
  const calls = { photos: [], discarded: [], copies: [], previews: [], starts: [], queries: [], saves: [], picker: [] };
  const timers = new Map();
  const models = load('client/common/src/main/ets/features/tutor/BatchOcrModels.ets');
  const imageSource = load(base + 'features/ocr/BatchQuestionImageSource.ets', {
    '@kit.CoreFileKit': {}, '@kit.ImageKit': {}, '@kit.MediaLibraryKit': {}, common: {},
    './QuestionImageSource': { discardPendingPhoto: uri => calls.photos.push(uri) }
  });
  const authSession = { read_access_token: () => token, matches_access_token: owner => owner === token,
    has_valid_session: () => token !== null, clear_session_if_token: owner => { if (owner === token) token = null; } };
  const common = { ...models, authSession, MAX_QUESTION_MARKDOWN_LENGTH: 20000,
    LearningSubject: { HIGHER_MATHEMATICS: 'math', OTHER: 'other' }, AuthFailureKind: { VALIDATION: 'VALIDATION', HTTP: 'HTTP' },
    ManualQuestionInput: class {
      constructor(requestId, subject, bodyMarkdown) { Object.assign(this, { requestId, subject, bodyMarkdown }); }
      static create(subject, body) { return new this('save-' + (++serial), subject, body); }
    }
  };
  const drafts = load(base + 'features/ocr/BatchOcrDrafts.ets', { common,
    '@kit.ArkTS': { util: { generateRandomUUID: () => 'request-' + (++serial) } },
    './BatchQuestionImageSource': { discardBatchImage: value => calls.discarded.push(value) }
  });
  const job = (requestId, status = 'COMPLETED') => ({ requestId, status, stage: status === 'COMPLETED' ? 'DONE' : 'OCR',
    completedTiles: 1, totalTiles: 1, result: status === 'COMPLETED' ? { questions: [1, 2].map(n => ({
      itemId: 'q' + n, bodyMarkdown: n + '. 求 $x+' + n + '=3$。', sourceIds: ['image-0'], tileIds: ['t1'], warnings: [], needsReview: false
    })), sources: [], tiles: [], warnings: [], incomplete: false } : undefined });
  const controls = {
    photo: async () => 'file://camera/photo.jpg',
    copy: async () => ({ ...original }),
    post: async (owner, id) => ({ isSuccess: true, data: job(id), error: null }),
    recover: async (owner, id) => ({ isSuccess: true, data: job(id), error: null }),
    gallery: async () => ['gallery://one', 'gallery://two']
  };
  const api = { start: async (...args) => { calls.starts.push(args); args[3]('UPLOADING'); return controls.post(...args); },
    recover: async (...args) => { calls.queries.push(args); return controls.recover(...args); } };
  const learningApi = { confirmManualQuestion: async (owner, input) => {
    calls.saves.push({ owner, input }); return { isSuccess: true, data: { questionId: 'saved-' + calls.saves.length }, error: null };
  } };
  const edges = { ...common, ...drafts, ...imageSource,
    BatchOcrApi: { create_default: () => api }, LearningApi: { create_default: () => learningApi }, OcrApi: { create_default: () => ({}) },
    PhoneBackController: class { handleBack = () => false; }, TextAreaController: class { stopEditing() {} },
    KeyboardAvoidMode: { OFFSET: 0, RESIZE: 1 }, getContext: () => ({ cacheDir: '/owned' }),
    setTimeout: callback => { const id = ++timerSerial; timers.set(id, callback); return id; }, clearTimeout: id => timers.delete(id),
    takePhoto: (...args) => controls.photo(...args), selectImage: async () => 'gallery://single',
    discardPendingPhoto: uri => calls.photos.push(uri), discardBatchImage: p => calls.discarded.push(p),
    copyBatchImage: (...args) => { calls.copies.push(args); return controls.copy(...args); },
    selectBatchImages: (context, count) => { calls.picker.push(count); return controls.gallery(); },
    loadPreview: async uri => { calls.previews.push(uri); return { getImageInfo: async () => ({ size: { width: 800, height: 600 } }), release: async () => {} }; }
  };
  const entry = component('components/ManualEntry.ets', 'ManualEntry', edges);
  entry.getUIContext = () => ({ getKeyboardAvoidMode: () => 1, setKeyboardAvoidMode: () => {} });
  entry.aboutToAppear();
  function review(capture = entry.cameraCapture, active = true) {
    const instance = component('components/BatchOcrReview.ets', 'BatchOcrReview', edges);
    instance.cameraCapture = capture; instance.active = active;
    const tasks = [];
    const accept = instance.accept_camera.bind(instance);
    instance.accept_camera = () => { const promise = accept(); tasks.push(promise); return promise; };
    instance.aboutToAppear();
    instance.tasks = tasks;
    return instance;
  }
  function card(batch) { const c = component('components/BatchOcrReview.ets', 'BatchOcrBatchCard', edges); c.batch = batch; return c; }
  return { entry, review, card, calls, controls, timers, drafts, job, setToken: value => { token = value; } };
}
async function run() {
  let checks = 0;
  async function check(name, test) { await test(); checks++; console.log('PASS ' + name); }
  await check('相机整图自动一次提交，阶段可查询，默认折叠未选且只添加已选题', async () => {
    const h = harness(), copy = deferred(), post = deferred();
    h.controls.copy = () => copy.promise; h.controls.post = () => post.promise;
    await h.entry.start_capture(true);
    assert.equal(h.entry.screen, 'BATCH'); assert.equal(h.entry.imageLoading, false);
    assert.equal(h.calls.previews.length, 0, 'camera must not consume original through crop preview');
    const r = h.review();
    assert.equal(r.choosing, true); assert.match(r.prepareStage, /准备.*原图/);
    copy.resolve({ ...original });
    await new Promise(setImmediate);
    assert.equal(h.calls.starts.length, 1);
    const b = r.batches[0], card = h.card(b);
    assert.equal(r.choosing, false); assert.equal(b.busy, true); assert.equal(b.posted, true);
    assert.match(card.progress_label(), /上传原图/);
    assert.deepEqual(Array.from(h.calls.starts[0][2]), [original]);
    assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
    await r.accept_camera(); await r.start(b);
    assert.equal(h.calls.starts.length, 1, 'repeated lifecycle/start cannot double submit');
    post.resolve({ isSuccess: true, data: h.job(b.requestId), error: null });
    await r.tasks[0];
    assert.equal(b.busy, false); assert.equal(b.drafts.length, 2);
    assert.ok(b.drafts.every(d => !d.selected && !d.expanded));
    assert.equal(h.calls.saves.length, 0, 'recognition must never save automatically');
    await r.save_selected(b); assert.equal(h.calls.saves.length, 0);
    b.drafts[1].selected = true; b.drafts[1].expanded = true;
    assert.equal(card.selected_count(b), 1);
    await r.save_selected(b);
    assert.equal(h.calls.saves.length, 1); assert.equal(h.calls.saves[0].input.bodyMarkdown, b.drafts[1].bodyMarkdown);
    assert.equal(b.drafts[0].savedQuestionId, ''); assert.equal(b.drafts[1].expanded, true);
  });
  await check('相机取消零批次零请求且退出loading', async () => {
    const h = harness(); h.controls.photo = async () => undefined;
    await h.entry.start_capture(true); assert.equal(h.entry.screen, 'SOURCE'); assert.equal(h.entry.imageLoading, false);
    h.review(); assert.equal(h.calls.starts.length, 0); assert.equal(h.calls.copies.length, 0);
  });
  for (const stale of ['inactive', 'destroy', 'token']) {
    await check('相机迟到回调 ' + stale + ' 清理原照片且零提交', async () => {
      const h = harness(), camera = deferred(); h.controls.photo = () => camera.promise;
      const pending = h.entry.start_capture(true);
      if (stale === 'inactive') { h.entry.active = false; h.entry.on_active_changed(); }
      if (stale === 'destroy') h.entry.aboutToDisappear();
      if (stale === 'token') h.setToken('account-B');
      camera.resolve('file://late/photo.jpg'); await pending;
      assert.deepEqual(h.calls.photos, ['file://late/photo.jpg']); assert.equal(h.calls.starts.length, 0);
      assert.equal(h.entry.cameraCapture, null); assert.equal(h.entry.imageLoading, false);
    });
  }
  for (const stale of ['back', 'inactive', 'destroy', 'token']) {
    await check('子组件挂载前 ' + stale + ' 不得重播相机意图', async () => {
      const h = harness(); await h.entry.start_capture(true); const ticket = h.entry.cameraCapture;
      if (stale === 'back') h.entry.go_back();
      if (stale === 'inactive') { h.entry.active = false; h.entry.on_active_changed(); }
      if (stale === 'destroy') h.entry.aboutToDisappear();
      if (stale === 'token') h.setToken('account-B');
      const r = h.review(ticket); await r.tasks[0];
      assert.equal(h.calls.starts.length, 0); assert.equal(h.calls.copies.length, 0);
      assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
    });
  }
  for (const stale of ['inactive', 'destroy', 'token']) {
    await check('原图复制迟到 ' + stale + ' 释放两份缓存且零提交', async () => {
      const h = harness(), copy = deferred(); h.controls.copy = () => copy.promise;
      await h.entry.start_capture(true); const r = h.review();
      if (stale === 'inactive') { r.active = false; r.active_changed(); }
      if (stale === 'destroy') r.aboutToDisappear();
      if (stale === 'token') { h.setToken('account-B'); r.active = false; r.active_changed(); }
      copy.resolve({ ...original }); await r.tasks[0];
      assert.equal(h.calls.starts.length, 0); assert.equal(h.drafts.batchDraftSession.batches.length, 0);
      assert.deepEqual(h.calls.discarded, [original.path]); assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
      assert.equal(r.choosing, false);
    });
  }
  await check('原图限制错误显示原因并退出loading、清理相机源', async () => {
    const h = harness(); h.controls.copy = async () => { throw Error('单图须不超过1600万像素'); };
    await h.entry.start_capture(true); const r = h.review(); await r.tasks[0];
    assert.equal(r.choosing, false); assert.match(r.notice, /1600万像素/); assert.equal(h.calls.starts.length, 0);
    assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
  });
  await check('active=false挂载不会提交，随后明确离开使意图失效', async () => {
    const h = harness(); await h.entry.start_capture(true); const r = h.review(h.entry.cameraCapture, false);
    await r.tasks[0]; assert.equal(h.calls.starts.length, 0); assert.equal(h.calls.copies.length, 0);
    r.aboutToDisappear(); const next = h.review(r.cameraCapture); await next.tasks[0];
    assert.equal(h.calls.starts.length, 0); assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
  });
  await check('POST等待中暂停与remount保留原requestId，返回只查询不重发', async () => {
    const h = harness(), post = deferred(); h.controls.post = () => post.promise;
    await h.entry.start_capture(true); const ticket = h.entry.cameraCapture, r = h.review();
    await new Promise(setImmediate);
    const batch = r.batches[0]; assert.equal(h.calls.starts.length, 1);
    r.aboutToDisappear(); assert.equal(batch.busy, false); assert.equal(h.calls.starts[0][4](), false);
    post.resolve({ isSuccess: true, data: h.job(batch.requestId), error: null }); await r.tasks[0];
    assert.equal(batch.drafts.length, 0, 'late response cannot rewrite paused state');
    const restored = h.review(ticket); await restored.tasks[0]; await restored.start(batch);
    assert.equal(h.calls.starts.length, 1); await restored.query(batch, false);
    assert.equal(h.calls.queries.length, 1); assert.equal(h.calls.queries[0][1], batch.requestId);
    assert.equal(batch.drafts.length, 2); assert.equal(h.calls.starts.length, 1);
  });
  await check('未知结果返回不重发，明确未受理仍只能手动重试', async () => {
    for (const known of [false, true]) {
      const h = harness(); h.controls.post = async () => {
        if (!known) throw Error('response lost');
        return { isSuccess: false, data: null, error: { httpStatus: 429, serverCode: 'A0502' } };
      };
      await h.entry.start_capture(true); const ticket = h.entry.cameraCapture, r = h.review(); await r.tasks[0];
      const b = r.batches[0]; assert.equal(b.posted, !known); assert.equal(b.busy, false);
      r.aboutToDisappear(); const restored = h.review(ticket); await restored.tasks[0];
      assert.equal(h.calls.starts.length, 1, 'mount never replays even a known pre-accept failure');
      if (!known) { await restored.query(b, false); assert.equal(h.calls.queries[0][1], b.requestId); }
      else { await restored.start(b); assert.equal(h.calls.starts.length, 2, 'only explicit manual start retries'); }
    }
  });
  await check('新拍照片置顶且旧批次编辑、勾选和身份保持不变', async () => {
    const h = harness();
    const old = new h.drafts.BatchReviewDraft([{ ...original, path: '/owned/old.jpg' }]);
    old.posted = true;
    h.drafts.batchDraftSession.batches.push(old);
    h.drafts.batchDraftSession.accept(old, h.job(old.requestId));
    old.drafts[0].bodyMarkdown = '人工校对过的旧题'; old.drafts[0].selected = true;
    const requestId = old.requestId, itemId = old.drafts[0].itemId;
    await h.entry.start_capture(true); const r = h.review(); await r.tasks[0];
    assert.equal(r.batches.length, 2); assert.notEqual(r.batches[0], old); assert.equal(r.batches[1], old);
    assert.equal(old.requestId, requestId); assert.equal(old.drafts[0].itemId, itemId);
    assert.equal(old.drafts[0].bodyMarkdown, '人工校对过的旧题'); assert.equal(old.drafts[0].selected, true);
    assert.equal(h.calls.starts.length, 1); assert.deepEqual(h.calls.discarded, []);
  });
  await check('原图准备中返回取消新照片且旧批次保留，重进也零POST', async () => {
    const h = harness(), copy = deferred(); h.controls.copy = () => copy.promise;
    const old = new h.drafts.BatchReviewDraft([{ ...original, path: '/owned/old.jpg' }]);
    h.drafts.batchDraftSession.batches.push(old);
    await h.entry.start_capture(true); const r = h.review();
    assert.match(r.prepareStage, /离开将取消本次照片导入，已有批次保留/);
    h.entry.go_back(); r.aboutToDisappear();
    assert.equal(h.entry.screen, 'SOURCE');
    copy.resolve({ ...original }); await r.tasks[0];
    const restored = h.review(); await restored.tasks[0];
    assert.equal(h.calls.starts.length, 0); assert.equal(restored.batches.length, 1); assert.equal(restored.batches[0], old);
    assert.deepEqual(h.calls.discarded, [original.path]); assert.deepEqual(h.calls.photos, ['file://camera/photo.jpg']);
    assert.equal(r.choosing, false);
  });
  await check('相册单题仍进入裁剪，批量图库保留顺序和手动开始', async () => {
    const h = harness(); await h.entry.start_capture(false);
    assert.equal(h.entry.screen, 'CROP'); assert.deepEqual(h.calls.previews, ['gallery://single']);
    assert.equal(h.entry.cameraCapture, null); assert.equal(h.calls.starts.length, 0);
    const r = h.review(); h.controls.copy = async (context, uri) => ({ ...original, path: '/owned/' + uri.split('//')[1] });
    await r.choose(4);
    assert.deepEqual(h.calls.picker, [4]); assert.deepEqual(Array.from(r.batches[0].images, image => image.path), ['/owned/one', '/owned/two']);
    assert.equal(h.calls.starts.length, 0); assert.equal(r.batches[0].posted, false); assert.equal(r.choosing, false);
    await r.start(r.batches[0]); assert.equal(h.calls.starts.length, 1);
  });
  console.log(JSON.stringify({ result: 'PASS', scenarios: checks, boundary: 'Real component methods/drafts; native edges controlled; no ArkUI rendering, device, network or model validation' }));
}
module.exports = run;
if (require.main === module) run().catch(error => { console.error(error); process.exitCode = 1; });
