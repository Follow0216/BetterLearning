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
const methods = new Set(['touch', 'cancel_gesture', 'discard_edit', 'control_touch', 'point', 'append_point',
  'erase_path', 'erase', 'commit', 'undo', 'update_undo', 'read_only_changed']);
const fields = new Set(['tool', 'readOnly', 'document', 'undoEnabled', 'pointers', 'pointerSources', 'editPointerId',
  'navigationKey', 'last', 'pinchView', 'pinchMid', 'pinchDistance', 'suppress', 'liveStroke', 'eraseBefore',
  'erased', 'undoHistory', 'pointBudget', 'view']);
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
const geometryNames = new Set(['world_point', 'zoom_canvas', 'valid_world_number', 'segment_distance', 'stroke_hit']);
const geometryCode = geometry.statements.filter(s => ts.isFunctionDeclaration(s) && geometryNames.has(s.name.text))
  .map(s => s.getText(geometry).replace(/^export\s+/, '')).join('\n');
const modelPath = path.join(root, 'client/common/src/main/ets/features/canvas/CanvasModels.ets');
const model = parse(modelPath, fs.readFileSync(modelPath, 'utf8'));
const canvasTool = model.statements.find(s => s.name?.text === 'CanvasTool').getText(model).replace(/^export\s+/, '');
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
const code = enumCode.join('\n') + '\n' + canvasTool + '\n' + geometryCode + `
class InputHarness {
  ${extracted.join('\n')}
  constructor() {
    this.view = { x: 0, y: 0, scale: 1 };
    this.document = { schemaVersion: 1, questionLayout: { bounds: { x: 0, y: 0, width: 720, height: 1024 } },
      strokes: [{ strokeId: 'existing', color: '#152744', width: 2.5, points: [{ x: 10, y: 10 }, { x: 30, y: 30 }] }] };
    this.changes = []; this.savedViews = []; this.failures = [];
    this.onDocumentChange = doc => { this.document = doc; this.changes.push(JSON.stringify(doc)); };
    this.onViewChange = view => this.savedViews.push(JSON.stringify(view));
    this.onUndoChange = () => {};
    this.onFailure = message => this.failures.push(message);
  }
  paint() {}
  change_view(view, persist) { this.view = view; if (persist) this.onViewChange(view); }
}
globalThis.InputHarness = InputHarness;
globalThis.TouchType = TouchType;
globalThis.SourceTool = SourceTool;
globalThis.SourceType = SourceType;
globalThis.CanvasTool = CanvasTool;
`;
const transpiled = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2020 }, reportDiagnostics: true });
assert.equal(transpiled.diagnostics.length, 0);
let nextId = 0;
const sandbox = { util: { generateRandomUUID: () => 'synthetic-' + (++nextId) } };
vm.createContext(sandbox);
vm.runInContext(transpiled.outputText, sandbox, { filename: 'actual-canvas-input-harness.js' });
const { InputHarness, SourceTool: Source, SourceType: Device, TouchType: Phase, CanvasTool: Tool } = sandbox;
const pt = (id, x, y) => ({ id, x, y });
const json = value => JSON.stringify(value);
function event(h, phase, source, changed, touches = changed, device = Device.TouchScreen) {
  let stopped = false;
  h.touch({ type: phase, source: device, sourceTool: source, changedTouches: changed, touches,
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
  assert.equal(json(h.document.strokes[1].points[0]), json({ x: -45, y: -15 }));
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
console.log(JSON.stringify({ scenarios, result: 'PASS', source: 'actual CanvasSurface input methods + existing geometry',
  boundary: 'synthetic events; native rendering, hit testing, filesystem, network and real stylus not exercised' }));
