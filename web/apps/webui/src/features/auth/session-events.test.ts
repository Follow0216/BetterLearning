import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  announceSessionChange,
  ensureSessionEpoch,
  isSessionChangeMessage,
  readSessionEpoch,
  SESSION_CHANGE_EVENT,
  SESSION_EPOCH_STORAGE_KEY,
  subscribeSessionChanges,
} from "./session-events";

const first = "11111111-1111-4111-8111-111111111111";
const second = "22222222-2222-4222-8222-222222222222";
const third = "33333333-3333-4333-8333-333333333333";

class FakeBroadcastChannel {
  static sent: unknown[] = [];
  static instances: FakeBroadcastChannel[] = [];
  closed = false;
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  constructor(readonly name: string) {
    FakeBroadcastChannel.instances.push(this);
  }
  postMessage(value: unknown) {
    FakeBroadcastChannel.sent.push(value);
  }
  close() {
    this.closed = true;
  }
  static emit(value: unknown) {
    for (const instance of FakeBroadcastChannel.instances) {
      if (!instance.closed) instance.onmessage?.({ data: value } as MessageEvent<unknown>);
    }
  }
}

beforeEach(() => {
  window.localStorage.clear();
  FakeBroadcastChannel.sent = [];
  FakeBroadcastChannel.instances = [];
  vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
  vi.stubGlobal("crypto", {
    randomUUID: vi
      .fn()
      .mockReturnValueOnce(first)
      .mockReturnValueOnce(second)
      .mockReturnValueOnce(third),
  });
});
afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
});

describe("session epoch", () => {
  it("creates only an opaque epoch and broadcasts only kind and epoch", () => {
    expect(ensureSessionEpoch()).toBe(first);
    const events: unknown[] = [];
    const receive = (event: Event) => events.push((event as CustomEvent<unknown>).detail);
    window.addEventListener(SESSION_CHANGE_EVENT, receive);
    try {
      expect(announceSessionChange("changed")).toBe(second);
    } finally {
      window.removeEventListener(SESSION_CHANGE_EVENT, receive);
    }
    expect(readSessionEpoch()).toBe(second);
    expect(window.localStorage.length).toBe(1);
    expect(window.localStorage.getItem(SESSION_EPOCH_STORAGE_KEY)).toBe(second);
    expect(events).toEqual([{ kind: "changed", epoch: second }]);
    expect(FakeBroadcastChannel.sent).toEqual([{ kind: "changed", epoch: second }]);
    expect(isSessionChangeMessage(FakeBroadcastChannel.sent[0])).toBe(true);
  });

  it("rejects malformed messages and detects a missed broadcast on focus", () => {
    expect(isSessionChangeMessage({ kind: "changed", epoch: "account:alice" })).toBe(false);
    expect(isSessionChangeMessage({ kind: "login", epoch: first })).toBe(false);
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, first);
    const changed = vi.fn();
    const unsubscribe = subscribeSessionChanges(changed);
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, second);
    window.dispatchEvent(new Event("focus"));
    window.dispatchEvent(new Event("focus"));
    expect(changed).toHaveBeenCalledOnce();
    unsubscribe();
  });

  it("continues notifying for A→B→C while deduplicating repeated channel, storage and legacy events", () => {
    expect(ensureSessionEpoch()).toBe(first);
    const changed = vi.fn();
    const unsubscribe = subscribeSessionChanges(changed);
    expect(announceSessionChange("changed")).toBe(second);
    FakeBroadcastChannel.emit({ kind: "changed", epoch: second });
    window.dispatchEvent(
      new StorageEvent("storage", { key: SESSION_EPOCH_STORAGE_KEY, newValue: second }),
    );
    expect(changed).toHaveBeenCalledTimes(1);

    expect(announceSessionChange("ended")).toBe(third);
    FakeBroadcastChannel.emit({ kind: "ended", epoch: third });
    window.dispatchEvent(
      new StorageEvent("storage", { key: SESSION_EPOCH_STORAGE_KEY, newValue: third }),
    );
    window.dispatchEvent(new Event("focus"));
    expect(changed).toHaveBeenCalledTimes(2);
    unsubscribe();
  });

  it("returns an empty epoch for invalid storage", () => {
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, "not-an-epoch");
    expect(readSessionEpoch()).toBe("");
  });
});
