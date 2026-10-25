export const AUTH_NAVIGATION_NOTICE_KEY = "betterlearning:auth-navigation-notice";
export const AUTH_NAVIGATION_NOTICE_TTL_MS = 30_000;

export interface AuthNoticeStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type AuthNavigationTarget = { event: "login"; accountId: string } | { event: "logout" };

// If removal fails, the same document must not retry that record after remount.
const rejectedRecords = new WeakMap<AuthNoticeStorage, string>();

function browserStorage(): AuthNoticeStorage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** Best effort invalidation also handles storage implementations that reject removal. */
export function discardAuthNavigationNotice(
  storage: AuthNoticeStorage | null = browserStorage(),
): boolean {
  if (storage === null) return false;
  try {
    storage.removeItem(AUTH_NAVIGATION_NOTICE_KEY);
    return true;
  } catch {
    try {
      storage.setItem(AUTH_NAVIGATION_NOTICE_KEY, "");
    } catch {
      // Storage failure must never block an authentication action.
    }
    return false;
  }
}

/** Only an already confirmed auth result may create a fixed navigation event. */
export function rememberAuthNavigationNotice(
  target: AuthNavigationTarget,
  storage: AuthNoticeStorage | null = browserStorage(),
  now = Date.now(),
): void {
  if (storage === null || !discardAuthNavigationNotice(storage)) return;
  if (
    !Number.isSafeInteger(now) ||
    now < 0 ||
    (target.event !== "login" && target.event !== "logout") ||
    (target.event === "login" &&
      (typeof target.accountId !== "string" || target.accountId.length === 0))
  )
    return;
  const record =
    target.event === "login"
      ? { version: 1, event: "login", accountId: target.accountId, issuedAt: now }
      : { version: 1, event: "logout", issuedAt: now };
  try {
    storage.setItem(AUTH_NAVIGATION_NOTICE_KEY, JSON.stringify(record));
  } catch {
    discardAuthNavigationNotice(storage);
  }
}

/** Remove before validating or returning; a failed removal must never produce a toast. */
export function takeAuthNavigationNotice(
  target: AuthNavigationTarget,
  storage: AuthNoticeStorage | null = browserStorage(),
  now = Date.now(),
): "login" | "logout" | null {
  if (storage === null) return null;
  let raw: string | null;
  try {
    raw = storage.getItem(AUTH_NAVIGATION_NOTICE_KEY);
  } catch {
    discardAuthNavigationNotice(storage);
    return null;
  }
  if (raw === null) return null;
  try {
    storage.removeItem(AUTH_NAVIGATION_NOTICE_KEY);
  } catch {
    rejectedRecords.set(storage, raw);
    discardAuthNavigationNotice(storage);
    return null;
  }
  if (rejectedRecords.get(storage) === raw) return null;
  try {
    const record: unknown = JSON.parse(raw);
    if (typeof record !== "object" || record === null || Array.isArray(record)) return null;
    const value = record as Record<string, unknown>;
    const fields =
      value.event === "login"
        ? ["version", "event", "issuedAt", "accountId"]
        : ["version", "event", "issuedAt"];
    if (
      Object.keys(value).length !== fields.length ||
      !fields.every((field) => Object.hasOwn(value, field)) ||
      value.version !== 1 ||
      !Number.isSafeInteger(value.issuedAt) ||
      typeof value.issuedAt !== "number" ||
      !Number.isSafeInteger(now) ||
      value.issuedAt < 0 ||
      now < value.issuedAt ||
      now - value.issuedAt > AUTH_NAVIGATION_NOTICE_TTL_MS ||
      value.event !== target.event
    )
      return null;
    if (value.event === "logout") return "logout";
    return typeof value.accountId === "string" &&
      value.accountId.length > 0 &&
      target.event === "login" &&
      value.accountId === target.accountId
      ? "login"
      : null;
  } catch {
    return null;
  }
}
