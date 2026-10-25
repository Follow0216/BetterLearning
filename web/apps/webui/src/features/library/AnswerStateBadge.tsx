import { ANSWER_STATE_LABELS, type QuestionAnswerState } from "@/lib/api/library-contract";

const STATE_STYLES: Record<QuestionAnswerState, string> = {
  NOT_STARTED: "text-[var(--auth-muted)]",
  IN_PROGRESS: "text-[var(--auth-primary)]",
  COMPLETED: "text-[var(--auth-success)]",
};

export function AnswerStateBadge({ state }: { state: QuestionAnswerState }) {
  return (
    <span
      className={`inline-flex shrink-0 items-center text-xs leading-5 font-medium ${STATE_STYLES[state]}`}
    >
      {ANSWER_STATE_LABELS[state]}
    </span>
  );
}
