import { parseNoteContextWrite, type NoteContextWrite } from "@/lib/api/learning-v2-contract";

export interface NoteDraft {
  draftId: string;
  noteId: string | null;
  title: string;
  body: string;
  context: NoteContextWrite | null;
  baseVersion: number | null;
  baseEtag: string | null;
  createRequest: { key: string; payload: string } | null;
  createUncertain: boolean;
  revision: number;
  updatedAt: string;
}
export type DraftResult<T> = { value: T; error: string };
const PREFIX = "bl.notes.draft.v1:";
const id = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function prefix(accountId: string) {
  if (!/^[1-9][0-9]*$/.test(accountId)) throw new Error("账号尚未确认");
  return `${PREFIX}${accountId}:`;
}
function parse(value: string | null): NoteDraft | null {
  if (!value) return null;
  try {
    const d = JSON.parse(value) as NoteDraft;
    if (d.context !== null) d.context = parseNoteContextWrite(d.context);
    return d &&
      id.test(d.draftId) &&
      (d.noteId === null || id.test(d.noteId)) &&
      typeof d.title === "string" &&
      typeof d.body === "string" &&
      Number.isInteger(d.revision) &&
      d.revision > 0 &&
      typeof d.updatedAt === "string" &&
      !Number.isNaN(Date.parse(d.updatedAt)) &&
      typeof d.createUncertain === "boolean" &&
      (d.baseVersion === null || (Number.isInteger(d.baseVersion) && d.baseVersion > 0)) &&
      (d.baseEtag === null || typeof d.baseEtag === "string") &&
      (d.createRequest === null ||
        (id.test(d.createRequest.key) && typeof d.createRequest.payload === "string"))
      ? d
      : null;
  } catch {
    return null;
  }
}
export function readNoteDrafts(accountId: string): DraftResult<NoteDraft[]> {
  try {
    const own = prefix(accountId),
      drafts: NoteDraft[] = [];
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index);
      if (!key?.startsWith(own)) continue;
      const draft = parse(localStorage.getItem(key));
      if (draft) drafts.push(draft);
    }
    return { value: drafts.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)), error: "" };
  } catch {
    return { value: [], error: "无法读取本机草稿，请保留当前页面并手动保存到云端。" };
  }
}
export function storeNoteDraft(accountId: string, draft: NoteDraft): string {
  try {
    if (!id.test(draft.draftId)) throw new Error("草稿编号无效");
    localStorage.setItem(`${prefix(accountId)}${draft.draftId}`, JSON.stringify(draft));
    return "";
  } catch {
    return "本机草稿保存失败（存储空间不足或浏览器禁止存储），请保留页面并保存到云端。";
  }
}
function contextSnapshot(context: NoteContextWrite | null) {
  return context === null
    ? null
    : {
        notebookId: context.notebookId,
        questionId: context.questionId,
        excerpt:
          context.excerpt === null
            ? null
            : {
                resultId: context.excerpt.resultId,
                turnId: context.excerpt.turnId,
                text: context.excerpt.text,
              },
      };
}

export function sameNoteContext(
  left: NoteContextWrite | null,
  right: NoteContextWrite | null,
): boolean {
  return JSON.stringify(contextSnapshot(left)) === JSON.stringify(contextSnapshot(right));
}

/** 强 ETag 由 noteId + version 确定；重新取得同版读取凭据不产生用户编辑。 */
export function sameNoteDraftSnapshot(left: NoteDraft, right: NoteDraft): boolean {
  const snapshot = (draft: NoteDraft) =>
    JSON.stringify({
      noteId: draft.noteId,
      title: draft.title,
      body: draft.body,
      context: contextSnapshot(draft.context),
      baseVersion: draft.baseVersion,
      createRequest:
        draft.createRequest === null
          ? null
          : { key: draft.createRequest.key, payload: draft.createRequest.payload },
      createUncertain: draft.createUncertain,
    });
  return snapshot(left) === snapshot(right);
}

/** 只检查原账号原快照，不去重或删除其他标签的独立草稿。 */
export function isNoteDraftSnapshotProtected(
  accountId: string,
  original: NoteDraft,
  next: NoteDraft,
): boolean {
  if (!sameNoteDraftSnapshot(original, next)) return false;
  try {
    const stored = parse(localStorage.getItem(`${prefix(accountId)}${original.draftId}`));
    return stored !== null && sameNoteDraftSnapshot(stored, next);
  } catch {
    return false;
  }
}

/** 只移除请求开始时的那一版；其他标签的新稿或请求飞行中的后续输入仍保留。 */
export function removeNoteDraft(
  accountId: string,
  draftId: string,
  expectedRevision: number,
): string {
  try {
    const key = `${prefix(accountId)}${draftId}`;
    const current = parse(localStorage.getItem(key));
    if (current?.revision === expectedRevision) localStorage.removeItem(key);
    return "";
  } catch {
    return "无法清理本机草稿；内容仍保留在此浏览器中。";
  }
}
export function createNoteDraft(context: NoteContextWrite | null = null): NoteDraft {
  return {
    draftId: crypto.randomUUID(),
    noteId: null,
    title: "",
    body: "",
    context,
    baseVersion: null,
    baseEtag: null,
    createRequest: null,
    createUncertain: false,
    revision: 0,
    updatedAt: new Date().toISOString(),
  };
}
