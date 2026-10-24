import Link from "next/link";
import { redirect } from "next/navigation";

import { BlIcon } from "@/components/icons/BlIcon";
import { INBOX_COLLECTION_ID } from "@/lib/api/library-contract";
import { getCollections, getQuestionPage, type LibraryFailure } from "@/lib/api/library-server";

import { CollectionDialog } from "./CollectionDialog";
import {
  LIBRARY_PAGE_SIZE,
  buildWorkspaceHref,
  collectionIdForView,
  parseLibraryView,
  parsePageNumber,
  totalPages,
  type LibraryView,
  type SearchParamsInput,
} from "./library-params";
import { Pagination } from "./Pagination";
import { QuestionList } from "./QuestionList";
import { RefreshButton } from "./RefreshButton";
import { StatePanel } from "./StatePanel";
import { WorkspaceFrame } from "./WorkspaceFrame";

function FailurePanel({ failure, retryHref }: { failure: LibraryFailure; retryHref: string }) {
  if (failure.kind === "not-found") {
    return (
      <StatePanel
        title="题集不存在"
        message={failure.message}
        traceId={failure.traceId}
        backHref="/workspace"
      />
    );
  }
  if (failure.kind === "forbidden") {
    return (
      <StatePanel
        title="没有访问权限"
        message={failure.message}
        traceId={failure.traceId}
        backHref="/workspace"
      />
    );
  }
  return (
    <StatePanel
      title={failure.kind === "unavailable" ? "题库服务暂不可用" : "加载题库失败"}
      message={`${failure.message}。当前不会以空题库代替。`}
      traceId={failure.traceId}
      retryHref={retryHref}
      backHref="/workspace"
    />
  );
}

function EmptyState({ view }: { view: LibraryView }) {
  const isInbox = view.kind === "inbox";
  const isCollection = view.kind === "collection";
  return (
    <div className="flex flex-col items-center rounded-2xl border border-dashed border-[var(--auth-border)] bg-white px-6 py-14 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]">
        <BlIcon name={isInbox ? "inbox" : "list"} size={22} />
      </span>
      <p className="mt-4 text-base font-medium text-[var(--auth-text)]">
        {isInbox
          ? "待整理里还没有题目"
          : isCollection
            ? "这个题集里还没有题目"
            : "题库里还没有题目"}
      </p>
      <p className="mt-1 max-w-[420px] text-sm leading-6 text-[var(--auth-muted)]">
        {isCollection
          ? "可以在题目详情页把题目移动到这个题集。"
          : "在手机端拍照录题并确认入库后，题目会出现在这里。"}
      </p>
    </div>
  );
}

export async function WorkspaceView({
  searchParams,
}: {
  searchParams: Promise<SearchParamsInput>;
}) {
  const resolved = await searchParams;
  const view = parseLibraryView(resolved);
  const requestedPage = parsePageNumber(resolved.page);
  const retryHref = buildWorkspaceHref(view, requestedPage);

  const [pageResult, collectionsResult] = await Promise.all([
    getQuestionPage({
      collectionId: collectionIdForView(view),
      page: requestedPage,
      pageSize: LIBRARY_PAGE_SIZE,
    }),
    getCollections(),
  ]);

  const collections = collectionsResult.ok ? collectionsResult.data.items : [];
  const collectionsFailure = collectionsResult.ok ? undefined : collectionsResult.failure;

  if (collectionsFailure?.kind === "unauthenticated") {
    redirect("/");
  }

  if (!pageResult.ok) {
    if (pageResult.failure.kind === "unauthenticated") {
      redirect("/");
    }
    return (
      <WorkspaceFrame
        view={view}
        collections={collections}
        collectionsFailure={collectionsFailure}
      >
        <FailurePanel failure={pageResult.failure} retryHref={retryHref} />
      </WorkspaceFrame>
    );
  }

  const data = pageResult.data;
  const pages = totalPages(data.total, data.pageSize);
  const safePage = Math.min(data.page, pages);
  const inboxCount = collectionsResult.ok
    ? collections.find((item) => item.collectionId === INBOX_COLLECTION_ID)?.questionCount
    : undefined;
  const allCount =
    view.kind === "all"
      ? data.total
      : collectionsResult.ok
        ? collections.reduce((sum, item) => sum + item.questionCount, 0)
        : undefined;
  const currentCollection =
    view.kind === "collection"
      ? collections.find((item) => item.collectionId === view.collectionId)
      : undefined;

  const heading =
    view.kind === "inbox"
      ? "待整理"
      : view.kind === "all"
        ? "全部题目"
        : (currentCollection?.name ??
          (collectionsFailure === undefined ? "题集" : `题集 #${view.collectionId}`));
  const description =
    view.kind === "inbox"
      ? "手机端确认入库的题目默认进入这里。"
      : view.kind === "all"
        ? "当前账号的全部真实题目，按入库时间倒序。"
        : "题集内直接归属的题目，按入库时间倒序；子题集题目不计入这里。";

  return (
    <WorkspaceFrame
      view={view}
      collections={collections}
      collectionsFailure={collectionsFailure}
      inboxCount={inboxCount}
      allCount={allCount}
    >
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl leading-8 font-semibold text-[var(--auth-text)]">{heading}</h1>
          <p className="mt-1 text-sm text-[var(--auth-muted)]">{description}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {currentCollection === undefined ? null : (
            <>
              <CollectionDialog
                mode="rename"
                collections={collections}
                collection={currentCollection}
                triggerLabel="改名"
              />
              <CollectionDialog
                mode="create"
                collections={collections}
                triggerLabel="新建子集"
                defaultParentCollectionId={currentCollection.collectionId}
              />
            </>
          )}
          <RefreshButton />
        </div>
      </div>

      <div className="mt-5">
        {data.items.length > 0 ? (
          <QuestionList items={data.items} />
        ) : data.total > 0 ? (
          <div className="rounded-2xl border border-[var(--auth-border)] bg-white px-6 py-12 text-center">
            <p className="text-sm text-[var(--auth-muted)]">当前页没有题目，数据可能已更新。</p>
            <Link
              href={buildWorkspaceHref(view, 1)}
              className="mt-3 inline-flex h-9 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)]"
            >
              回到第一页
            </Link>
          </div>
        ) : (
          <EmptyState view={view} />
        )}
      </div>

      {data.total > 0 ? (
        <div className="mt-4">
          <Pagination view={view} page={safePage} pageSize={data.pageSize} total={data.total} />
        </div>
      ) : null}
    </WorkspaceFrame>
  );
}
