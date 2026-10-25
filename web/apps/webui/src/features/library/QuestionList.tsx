import { BlIcon } from "@/components/icons/BlIcon";
import {
  SUBJECT_LABELS,
  type CollectionSummary,
  type QuestionSummary,
} from "@/lib/api/library-contract";

import { AnswerStateBadge } from "./AnswerStateBadge";
import { QuestionListLink } from "./LibraryNavigation";
import {
  buildQuestionHref,
  buildWorkspaceHref,
  formatQuestionTime,
  type LibraryView,
} from "./library-params";
import styles from "./workspace.module.css";

export function QuestionList({
  items,
  collections,
  view,
  page,
}: {
  items: QuestionSummary[];
  collections: CollectionSummary[];
  view: LibraryView;
  page: number;
}) {
  const sourceHref = buildWorkspaceHref(view, page);
  const collectionNames = new Map(
    collections.map((collection) => [collection.collectionId, collection.name]),
  );
  return (
    <ul className={styles.questionList}>
      {items.map((question) => {
        const collectionName = collectionNames.get(question.collectionId);
        return (
          <li key={question.questionId} className={styles.questionRow}>
            <QuestionListLink
              href={buildQuestionHref(question.questionId, { view, page })}
              sourceHref={sourceHref}
              className={styles.questionLink}
            >
              <span className="min-w-0 flex-1">
                <span className={styles.questionSummary}>{question.summary}</span>
                <span className={styles.questionMeta}>
                  <span>{SUBJECT_LABELS[question.subject]}</span>
                  <AnswerStateBadge state={question.answerState} />
                  <time dateTime={question.createdAt}>
                    {formatQuestionTime(question.createdAt)}
                  </time>
                  {collectionName === undefined ? null : (
                    <span className="max-w-full truncate">{collectionName}</span>
                  )}
                </span>
              </span>
              <BlIcon name="chevron_right" size={16} className="text-[var(--auth-muted)]" />
            </QuestionListLink>
          </li>
        );
      })}
    </ul>
  );
}
