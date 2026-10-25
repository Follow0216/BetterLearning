#!/usr/bin/env node
// Runs actual CanvasSurface input methods with native rendering/file IO stubbed.
// This is a synthetic event test, not proof of stylus hardware or ArkUI hit testing.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const loader = process.env.BETTERLEARNING_ETS_LOADER ||
  '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = ts.readConfigFile(path.join(loader, 'tsconfig.json'), ts.sys.readFile).config.compilerOptions;
const root = path.resolve(__dirname, '../../../..');
const surfacePath = path.join(root, 'client/apps/tablet/src/main/ets/features/canvas/CanvasSurface.ets');
const surfaceText = fs.readFileSync(surfacePath, 'utf8');
function parse(file, source) {
  const result = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(result.parseDiagnostics.length, 0, file + ': ' +
    result.parseDiagnostics.map(d => ts.flattenDiagnosticMessageText(d.messageText, ' ')).join('; '));
  return result;
}
const surface = parse(surfacePath, surfaceText);
const component = surface.statements.find(s => s.name?.text === 'CanvasSurface');
const methods = new Set(['reset', 'aboutToDisappear', 'touch', 'cancel_gesture', 'discard_edit', 'control_touch', 'point', 'append_point',
  'erase_path', 'erase', 'commit', 'undo', 'update_undo', 'read_only_changed',
  'stop_stroke', 'capture_at_pen_up', 'fulfill_freeze', 'cancel_freeze', 'document_changed']);
const fields = new Set(['freezeHandle', 'paintGeneration', 'backgroundMap', 'viewSet', 'tool', 'readOnly', 'document', 'undoEnabled', 'pointers', 'pointerSources', 'editPointerId',
  'penKind', 'penColor', 'penSize', 'pressureSampler', 'strokeBudget', 'strokeStopped', 'navigationKey', 'last', 'pinchView', 'pinchMid', 'pinchDistance', 'suppress', 'liveStroke', 'eraseBefore',
  'erased', 'undoHistory', 'pointBudget', 'view', 'alive', 'committedDocument', 'waitingFreeze']);
const extracted = [];
for (const member of component.members) {
  const name = member.name?.getText(surface);
  if (ts.isMethodDeclaration(member) && methods.delete(name)) extracted.push(member.getText(surface));
  else if (ts.isPropertyDeclaration(member) && fields.delete(name)) {
    extracted.push(name + ' = ' + member.initializer.getText(surface) + ';');
  }
}
assert.equal(methods.size + fields.size, 0, 'Missing actual source members');

const geometryPath = path.join(root, 'client/common/src/main/ets/features/canvas/CanvasGeometry.ets');
const geometry = parse(geometryPath, fs.readFileSync(geometryPath, 'utf8'));
const geometryNames = new Set(['world_point', 'zoom_canvas', 'valid_world_number', 'segment_distance', 'stroke_hit', 'clone_canvas_document']);
const geometryCode = geometry.statements.filter(s => ts.isFunctionDeclaration(s) && geometryNames.has(s.name.text))
  .map(s => s.getText(geometry).replace(/^export\s+/, '')).join('\n');
const modelPath = path.join(root, 'client/common/src/main/ets/features/canvas/CanvasModels.ets');
const model = parse(modelPath, fs.readFileSync(modelPath, 'utf8'));
const canvasTool = model.statements.filter(s => ['CanvasTool', 'CanvasPenKind'].includes(s.name?.text))
  .map(s => s.getText(model).replace(/^export\s+/, '')).join('\n');
function moduleCode(relative) {
  const file = path.join(root, relative);
  const parsed = parse(file, fs.readFileSync(file, 'utf8'));
  return parsed.statements.filter(s => !ts.isImportDeclaration(s))
    .map(s => s.getText(parsed).replace(/^export\s+/, '')).join('\n');
}
const penCode = moduleCode('client/common/src/main/ets/features/canvas/CanvasPens.ets') + '\n' +
  moduleCode('client/apps/tablet/src/main/ets/features/canvas/CanvasPenInput.ets') + '\n' +
  moduleCode('client/apps/tablet/src/main/ets/features/canvas/CanvasStrokeBudget.ets');
const enumNames = new Set(['TouchType', 'SourceTool', 'SourceType']);
const enumCode = [];
for (const name of ['common.d.ts', 'enums.d.ts']) {
  const file = path.resolve(loader, '../../component', name);
  const sdk = parse(file, fs.readFileSync(file, 'utf8'));
  for (const statement of sdk.statements) {
    if (ts.isEnumDeclaration(statement) && enumNames.delete(statement.name.text)) {
      enumCode.push(statement.getText(sdk).replace(/^declare\s+/, ''));
    }
  }
}
assert.equal(enumNames.size, 0, 'Missing SDK enums');
const authPath = path.join(root, 'client/common/src/main/ets/features/auth/AuthModels.ets');
const auth = parse(authPath, fs.readFileSync(authPath, 'utf8'));
const authNames = new Set(['AuthFailureKind', 'AuthError', 'AuthResult']);
const authCode = auth.statements.filter(s => s.name && authNames.has(s.name.text))
  .map(s => s.getText(auth).replace(/^export\s+/, '')).join('\n');
const code = authCode + '\n' + enumCode.join('\n') + '\n' + canvasTool + '\n' + penCode + '\n' + geometryCode + `
class InputHarness {
  ${extracted.join('\n')}
  constructor() {
    this.view = { x: 0, y: 0, scale: 1 };
    this.document = { schemaVersion: 1, questionLayout: { bounds: { x: 0, y: 0, width: 720, height: 1024 } },
      strokes: [{ strokeId: 'existing', color: '#152744', width: 2.5, points: [{ x: 10, y: 10 }, { x: 30, y: 30 }] }] };
    this.committedDocument = this.document; this.alive = true;
    this.changes = []; this.savedViews = []; this.failures = [];
    this.onDocumentChange = doc => { this.document = doc; this.changes.push(JSON.stringify(doc)); };
    this.onViewChange = view => this.savedViews.push(JSON.stringify(view));
    this.onUndoChange = () => {}; this.onFreezeHandle = () => {};
    this.onFailure = message => this.failures.push(message);
  }
  paint() {}
  configure_view() {}
  change_view(view, persist) { this.view = view; if (persist) this.onViewChange(view); }
}
globalThis.InputHarness = InputHarness;
globalThis.TouchType = TouchType;
globalThis.SourceTool = SourceTool;
globalThis.SourceType = SourceType;
globalThis.CanvasTool = CanvasTool;
globalThis.CanvasPenKind = CanvasPenKind;
globalThis.CanvasStrokeBudget = CanvasStrokeBudget;
`;
const transpiled = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 }, reportDiagnostics: true });
assert.equal(transpiled.diagnostics.length, 0);
let nextId = 0;
const sandbox = { canvas_utf8: text => new TextEncoder().encode(text), util: { generateRandomUUID: () => 'synthetic-' + (++nextId) } };
vm.createContext(sandbox);
vm.runInContext(transpiled.outputText, sandbox, { filename: 'actual-canvas-input-harness.js' });
const { InputHarness, SourceTool: Source, SourceType: Device, TouchType: Phase, CanvasTool: Tool } = sandbox;
const pt = (id, x, y) => ({ id, x, y });
const json = value => JSON.stringify(value);
function event(h, phase, source, changed, touches = changed, device = Device.TouchScreen, pressure = 0.6) {
  let stopped = false;
  h.touch({ type: phase, pressure, source: device, sourceTool: source, changedTouches: changed, touches,
    stopPropagation: () => { stopped = true; } });
  assert.ok(stopped, 'Canvas touch must stop bubbling');
}
function drag(h, source, id = 1) {
  event(h, Phase.Down, source, [pt(id, 10, 10)]);
  event(h, Phase.Move, source, [pt(id, 25, 20)]);
  event(h, Phase.Up, source, [pt(id, 30, 25)]);
}
let scenarios = 0;
function check(name, run) {
  try { run(); scenarios++; }
  catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}
for (const source of [Source.Finger, Source.Unknown, Source.MOUSE, Source.TOUCHPAD, undefined]) {
  for (const tool of [Tool.PEN, Tool.ERASER, Tool.MOVE]) {
    check('non-pen ' + source + ' with ' + tool, () => {
      const h = new InputHarness(); h.tool = tool; const before = json(h.document);
      drag(h, source);
      assert.equal(json(h.document), before);
      assert.equal(h.changes.length, 0);
      assert.equal(h.undoHistory.length, 0);
      assert.equal(h.liveStroke, null); assert.equal(h.erased, null);
      assert.equal(h.view.x, -20); assert.equal(h.view.y, -15);
    });
  }
}
for (const device of [Device.Unknown, Device.Mouse, Device.KEY, Device.JOYSTICK, null]) {
  for (const tool of [Tool.PEN, Tool.ERASER, Tool.MOVE]) {
    check('non-touchscreen device cannot claim Pen tool ' + device + '/' + tool, () => {
      const h = new InputHarness(); h.tool = tool; const before = json(h.document);
      for (const [phase, x] of [[Phase.Down, 10], [Phase.Move, 20], [Phase.Up, 30]]) {
        const points = [pt(1, x, x)];
        event(h, phase, Source.Pen, points, points, device);
      }
      assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
    });
  }
  for (const tool of [Tool.PEN, Tool.ERASER]) {
    for (const phase of [Phase.Move, Phase.Up]) {
      check('device source cancels in-flight Pen ' + device + '/' + tool + '/' + phase, () => {
        const h = new InputHarness(); h.tool = tool; const before = json(h.document);
        event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
        const points = [pt(1, 20, 20)];
        event(h, phase, Source.Pen, points, points, device);
        if (phase !== Phase.Up) event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
        assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
      });
    }
  }
}
check('single stylus writes once and undo restores', () => {
  const h = new InputHarness(); const before = json(h.document);
  h.view = { x: -50, y: -20, scale: 2 };
  drag(h, Source.Pen);
  assert.equal(h.changes.length, 1); assert.equal(h.document.strokes.length, 2);
  assert.equal(json(h.document.strokes[1].points[0]), json({ x: -45, y: -15, pressure: 0.6 }));
  assert.equal(h.undoHistory.length, 1);
  h.undo(); assert.equal(json(h.document), before);
});
check('stylus erases only on up and undo restores', () => {
  const h = new InputHarness(); h.tool = Tool.ERASER; const before = json(h.document);
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  assert.equal(json(h.document), before); assert.equal(h.erased.length, 0);
  event(h, Phase.Move, Source.Pen, [pt(1, 25, 20)]);
  assert.equal(h.changes.length, 0);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 25)]);
  assert.equal(h.changes.length, 1); assert.equal(h.document.strokes.length, 0);
  h.undo(); assert.equal(json(h.document), before);
});
for (const tool of [Tool.PEN, Tool.ERASER, Tool.MOVE]) {
  check('completed read-only ' + tool, () => {
    const h = new InputHarness(); h.tool = tool; h.readOnly = true; const before = json(h.document);
    drag(h, Source.Pen); assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
    assert.equal(h.view.x, -20);
  });
}
check('stylus move tool pans', () => {
  const h = new InputHarness(); h.tool = Tool.MOVE;
  drag(h, Source.Pen); assert.equal(h.changes.length, 0); assert.equal(h.view.x, -20);
});
for (const tool of [Tool.PEN, Tool.ERASER]) {
  for (const endSource of [Source.Finger, Source.Unknown, Source.MOUSE]) {
    check('source change cancels ' + tool + '/' + endSource, () => {
      const h = new InputHarness(); h.tool = tool; const before = json(h.document);
      event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
      event(h, Phase.Move, endSource, [pt(1, 20, 20)]);
      event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
      assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
      assert.equal(h.liveStroke, null); assert.equal(h.erased, null);
    });
  }
  check('Cancel rolls back ' + tool, () => {
    const h = new InputHarness(); h.tool = tool; const before = json(h.document);
    event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
    event(h, Phase.Cancel, Source.Pen, []);
    event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
    assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
  });
  check('mixed pen and finger cannot commit ' + tool, () => {
    const h = new InputHarness(); h.tool = tool; const before = json(h.document);
    event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
    event(h, Phase.Down, Source.Finger, [pt(2, 100, 100)], [pt(1, 10, 10), pt(2, 100, 100)]);
    assert.equal(h.liveStroke, null); assert.equal(h.erased, null);
    event(h, Phase.Move, Source.Finger, [pt(2, 120, 110)], [pt(1, 10, 10), pt(2, 120, 110)]);
    assert.equal(h.view.x, -20);
    event(h, Phase.Up, Source.Finger, [pt(2, 120, 110)], [pt(1, 10, 10)]);
    event(h, Phase.Move, Source.Pen, [pt(1, 30, 30)]);
    event(h, Phase.Up, Source.Pen, [pt(1, 40, 40)]);
    assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
    drag(h, Source.Pen, 3); assert.equal(h.changes.length, 1, 'Fresh pen gesture must work after all contacts lift');
  });
}
check('finger cannot upgrade to pen mid-contact', () => {
  const h = new InputHarness(); const before = json(h.document);
  event(h, Phase.Down, Source.Finger, [pt(1, 10, 10)]);
  event(h, Phase.Move, Source.Pen, [pt(1, 20, 20)]);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
  assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
});
check('orphan move/up cannot write', () => {
  const h = new InputHarness();
  event(h, Phase.Move, Source.Pen, [pt(7, 10, 10)]);
  event(h, Phase.Up, Source.Pen, [pt(7, 20, 20)]);
  assert.equal(h.changes.length, 0);
});
check('duplicate Down cancels ink', () => {
  const h = new InputHarness();
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  event(h, Phase.Down, Source.Pen, [pt(1, 20, 20)]);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
  assert.equal(h.changes.length, 0);
});
check('snapshot discovers missed second contact and cancels', () => {
  const h = new InputHarness();
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  event(h, Phase.Move, Source.Pen, [pt(1, 20, 20)], [pt(1, 20, 20), pt(2, 100, 100)]);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)], [pt(2, 100, 100)]);
  assert.equal(h.changes.length, 0); assert.equal(h.liveStroke, null);
});
check('batched Pen Down does not guess per-pointer source', () => {
  const h = new InputHarness();
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10), pt(2, 100, 100)]);
  event(h, Phase.Up, Source.Pen, [pt(1, 20, 20), pt(2, 110, 110)]);
  assert.equal(h.changes.length, 0);
});
for (const batched of [false, true]) {
  check('two-finger zoom and remaining-finger pan ' + batched, () => {
    const h = new InputHarness(); const before = json(h.document);
    if (batched) event(h, Phase.Down, Source.Finger, [pt(1, 0, 0), pt(2, 100, 0)]);
    else {
      event(h, Phase.Down, Source.Finger, [pt(1, 0, 0)]);
      event(h, Phase.Down, Source.Finger, [pt(2, 100, 0)], [pt(1, 0, 0), pt(2, 100, 0)]);
    }
    event(h, Phase.Move, Source.Finger, [pt(2, 200, 0)], [pt(1, 0, 0), pt(2, 200, 0)]);
    assert.equal(h.view.scale, 2);
    event(h, Phase.Up, Source.Finger, [pt(2, 200, 0)], [pt(1, 0, 0)]);
    const x = h.view.x;
    event(h, Phase.Move, Source.Finger, [pt(1, 10, 0)]);
    assert.equal(h.view.x, x, 'Rebase after pinch without jump');
    event(h, Phase.Move, Source.Finger, [pt(1, 30, 0)]);
    assert.equal(h.view.x, x - 10);
    event(h, Phase.Up, Source.Finger, [pt(1, 30, 0)]);
    assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
    assert.equal(h.savedViews.length, 1);
  });
}
check('read-only transition cancels preview', () => {
  const h = new InputHarness(); const before = json(h.document);
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  h.readOnly = true; h.read_only_changed();
  event(h, Phase.Up, Source.Pen, [pt(1, 20, 20)]);
  assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
});
for (const phase of [Phase.Up, Phase.Move]) {
  check('empty changedTouches cancels and recovers ' + phase, () => {
    const h = new InputHarness(); const before = json(h.document);
    event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
    event(h, phase, Source.Pen, []);
    assert.equal(h.liveStroke, null); assert.equal(h.pointers.size, 0);
    assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
    drag(h, Source.Pen, 2); assert.equal(h.changes.length, 1);
  });
}
check('different-id Pen Up cannot finish a stroke', () => {
  const h = new InputHarness(); const before = json(h.document);
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  event(h, Phase.Up, Source.Pen, [pt(2, 20, 20)], [pt(1, 10, 10)]);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
  assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
});
check('control touch cancels ink without changing document', () => {
  const h = new InputHarness(); const before = json(h.document); let stopped = false;
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  h.control_touch({ stopPropagation: () => { stopped = true; } });
  event(h, Phase.Up, Source.Pen, [pt(1, 20, 20)]);
  assert.ok(stopped); assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
  assert.equal((surfaceText.match(/\.hitTestBehavior\(HitTestMode.Default\)/g) || []).length, 2);
  assert.equal((surfaceText.match(/\.onTouch\(\(event: TouchEvent\) => \{ this.control_touch\(event\); \}\)/g) || []).length, 2);
});
for (const kind of Object.values(sandbox.CanvasPenKind)) {
  check('pen kind persists with selected color and size ' + kind, () => {
    const h = new InputHarness(); h.penKind = kind; h.penColor = '#2563EB'; h.penSize = 2;
    drag(h, Source.Pen);
    const stroke = h.document.strokes[1];
    assert.equal(stroke.penKind, kind);
    assert.equal(stroke.color, kind === 'highlighter' ? '#4D2563EB' : '#2563EB');
    assert.equal(stroke.width, kind === 'highlighter' ? 24 : kind === 'fountain' ? 6 : 4);
    assert.equal(stroke.points[0].pressure, kind === 'fountain' ? 0.6 : undefined);
    const saved = JSON.parse(JSON.stringify(h.document));
    assert.equal(json(saved), json(h.document));
    h.undo(); assert.equal(h.document.strokes.length, 1);
  });
}
check('fountain pressure varies continuously and missing up retains the last pressure', () => {
  const h = new InputHarness();
  for (const [phase, x, pressure] of [[Phase.Down, 10, 0.1], [Phase.Move, 20, 0.5],
    [Phase.Move, 30, 1], [Phase.Up, 40, 0]]) {
    const points = [pt(1, x, x)]; event(h, phase, Source.Pen, points, points, Device.TouchScreen, pressure);
  }
  const pressures = h.document.strokes[1].points.map(p => p.pressure);
  assert.ok(pressures[0] < pressures[1] && pressures[1] < pressures[2]);
  assert.equal(pressures[3], pressures[2]);
  for (let i = 1; i < pressures.length; i++) assert.ok(Math.abs(pressures[i] - pressures[i - 1]) <= 0.180001);
});
check('stationary pressure changes are retained without requiring coordinate movement', () => {
  const h = new InputHarness(); const points = [pt(1, 10, 10)];
  event(h, Phase.Down, Source.Pen, points, points, Device.TouchScreen, 0.1);
  event(h, Phase.Move, Source.Pen, points, points, Device.TouchScreen, 1);
  event(h, Phase.Up, Source.Pen, points, points, Device.TouchScreen, 0);
  assert.equal(h.document.strokes[1].points.length, 2);
});
check('finger pressure never grants ink permission', () => {
  const h = new InputHarness(); const before = json(h.document);
  for (const [phase, x] of [[Phase.Down, 10], [Phase.Move, 20], [Phase.Up, 30]]) {
    const points = [pt(1, x, x)]; event(h, phase, Source.Finger, points, points, Device.TouchScreen, 1);
  }
  assert.equal(json(h.document), before);
});
check('capacity rejects the next sample explicitly and preserves the accepted prefix on up', () => {
  const h = new InputHarness(); const before = json(h.document);
  event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
  const starter = json(h.liveStroke);
  const exact = Buffer.byteLength(json({ ...h.committedDocument, strokes: h.committedDocument.strokes.concat([h.liveStroke]) }));
  h.strokeBudget = new sandbox.CanvasStrokeBudget(exact);
  assert.equal(h.strokeBudget.begin(h.committedDocument, h.liveStroke), true);
  event(h, Phase.Move, Source.Pen, [pt(1, 20, 20)]);
  assert.equal(h.strokeStopped, true); assert.equal(json(h.liveStroke), starter);
  assert.equal(json(h.document), before); assert.ok(h.failures[0].includes('新的笔迹已停止'));
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
  assert.equal(h.document.strokes.length, 2); assert.equal(json(h.document.strokes[1]), starter);
  assert.ok(h.failures[h.failures.length - 1].includes('已保留'));
  h.undo(); assert.equal(json(h.document), before);
});
check('capacity refuses new pen without modifying the old document and recovers after erasure', () => {
  const h = new InputHarness(); const before = json(h.document);
  h.strokeBudget = new sandbox.CanvasStrokeBudget(1);
  drag(h, Source.Pen); assert.equal(json(h.document), before); assert.equal(h.changes.length, 0);
  assert.ok(h.failures.length > 0);
  assert.ok(!h.failures[h.failures.length - 1].includes('本笔已接收'));
  h.strokeBudget = new sandbox.CanvasStrokeBudget(); h.tool = Tool.ERASER;
  drag(h, Source.Pen); assert.equal(h.document.strokes.length, 0);
  h.tool = Tool.PEN; drag(h, Source.Pen, 2); assert.equal(h.document.strokes.length, 1);
});
check('point budget stops explicitly without silently discarding samples', () => {
  const h = new InputHarness(); event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]); h.pointBudget = 1;
  event(h, Phase.Move, Source.Pen, [pt(1, 20, 20)]); assert.equal(h.strokeStopped, true);
  event(h, Phase.Up, Source.Pen, [pt(1, 30, 30)]);
  assert.equal(h.document.strokes[1].points.length, 1); assert.ok(h.failures.length > 0);
});
for (const lifecycle of ['reset', 'aboutToDisappear']) {
  check('budget releases old question references immediately on ' + lifecycle, () => {
    const h = new InputHarness();
    drag(h, Source.Pen);
    assert.ok(h.strokeBudget.sizes.size > 0); assert.notEqual(h.strokeBudget.layout, null);
    if (lifecycle === 'reset') h.document = { ...h.document, questionLayout: { bounds: { x: 0, y: 0, width: 720, height: 1024 } }, strokes: [] };
    h[lifecycle]();
    assert.equal(h.strokeBudget.sizes.size, 0); assert.equal(h.strokeBudget.layout, null);
    assert.equal(h.strokeBudget.stroke, null); assert.equal(h.strokeBudget.emptyBytes, 0);
    assert.equal(h.strokeBudget.documentBytes, 0);
  });
}
check('normal up keeps old stroke cache while erasure and undo prune removed identities immediately', () => {
  const h = new InputHarness(); const old = h.document.strokes[0]; drag(h, Source.Pen);
  assert.ok(h.strokeBudget.sizes.has(old), 'normal up must not clear cache');
  const added = h.document.strokes[1];
  h.strokeBudget.begin(h.document, { strokeId: 'future', color: '#152744', width: 2, points: [{ x: 0, y: 0 }] });
  h.strokeBudget.finish(); assert.ok(h.strokeBudget.sizes.has(added));
  h.undo(); assert.equal(h.strokeBudget.sizes.has(added), false); assert.ok(h.strokeBudget.sizes.has(old));
  h.tool = Tool.ERASER; drag(h, Source.Pen, 3);
  assert.equal(h.strokeBudget.sizes.has(old), false); assert.equal(h.document.strokes.length, 0);
});
async function freezeChecks() {
  async function checkAsync(name, run) {
    try { await run(); scenarios++; }
    catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
  }
  await checkAsync('idle freeze is a deep isolated document', async () => {
    const h = new InputHarness();
    const result = await h.capture_at_pen_up();
    assert.equal(result.isSuccess, true); const frozen = json(result.data);
    drag(h, Source.Pen);
    assert.equal(json(result.data), frozen); assert.equal(result.data.strokes.length, 1);
    result.data.strokes[0].points[0].x = -999;
    assert.equal(h.committedDocument.strokes[0].points[0].x, 10);
  });
  await checkAsync('freeze waits for actual pen up and excludes the next pen', async () => {
    const h = new InputHarness();
    event(h, Phase.Down, Source.Pen, [pt(1, 40, 40)]);
    const pending = h.capture_at_pen_up(); let settled = false;
    pending.then(() => { settled = true; }); await Promise.resolve(); assert.equal(settled, false);
    event(h, Phase.Move, Source.Pen, [pt(1, 50, 50)]);
    event(h, Phase.Up, Source.Pen, [pt(1, 60, 70)]);
    // 故意在 Promise continuation 前提交下一笔，检查捕获发生于 Up 同步栈。
    drag(h, Source.Pen, 2);
    const result = await pending;
    assert.equal(result.isSuccess, true); assert.equal(result.data.strokes.length, 2);
    assert.equal(h.committedDocument.strokes.length, 3);
    const points = result.data.strokes[1].points;
    assert.equal(points[points.length - 1].x, 60); assert.equal(points[points.length - 1].y, 70);
  });
  await checkAsync('eraser freeze includes the whole-stroke removal only after up', async () => {
    const h = new InputHarness(); h.tool = Tool.ERASER;
    event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
    const pending = h.capture_at_pen_up();
    assert.equal(h.committedDocument.strokes.length, 1);
    event(h, Phase.Up, Source.Pen, [pt(1, 20, 20)]);
    const result = await pending; assert.equal(result.isSuccess, true); assert.equal(result.data.strokes.length, 0);
  });
  await checkAsync('finger navigation does not delay or alter ink capture', async () => {
    const h = new InputHarness(); event(h, Phase.Down, Source.Finger, [pt(1, 10, 10)]);
    const result = await h.capture_at_pen_up(); assert.equal(result.isSuccess, true);
    assert.equal(result.data.strokes.length, 1); assert.equal(h.waitingFreeze, null);
  });
  await checkAsync('source conflict fails pending capture', async () => {
    const h = new InputHarness(); event(h, Phase.Down, Source.Pen, [pt(1, 10, 10)]);
    const pending = h.capture_at_pen_up();
    event(h, Phase.Move, Source.Finger, [pt(1, 30, 30)]);
    const result = await pending; assert.equal(result.isSuccess, false); assert.equal(h.committedDocument.strokes.length, 1);
  });
  await checkAsync('local freeze cancellation preserves the user active pen', async () => {
    const h = new InputHarness(); event(h, Phase.Down, Source.Pen, [pt(1, 40, 40)]);
    const pending = h.capture_at_pen_up(); h.cancel_freeze();
    assert.equal((await pending).isSuccess, false); assert.notEqual(h.liveStroke, null);
    event(h, Phase.Up, Source.Pen, [pt(1, 50, 50)]); assert.equal(h.committedDocument.strokes.length, 2);
  });
  await checkAsync('rapid pens survive delayed and stale parent prop echoes', async () => {
    const h = new InputHarness();
    h.onDocumentChange = doc => { h.changes.push(json(doc)); };
    drag(h, Source.Pen); drag(h, Source.Pen, 2);
    assert.equal(h.document.strokes.length, 1, 'parent Prop intentionally remains stale');
    h.document_changed();
    const result = await h.capture_at_pen_up(); assert.equal(result.isSuccess, true); assert.equal(result.data.strokes.length, 3);
  });
  await checkAsync('read-only cancellation fails pending capture without committing preview', async () => {
    const h = new InputHarness(); event(h, Phase.Down, Source.Pen, [pt(1, 40, 40)]);
    const pending = h.capture_at_pen_up(); h.readOnly = true; h.read_only_changed();
    assert.equal((await pending).isSuccess, false); assert.equal(h.committedDocument.strokes.length, 1);
  });
  await checkAsync('second pending capture cannot replace the first waiter', async () => {
    const h = new InputHarness(); event(h, Phase.Down, Source.Pen, [pt(1, 40, 40)]);
    const first = h.capture_at_pen_up(); const second = await h.capture_at_pen_up();
    assert.equal(second.isSuccess, false);
    event(h, Phase.Up, Source.Pen, [pt(1, 50, 50)]); assert.equal((await first).isSuccess, true);
  });
  await checkAsync('pressure metadata freezes at pen-up and excludes later pen changes', async () => {
    const h = new InputHarness(); const points = [pt(1, 40, 40)];
    event(h, Phase.Down, Source.Pen, points, points, Device.TouchScreen, 0.1);
    const pending = h.capture_at_pen_up();
    event(h, Phase.Up, Source.Pen, [pt(1, 60, 60)], [pt(1, 60, 60)], Device.TouchScreen, 1);
    const result = await pending; const frozen = json(result.data);
    h.penKind = 'highlighter'; drag(h, Source.Pen, 2);
    assert.equal(result.data.strokes[1].penKind, 'fountain');
    assert.ok(result.data.strokes[1].points[1].pressure > result.data.strokes[1].points[0].pressure);
    assert.equal(json(result.data), frozen);
  });
  console.log(JSON.stringify({ scenarios, result: 'PASS', source: 'actual CanvasSurface input/freeze methods + existing geometry/AuthResult',
    boundary: 'synthetic events; native rendering, hit testing, filesystem, network and real stylus not exercised' }));
}
freezeChecks().catch(error => { console.error(error); process.exitCode = 1; });
