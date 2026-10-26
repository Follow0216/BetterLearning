#!/usr/bin/env node
// Offline execution of the real BatchOcrApi.ets; all native and transport edges are stubs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../..');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const id = '12345678-1234-1234-1234-123456789abc';
const token = 'owner-token';
const imageInput = { path: '/owned/image.png', contentType: 'image/png', width: 100, height: 200, byteLength: 8 };
const png = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10]);

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function loadApi(edges) {
  const file = path.join(root, 'client/common/src/main/ets/features/tutor/BatchOcrApi.ets');
  const source = fs.readFileSync(file, 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 }
  }).outputText;
  const exports = {};
  const dependencies = {
    '@kit.NetworkKit': { http: { RequestMethod: { GET: 'GET', DELETE: 'DELETE' } } },
    '@kit.ImageKit': { image: { createImageSource: edges.createImageSource } },
    '../auth/AuthApi': { AuthApi: edges.AuthApi, DEFAULT_DEV_BASE_URL: 'offline://' },
    '../auth/AuthModels': {
      AuthError: {
        local_validation: message => ({ kind: 'VALIDATION', message }),
        protocol_failure: message => ({ kind: 'PROTOCOL', message })
      },
      AuthResult: {
        success: data => ({ isSuccess: true, data, error: null }),
        failure: error => ({ isSuccess: false, data: null, error })
      }
    },
    '../auth/AuthSession': { authSession: edges.authSession },
    '../canvas/CanvasFiles': { canvas_read: edges.canvasRead },
    './BatchOcrModels': {
      batch_ocr_uuid: value => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value),
      parse_batch_ocr_job: body => JSON.parse(body)
    }
  };
  vm.runInNewContext(compiled, {
    exports, Uint8Array, ArrayBuffer,
    require: key => {
      if (!(key in dependencies)) { throw Error('Unexpected dependency ' + key); }
      return dependencies[key];
    }
  }, { filename: file });
  return exports.BatchOcrApi;
}

function harness(phase, mode = 'current', phaseImage = 1) {
  const gate = deferred();
  const arrived = deferred();
  const calls = { post: [], resource: [], stages: [], releaseMap: 0, releaseSource: 0, reads: 0, readPaths: [] };
  const bytesByPath = new Map();
  let decodedCount = 0;
  let current = true;
  let accessToken = token;
  const waitAt = async name => {
    if (phase === name && decodedCount === phaseImage) { arrived.resolve(); await gate.promise; }
  };
  class AuthApi {
    async request_multipart(...args) {
      calls.post.push(args);
      return { isSuccess: true, data: JSON.stringify({ requestId: id, status: 'QUEUED' }), error: null };
    }
    async request_resource(...args) {
      calls.resource.push(args);
      return { isSuccess: true, data: JSON.stringify({ requestId: id, status: 'QUEUED' }), error: null };
    }
  }
  const Api = loadApi({
    AuthApi,
    authSession: { matches_access_token: candidate => candidate === accessToken },
    canvasRead: path => {
      calls.reads++; calls.readPaths.push(path);
      if (phase === 'canvas_read' && calls.reads === phaseImage) {
        if (mode === 'token') { accessToken = 'new-token'; } else { current = false; }
      }
      return bytesByPath.get(path) || png;
    },
    createImageSource: () => { decodedCount++; return ({
      getImageInfo: async () => { await waitAt('getImageInfo'); return { size: { width: 100, height: 200 } }; },
      createPixelMap: async () => {
        await waitAt('createPixelMap');
        return { release: async () => { calls.releaseMap++; await waitAt('map.release'); } };
      },
      release: async () => { calls.releaseSource++; await waitAt('source.release'); }
    }); }
  });
  return {
    api: new Api(), calls, arrived, bytesByPath,
    stale: () => { if (mode === 'token') { accessToken = 'new-token'; } else { current = false; } gate.resolve(); },
    isCurrent: () => current,
    onStage: stage => calls.stages.push(stage)
  };
}

async function run() {
  for (const phase of ['getImageInfo', 'createPixelMap', 'map.release', 'source.release']) {
    for (const mode of ['current', 'token']) {
      const h = harness(phase, mode);
      const pending = h.api.start(token, id, [imageInput], h.onStage, h.isCurrent);
      await h.arrived.promise;
      h.stale();
      const result = await pending;
      assert.equal(result.isSuccess, false, `${phase}/${mode} must reject stale input`);
      assert.equal(h.calls.post.length, 0, `${phase}/${mode} must not POST`);
      assert.deepEqual(h.calls.stages, ['PREPARING']);
      assert.equal(h.calls.releaseSource, 1, `${phase}/${mode} must release source`);
      assert.equal(h.calls.releaseMap, phase === 'getImageInfo' ? 0 : 1);
    }
  }
  for (const mode of ['current', 'token']) {
    const h = harness('canvas_read', mode);
    const result = await h.api.start(token, id, [imageInput], h.onStage, h.isCurrent);
    assert.equal(result.isSuccess, false);
    assert.equal(h.calls.post.length, 0, `canvas_read/${mode} must not POST`);
  }
  for (const mode of ['current', 'token']) {
    const h = harness('none', mode);
    const result = await h.api.start(token, id, [imageInput], stage => {
      h.onStage(stage);
      if (stage === 'UPLOADING') { h.stale(); }
    }, h.isCurrent);
    assert.equal(result.isSuccess, false, `UPLOADING callback/${mode} must reject stale input`);
    assert.deepEqual(h.calls.stages, ['PREPARING', 'UPLOADING']);
    assert.equal(h.calls.post.length, 0, `UPLOADING callback/${mode} must not POST`);
  }
  {
    const h = harness('none');
    h.stale();
    const result = await h.api.start(token, id, [imageInput], h.onStage, h.isCurrent);
    assert.equal(result.isSuccess, false);
    assert.equal(h.calls.reads, 0);
    assert.equal(h.calls.post.length, 0);
  }
  {
    const h = harness('none');
    const result = await h.api.start(token, id, [imageInput], h.onStage, h.isCurrent);
    assert.equal(result.isSuccess, true);
    assert.equal(h.calls.post.length, 1, 'valid input sends exactly one multipart POST');
    assert.deepEqual(h.calls.stages, ['PREPARING', 'UPLOADING']);
    assert.equal(h.calls.post[0][0], '/api/v1/learning/ocr/batches/' + id);
    assert.equal(h.calls.post[0][2].length, 1);
    assert.equal(h.calls.releaseMap, 1);
    assert.equal(h.calls.releaseSource, 1);
    const recovered = await h.api.recover(token, id);
    assert.equal(recovered.isSuccess, true);
    assert.equal(h.calls.resource.length, 1);
    assert.equal(h.calls.resource[0][0], 'GET');
    assert.equal(h.calls.post.length, 1, 'recover must not repeat POST');
    await h.api.cancel(token, id);
    assert.equal(h.calls.resource[1][0], 'DELETE');
    assert.equal(h.calls.post.length, 1, 'cancel must not POST');
  }
  {
    const images = Array.from({ length: 4 }, (_, i) => ({ ...imageInput, path: '/owned/original-' + i + '.png' }));
    const h = harness('none');
    const result = await h.api.start(token, id, images, h.onStage, h.isCurrent);
    assert.equal(result.isSuccess, true); assert.equal(h.calls.post.length, 1);
    assert.deepEqual(h.calls.readPaths, images.map(image => image.path));
    assert.deepEqual(Array.from(h.calls.post[0][2], part => part.name), ['image0', 'image1', 'image2', 'image3']);
    assert.deepEqual(Array.from(h.calls.post[0][2], part => part.remoteFileName), ['image0.png', 'image1.png', 'image2.png', 'image3.png']);
    assert.equal(h.calls.releaseMap, 4); assert.equal(h.calls.releaseSource, 4);
  }
  for (const phase of ['getImageInfo', 'createPixelMap', 'map.release', 'source.release', 'canvas_read']) {
    for (const mode of ['current', 'token']) {
      const h = harness(phase, mode, 2);
      const images = [imageInput, { ...imageInput, path: '/owned/second.png' }, { ...imageInput, path: '/owned/third.png' }];
      const pending = h.api.start(token, id, images, h.onStage, h.isCurrent);
      if (phase !== 'canvas_read') { await h.arrived.promise; h.stale(); }
      const result = await pending;
      assert.equal(result.isSuccess, false, `second image ${phase}/${mode} must reject stale import`);
      assert.equal(h.calls.post.length, 0); assert.equal(h.calls.reads, 2, 'do not prepare later images after invalidation');
    }
  }
  for (const invalid of ['image-count', 'single-size', 'total-size', 'wrong-bytes', 'dimensions']) {
    const h = harness('none');
    let images = [imageInput];
    if (invalid === 'image-count') images = Array.from({ length: 5 }, () => imageInput);
    if (invalid === 'dimensions') images = [{ ...imageInput, width: 4001, height: 4000 }];
    if (invalid === 'wrong-bytes') h.bytesByPath.set(imageInput.path, Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7]));
    if (invalid === 'single-size') {
      const bytes = new Uint8Array(16777217); bytes.set(png); h.bytesByPath.set(imageInput.path, bytes);
      images = [{ ...imageInput, byteLength: bytes.length }];
    }
    if (invalid === 'total-size') {
      const bytes = new Uint8Array(16777216); bytes.set(png);
      images = [0, 1].map(i => ({ ...imageInput, path: '/owned/large-' + i + '.png', byteLength: bytes.length }));
      images.push({ ...imageInput, path: '/owned/last.png' });
      for (const input of images.slice(0, 2)) h.bytesByPath.set(input.path, bytes);
    }
    const result = await h.api.start(token, id, images, h.onStage, h.isCurrent);
    assert.equal(result.isSuccess, false, invalid); assert.equal(h.calls.post.length, 0, invalid + ' must send zero multipart requests');
  }
  console.log(JSON.stringify({ result: 'PASS', checks: 'real BatchOcrApi lifecycle; zero POST after stale generation/token at image awaits, canvas read, and UPLOADING callback; one valid POST for 1 or 4 ordered originals; multi-image stale guards; count/byte/total/dimension rejection; GET recovery; DELETE cancellation', boundary: 'Native image, canvas and transport are controlled stubs; no device or network' }));
}

module.exports = run;
if (require.main === module) {
  run().catch(error => { console.error(error); process.exitCode = 1; });
}
