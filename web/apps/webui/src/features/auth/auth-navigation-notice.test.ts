import assert from "node:assert/strict";
import { test } from "node:test";

import type { AuthNoticeStorage } from "./auth-navigation-notice";

// The native Node TypeScript loader needs an explicit extension; the app's
// bundler resolves extensionless imports. Keep both without changing tsconfig.
const {
  AUTH_NAVIGATION_NOTICE_KEY: key,
  AUTH_NAVIGATION_NOTICE_TTL_MS: ttl,
  discardAuthNavigationNotice: discard,
  rememberAuthNavigationNotice: remember,
  takeAuthNavigationNotice: take,
}: typeof import("./auth-navigation-notice") = await import(
  new URL("./auth-navigation-notice.ts", import.meta.url).href
);

const now = 50_000;
const login = { event: "login", accountId: "account-a" } as const;
const logout = { event: "logout" } as const;

function setup(raw: string | null = null) {
  let value = raw;
  const operations: string[] = [];
  const failures = { read: false, write: false, remove: false };
  const storage: AuthNoticeStorage = {
    getItem(requestedKey) {
      assert.equal(requestedKey, key);
      operations.push("read");
      if (failures.read) throw new Error("read unavailable");
      return value;
    },
    setItem(requestedKey, nextValue) {
      assert.equal(requestedKey, key);
      operations.push("write");
      if (failures.write) throw new Error("write unavailable");
      value = nextValue;
    },
    removeItem(requestedKey) {
      assert.equal(requestedKey, key);
      operations.push("remove");
      if (failures.remove) throw new Error("remove unavailable");
      value = null;
    },
  };
  return { storage, operations, failures, raw: () => value };
}

test("confirmed login writes only fixed event fields and delivers to the same account once", () => {
  const t = setup();
  remember(login, t.storage, now);
  assert.deepEqual(JSON.parse(t.raw()!), {
    version: 1,
    event: "login",
    accountId: "account-a",
    issuedAt: now,
  });
  assert.equal(take(login, t.storage, now), "login");
  assert.equal(take(login, t.storage, now), null);
  assert.equal(t.raw(), null);
});

test("logout is delivered only to the caller's confirmed anonymous destination", () => {
  const t = setup();
  remember(logout, t.storage, now);
  assert.deepEqual(JSON.parse(t.raw()!), { version: 1, event: "logout", issuedAt: now });
  assert.equal(take(logout, t.storage, now), "logout");
  assert.equal(take(logout, t.storage, now), null);
});

test("a different account discards rather than defers the login event", () => {
  const t = setup();
  remember(login, t.storage, now);
  assert.equal(take({ event: "login", accountId: "account-b" }, t.storage, now), null);
  assert.equal(take(login, t.storage, now), null);
});

test("the wrong destination event discards either kind", () => {
  const t = setup();
  remember(login, t.storage, now);
  assert.equal(take(logout, t.storage, now), null);
  assert.equal(take(login, t.storage, now), null);
  remember(logout, t.storage, now);
  assert.equal(take(login, t.storage, now), null);
  assert.equal(take(logout, t.storage, now), null);
});

test("TTL includes its boundary, rejects expiration and future times", () => {
  const t = setup();
  remember(login, t.storage, now);
  assert.equal(take(login, t.storage, now + ttl), "login");
  remember(login, t.storage, now);
  assert.equal(take(login, t.storage, now + ttl + 1), null);
  remember(login, t.storage, now);
  assert.equal(take(login, t.storage, now - 1), null);
});

test("malformed, extra, missing, unknown or incorrectly typed fields are removed", () => {
  const valid = { version: 1, event: "login", accountId: "account-a", issuedAt: now };
  const values: unknown[] = [
    null,
    [],
    "login",
    1,
    {},
    { ...valid, version: 2 },
    { ...valid, event: "register" },
    { ...valid, accountId: "" },
    { ...valid, accountId: 1 },
    { ...valid, issuedAt: "50000" },
    { ...valid, issuedAt: -1 },
    { ...valid, issuedAt: now + 0.5 },
    { ...valid, issuedAt: Infinity },
    { ...valid, issuedAt: Number.MAX_SAFE_INTEGER + 1 },
    { ...valid, message: "arbitrary message" },
    { ...valid, token: "forbidden" },
    { version: 1, event: "login", issuedAt: now },
    { version: 1, event: "logout", issuedAt: now, accountId: "account-a" },
  ];
  for (const value of values) {
    const t = setup(JSON.stringify(value));
    assert.equal(take(login, t.storage, now), null, JSON.stringify(value));
    assert.equal(t.raw(), null);
  }
  const t = setup("{broken-json");
  assert.equal(take(login, t.storage, now), null);
  assert.equal(t.raw(), null);
  const malformedLogout = setup(
    JSON.stringify({ version: 1, ...logout, issuedAt: now, accountId: "account-a" }),
  );
  assert.equal(take(logout, malformedLogout.storage, now), null);
  assert.equal(malformedLogout.raw(), null);
});

test("removal completes before a valid event can be delivered", () => {
  const t = setup(JSON.stringify({ version: 1, ...login, issuedAt: now }));
  assert.equal(take(login, t.storage, now), "login");
  assert.deepEqual(t.operations, ["read", "remove"]);
  assert.equal(t.raw(), null);
});

test("remove failure produces no event and overwrites the stale record when possible", () => {
  const t = setup(JSON.stringify({ version: 1, ...login, issuedAt: now }));
  t.failures.remove = true;
  assert.equal(take(login, t.storage, now), null);
  assert.equal(t.raw(), "");
  t.failures.remove = false;
  assert.equal(take(login, t.storage, now), null);
});

test("failed remove and overwrite cannot replay that raw record in the same document", () => {
  const t = setup(JSON.stringify({ version: 1, ...login, issuedAt: now }));
  t.failures.remove = true;
  t.failures.write = true;
  assert.equal(take(login, t.storage, now), null);
  t.failures.remove = false;
  t.failures.write = false;
  assert.equal(take(login, t.storage, now), null);
  assert.equal(t.raw(), null);
});

test("read failure is contained and discards the pending record", () => {
  const t = setup(JSON.stringify({ version: 1, ...logout, issuedAt: now }));
  t.failures.read = true;
  assert.equal(take(logout, t.storage, now), null);
  assert.equal(t.raw(), null);
});

test("write failure and unavailable storage never interrupt authentication callers", () => {
  const t = setup();
  t.failures.write = true;
  assert.doesNotThrow(() => remember(login, t.storage, now));
  assert.equal(take(login, t.storage, now), null);
  assert.doesNotThrow(() => remember(logout, null, now));
  assert.equal(take(logout, null, now), null);
  assert.equal(discard(null), false);
  assert.doesNotThrow(() => remember(logout));
  assert.equal(take(logout), null);
});

test("a browser sessionStorage getter failure is contained", () => {
  const previousWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: {
      get sessionStorage() {
        throw new Error("storage is disabled");
      },
    },
  });
  try {
    assert.doesNotThrow(() => remember(login));
    assert.equal(take(login), null);
    assert.equal(discard(), false);
  } finally {
    if (previousWindow) Object.defineProperty(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
  }
});

test("a new action can discard an event and replacement cannot retain the old account", () => {
  const t = setup();
  remember(login, t.storage, now);
  discard(t.storage);
  assert.equal(take(login, t.storage, now), null);
  remember(login, t.storage, now);
  remember({ event: "login", accountId: "account-b" }, t.storage, now + 1);
  assert.equal(take(login, t.storage, now + 1), null);
  assert.equal(t.raw(), null);
});

test("write whitelists fields and refuses invalid issuance times", () => {
  const t = setup();
  const enrichedLogin = { ...login, message: "must not persist" };
  remember(enrichedLogin, t.storage, now);
  assert.deepEqual(Object.keys(JSON.parse(t.raw()!)).sort(), [
    "accountId",
    "event",
    "issuedAt",
    "version",
  ]);
  for (const invalidNow of [NaN, Infinity, -1, 0.5]) {
    remember(login, t.storage, invalidNow);
    assert.equal(t.raw(), null);
  }
});
