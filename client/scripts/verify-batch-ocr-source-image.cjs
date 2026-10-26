#!/usr/bin/env node
// Offline checks execute the real owned-cache helper with fake native Kits.
// Native rendering and the camera picker still require a device check.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const root = path.resolve(__dirname, '../..');
const loader = process.env.BETTERLEARNING_ETS_LOADER || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const png = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);
const jpeg = new Uint8Array([255, 216, 255, 224, 1, 2, 3, 4]);
const cacheDir = '/data/storage/el2/base/cache';
const ownedDir = cacheDir + '/ocr-batch-owned';

function harness(options = {}) {
  const bytes = options.bytes || png;
  const header = options.header || { width: 1456, height: 1858 };
  const decoded = options.decoded || header;
  const files = new Map();
  const removed = [];
  const converted = [];
  const photoCleanups = [];
  const opened = [];
  let decoderReleases = 0;
  let bitmapReleases = 0;
  let closed = 0;
  let offset = 0;
  const dependencies = {
    '@kit.CoreFileKit': {
      fileIo: {
        OpenMode: { READ_ONLY: 0 }, openSync: uri => { opened.push(uri); return { fd: 1 }; },
        statSync: () => ({ size: options.reportedSize ?? bytes.length }), closeSync: () => { closed++; },
        readSync: (_fd, buffer) => {
          const count = Math.min(bytes.length - offset, buffer.byteLength, options.readChunk ?? bytes.length);
          if (count > 0) new Uint8Array(buffer).set(bytes.subarray(offset, offset + count));
          offset += count;
          return count;
        },
        mkdtempSync: () => ownedDir,
        accessSync: () => true, rmdirSync: directory => removed.push(directory)
      },
      fileUri: { getUriFromPath: raw => {
        assert.ok(raw.startsWith('/data/storage/'), 'native converter receives an absolute sandbox path');
        converted.push(raw);
        return 'file://com.betterlearning.phone' + raw;
      } }
    },
    '@kit.ImageKit': { image: { createImageSource: () => ({
      getImageInfo: async () => {
        if (options.failAt === 'header') throw Error('header decode failed');
        return { size: header };
      },
      createPixelMap: async () => {
        if (options.failAt === 'bitmap') throw Error('bitmap decode failed');
        return {
          getImageInfo: async () => ({ size: decoded }),
          release: async () => { bitmapReleases++; }
        };
      },
      release: async () => { decoderReleases++; }
    }) } },
    '@kit.MediaLibraryKit': { photoAccessHelper: {} },
    './QuestionImageSource': { discardPendingPhoto: uri => photoCleanups.push(uri) },
    common: { canvas_write: (raw, content) => {
      if (options.failAt === 'write') throw Error('write failed');
      files.set(raw, content.slice());
    } }
  };
  const source = fs.readFileSync(path.join(root, 'client/apps/phone/src/main/ets/features/ocr/BatchQuestionImageSource.ets'), 'utf8');
  const module = { exports: {} };
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText,
    { module, exports: module.exports, Uint8Array, ArrayBuffer, Map, Number, Error, require: key => {
      if (!(key in dependencies)) throw Error('Unexpected dependency ' + key);
      return dependencies[key];
    } });
  return { api: module.exports, files, removed, converted, photoCleanups, opened,
    releases: () => [decoderReleases, bitmapReleases], closed: () => closed };
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

for (const [name, bytes, contentType, extension] of [
  ['PNG', png, 'image/png', 'png'], ['JPEG', jpeg, 'image/jpeg', 'jpg']
]) {
  test(name + ' copies original bytes and decoded dimensions to an owned upload path', async () => {
    const h = harness({ bytes, readChunk: 3 });
    const input = await h.api.copyBatchImage({ cacheDir }, 'selected-photo', () => true);
    assert.equal(input.path, ownedDir + '/original.' + extension);
    assert.equal(input.contentType, contentType);
    assert.deepEqual([input.width, input.height, input.byteLength], [1456, 1858, bytes.length]);
    assert.deepEqual(Array.from(h.files.get(input.path)), Array.from(bytes), 'copy must not crop or re-encode the source');
    assert.deepEqual(h.opened, ['selected-photo']);
    assert.equal(h.closed(), 1);
    assert.deepEqual(h.releases(), [1, 1]);
    assert.deepEqual(h.removed, []);
  });
}

test('gallery path is never treated as an owned cache key', async () => {
  const h = harness();
  const input = await h.api.copyBatchImage({ cacheDir }, 'gallery://selected-photo', () => true);
  h.api.discardBatchImage('gallery://selected-photo');
  h.api.discardBatchImage(h.api.batchImageDisplayUri(input.path));
  assert.deepEqual(h.removed, []);
  h.api.discardBatchImage(input.path);
  assert.deepEqual(h.removed, [ownedDir]);
});

test('oversized bytes fail before decoding or allocating an owned cache', async () => {
  const h = harness({ reportedSize: 16777217 });
  await assert.rejects(h.api.copyBatchImage({ cacheDir }, 'large-photo', () => true), /16 MiB/);
  assert.deepEqual(h.releases(), [0, 0]);
  assert.deepEqual(h.removed, []);
  assert.equal(h.files.size, 0);
  assert.equal(h.closed(), 1);
});

for (const options of [
  { header: { width: 16001, height: 1 } },
  { decoded: { width: 1455, height: 1858 } },
  { failAt: 'header' },
  { failAt: 'bitmap' }
]) {
  test('invalid dimensions or decode failure creates no owned image: ' + JSON.stringify(options), async () => {
    const h = harness(options);
    await assert.rejects(h.api.copyBatchImage({ cacheDir }, 'bad-photo', () => true));
    assert.equal(h.files.size, 0);
    assert.deepEqual(h.removed, []);
    assert.equal(h.releases()[0], 1, 'decoder is always released');
  });
}

test('expired selection leaves no owned copy; write failure cleans the registered cache', async () => {
  const expired = harness();
  await assert.rejects(expired.api.copyBatchImage({ cacheDir }, 'old-photo', () => false), /失效/);
  assert.equal(expired.files.size, 0);
  assert.deepEqual(expired.removed, []);
  assert.deepEqual(expired.releases(), [1, 0]);
  const failed = harness({ failAt: 'write' });
  await assert.rejects(failed.api.copyBatchImage({ cacheDir }, 'selected-photo', () => true), /write failed/);
  assert.deepEqual(failed.removed, [ownedDir]);
  assert.equal(failed.files.size, 0);
});

test('camera claim is single use and releasePhoto preserves the current generation', () => {
  const h = harness();
  const capture = new h.api.BatchCameraCapture('camera://photo', 'token-a');
  assert.equal(capture.isCurrent('token-a'), true);
  assert.equal(capture.isCurrent('token-b'), false);
  assert.equal(capture.claim('token-b'), undefined);
  assert.equal(capture.claim('token-a'), 'camera://photo');
  assert.equal(capture.claim('token-a'), undefined);
  capture.releasePhoto();
  capture.releasePhoto();
  assert.deepEqual(h.photoCleanups, ['camera://photo']);
  assert.equal(capture.isCurrent('token-a'), true);
});

test('camera discard invalidates callbacks and releases photo exactly once', () => {
  const h = harness();
  const capture = new h.api.BatchCameraCapture('camera://photo', 'token-a');
  capture.discard();
  capture.discard();
  assert.equal(capture.isCurrent('token-a'), false);
  assert.equal(capture.claim('token-a'), undefined);
  assert.deepEqual(h.photoCleanups, ['camera://photo']);
  capture.releasePhoto();
  assert.deepEqual(h.photoCleanups, ['camera://photo']);
});
