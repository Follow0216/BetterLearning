import { INBOX_COLLECTION_ID, isValidQuestionId } from "@/lib/api/library-contract";

export const LIBRARY_PAGE_SIZE = 20;
export const MAX_PAGE = 1_000_000;

export type LibraryView =
  { kind: "all" } | { kind: "inbox" } | { kind: "collection"; collectionId: string };

export const ALL_VIEW: LibraryView = { kind: "all" };
export const INBOX_VIEW: LibraryView = { kind: "inbox" };

export type SearchParamsInput = Record<string, string | string[] | undefined>;

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export function parseLibraryView(searchParams: SearchParamsInput): LibraryView {
  if (firstValue(searchParams.view) === "inbox") {
    return INBOX_VIEW;
  }
  const collectionId = firstValue(searchParams.collection);
  if (collectionId === INBOX_COLLECTION_ID) {
    return INBOX_VIEW;
  }
  if (collectionId !== undefined && isValidQuestionId(collectionId)) {
    return { kind: "collection", collectionId };
  }
  return ALL_VIEW;
}

export function parsePageNumber(value: string | string[] | undefined): number {
  const raw = firstValue(value);
  if (raw === undefined || !/^[1-9]\d*$/.test(raw)) {
    return 1;
  }
  const page = Number(raw);
  return Number.isSafeInteger(page) && page >= 1 && page <= MAX_PAGE ? page : 1;
}

export function collectionIdForView(view: LibraryView): string | undefined {
  if (view.kind === "inbox") {
    return INBOX_COLLECTION_ID;
  }
  if (view.kind === "collection") {
    return view.collectionId;
  }
  return undefined;
}

export function viewKey(view: LibraryView): string {
  return view.kind === "collection" ? `collection:${view.collectionId}` : view.kind;
}

export function isSameView(left: LibraryView, right: LibraryView): boolean {
  return viewKey(left) === viewKey(right);
}

export function buildWorkspaceHref(view: LibraryView, page: number): string {
  const params = new URLSearchParams();
  if (view.kind === "inbox") {
    params.set("view", "inbox");
  }
  if (view.kind === "collection") {
    params.set("collection", view.collectionId);
  }
  if (page > 1) {
    params.set("page", String(page));
  }
  const query = params.toString();
  return query.length === 0 ? "/workspace" : `/workspace?${query}`;
}

export function buildQuestionHref(questionId: string): string {
  return `/workspace/questions/${encodeURIComponent(questionId)}`;
}

export function totalPages(total: number, pageSize: number): number {
  if (total <= 0 || pageSize <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil(total / pageSize));
}

export function formatQuestionTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Shanghai",
  }).format(date);
}
