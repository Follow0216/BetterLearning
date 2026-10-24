import { INBOX_COLLECTION_ID } from "@/lib/api/library-contract";

export const LIBRARY_PAGE_SIZE = 20;
export const MAX_PAGE = 1_000_000;

export type LibraryView = "all" | "inbox";

export type SearchParamsInput = Record<string, string | string[] | undefined>;

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export function parseLibraryView(searchParams: SearchParamsInput): LibraryView {
  return firstValue(searchParams.view) === "inbox" ? "inbox" : "all";
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
  return view === "inbox" ? INBOX_COLLECTION_ID : undefined;
}

export function buildWorkspaceHref(view: LibraryView, page: number): string {
  const params = new URLSearchParams();
  if (view === "inbox") {
    params.set("view", "inbox");
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