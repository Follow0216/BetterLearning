import assert from "node:assert/strict";
import { test } from "node:test";

import { ToastStore } from "./toast-store.ts";

function setup() {
  let now = 0;
  let next = 0;
  const timers = new Map<number, { due: number; callback: () => void }>();
  const store = new ToastStore({
    now: () => now,
    setTimeout(callback, delay) {
      const id = ++next;
      timers.set(id, { due: now + delay, callback });
      return id;
    },
    clearTimeout(id) {
      timers.delete(id as number);
    },
  });
  const show = (key: string, type: "info" | "success" | "error" = "info", durationMs?: number) =>
    store.show({ key, type, message: key, durationMs });
  const present = () =>
    store.getSnapshot().forEach((item) => store.present(item.key, item.revision));
  const advance = (time: number) => {
    const end = now + time;
    while (true) {
      const candidate = [...timers.entries()].sort((a, b) => a[1].due - b[1].due)[0];
      if (!candidate || candidate[1].due > end) break;
      now = candidate[1].due;
      timers.delete(candidate[0]);
      candidate[1].callback();
    }
    now = end;
  };
  const keys = () => store.getSnapshot().map((item) => item.key);
  return { store, timers, show, present, advance, keys };
}

test("host/DOM presentation gates timers; FIFO waits its full duration", () => {
  const t = setup();
  t.show("a");
  t.show("b", "error");
  t.show("c");
  t.advance(9000);
  assert.equal(t.timers.size, 0);
  t.store.attachHost();
  assert.deepEqual(t.keys(), ["a", "b"]);
  t.advance(9000);
  assert.deepEqual(t.keys(), ["a", "b"]);
  t.present();
  t.advance(3000);
  assert.deepEqual(t.keys(), ["b", "c"]);
  t.advance(3000);
  assert.deepEqual(t.keys(), ["c"]);
  t.present();
  t.advance(2999);
  assert.deepEqual(t.keys(), ["c"]);
  t.advance(1);
  assert.deepEqual(t.keys(), []);
});

test("duplicate keys stay in place, refresh visible duration and retain queued order", () => {
  const t = setup();
  t.store.attachHost();
  t.show("a");
  t.show("b");
  t.show("c");
  t.show("d");
  t.present();
  t.advance(2000);
  t.store.show({ key: "a", type: "error", message: "updated" });
  t.store.show({ key: "c", type: "success", message: "queued update" });
  assert.deepEqual(t.keys(), ["a", "b"]);
  t.present();
  t.advance(1000);
  assert.deepEqual(t.keys(), ["a", "c"]);
  assert.equal(t.store.getSnapshot()[1].message, "queued update");
  t.present();
  t.advance(3000);
  assert.deepEqual(t.keys(), ["a", "d"]);
  t.advance(2000);
  assert.deepEqual(t.keys(), ["d"]);
});

test("hover and focus overlap; resume uses remaining duration", () => {
  const t = setup();
  t.store.attachHost();
  t.show("a");
  t.present();
  t.advance(1000);
  t.store.setPaused("a", "hover", true);
  t.store.setPaused("a", "focus", true);
  t.advance(9000);
  t.store.setPaused("a", "hover", false);
  t.advance(9000);
  assert.deepEqual(t.keys(), ["a"]);
  t.store.setPaused("a", "focus", false);
  t.advance(1999);
  assert.deepEqual(t.keys(), ["a"]);
  t.advance(1);
  assert.deepEqual(t.keys(), []);
});

test("manual dismissal works for visible and queued entries and is idempotent", () => {
  const t = setup();
  t.store.attachHost();
  t.show("a");
  t.show("b");
  t.show("c");
  t.show("d");
  t.present();
  t.store.dismiss("c");
  t.store.dismiss("a");
  t.store.dismiss("a");
  assert.deepEqual(t.keys(), ["b", "d"]);
  t.show("a");
  t.store.dismiss("b");
  assert.deepEqual(t.keys(), ["d", "a"]);
});

test("scope identity rejects late results and repeated identity is a no-op", () => {
  const t = setup();
  t.store.attachHost();
  t.store.onScopeChange("route:1");
  const token = t.store.captureScope();
  t.show("a");
  t.show("b");
  t.show("c");
  t.present();
  t.store.onScopeChange("route:1");
  assert.equal(t.store.captureScope(), token);
  assert.deepEqual(t.keys(), ["a", "b"]);
  t.store.onScopeChange("route:2");
  assert.equal(t.timers.size, 0);
  t.store.show({ key: "late", type: "error", message: "late" }, token);
  assert.deepEqual(t.keys(), []);
  t.store.clearScope();
  assert.equal(t.store.captureScope(), token + 2);
});

test("invalid input ignored; trimmed keys merge and invalid durations use defaults", () => {
  const t = setup();
  t.store.attachHost();
  t.store.show({ key: " ", type: "info", message: "empty" });
  t.store.show({ key: "x", type: "info", message: " " });
  assert.deepEqual(t.keys(), []);
  t.store.show({
    key: " x ",
    type: "error",
    message: " error ",
    description: " ",
    durationMs: NaN,
  });
  assert.equal(t.store.getSnapshot()[0].durationMs, 6000);
  assert.equal(t.store.getSnapshot()[0].description, undefined);
  t.show("x", "success", -1);
  assert.equal(t.store.getSnapshot()[0].durationMs, 3000);
  t.present();
  t.advance(3000);
  assert.deepEqual(t.keys(), []);
});

test("detach cancels timers and invalidates scope; StrictMode can reattach", () => {
  const t = setup();
  const detach = t.store.attachHost();
  const token = t.store.captureScope();
  t.show("a");
  t.present();
  detach();
  assert.equal(t.timers.size, 0);
  t.store.attachHost();
  t.store.show({ key: "late", type: "info", message: "late" }, token);
  assert.deepEqual(t.keys(), []);
  t.show("new");
  t.present();
  assert.equal(t.timers.size, 1);
  t.store.dispose();
  t.show("ignored");
  t.advance(9000);
  assert.deepEqual(t.keys(), []);
  assert.equal(t.timers.size, 0);
});

test("stale timer callback cannot dismiss a replaced key", () => {
  const t = setup();
  t.store.attachHost();
  t.show("a");
  t.present();
  const stale = [...t.timers.values()][0].callback;
  t.show("a", "error");
  t.present();
  stale();
  assert.deepEqual(t.keys(), ["a"]);
  t.advance(6000);
  assert.deepEqual(t.keys(), []);
});
