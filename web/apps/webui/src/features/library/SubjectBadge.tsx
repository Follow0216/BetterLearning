import { SUBJECT_LABELS, type QuestionSubject } from "@/lib/api/library-contract";

export function SubjectBadge({ subject }: { subject: QuestionSubject }) {
  const tone =
    subject === "HIGHER_MATHEMATICS"
      ? "bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]"
      : "bg-[var(--auth-bg)] text-[var(--auth-muted)]";
  return (
    <span
      className={`inline-flex h-6 shrink-0 items-center rounded-full px-2 text-[11px] font-medium ${tone}`}
    >
      {SUBJECT_LABELS[subject]}
    </span>
  );
}
