import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../');
const require = createRequire(import.meta.url);
let ts;
try { ts = require(process.env.TOAST_TYPESCRIPT_PATH || 'typescript'); }
catch { ts = require('/Applications/DevEco-Studio.app/Contents/tools/arktsdoc/node_modules/typescript/lib/typescript.js'); }
const sourceDir = path.join(root, 'client/common/src/main/ets/components/feedback');
const source = readFileSync(path.join(sourceDir, 'ToastStore.ets'), 'utf8');
const temporary = mkdtempSync(path.join(tmpdir(), 'betterlearning-toast-check-'));
try {
  for (const name of ['ToastModels', 'ToastStore']) {
    writeFileSync(path.join(temporary, name + '.ts'), readFileSync(path.join(sourceDir, name + '.ets')));
  }
  const program = ts.createProgram([path.join(temporary, 'ToastStore.ts')], {
    target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, strict: true, noEmit: true
  });
  const diagnostics = ts.getPreEmitDiagnostics(program);
  assert.equal(diagnostics.length, 0, ts.formatDiagnosticsWithColorAndContext(diagnostics, {
    getCanonicalFileName: value => value, getCurrentDirectory: () => temporary, getNewLine: () => '\n'
  }));
} finally { rmSync(temporary, { recursive: true }); }
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText;

function harness() {
  let now = 0;
  let nextTimer = 0; // SDK 的第一个有效 timer ID 可以为 0。
  const tasks = new Map();
  const allCallbacks = [];
  let snapshot = [];
  const context = vm.createContext({
    exports: {}, Date: { now: () => now },
    setTimeout: (callback, delay) => {
      assert.ok(delay >= 1 && delay <= 2147483647, 'native timer delay must be in range');
      const id = nextTimer++;
      tasks.set(id, { at: now + delay, callback });
      allCallbacks.push(callback);
      return id;
    },
    clearTimeout: id => tasks.delete(id)
  });
  vm.runInContext(compiled, context);
  const store = new context.exports.ToastStore();
  const h = {
    store, tasks, allCallbacks,
    get snapshot() { return snapshot; },
    hostId: -1,
    attach() { this.hostId = store.attachHost(items => { snapshot = items; }); return this.hostId; },
    shown() { for (const item of snapshot) store.markDisplayed(this.hostId, item.id, item.revision); },
    advance(amount) {
      const until = now + amount;
      while (true) {
        const next = [...tasks.entries()].sort((a, b) => a[1].at - b[1].at)[0];
        if (!next || next[1].at > until) break;
        tasks.delete(next[0]); now = next[1].at; next[1].callback();
      }
      now = until;
    },
    keys() { return snapshot.map(item => item.key).join(','); },
    show(key, type = 'info', extra = {}) { store.show({ key, type, message: key, ...extra }); }
  };
  return h;
}
let passed = 0;
function check(name, test) { test(); passed++; console.log('PASS ' + name); }

check('无Host及未挂载条目不消耗时间，挂载确认幂等', () => {
  const h = harness(); h.show('a'); h.advance(10000); assert.equal(h.tasks.size, 0);
  h.attach(); h.advance(10000); assert.equal(h.keys(), 'a'); assert.equal(h.tasks.size, 0);
  h.shown(); h.advance(2000); h.shown(); h.advance(999); assert.equal(h.keys(), 'a');
  h.advance(1); assert.equal(h.keys(), '');
});
check('最多两条可见，FIFO，排队项显示后拥有完整时长', () => {
  const h = harness(); h.attach(); ['a', 'b', 'c', 'd'].forEach(key => h.show(key));
  assert.equal(h.keys(), 'a,b'); h.shown(); h.advance(3000); assert.equal(h.keys(), 'c,d');
  assert.equal(h.tasks.size, 0); h.advance(9000); assert.equal(h.keys(), 'c,d');
  h.shown(); h.advance(2999); assert.equal(h.keys(), 'c,d'); h.advance(1); assert.equal(h.keys(), '');
});
check('成功3秒，错误6秒，非法时长回退默认', () => {
  const h = harness(); h.attach(); h.show('a', 'success', { durationMs: 0 }); h.show('b', 'error', { durationMs: Infinity });
  h.shown(); h.advance(3000); assert.equal(h.keys(), 'b'); h.advance(2999); assert.equal(h.keys(), 'b');
  h.advance(1); assert.equal(h.keys(), '');
});
check('可见同key合并保留位置，更新内容后重新挂载并计完整时长', () => {
  const h = harness(); h.attach(); h.show('a'); h.show('b'); h.shown();
  const old = h.snapshot[0]; const oldTimeout = h.allCallbacks[0]; h.advance(2500);
  h.show('a', 'error', { message: '新的结果', description: '说明' });
  assert.equal(h.keys(), 'a,b'); assert.equal(h.snapshot[0].id, old.id);
  assert.equal(h.snapshot[0].revision, old.revision + 1); assert.equal(h.snapshot[0].message, '新的结果');
  h.store.markDisplayed(h.hostId, old.id, old.revision); oldTimeout(); h.advance(500);
  assert.equal(h.keys(), 'a'); assert.equal(h.tasks.size, 0); h.shown(); h.advance(5999);
  assert.equal(h.keys(), 'a'); h.advance(1); assert.equal(h.keys(), '');
});
check('排队同key更新保留FIFO位置，排队关闭不闪现', () => {
  const h = harness(); h.attach(); ['a', 'b', 'c', 'd'].forEach(key => h.show(key));
  h.show('c', 'error', { message: '更新的c' }); h.store.dismiss('d'); h.store.dismiss('a');
  assert.equal(h.keys(), 'b,c'); assert.equal(h.snapshot[1].message, '更新的c'); h.shown();
  h.advance(3000); assert.equal(h.keys(), 'c'); h.advance(3000); assert.equal(h.keys(), '');
});
check('timer ID 0可取消，重复关闭无副作用', () => {
  const h = harness(); h.attach(); h.show('a'); h.shown(); assert.ok(h.tasks.has(0));
  h.store.dismiss('a'); h.store.dismiss('a'); assert.equal(h.tasks.size, 0); assert.equal(h.keys(), '');
});
check('旧row关闭/挂载/timeout不能影响同key新实例', () => {
  const h = harness(); h.attach(); h.show('a'); h.shown(); const old = h.snapshot[0]; const callback = h.allCallbacks[0];
  h.store.dismiss('a'); h.show('a'); const fresh = h.snapshot[0]; assert.notEqual(old.id, fresh.id);
  h.store.dismissItem(old.id, old.revision); h.store.markDisplayed(h.hostId, old.id, old.revision); callback();
  assert.equal(h.keys(), 'a'); assert.equal(h.tasks.size, 0); h.shown(); h.advance(3000); assert.equal(h.keys(), '');
});
check('scope切换清显示/队列并拒绝迟到回调，A-B-A不复活旧scope', () => {
  const h = harness(); h.attach(); h.store.onScopeChange('A'); const scope = h.store.captureScope();
  ['a', 'b', 'c'].forEach(key => h.show(key)); h.shown(); const callbacks = [...h.allCallbacks];
  h.store.onScopeChange('A'); assert.equal(h.keys(), 'a,b');
  h.store.onScopeChange('B'); h.store.onScopeChange('A');
  h.store.show({ key: 'old', type: 'error', message: '迟到' }, scope); callbacks.forEach(callback => callback());
  assert.equal(h.keys(), ''); assert.equal(h.tasks.size, 0);
  h.show('new'); assert.equal(h.keys(), 'new'); const current = h.store.captureScope(); h.store.clearScope();
  assert.notEqual(current, h.store.captureScope());
});
check('Host卸载清理并解绑，旧Host不能清除新Host', () => {
  const h = harness(); const oldHost = h.attach(); h.show('a'); h.shown(); h.store.detachHost(oldHost);
  assert.equal(h.tasks.size, 0); h.show('b'); h.attach(); assert.equal(h.keys(), 'b');
  h.store.detachHost(oldHost); h.shown(); assert.equal(h.tasks.size, 1); h.advance(3000); assert.equal(h.keys(), '');
});
check('每Store状态隔离，第二Host不抢占，快照不可回写', () => {
  const a = harness(); const b = harness(); a.attach(); b.attach(); a.show('a'); b.show('b');
  assert.equal(a.store.attachHost(() => assert.fail('unexpected second Host')), -1);
  a.snapshot[0].message = '恶意修改'; a.show('c'); assert.equal(a.snapshot[0].message, 'a');
  a.store.clearScope(); assert.equal(b.keys(), 'b');
});
check('dispose取消显示/队列/回调，后续所有操作均无副作用', () => {
  const h = harness(); h.attach(); ['a', 'b', 'c'].forEach(key => h.show(key)); h.shown();
  const callbacks = [...h.allCallbacks]; h.store.dispose(); h.store.dispose(); h.show('d');
  h.store.clearScope(); h.store.onScopeChange('x'); h.store.dismiss('a'); callbacks.forEach(callback => callback());
  assert.equal(h.keys(), ''); assert.equal(h.tasks.size, 0); assert.equal(h.store.attachHost(() => {}), -1);
});
check('输入规范化和短/超长定时器边界', () => {
  const h = harness(); h.attach(); h.show(' '); h.show('invalid', 'info', { message: ' ' });
  assert.equal(h.keys(), ''); h.show(' a ', 'info', { message: ' 文案 ', description: '  ', durationMs: 0.1 });
  assert.equal(h.snapshot[0].key, 'a'); assert.equal(h.snapshot[0].message, '文案'); assert.equal(h.snapshot[0].description, '');
  h.shown(); h.advance(1); assert.equal(h.keys(), '');
  h.show('long', 'info', { durationMs: 2147483648 }); h.shown(); h.advance(2147483647); assert.equal(h.keys(), 'long');
  h.advance(1); assert.equal(h.keys(), '');
});
console.log(JSON.stringify({ suite: 'toast-store-v1', passed, plainTypeScriptStrict: true, nativeBuild: 'not verified' }));
