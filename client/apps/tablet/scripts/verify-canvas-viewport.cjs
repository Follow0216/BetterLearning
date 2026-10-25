#!/usr/bin/env node
// Executes actual ETS methods with native image/Canvas/Web/filesystem APIs replaced.
// This checks geometry and scheduling; it does not prove native rendering or device quality.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
function read(relative) {
  const file = path.join(root, relative);
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, relative + ': parse diagnostics');
  return source;
}
const canvasDir = 'client/apps/tablet/src/main/ets/features/canvas/';
const commonDir = 'client/common/src/main/ets/features/canvas/';
const surface = read(canvasDir + 'CanvasSurface.ets');
read(canvasDir + 'TabletCanvasWorkspace.ets');
read('client/common/Index.ets');
const geometry = read(commonDir + 'CanvasGeometry.ets');
const model = read(commonDir + 'CanvasModels.ets');
const raster = read(commonDir + 'QuestionRasterHost.ets');
const painter = read(canvasDir + 'CanvasPainter.ets');
function members(source, componentName, methodNames, fieldNames = []) {
  const component = source.statements.find(s => s.name?.text === componentName);
  const methods = new Set(methodNames), fields = new Set(fieldNames), code = [];
  for (const member of component.members) {
    const name = member.name?.getText(source);
    if (ts.isMethodDeclaration(member) && methods.delete(name)) code.push(member.getText(source));
    else if (ts.isPropertyDeclaration(member) && fields.delete(name)) code.push(name + ' = ' + member.initializer.getText(source) + ';');
  }
  assert.equal(methods.size + fields.size, 0, 'Missing source members: ' + [...methods, ...fields]);
  return code.join('\n');
}
const surfaceCode = members(surface, 'CanvasSurface', [
  'configure_view', 'release_background', 'load_raster', 'rebuild_background', 'build_background',
  'paint', 'paint_cached_rasters', 'change_view', 'reset', 'aboutToDisappear'
], ['ready', 'alive', 'canvasWidthVp', 'canvasHeightVp', 'view', 'viewSet', 'backgroundMap', 'paintGeneration',
  'backgroundView', 'backgroundWidthVp', 'backgroundHeightVp', 'backgroundRequested', 'backgroundRunning',
  'rasterMaps', 'rasterPixels', 'rasterOrder', 'rasterPixelCount']);
const rasterCode = members(raster, 'QuestionRasterHost', ['prepare']);
const functions = geometry.statements.filter(ts.isFunctionDeclaration).map(s => s.getText(geometry).replace(/^export\s+/, '')).join('\n');
const constants = model.statements.filter(ts.isVariableStatement).map(s => s.getText(model).replace(/^export\s+/, '')).join('\n');
const intersection = painter.statements.find(s => s.name?.text === 'canvas_intersects').getText(painter).replace(/^export\s+/, '');
let serial = 0, decoder, writes = [], snapshots = [], rendered = [], allMaps = [];
class PixelMap {
  constructor(width, height, label = 'map') { this.width = width; this.height = height; this.label = label; this.releases = 0; allMaps.push(this); }
  async getImageInfo() { if (this.badInfo) throw new Error('native image info failure'); return { size: { width: this.width, height: this.height } }; }
  async release() { this.releases++; assert.equal(this.releases, 1, 'PixelMap released twice: ' + this.label); }
}
class Context {
  constructor() { this.operations = []; }
  resetTransform() { this.operations.push(['reset']); }
  fillRect(...args) { this.operations.push(['fill', ...args]); }
  scale(...args) { this.operations.push(['scale', ...args]); }
  translate(...args) { this.operations.push(['translate', ...args]); }
  save() {}
  restore() {}
  drawImage(map, ...args) { assert.equal(map.releases, 0, 'Drawing a released map'); this.operations.push(['draw', map.label, ...args]); }
  getPixelMap(x, y, width, height) { rendered.push({ width, height, operations: this.operations.slice() }); return new PixelMap(width, height, 'background-' + ++serial); }
}
class Offscreen {
  constructor(width, height) { this.width = width; this.height = height; this.context = new Context(); }
  getContext() { return this.context; }
}
const sandbox = {
  OffscreenCanvas: Offscreen, LengthMetricsUnit: { PX: 1 }, console, setTimeout,
  canvas_image: file => decoder(file), canvas_png: async map => new Uint8Array([map.width & 255, map.height & 255]),
  canvas_sha256: async () => (++serial).toString(16).padStart(64, '0'),
  canvas_utf8: text => new TextEncoder().encode(text), canvas_write: (file, bytes) => writes.push({ file, bytes }),
  hilog: { warn() {} }, stroke_bounds: () => { throw new Error('No strokes expected in this harness'); }, draw_canvas_stroke() {}
};
vm.createContext(sandbox);
const code = constants + '\n' + functions + '\n' + intersection + `
class SurfaceHarness {
  ${surfaceCode}
  constructor(doc, files) {
    this.committedDocument = doc; this.document = doc; this.rasterFiles = files; this.initialView = null;
    this.ready = true; this.canvasWidthVp = 800; this.canvasHeightVp = 600; this.context = new CanvasRenderingContext2D();
    this.erased = null; this.liveStroke = null; this.undoHistory = []; this.saved = []; this.failures = [];
    this.freezeHandle = {}; this.onFreezeHandle = () => {};
    // Input harness covers actual budget clearing; viewport keeps rendering cleanup real.
    this.budgetClears = 0; this.strokeBudget = { clear: () => this.budgetClears++ };
    this.onViewChange = view => this.saved.push(JSON.parse(JSON.stringify(view)));
    this.onFailure = message => this.failures.push(message);
  }
  getUIContext() { return { vp2px: () => 2 }; }
  cancel_gesture() {}
  update_undo() {}
  paint_paper() {}
}
class RasterHarness {
  ${rasterCode}
  constructor(bounds, density, laterDensity = density) {
    this.started = false; this.alive = true; this.pageReady = true; this.generation = 1;
    this.firstPainted = true; this.pageVisible = true; this.hostSized = true;
    this.bodyMarkdown = 'normal calculus'; this.directory = '/synthetic'; this.bounds = bounds;
    this.requestActive = () => true; this.failures = []; this.result = null;
    this.controller = { runJavaScript: async () => {} };
    this.onReady = result => { this.result = result; }; this.onFailure = message => this.failures.push(message);
    this.wait_status = async () => ({ bounds, blocks: [{ blockId: 'question_0', ...bounds }] });
    this.capture = async (w, h, index) => {
      const d = index === 0 ? density : laterDensity;
      const map = new NativePixelMap(Math.round(w * d), Math.round(h * d), 'snapshot-' + index);
      snapshots.push(map); return map;
    };
  }
}
Object.assign(globalThis, { SurfaceHarness, RasterHarness, initial_canvas_view, fit_canvas, valid_canvas_view,
  world_point, zoom_canvas, validate_canvas_document, validate_answer_manifest, canvas_extent, canvas_grid, check_grid });
`;
sandbox.CanvasRenderingContext2D = Context;
sandbox.NativePixelMap = PixelMap;
sandbox.snapshots = snapshots;
const transpiled = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 }, reportDiagnostics: true });
assert.equal(transpiled.diagnostics.length, 0);
vm.runInContext(transpiled.outputText, sandbox, { filename: 'actual-viewport-raster-methods.js' });
const S = sandbox;
const copy = value => JSON.parse(JSON.stringify(value));
const eq = (a, b) => assert.deepEqual(copy(a), copy(b));
function grid(width, height, density = 1, x = 0, y = 0) {
  const result = [];
  for (let by = 0; by < height; by += 1024) for (let bx = 0; bx < width; bx += 1024) {
    const w = Math.min(1024, width - bx), h = Math.min(1024, height - by), index = result.length;
    result.push({ index, partName: 'question_' + index.toString().padStart(4, '0'), x: x + bx, y: y + by, width: w, height: h,
      pixelWidth: Math.round((bx + w) * density) - Math.round(bx * density),
      pixelHeight: Math.round((by + h) * density) - Math.round(by * density), sha256: 'a'.repeat(64) });
  }
  return result;
}
function document(width = 720, height = 320, density = 2, x = 0, y = 0) {
  const bounds = { x, y, width, height };
  return { schemaVersion: 1, questionLayout: { bounds, blocks: [{ ...bounds, blockId: 'q' }], rasterBlocks: grid(width, height, density, x, y),
    questionBodySha256: 'b'.repeat(64), contentWidth: 720, fontSize: 18, rendererId: 'frozen-v1' }, strokes: [] };
}
function harness(doc = document()) {
  const files = doc.questionLayout.rasterBlocks.map(block => ({ partName: block.partName, filePath: '/synthetic/' + block.partName }));
  return new S.SurfaceHarness(doc, files);
}
function autoDecode(h) { decoder = async file => {
  const block = h.committedDocument.questionLayout.rasterBlocks.find(b => file.endsWith(b.partName));
  return new PixelMap(block.pixelWidth, block.pixelHeight, file);
}; }
async function settle(h) {
  for (let i = 0; i < 2000 && h.backgroundRunning; i++) await Promise.resolve();
  assert.equal(h.backgroundRunning, false, 'Background worker did not settle');
}
let scenarios = 0;
async function check(name, run) { await run(); scenarios++; console.log('PASS ' + name); }
async function main() {
  await check('long questions retain readable text and begin at top', () => {
    for (const width of [480, 800, 1200]) for (const height of [320, 4600]) {
      const view = S.initial_canvas_view({ x: -11, y: 100, width: 720, height }, width, 18);
      assert.ok(view.scale * 18 >= 16); assert.equal((100 - view.y) * view.scale, 24);
    }
    assert.ok(S.fit_canvas({ x: 0, y: 0, width: 720, height: 4600 }, 800, 600).scale < 0.2);
  });
  await check('invalid saved viewport is rejected and valid viewport remains exact', () => {
    for (const view of [null, { x: NaN, y: 0, scale: 1 }, { x: 0, y: 0, scale: 0 }, { x: 0, y: 0, scale: 9 }]) assert.equal(S.valid_canvas_view(view), false);
    const h = harness(); autoDecode(h); h.initialView = { x: -87.25, y: 910, scale: 1.5 }; h.configure_view();
    eq(h.view, h.initialView); eq(h.saved[0], h.initialView); return settle(h).then(() => h.aboutToDisappear());
  });
  await check('fresh fit is persisted before any navigation', async () => {
    const h = harness(document(720, 4600)); autoDecode(h); h.configure_view();
    eq(h.saved[0], h.view); assert.ok(h.view.scale * 18 >= 16); await settle(h); h.aboutToDisappear();
  });
  for (const density of [1, 1.25, 1.5, 1.734375, 2]) {
    await check('legacy/high-density grid and short/narrow edges ' + density, () => {
      for (const [w, h] of [[720, 2600], [2051, 2057], [9, 2049], [2049, 7]]) {
        const doc = document(w, h, density, -14, -9); S.validate_canvas_document(doc);
        const extent = S.canvas_extent(doc), chunks = S.canvas_grid(extent);
        chunks.forEach(block => { block.sha256 = 'c'.repeat(64); });
        S.validate_answer_manifest({ schemaVersion: 1, saveRequestId: '11111111-1111-4111-8111-111111111111', baseFileRef: null, canvas: extent, document: doc, blocks: chunks });
      }
    });
  }
  await check('inconsistent density and oversized/chunk density are rejected', () => {
    const doc = document(720, 2050, 2); doc.questionLayout.rasterBlocks[1].pixelWidth = 720;
    assert.throws(() => S.validate_canvas_document(doc));
    const blocks = grid(720, 400, 2); blocks[0].partName = 'chunk_0000';
    assert.throws(() => S.check_grid(blocks, 0, 0, 720, 400, 'chunk_'));
    const huge = document(4100, 4100, 2); assert.throws(() => S.validate_canvas_document(huge), /像素/);
  });
  await check('combined pixel budget counts high-density sources', () => {
    const doc = document(3900, 3900, 2); S.validate_canvas_document(doc);
    const extent = S.canvas_extent(doc), chunks = S.canvas_grid(extent); chunks.forEach(b => { b.sha256 = 'd'.repeat(64); });
    assert.throws(() => S.validate_answer_manifest({ schemaVersion: 1, saveRequestId: '11111111-1111-4111-8111-111111111111',
      baseFileRef: null, canvas: extent, document: doc, blocks: chunks }), /图块限制/);
  });
  await check('pan/zoom keeps map alive and projects it in world coordinates', async () => {
    const h = harness(); autoDecode(h); h.configure_view(); await settle(h);
    const old = h.backgroundMap, oldView = copy(h.backgroundView), oldWidth = h.backgroundWidthVp;
    h.context.operations = [];
    h.change_view({ x: 60, y: -40, scale: 1.5 }, false);
    assert.equal(old.releases, 0); assert.equal(h.backgroundMap, old);
    const draw = h.context.operations.find(op => op[0] === 'draw' && op[1] === old.label);
    assert.equal(draw[2], (oldView.x - 60) * 1.5); assert.equal(draw[3], (oldView.y + 40) * 1.5);
    assert.equal(draw[4], oldWidth * 1.5 / oldView.scale);
    assert.ok(h.context.operations.some(op => op[0] === 'draw' && op[1].startsWith('/synthetic/')), 'Direct source redraw missing');
    await settle(h); assert.equal(old.releases, 1); h.aboutToDisappear();
  });
  await check('rapid navigation has one decoder and final view wins', async () => {
    const h = harness(); let resolve, loads = 0;
    decoder = file => { loads++; return new Promise(r => { resolve = () => r(new PixelMap(1440, 640, file)); }); };
    h.configure_view();
    for (let i = 0; i < 30; i++) h.change_view({ x: i, y: i * 2, scale: 1.2 }, false);
    assert.equal(loads, 1); resolve(); await settle(h);
    eq(h.backgroundView, { x: 29, y: 58, scale: 1.2 }); assert.equal(h.failures.length, 0); h.aboutToDisappear();
  });
  await check('document switch rejects late old raster and paints new document', async () => {
    const h = harness(); let resolve;
    decoder = file => new Promise(r => { resolve = () => r(new PixelMap(1440, 640, file)); });
    h.configure_view(); const oldGeneration = h.paintGeneration;
    h.document = document(720, 500, 1); autoDecode({ committedDocument: h.document }); h.reset();
    assert.ok(h.paintGeneration > oldGeneration); resolve(); await settle(h);
    assert.equal(h.failures.length, 0); assert.equal(h.rasterMaps.size, 1);
    assert.equal([...h.rasterMaps.values()][0].height, 500); h.aboutToDisappear();
  });
  await check('disappear releases late raster without publishing background', async () => {
    const h = harness(); let resolve, pending;
    decoder = () => new Promise(r => { resolve = () => { pending = new PixelMap(1440, 640); r(pending); }; });
    h.configure_view(); h.aboutToDisappear(); resolve(); await settle(h);
    assert.equal(pending.releases, 1); assert.equal(h.backgroundMap, null); assert.equal(h.rasterMaps.size, 0);
  });
  await check('DPR screen cache is bounded and preserves logical view', async () => {
    const h = harness(); autoDecode(h); h.configure_view(); await settle(h);
    assert.equal(h.backgroundMap.width, 1600); assert.equal(h.backgroundMap.height, 1200);
    const view = copy(h.view); h.canvasWidthVp = 2800; h.canvasHeightVp = 1800; h.configure_view(); await settle(h);
    assert.ok(h.backgroundMap.width * h.backgroundMap.height <= 4194304); eq(h.view, view); h.aboutToDisappear();
  });
  await check('native metadata failure releases decoded map and reports once', async () => {
    const h = harness(); const bad = new PixelMap(1440, 640); bad.badInfo = true; decoder = async () => bad;
    h.configure_view(); await settle(h); assert.equal(bad.releases, 1); assert.equal(h.failures.length, 1); h.aboutToDisappear();
  });
  await check('LRU decoded source budget and document cleanup', async () => {
    const h = harness(document(720, 4600, 2)); autoDecode(h); h.configure_view(); await settle(h);
    for (const y of [800, 1700, 2600, 3500]) { h.change_view({ x: -24, y, scale: 1 }, false); await settle(h); }
    assert.ok(h.rasterPixelCount <= 8388608); const remaining = [...h.rasterMaps.values()];
    h.aboutToDisappear(); assert.ok(remaining.every(map => map.releases === 1));
  });
  for (const density of [1, 1.5, 2, 3]) {
    await check('actual raster normalization preserves bounded density ' + density, async () => {
      writes = []; const h = new S.RasterHarness({ x: -3, y: 0, width: 2051, height: 2057 }, density);
      await h.prepare(); assert.equal(h.failures.length, 0); assert.ok(h.result);
      S.validate_canvas_document({ schemaVersion: 1, questionLayout: h.result.layout, strokes: [] });
      const blocks = h.result.layout.rasterBlocks, expected = Math.min(2, density);
      assert.equal(blocks.reduce((sum, b) => sum + b.pixelWidth * b.pixelHeight, 0), Math.round(2051 * expected) * Math.round(2057 * expected));
      assert.equal(writes.length, blocks.length); assert.ok(snapshots.every(map => map.releases === 1));
    });
  }
  await check('later low-resolution snapshot never fakes high-resolution source', async () => {
    const h = new S.RasterHarness({ x: 0, y: 0, width: 720, height: 2050 }, 2, 1);
    await h.prepare(); assert.equal(h.result, null); assert.equal(h.failures.length, 1);
  });
  console.log(JSON.stringify({ scenarios, result: 'PASS', boundary: 'Actual source geometry/raster/cache methods; native Canvas/Web/PNG/filesystem mocked. No device, network or visual quality acceptance.' }));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
