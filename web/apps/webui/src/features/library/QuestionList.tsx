import Link from "next/link";

import { BlIcon } from "@/components/icons/BlIcon";
import type { QuestionSummary } from "@/lib/api/library-contract";

import { AnswerStateBadge } from "./AnswerStateBadge";
import { buildQuestionHref, formatQuestionTime } from "./library-params";
import { SubjectBadge } from "./SubjectBadge";

export function QuestionList({ items }: { items: QuestionSummary[] }) {
  return (
    <ul className="divide-y divide-[var(--auth-divider)] overflow-hidden rounded-2xl border border-[var(--auth-border)] bg-white">
      {items.map((question) => (
        <li key={question.questionId}>
          <Link
            href={buildQuestionHref(question.questionId)}
            className="flex items-center gap-3 px-4 py-3.5 transition-colors hover:bg-[var(--auth-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none focus-visible:ring-inset"
          >
            <SubjectBadge subject={question.subject} />
            <span className="min-w-0 flex-1 text-sm leading-6 break-words text-[var(--auth-text)]">
              {question.summary}
            </span>
            <AnswerStateBadge state={question.answerState} />
            <time
              dateTime={question.createdAt}
              className="hidden shrink-0 text-xs text-[var(--auth-muted)] tabular-nums sm:block"
            >
              {formatQuestionTime(question.createdAt)}
            </time>
            <BlIcon name="chevron_right" size={16} className="text-[var(--auth-muted)]" />
          </Link>
        </li>
      ))}
    </ul>
  );
}
