export type ToastType = "info" | "success" | "error";

export interface ToastOptions {
  key: string;
  type: ToastType;
  message: string;
  description?: string;
  durationMs?: number;
}

export interface ToastItem extends ToastOptions {
  durationMs: number;
  revision: number;
}

export interface ToastApi {
  show(options: ToastOptions, scopeToken?: number): void;
  dismiss(key: string): void;
  clearScope(): void;
  onScopeChange(scopeId: string): void;
  captureScope(): number;
}

interface Clock {
  now(): number;
  setTimeout(callback: () => void, delay: number): unknown;
  clearTimeout(timer: unknown): void;
}

interface Entry {
  item: ToastItem;
  remaining: number;
  startedAt: number | null;
  timer: unknown;
  presented: boolean;
  hover: boolean;
  focus: boolean;
}

const EMPTY: readonly ToastItem[] = [];
const clock: Clock = {
  now: () => performance.now(),
  setTimeout: (callback, delay) => setTimeout(callback, delay),
  clearTimeout: (timer) => clearTimeout(timer as ReturnType<typeof setTimeout>),
};

/** One instance per Provider; constructing this store never starts a timer. */
export class ToastStore implements ToastApi {
  private entries: Entry[] = [];
  private listeners = new Set<() => void>();
  private snapshot: readonly ToastItem[] = EMPTY;
  private generation = 0;
  private revision = 0;
  private scopeId: string | undefined;
  private mounted = false;
  private disposed = false;
  private clock: Clock;

  constructor(timerClock: Clock = clock) {
    this.clock = timerClock;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): readonly ToastItem[] => this.snapshot;
  getServerSnapshot = (): readonly ToastItem[] => EMPTY;
  captureScope = (): number => this.generation;

  show = (options: ToastOptions, scopeToken?: number): void => {
    if (this.disposed || (scopeToken !== undefined && scopeToken !== this.generation)) return;
    const key = options.key.trim();
    const message = options.message.trim();
    if (!key || !message) return;
    const durationMs =
      Number.isFinite(options.durationMs) && (options.durationMs ?? 0) > 0
        ? Math.ceil(options.durationMs!)
        : options.type === "error"
          ? 6000
          : 3000;
    const item: ToastItem = {
      key,
      type: options.type,
      message,
      description: options.description?.trim() || undefined,
      durationMs,
      revision: ++this.revision,
    };
    const existing = this.entries.find((entry) => entry.item.key === key);
    if (existing) {
      this.stop(existing);
      existing.item = item;
      existing.remaining = durationMs;
      existing.presented = false;
    } else {
      this.entries.push({
        item,
        remaining: durationMs,
        startedAt: null,
        timer: undefined,
        presented: false,
        hover: false,
        focus: false,
      });
    }
    this.publish();
  };

  dismiss = (key: string): void => {
    if (this.disposed) return;
    const entry = this.entries.find((candidate) => candidate.item.key === key);
    if (!entry) return;
    this.stop(entry);
    this.entries = this.entries.filter((candidate) => candidate !== entry);
    this.publish();
  };

  clearScope = (): void => {
    if (this.disposed) return;
    this.entries.forEach((entry) => this.stop(entry));
    this.entries = [];
    this.generation++;
    this.publish();
  };

  onScopeChange = (scopeId: string): void => {
    if (this.disposed || this.scopeId === scopeId) return;
    this.scopeId = scopeId;
    this.clearScope();
  };

  attachHost = (): (() => void) => {
    if (this.disposed || this.mounted) return () => {};
    this.mounted = true;
    this.publish();
    return () => {
      this.mounted = false;
      this.clearScope();
    };
  };

  /** Called after the card commits to the DOM, so queued items never lose time. */
  present = (key: string, revision: number): void => {
    const entry = this.entries.slice(0, 2).find((candidate) => candidate.item.key === key);
    if (!this.mounted || !entry || entry.item.revision !== revision || entry.presented) return;
    entry.presented = true;
    this.start(entry);
  };

  setPaused = (key: string, reason: "hover" | "focus", paused: boolean): void => {
    const entry = this.entries.find((candidate) => candidate.item.key === key);
    if (!entry || entry[reason] === paused) return;
    entry[reason] = paused;
    if (entry.hover || entry.focus) this.stop(entry);
    else this.start(entry);
  };

  dispose = (): void => {
    if (this.disposed) return;
    this.clearScope();
    this.disposed = true;
    this.mounted = false;
    this.listeners.clear();
  };

  private publish(): void {
    this.snapshot = this.mounted ? this.entries.slice(0, 2).map((entry) => entry.item) : EMPTY;
    this.listeners.forEach((listener) => listener());
  }

  private stop(entry: Entry): void {
    if (entry.startedAt !== null) {
      entry.remaining = Math.max(0, entry.remaining - (this.clock.now() - entry.startedAt));
    }
    if (entry.timer !== undefined) this.clock.clearTimeout(entry.timer);
    entry.startedAt = null;
    entry.timer = undefined;
  }

  private start(entry: Entry): void {
    if (
      !this.mounted ||
      !entry.presented ||
      entry.hover ||
      entry.focus ||
      entry.startedAt !== null ||
      !this.entries.slice(0, 2).includes(entry)
    )
      return;
    entry.startedAt = this.clock.now();
    const revision = entry.item.revision;
    entry.timer = this.clock.setTimeout(
      () => {
        if (this.disposed || !this.entries.includes(entry) || entry.item.revision !== revision)
          return;
        this.stop(entry);
        if (entry.remaining <= 0) this.dismiss(entry.item.key);
        else this.start(entry);
      },
      Math.min(entry.remaining, 2_147_483_647),
    );
  }
}
