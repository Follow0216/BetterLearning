export const MAX_NOTE_BYTES = 64 * 1024;

export function noteByteLength(value: string): number {
  return new TextEncoder().encode(value.replace(/\r\n/g, "\n")).length;
}

export const indexLabels: Record<string, string> = {
  INDEX_PENDING: "已保存 · 索引处理中",
  READY: "已保存 · 可检索",
  FAILED: "已保存 · 索引失败",
  WITHDRAWN: "已撤销检索",
};

export const sourceLabels: Record<string, string> = {
  QUESTION: "题目",
  SAVED_ANSWER: "用户历史作答",
  TUTOR_SNAPSHOT: "辅导冻结快照",
  TUTOR_REPLY: "历史辅导建议",
  NOTE: "个人笔记",
};

export const tutorStatusLabels: Record<string, string> = {
  WAITING_INPUT: "等待平板快照",
  QUEUED: "排队中",
  RUNNING: "辅导中",
  CANCELLING: "正在取消",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  TIMED_OUT: "已超时",
};

export function displayTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Shanghai",
  }).format(new Date(value));
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return "加载失败，请重试";
}

export function highlightPercent(
  block: { x: number; y: number; width: number; height: number },
  region: { x: number; y: number; width: number; height: number },
): { left: string; top: string; width: string; height: string } {
  const left = Math.max(block.x, region.x);
  const top = Math.max(block.y, region.y);
  const right = Math.min(block.x + block.width, region.x + region.width);
  const bottom = Math.min(block.y + block.height, region.y + region.height);
  if (right <= left || bottom <= top) throw new Error("引用区域不在原图内");
  return {
    left: `${((left - block.x) / block.width) * 100}%`,
    top: `${((top - block.y) / block.height) * 100}%`,
    width: `${((right - left) / block.width) * 100}%`,
    height: `${((bottom - top) / block.height) * 100}%`,
  };
}
