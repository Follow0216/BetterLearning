#!/usr/bin/env node
// Executes actual pure ArkTS model/geometry/pen/painter code. Canvas calls are recorded, not rasterized.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const root = path.resolve(__dirname, '../../../..');
const files = ['client/common/src/main/ets/features/canvas/CanvasModels.ets',
  'client/common/src/main/ets/features/canvas/CanvasPens.ets',
  'client/common/src/main/ets/features/canvas/CanvasGeometry.ets',
  'client/apps/tablet/src/main/ets/features/canvas/CanvasPenInput.ets',
  'client/apps/tablet/src/main/ets/features/canvas/CanvasPainter.ets'];
const modules = files.map(relative => {
  const file = path.join(root, relative);
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(source.parseDiagnostics.length, 0, relative + ': parse diagnostics');
  return source.statements.filter(s => !ts.isImportDeclaration(s))
    .map(s => s.getText(source).replace(/^export\s+/, '')).join('\n');
});
const emitted = ts.transpileModule(modules.join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }, reportDiagnostics: true
});
assert.equal(emitted.diagnostics.length, 0);
const sandbox = vm.createContext({});
vm.runInContext(emitted.outputText, sandbox);
const api = vm.runInContext('({ CanvasPressureSampler, CanvasPenKind, canvas_pen_width, canvas_pen_color, ' +
  'canvas_point_width, validate_canvas_document, validate_answer_manifest, canvas_extent, canvas_grid, ' +
  'clone_canvas_document, draw_canvas_stroke, stroke_bounds, stroke_hit })', sandbox);
let scenarios = 0;
function check(name, run) {
  try { run(); scenarios++; }
  catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}
const stroke = (extra = {}) => ({ strokeId: 's', color: '#152744', width: 4,
  points: [{ x: -2, y: -2 }, { x: 30, y: 12 }], ...extra });
const hash = 'a'.repeat(64);
const doc = s => ({ schemaVersion: 1, questionLayout: { questionBodySha256: hash, contentWidth: 16,
  fontSize: 16, rendererId: 'legacy-renderer', bounds: { x: 0, y: 0, width: 16, height: 16 },
  blocks: [{ blockId: 'body', x: 0, y: 0, width: 16, height: 16 }],
  rasterBlocks: [{ index: 0, partName: 'question_0000', x: 0, y: 0, width: 16, height: 16,
    pixelWidth: 16, pixelHeight: 16, sha256: hash }] }, strokes: [s] });
class Context {
  constructor() { this.calls = []; }
  beginPath() { this.calls.push(['beginPath']); }
  moveTo(...args) { this.calls.push(['moveTo', ...args]); }
  lineTo(...args) { this.calls.push(['lineTo', ...args]); }
  arc(...args) { this.calls.push(['arc', ...args]); }
  closePath() { this.calls.push(['closePath']); }
  fill() { this.calls.push(['fill']); }
  stroke() { this.calls.push(['stroke']); }
}
for (const raw of [undefined, 0, -1, NaN, Infinity]) {
  check('no usable pressure remains stable ' + raw, () => {
    const sampler = new api.CanvasPressureSampler(); assert.equal(sampler.begin(raw), 0.6);
    for (let i = 0; i < 10; i++) assert.equal(sampler.next(raw), 0.6);
  });
}
check('pressure clamp, smooth ramp, loss and reset', () => {
  const sampler = new api.CanvasPressureSampler(); assert.equal(sampler.begin(9), 1);
  let last = sampler.begin(0.01);
  for (let i = 0; i < 30; i++) {
    const value = sampler.next(1); assert.ok(value >= last && value <= 1 && value - last <= 0.180001); last = value;
  }
  assert.ok(last > 0.999);
  assert.equal(sampler.next(0), last); assert.equal(sampler.next(NaN), last);
  sampler.reset(); assert.equal(sampler.next(undefined), 0.6);
});
check('pressure varies continuously within maximum width', () => {
  const s = stroke({ penKind: 'fountain' }); let last = 0;
  for (let i = 0; i <= 1000; i++) {
    const width = api.canvas_point_width(s, { x: 0, y: 0, pressure: i / 1000 });
    assert.ok(width >= 1.2 && width <= 4 && width >= last);
    if (i > 0) assert.ok(width - last < 0.003); last = width;
  }
});
for (const kind of [undefined, null, 'ballpoint', 'highlighter']) {
  check('legacy and fixed pens ignore pressure ' + kind, () => {
    const s = stroke({ penKind: kind });
    for (const pressure of [undefined, null, 0, 0.5, 1]) assert.equal(api.canvas_point_width(s, { x: 0, y: 0, pressure }), 4);
  });
}
for (const kind of ['ballpoint', 'fountain', 'highlighter']) {
  for (const pressure of [undefined, null, 0, 0.5, 1]) {
    check('valid pen/pressure JSON round trip ' + kind + '/' + pressure, () => {
      const s = stroke({ penKind: kind, points: [{ x: -2, y: -2, pressure }] });
      const restored = JSON.parse(JSON.stringify(doc(s)));
      api.validate_canvas_document(restored);
      const clone = api.clone_canvas_document(restored);
      assert.equal(JSON.stringify(clone), JSON.stringify(restored));
      clone.strokes[0].points[0].x = -99; assert.equal(restored.strokes[0].points[0].x, -2);
    });
  }
}
for (const pressure of [-0.1, 1.1, NaN, Infinity, '0.5']) {
  check('reject invalid pressure ' + pressure, () => {
    assert.throws(() => api.validate_canvas_document(doc(stroke({ points: [{ x: 0, y: 0, pressure }] }))));
  });
}
check('reject unknown or wrongly typed pen', () => {
  for (const penKind of ['brush', '', 1]) assert.throws(() => api.validate_canvas_document(doc(stroke({ penKind }))));
});
check('max width continues to contain variable stroke and eraser hit', () => {
  const s = stroke({ penKind: 'fountain', points: [{ x: -20, y: -20, pressure: 0 }, { x: 30, y: 12, pressure: 1 }] });
  const d = doc(s); const extent = api.canvas_extent(d); const bounds = api.stroke_bounds(s);
  assert.ok(bounds.x <= -22 && bounds.y <= -22);
  assert.ok(extent.originX <= bounds.x && extent.originY <= bounds.y);
  assert.ok(extent.originX + extent.width >= bounds.x + bounds.width);
  assert.ok(api.stroke_hit(s, { x: -22, y: -20 }, 0));
  const blocks = api.canvas_grid(extent); blocks.forEach(b => { b.sha256 = hash; });
  api.validate_answer_manifest({ schemaVersion: 1, saveRequestId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    baseFileRef: null, canvas: extent, document: d, blocks });
});
check('legacy painter keeps the original continuous stroke', () => {
  const c = new Context(); api.draw_canvas_stroke(c, stroke());
  assert.equal(c.lineWidth, 4); assert.equal(c.calls.filter(x => x[0] === 'stroke').length, 1);
  assert.equal(c.calls.filter(x => x[0] === 'fill').length, 0);
});
check('fountain uses one filled path with pressure radii and no segment alpha stacking', () => {
  const c = new Context(); const s = stroke({ penKind: 'fountain',
    points: [{ x: 0, y: 0, pressure: 0 }, { x: 20, y: 0, pressure: 0.5 }, { x: 40, y: 0, pressure: 1 }] });
  api.draw_canvas_stroke(c, s);
  assert.equal(c.calls.filter(x => x[0] === 'beginPath').length, 1);
  assert.equal(c.calls.filter(x => x[0] === 'fill').length, 1);
  assert.equal(c.calls.filter(x => x[0] === 'stroke').length, 0);
  assert.deepEqual(c.calls.filter(x => x[0] === 'arc').map(x => x[3]), [0.6, 1.2999999999999998, 2]);
});
check('highlighter converts AARRGGBB to rgba and draws the whole path only once', () => {
  const c = new Context(); const color = api.canvas_pen_color('highlighter', '#EAB308');
  assert.equal(color, '#4DEAB308');
  api.draw_canvas_stroke(c, stroke({ penKind: 'highlighter', color }));
  assert.equal(c.strokeStyle, 'rgba(234,179,8,' + (77 / 255) + ')');
  assert.equal(c.calls.filter(x => x[0] === 'stroke').length, 1);
  assert.equal(c.calls.filter(x => x[0] === 'fill').length, 0);
});
check('stationary and single point strokes keep a visible bounded dot', () => {
  for (const points of [[{ x: 1, y: 1, pressure: 0.2 }], [{ x: 1, y: 1, pressure: 0.2 }, { x: 1, y: 1, pressure: 0.9 }]]) {
    const c = new Context(); api.draw_canvas_stroke(c, stroke({ penKind: 'fountain', points }));
    assert.equal(c.calls.filter(x => x[0] === 'fill').length, 1);
    assert.ok(c.calls.flat().filter(x => typeof x === 'number').every(Number.isFinite));
  }
});
check('three size presets are ordered and within the manifest width limit', () => {
  for (const kind of ['ballpoint', 'fountain', 'highlighter']) {
    const sizes = [0, 1, 2].map(size => api.canvas_pen_width(kind, size));
    assert.ok(sizes[0] >= 0.5 && sizes[0] < sizes[1] && sizes[1] < sizes[2] && sizes[2] <= 64);
  }
});
console.log(JSON.stringify({ scenarios, result: 'PASS', source: files,
  boundary: 'pure source execution + Canvas call recording; no ArkUI rasterization, disk/network round trip or physical stylus' }));
