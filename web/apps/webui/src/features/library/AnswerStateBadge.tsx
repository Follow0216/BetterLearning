import {
  ANSWER_STATE_LABELS,
  type QuestionAnswerState,
} from "@/lib/api/library-contract";

const STATE_STYLES: Record<QuestionAnswerState, string> = {
  NOT_STARTED: "border-[var(--auth-border)] bg-[var(--auth-bg)] text-[var(--auth-muted)]",
  IN_PROGRESS: "border-[#cfe0ff] bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]",
  COMPLETED:
    "border-[var(--auth-success-border)] bg-[var(--auth-success-soft)] text-[var(--auth-success)]",
};

export function AnswerStateBadge({ state }: { state: QuestionAnswerState }) {
  return (
    <span
      className={`inline-flex shrink-0 items-center rounded-full border px-2 py-0.5 text-[11px] leading-4 font-medium ${STATE_STYLES[state]}`}
    >
      {ANSWER_STATE_LABELS[state]}
    </span>
  );
}