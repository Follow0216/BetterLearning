import Link from "next/link";
import { redirect } from "next/navigation";
import { Suspense, type ReactNode } from "react";

import { BlIcon } from "@/components/icons/BlIcon";
import { SessionBoundary } from "@/features/learning-notes/SessionBoundary";
import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { TutorRequestHistory } from "@/features/tutoring/TutorRequestHistory";
import {
  INBOX_COLLECTION_ID,
  INBOX_COLLECTION_NAME,
  isValidQuestionId,
} from "@/lib/api/library-contract";
import { getCollections, getQuestionDetail, type LibraryFailure } from "@/lib/api/library-server";

import { AnswerStateBadge } from "./AnswerStateBadge";
import {
  buildLibraryReturnHref,
  buildHomeQuestionHref,
  isHomeQuestionSource,
  buildQuestionHref,
  formatQuestionTime,
  parseLibrarySource,
  type SearchParamsInput,
} from "./library-params";
import { MoveQuestionControl } from "./MoveQuestionControl";
import { QuestionDetailTabs } from "./QuestionDetailTabs";
import { RefreshButton } from "./RefreshButton";
import { SavedAnswerPanel } from "./SavedAnswerPanel";
import { StatePanel } from "./StatePanel";
import { SubjectBadge } from "./SubjectBadge";
import { TutorHistoryPanel } from "./TutorHistoryPanel";
import styles from "./workspace.module.css";
import { WorkspaceHeader } from "./WorkspaceHeader";

function DetailFrame({
  children,
  backHref,
  backLabel,
}: {
  children: ReactNode;
  backHref: string;
  backLabel: string;
}) {
  return (
    <SessionBoundary>
      <div className={styles.root}>
        <WorkspaceHeader />
        <main tabIndex={-1} id="workspace-content" className={styles.detailShell}>
          <Link
            href={backHref}
            scroll={backHref === "/workspace" || backHref === "/"}
            className="inline-flex min-h-9 items-center gap-1.5 rounded-lg text-sm font-medium text-[var(--auth-muted)] transition-colors hover:text-[var(--auth-primary)]"
          >
            <BlIcon name="back" size={16} />
            {backLabel}
          </Link>
          {children}
        </main>
      </div>
    </SessionBoundary>
  );
}

function FailurePanel({
  failure,
  retryHref,
  backHref,
  backLabel,
}: {
  failure: LibraryFailure;
  retryHref: string;
  backHref: string;
  backLabel: string;
}) {
  return (
    <StatePanel
      title={
        failure.kind === "not-found"
          ? "题目不存在"
          : failure.kind === "forbidden"
            ? "没有访问权限"
            : failure.kind === "unavailable"
              ? "题库服务暂不可用"
              : "加载题目失败"
      }
      message={failure.message}
      traceId={failure.traceId}
      retryHref={
        failure.kind === "not-found" || failure.kind === "forbidden" ? undefined : retryHref
      }
      backHref={backHref}
      backLabel={backLabel}
    />
  );
}

export async function QuestionDetailView({
  params,
  searchParams,
}: {
  params: Promise<{ questionId: string }>;
  searchParams: Promise<SearchParamsInput>;
}) {
  const [{ questionId }, resolvedSearch] = await Promise.all([params, searchParams]);
  const source = parseLibrarySource(resolvedSearch);
  const fromHome = isHomeQuestionSource(resolvedSearch);
  const backHref = fromHome ? "/" : buildLibraryReturnHref(source);
  const backLabel = fromHome ? "返回学习首页" : "返回题库";
  const retryHref = fromHome
    ? buildHomeQuestionHref(questionId)
    : buildQuestionHref(questionId, source ?? undefined);

  if (!isValidQuestionId(questionId)) {
    return (
      <DetailFrame backHref={backHref} backLabel={backLabel}>
        <StatePanel
          title="题目链接无效"
          message="题目编号格式不正确，无法加载详情。"
          backHref={backHref}
          backLabel={backLabel}
        />
      </DetailFrame>
    );
  }

  const [result, collectionsResult] = await Promise.all([
    getQuestionDetail(questionId),
    getCollections(),
  ]);
  if (!result.ok) {
    if (result.failure.kind === "unauthenticated") redirect("/account");
    return (
      <DetailFrame backHref={backHref} backLabel={backLabel}>
        <FailurePanel
          failure={result.failure}
          retryHref={retryHref}
          backHref={backHref}
          backLabel={backLabel}
        />
      </DetailFrame>
    );
  }
  if (!collectionsResult.ok && collectionsResult.failure.kind === "unauthenticated")
    redirect("/account");

  const question = result.data;
  const collections = collectionsResult.ok ? collectionsResult.data.items : [];
  const collectionName =
    question.collectionId === INBOX_COLLECTION_ID
      ? INBOX_COLLECTION_NAME
      : (collections.find((item) => item.collectionId === question.collectionId)?.name ??
        `题集 #${question.collectionId}`);

  return (
    <DetailFrame backHref={backHref} backLabel={backLabel}>
      <article className={styles.detailArticle}>
        <header className={styles.detailHeader}>
          <div className="min-w-0">
            <h1 className={styles.pageTitle}>题目详情</h1>
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs leading-5 text-[var(--auth-muted)]">
              <SubjectBadge subject={question.subject} />
              <AnswerStateBadge state={question.answerState} />
              {question.collectionId === INBOX_COLLECTION_ID || collectionsResult.ok ? (
                <span className="max-w-full break-words">归属：{collectionName}</span>
              ) : null}
              <time dateTime={question.createdAt}>
                入库于 {formatQuestionTime(question.createdAt)}
              </time>
            </div>
            <p className="mt-2 font-mono text-[11px] text-[var(--auth-muted)]">
              题目 #{question.questionId}
            </p>
          </div>
          {collectionsResult.ok ? (
            <details className="max-w-full min-w-0 text-sm">
              <summary className="flex min-h-9 cursor-pointer list-none items-center justify-center gap-2 rounded-lg border border-[var(--auth-border)] px-3 text-[var(--auth-muted)] hover:bg-[var(--auth-hover)]">
                移动题目
                <BlIcon name="chevron_right" size={14} />
              </summary>
              <div className="pt-3">
                <MoveQuestionControl
                  questionId={question.questionId}
                  currentCollectionId={question.collectionId}
                  collections={collections}
                />
              </div>
            </details>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              <span role="alert" className="text-xs text-[var(--auth-danger)]">
                题集信息加载失败：{collectionsResult.failure.message}
              </span>
              <RefreshButton />
            </div>
          )}
        </header>
        <div className={styles.detailGrid}>
          <section className={styles.questionPane} aria-labelledby="question-body-heading">
            <h2 id="question-body-heading" className={styles.sectionLabel}>
              题目
            </h2>
            <QuestionMarkdown bodyMarkdown={question.bodyMarkdown} ariaLabel="题目正文" />
            <p className={styles.readonlyNote}>
              网页端只读查看题目、作答和辅导记录。手机端录题与提问，平板端手写作答。
            </p>
          </section>
          <section className={styles.answerPane} aria-label="作答与辅导">
            <QuestionDetailTabs
              key={questionId}
              answer={<SavedAnswerPanel questionId={questionId} />}
              tutor={
                <>
                  <TutorRequestHistory questionId={questionId} />
                  <Suspense
                    fallback={
                      <p role="status" className="text-sm text-[var(--auth-muted)]">
                        正在读取 AI 辅导记录…
                      </p>
                    }
                  >
                    <TutorHistoryPanel questionId={questionId} />
                  </Suspense>
                </>
              }
            />
          </section>
        </div>
      </article>
    </DetailFrame>
  );
}
