import Link from "next/link";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";

import { BlIcon } from "@/components/icons/BlIcon";
import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { isValidQuestionId } from "@/lib/api/library-contract";
import { getQuestionDetail, type LibraryFailure } from "@/lib/api/library-server";

import { formatQuestionTime } from "./library-params";
import { StatePanel } from "./StatePanel";
import { SubjectBadge } from "./SubjectBadge";
import { WorkspaceHeader } from "./WorkspaceHeader";

function DetailFrame({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-dvh bg-[var(--auth-bg)] text-[var(--auth-text)]">
      <WorkspaceHeader />
      <div className="mx-auto w-full max-w-[900px] px-4 py-6 md:px-6">
        <Link
          href="/workspace"
          className="inline-flex h-9 items-center gap-1.5 rounded-lg px-2 text-sm font-medium text-[var(--auth-muted)] transition-colors hover:text-[var(--auth-text)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
        >
          <BlIcon name="back" size={16} />
          返回题库
        </Link>
        <div className="mt-3">{children}</div>
      </div>
    </div>
  );
}

function FailurePanel({ failure, retryHref }: { failure: LibraryFailure; retryHref: string }) {
  if (failure.kind === "not-found") {
    return (
      <StatePanel
        title="题目不存在"
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
      title={failure.kind === "unavailable" ? "题库服务暂不可用" : "加载题目失败"}
      message={failure.message}
      traceId={failure.traceId}
      retryHref={retryHref}
      backHref="/workspace"
    />
  );
}

export async function QuestionDetailView({ params }: { params: Promise<{ questionId: string }> }) {
  const { questionId } = await params;
  const retryHref = `/workspace/questions/${encodeURIComponent(questionId)}`;

  if (!isValidQuestionId(questionId)) {
    return (
      <DetailFrame>
        <StatePanel
          title="题目链接无效"
          message="题目编号格式不正确，无法加载详情。"
          backHref="/workspace"
        />
      </DetailFrame>
    );
  }

  const result = await getQuestionDetail(questionId);
  if (!result.ok) {
    if (result.failure.kind === "unauthenticated") {
      redirect("/");
    }
    return (
      <DetailFrame>
        <FailurePanel failure={result.failure} retryHref={retryHref} />
      </DetailFrame>
    );
  }

  const question = result.data;
  return (
    <DetailFrame>
      <article className="rounded-2xl border border-[var(--auth-border)] bg-white p-5 md:p-7">
        <div className="flex flex-wrap items-center gap-3">
          <SubjectBadge subject={question.subject} />
          <time dateTime={question.createdAt} className="text-xs text-[var(--auth-muted)]">
            入库于 {formatQuestionTime(question.createdAt)}
          </time>
          <span className="ml-auto font-mono text-[11px] text-[var(--auth-muted)]">
            题目 #{question.questionId}
          </span>
        </div>
        <div className="mt-5 border-t border-[var(--auth-divider)] pt-5">
          <QuestionMarkdown bodyMarkdown={question.bodyMarkdown} ariaLabel="题目正文" />
        </div>
      </article>
    </DetailFrame>
  );
}
