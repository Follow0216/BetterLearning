#!/usr/bin/env node
// Two offline checks of the real owned-cache helper. Native rendering remains a device check.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const root = path.resolve(__dirname, '../..');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));

function harness() {
  const bytes = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
  const files = new Map();
  const removed = [];
  const converted = [];
  let decoderReleases = 0;
  let bitmapReleases = 0;
  const dependencies = {
    '@kit.CoreFileKit': {
      fileIo: {
        OpenMode: { READ_ONLY: 0 }, openSync: () => ({ fd: 1 }),
        statSync: () => ({ size: bytes.length }), closeSync: () => {},
        readSync: (_fd, buffer) => { new Uint8Array(buffer).set(bytes); return bytes.length; },
        mkdtempSync: () => '/data/storage/el2/base/cache/ocr-batch-owned',
        accessSync: () => true, rmdirSync: directory => removed.push(directory)
      },
      fileUri: { getUriFromPath: raw => {
        assert.ok(raw.startsWith('/data/storage/'), 'native converter receives an absolute sandbox path');
        converted.push(raw);
        return 'file://com.betterlearning.phone' + raw;
      } }
    },
    '@kit.ImageKit': { image: { createImageSource: () => ({
      getImageInfo: async () => ({ size: { width: 1456, height: 1858 } }),
      createPixelMap: async () => ({
        getImageInfo: async () => ({ size: { width: 1456, height: 1858 } }),
        release: async () => { bitmapReleases++; }
      }), release: async () => { decoderReleases++; }
    }) } },
    '@kit.MediaLibraryKit': { photoAccessHelper: {} },
    common: { canvas_write: (raw, content) => files.set(raw, content.slice()) }
  };
  const source = fs.readFileSync(path.join(root, 'client/apps/phone/src/main/ets/features/ocr/BatchQuestionImageSource.ets'), 'utf8');
  const module = { exports: {} };
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText,
    { module, exports: module.exports, Uint8Array, ArrayBuffer, Map, Number, Error, require: key => {
      if (!(key in dependencies)) throw Error('Unexpected dependency ' + key);
      return dependencies[key];
    } });
  return { api: module.exports, files, removed, converted, releases: () => [decoderReleases, bitmapReleases] };
}

test('owned image uses a sandbox URI only for display and keeps its raw upload path', async () => {
  const h = harness();
  const input = await h.api.copyBatchImage({ cacheDir: '/data/storage/el2/base/cache' }, 'selected-photo', () => true);
  const raw = input.path;
  const uri = h.api.batchImageDisplayUri(raw);
  assert.equal(uri, 'file://com.betterlearning.phone' + raw);
  assert.deepEqual(h.converted, [raw]);
  assert.equal(input.path, raw);
  assert.equal(h.files.get(input.path).length, input.byteLength, 'original upload/cache key remains readable');
  assert.equal(h.removed.length, 0, 'display conversion does not delete the original');
  assert.deepEqual(h.releases(), [1, 1], 'copy validation releases decoder and bitmap');
});

test('display URI cannot replace the ownership key used for explicit cache cleanup', async () => {
  const h = harness();
  const input = await h.api.copyBatchImage({ cacheDir: '/data/storage/el2/base/cache' }, 'selected-photo', () => true);
  h.api.discardBatchImage(h.api.batchImageDisplayUri(input.path));
  assert.deepEqual(h.removed, [], 'a display URI is not an owned raw file key');
  h.api.discardBatchImage(input.path);
  assert.deepEqual(h.removed, ['/data/storage/el2/base/cache/ocr-batch-owned']);
  h.api.discardBatchImage(input.path);
  assert.equal(h.removed.length, 1, 'cleanup remains idempotent');
});
