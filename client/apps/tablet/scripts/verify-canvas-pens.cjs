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
  'client/apps/tablet/src/main/ets/features/canvas/CanvasStrokeBudget.ets',
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
const encodedValues = [];
const sandbox = vm.createContext({ canvas_utf8: text => { encodedValues.push(text); return new TextEncoder().encode(text); } });
vm.runInContext(emitted.outputText, sandbox);
const api = vm.runInContext('({ CanvasPressureSampler, CanvasPenKind, CanvasStrokeBudget, CANVAS_EDITABLE_JSON_LIMIT, canvas_stored_pressure, canvas_pen_width, canvas_pen_color, ' +
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
check('stored pressure precision is bounded independently of sampler internals', () => {
  const sampler = new api.CanvasPressureSampler(); sampler.begin(0.123456789);
  const smoothed = sampler.next(0.987654321); const stored = api.canvas_stored_pressure(smoothed);
  assert.ok(Math.abs(smoothed - stored) <= 0.0005);
  assert.equal(stored * 1000, Math.round(stored * 1000));
  assert.notEqual(smoothed, stored);
});
const byteLength = value => Buffer.byteLength(JSON.stringify(value), 'utf8');
check('budget counts UTF-8 layout, stroke metadata and point commas exactly', () => {
  const d = doc(stroke({ strokeId: '旧笔迹😀' })); d.questionLayout.rendererId = '题面✓';
  const live = stroke({ strokeId: '新笔迹😀', penKind: 'fountain', points: [{ x: 0, y: 0, pressure: 0.123 }] });
  const point = { x: 1.2345, y: 6.789, pressure: 0.987 };
  const exact = byteLength({ ...d, strokes: d.strokes.concat([{ ...live, points: live.points.concat([point]) }]) });
  const budget = new api.CanvasStrokeBudget(exact);
  assert.ok(budget.begin(d, live)); assert.ok(budget.append(point)); live.points.push(point);
  assert.equal(byteLength({ ...d, strokes: d.strokes.concat([live]) }), exact);
  assert.equal(budget.append({ x: 2, y: 3, pressure: 0.5 }), false);
});
check('cancel, commit, undo and erasure invalidate size accounting without retaining canceled ink', () => {
  const base = doc(stroke()); const live = stroke({ strokeId: 'new', points: [{ x: 0, y: 0, pressure: 0.6 }] });
  const finalSize = byteLength({ ...base, strokes: base.strokes.concat([live]) });
  const budget = new api.CanvasStrokeBudget(finalSize);
  for (let i = 0; i < 30; i++) { assert.ok(budget.begin(base, live)); budget.finish(); }
  const committed = { ...base, strokes: base.strokes.concat([live]) };
  assert.equal(budget.begin(committed, stroke({ strokeId: 'extra' })), false);
  budget.finish();
  assert.ok(budget.begin({ ...base, strokes: base.strokes }, live), 'undo restores budget'); budget.finish();
  assert.ok(budget.begin({ ...base, strokes: [] }, live), 'eraser frees budget');
});
check('reserved envelope covers maximum descriptor count and coordinate digits', () => {
  const blocks = Array.from({ length: 256 }, (_, index) => ({ index, partName: 'chunk_' + String(index).padStart(4, '0'),
    x: 1000000, y: 1000000, width: 1024, height: 1024, pixelWidth: 1024, pixelHeight: 1024, sha256: hash }));
  const envelope = byteLength({ schemaVersion: 1, saveRequestId: 'a'.repeat(36), baseFileRef: 'b'.repeat(36),
    canvas: { width: 2000000, height: 2000000, originX: -1000000, originY: -1000000, coordinateUnit: 'logical' }, document: {}, blocks }) - 2;
  assert.ok(envelope < 8388608 - api.CANVAS_EDITABLE_JSON_LIMIT);
});
const longTraceEvidence = {};
check('representative 150000 point pressure trace stays uploadable after quantization', () => {
  const original = []; const stored = []; const sampler = new api.CanvasPressureSampler(); sampler.begin(0.6);
  for (let i = 0; i < 150000; i++) {
    const pressure = sampler.next(0.5 + 0.4 * Math.sin(i / 37));
    const point = { x: Number((i * 0.0173).toFixed(4)), y: Number((i * 0.0097).toFixed(4)), pressure };
    original.push(point); stored.push({ ...point, pressure: api.canvas_stored_pressure(pressure) });
  }
  const originalDoc = doc(stroke({ penKind: 'fountain', points: original }));
  const storedDoc = doc(stroke({ penKind: 'fountain', points: stored }));
  const originalBytes = byteLength(originalDoc); const storedBytes = byteLength(storedDoc);
  assert.ok(originalBytes > 8388608, 'reproduces pressure precision regression');
  assert.ok(storedBytes < api.CANVAS_EDITABLE_JSON_LIMIT);
  const empty = { ...storedDoc, strokes: [] }; const live = { ...storedDoc.strokes[0], points: [stored[0]] };
  const budget = new api.CanvasStrokeBudget(); assert.ok(budget.begin(empty, live));
  for (let i = 1; i < stored.length; i++) { assert.ok(budget.append(stored[i])); live.points.push(stored[i]); }
  assert.equal(byteLength({ ...empty, strokes: [live] }), storedBytes);
  Object.assign(longTraceEvidence, { points: 150000, originalBytes, storedBytes, documentLimit: api.CANVAS_EDITABLE_JSON_LIMIT });
});
check('200000 high precision coordinate points stop before JSON overflow and preserve accepted prefix', () => {
  const d = doc(stroke()); const live = stroke({ strokeId: 'long', penKind: 'fountain', points: [{ x: 0, y: 0, pressure: 0.6 }] });
  const budget = new api.CanvasStrokeBudget(); assert.ok(budget.begin(d, live)); let stopped = false;
  for (let i = 1; i < 200000; i++) {
    const point = { x: i * Math.PI / 10, y: i * Math.E / 10, pressure: api.canvas_stored_pressure(0.5 + 0.4 * Math.sin(i)) };
    if (!budget.append(point)) { stopped = true; break; } live.points.push(point);
  }
  const acceptedBytes = byteLength({ ...d, strokes: d.strokes.concat([live]) });
  assert.ok(stopped && live.points.length < 200000);
  assert.ok(acceptedBytes <= api.CANVAS_EDITABLE_JSON_LIMIT);
  assert.equal(d.strokes.length, 1); assert.equal(d.strokes[0].strokeId, 's');
  Object.assign(longTraceEvidence, { highPrecisionAcceptedPoints: live.points.length, acceptedBytes });
});
check('clear immediately releases a large old document layout and cached stroke identities', () => {
  const old = doc(stroke({ points: Array.from({ length: 200000 }, (_, i) => ({ x: i / 10, y: i / 20 })) }));
  const budget = new api.CanvasStrokeBudget();
  budget.begin(old, stroke({ strokeId: 'new' })); budget.finish();
  assert.equal(budget.sizes.size, 1); assert.equal(budget.layout, old.questionLayout);
  budget.clear();
  assert.equal(budget.sizes.size, 0); assert.equal(budget.layout, null); assert.equal(budget.stroke, null);
  assert.equal(budget.emptyBytes, 0); assert.equal(budget.documentBytes, 0); assert.equal(budget.remaining, 0);
});
check('finish reuses old encoded sizes while retain prunes removed cache keys without recoding points', () => {
  const first = stroke({ strokeId: 'first' }); const second = stroke({ strokeId: 'second' });
  const d = doc(first); d.strokes.push(second); const budget = new api.CanvasStrokeBudget();
  budget.begin(d, stroke({ strokeId: 'active1' })); budget.finish();
  const serialized = JSON.stringify(first); const count = encodedValues.filter(value => value === serialized).length;
  budget.begin(d, stroke({ strokeId: 'active2' })); budget.finish();
  assert.equal(encodedValues.filter(value => value === serialized).length, count, 'normal up reuses committed stroke size');
  budget.retain([first]);
  assert.equal(budget.sizes.has(second), false); assert.equal(budget.sizes.has(first), true);
  assert.equal(encodedValues.filter(value => value === serialized).length, count, 'pruning must not reserialize kept points');
  budget.retain([]); assert.equal(budget.sizes.size, 0);
});
console.log(JSON.stringify({ scenarios, longTraceEvidence, result: 'PASS', source: files,
  boundary: 'pure source execution + Canvas call recording; no ArkUI rasterization, disk/network round trip or physical stylus' }));
