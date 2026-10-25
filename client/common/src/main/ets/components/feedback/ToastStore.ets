import { ToastItem, ToastOptions } from './ToastModels';

class ToastEntry {
  item: ToastItem;
  displayed: boolean = false;
  timer: number = -1;
  timerRevision: number = 0;
  remainingMs: number = 0;
  startedAt: number = 0;

  constructor(item: ToastItem) {
    this.item = item;
    this.remainingMs = item.durationMs;
  }
}

/**
 * 每窗口一个实例；只管理短暂显示，不引用业务状态、网络层或进程全局容器。
 * Host 确认条目挂载后才计时，所有旧回调同时校验 scope / id / revision。
 */
export class ToastStore {
  private visible: ToastEntry[] = [];
  private queued: ToastEntry[] = [];
  private listener: ((items: ToastItem[]) => void) | null = null;
  private hostId: number = 0;
  private nextId: number = 1;
  private scopeToken: number = 0;
  private scopeId: string = '';
  private disposed: boolean = false;

  show(options: ToastOptions, scopeToken: number = this.scopeToken): void {
    if (this.disposed || scopeToken !== this.scopeToken) {
      return;
    }
    const key: string = options.key.trim();
    const message: string = options.message.trim();
    if (key.length === 0 || message.length === 0) {
      return;
    }
    const fallback: number = options.type === 'error' ? 6000 : 3000;
    const duration: number = options.durationMs !== undefined &&
      Number.isFinite(options.durationMs) && options.durationMs > 0 ? Math.max(1, Math.ceil(options.durationMs)) : fallback;
    const description: string = options.description === undefined ? '' : options.description.trim();
    const existing: ToastEntry | null = this.findByKey(key);
    if (existing !== null) {
      this.cancelTimer(existing);
      existing.item = {
        id: existing.item.id, revision: existing.item.revision + 1, key: key,
        type: options.type, message: message, description: description, durationMs: duration
      };
      existing.remainingMs = duration;
      // 新内容会使用新的渲染 key；等待新 row 挂载，不提前消耗新时长。
      existing.displayed = false;
    } else {
      const item: ToastItem = {
        id: this.nextId++, revision: 0, key: key, type: options.type,
        message: message, description: description, durationMs: duration
      };
      this.queued.push(new ToastEntry(item));
    }
    this.promote();
    this.publish();
  }

  dismiss(key: string): void {
    if (this.disposed) {
      return;
    }
    const entry: ToastEntry | null = this.findByKey(key.trim());
    if (entry === null) {
      return;
    }
    this.remove(entry);
  }

  clearScope(): void {
    if (this.disposed) {
      return;
    }
    this.scopeToken++;
    this.cancelAll();
    this.visible = [];
    this.queued = [];
    this.publish();
  }

  onScopeChange(scopeId: string): void {
    if (this.disposed || scopeId === this.scopeId) {
      return;
    }
    this.scopeId = scopeId;
    this.clearScope();
  }

  captureScope(): number {
    return this.scopeToken;
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.clearScope();
    this.listener = null;
    this.disposed = true;
  }

  /** @internal 一个 Store 同时只绑定一个 Host；第二个 Host 不抢占原宿主。 */
  attachHost(listener: (items: ToastItem[]) => void): number {
    if (this.disposed || this.listener !== null) {
      return -1;
    }
    this.hostId++;
    this.listener = listener;
    this.promote();
    this.publish();
    return this.hostId;
  }

  /** @internal 先解绑再清理，避免向正在移除的组件回写状态。 */
  detachHost(hostId: number): void {
    if (hostId !== this.hostId || this.listener === null) {
      return;
    }
    this.listener = null;
    this.clearScope();
  }

  /** @internal 只认当前 Host 和当前内容版本，重复 onAppear 不重启计时。 */
  markDisplayed(hostId: number, id: number, revision: number): boolean {
    if (this.disposed || this.listener === null || hostId !== this.hostId) {
      return false;
    }
    const entry: ToastEntry | null = this.findVisible(id);
    if (entry === null || entry.item.revision !== revision || entry.displayed) {
      return false;
    }
    entry.displayed = true;
    this.startTimer(entry);
    return true;
  }

  /** @internal 关闭回调校验 row 身份，旧 row 不能关闭同 key 的后续条目。 */
  dismissItem(id: number, revision: number): void {
    if (this.disposed) {
      return;
    }
    const entry: ToastEntry | null = this.findVisible(id);
    if (entry !== null && entry.item.revision === revision) {
      this.remove(entry);
    }
  }

  private findByKey(key: string): ToastEntry | null {
    for (let index: number = 0; index < this.visible.length; index++) {
      if (this.visible[index].item.key === key) {
        return this.visible[index];
      }
    }
    for (let index: number = 0; index < this.queued.length; index++) {
      if (this.queued[index].item.key === key) {
        return this.queued[index];
      }
    }
    return null;
  }

  private findVisible(id: number): ToastEntry | null {
    for (let index: number = 0; index < this.visible.length; index++) {
      if (this.visible[index].item.id === id) {
        return this.visible[index];
      }
    }
    return null;
  }

  private promote(): void {
    if (this.listener === null) {
      return;
    }
    while (this.visible.length < 2 && this.queued.length > 0) {
      const entry: ToastEntry | undefined = this.queued.shift();
      if (entry !== undefined) {
        this.visible.push(entry);
      }
    }
  }

  private remove(entry: ToastEntry): void {
    this.cancelTimer(entry);
    this.visible = this.visible.filter((value: ToastEntry) => value.item.id !== entry.item.id);
    this.queued = this.queued.filter((value: ToastEntry) => value.item.id !== entry.item.id);
    this.promote();
    this.publish();
  }

  private startTimer(entry: ToastEntry): void {
    const scope: number = this.scopeToken;
    const id: number = entry.item.id;
    const revision: number = entry.item.revision;
    const timerRevision: number = ++entry.timerRevision;
    entry.startedAt = Date.now();
    // 避免超长正数超出平台定时器范围后立即触发；分段等待仍遵守指定总时长。
    entry.timer = setTimeout(() => {
      const current: ToastEntry | null = this.findVisible(id);
      if (this.disposed || scope !== this.scopeToken || current !== entry ||
        current.item.revision !== revision || current.timerRevision !== timerRevision) {
        return;
      }
      current.timer = -1;
      const elapsed: number = Math.max(0, Date.now() - current.startedAt);
      current.remainingMs = Math.max(0, current.remainingMs - elapsed);
      if (current.remainingMs > 0) {
        this.startTimer(current);
      } else {
        this.remove(current);
      }
    }, Math.min(entry.remainingMs, 2147483647));
  }

  private cancelTimer(entry: ToastEntry): void {
    entry.timerRevision++;
    if (entry.timer !== -1) {
      clearTimeout(entry.timer);
      entry.timer = -1;
    }
  }

  private cancelAll(): void {
    this.visible.forEach((entry: ToastEntry) => this.cancelTimer(entry));
    this.queued.forEach((entry: ToastEntry) => this.cancelTimer(entry));
  }

  private publish(): void {
    if (this.listener !== null) {
      // 不泄露内部 item 引用，订阅者不能改写 Store 状态。
      const items: ToastItem[] = this.visible.map((entry: ToastEntry): ToastItem => {
        return {
          id: entry.item.id, revision: entry.item.revision, key: entry.item.key,
          type: entry.item.type, message: entry.item.message, description: entry.item.description,
          durationMs: entry.item.durationMs
        };
      });
      this.listener(items);
    }
  }
}
