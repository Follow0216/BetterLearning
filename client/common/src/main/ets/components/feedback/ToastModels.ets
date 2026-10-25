/** 跨端语义与 docs/contracts/frontend-toast-v1.md 一致。 */
export type ToastType = 'info' | 'success' | 'error';

export interface ToastOptions {
  key: string;
  type: ToastType;
  message: string;
  description?: string;
  durationMs?: number;
}

/** Host 的不可回写快照；id 区分同 key 的不同生命周期，revision 区分合并版本。 */
export interface ToastItem {
  id: number;
  revision: number;
  key: string;
  type: ToastType;
  message: string;
  description: string;
  durationMs: number;
}
