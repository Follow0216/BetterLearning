/** 仅同步不透明的会话世代；账号、令牌及私有正文不得进入跨标签消息。 */
export const SESSION_EPOCH_STORAGE_KEY = "bl.session.epoch";
export const SESSION_BROADCAST_CHANNEL = "bl-session";
export const SESSION_CHANGE_EVENT = "bl:session-changed";
export const LEGACY_SESSION_ENDED_EVENT = "bl:session-ended";

export type SessionChangeKind = "changed" | "ended";
export interface SessionChangeMessage {
  kind: SessionChangeKind;
  epoch: string;
}

const EPOCH = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function isSessionChangeMessage(value: unknown): value is SessionChangeMessage {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const message = value as Record<string, unknown>;
  return (
    (message.kind === "changed" || message.kind === "ended") &&
    typeof message.epoch === "string" &&
    EPOCH.test(message.epoch)
  );
}

export function readSessionEpoch(): string {
  if (typeof window === "undefined") return "";
  try {
    const value = window.localStorage.getItem(SESSION_EPOCH_STORAGE_KEY);
    return value && EPOCH.test(value) ? value : "";
  } catch {
    return "";
  }
}

export function ensureSessionEpoch(): string {
  if (typeof window === "undefined") return "";
  const existing = readSessionEpoch();
  if (existing) return existing;
  const epoch = crypto.randomUUID();
  try {
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, epoch);
    return epoch;
  } catch {
    return "";
  }
}

/** 登录成功、主动登出或鉴权失效后调用；同标签和跨标签均通知。 */
export function announceSessionChange(kind: SessionChangeKind): string {
  const message: SessionChangeMessage = { kind, epoch: crypto.randomUUID() };
  try {
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, message.epoch);
  } catch {
    // 存储禁用时仍通过同标签事件与 BroadcastChannel 清理已挂载私有内容。
  }
  window.dispatchEvent(
    new CustomEvent<SessionChangeMessage>(SESSION_CHANGE_EVENT, { detail: message }),
  );
  if (kind === "ended") window.dispatchEvent(new Event(LEGACY_SESSION_ENDED_EVENT));
  try {
    if (typeof BroadcastChannel !== "undefined") {
      const channel = new BroadcastChannel(SESSION_BROADCAST_CHANNEL);
      channel.postMessage(message);
      channel.close();
    }
  } catch {
    // BroadcastChannel 不可用时，focus/visibility/pageshow 会核对 localStorage 世代。
  }
  return message.epoch;
}

export function returnToAccount(): void {
  window.location.replace("/account");
}

/** 监听本标签、其他标签及恢复前漏掉的会话世代变化；每个新世代通知一次。 */
export function subscribeSessionChanges(onChange: () => void): () => void {
  if (typeof window === "undefined") return () => {};
  let lastEpoch = ensureSessionEpoch();
  let legacyNotifiedFor: string | null = null;
  const changed = (epoch: string) => {
    if (epoch === lastEpoch) return;
    lastEpoch = epoch;
    legacyNotifiedFor = null;
    onChange();
  };
  const local = (event: Event) => {
    const message = (event as CustomEvent<unknown>).detail;
    if (!isSessionChangeMessage(message)) return;
    changed(message.epoch);
    // announceSessionChange('ended') 紧接着发送旧事件；不重复触发。
    if (message.kind === "ended") legacyNotifiedFor = message.epoch;
  };
  const checkEpoch = () => {
    const current = readSessionEpoch();
    // 存储被禁用时读到空值，不能误判为换号；消息中的 epoch 仍可检测。
    if (current) changed(current);
  };
  const stored = (event: StorageEvent) => {
    if (event.key !== SESSION_EPOCH_STORAGE_KEY && event.key !== null) return;
    const current = readSessionEpoch();
    if (current) changed(current);
    else if (event.newValue === null) changed("");
  };
  const legacyEnded = () => {
    const current = readSessionEpoch();
    if (current && current !== lastEpoch) changed(current);
    if (legacyNotifiedFor === lastEpoch) return;
    legacyNotifiedFor = lastEpoch;
    onChange();
  };
  const visible = () => {
    if (document.visibilityState === "visible") checkEpoch();
  };
  let channel: BroadcastChannel | null = null;
  try {
    if (typeof BroadcastChannel !== "undefined") {
      channel = new BroadcastChannel(SESSION_BROADCAST_CHANNEL);
      channel.onmessage = (event: MessageEvent<unknown>) => {
        if (isSessionChangeMessage(event.data)) changed(event.data.epoch);
      };
    }
  } catch {
    // 存储世代与恢复事件仍可处理漏掉的跨标签广播。
  }
  window.addEventListener(SESSION_CHANGE_EVENT, local);
  window.addEventListener(LEGACY_SESSION_ENDED_EVENT, legacyEnded);
  window.addEventListener("storage", stored);
  window.addEventListener("focus", checkEpoch);
  document.addEventListener("visibilitychange", visible);
  window.addEventListener("pageshow", checkEpoch);
  return () => {
    channel?.close();
    window.removeEventListener(SESSION_CHANGE_EVENT, local);
    window.removeEventListener(LEGACY_SESSION_ENDED_EVENT, legacyEnded);
    window.removeEventListener("storage", stored);
    window.removeEventListener("focus", checkEpoch);
    document.removeEventListener("visibilitychange", visible);
    window.removeEventListener("pageshow", checkEpoch);
  };
}
