import { SUBJECT_LABELS, type QuestionSubject } from "@/lib/api/library-contract";

export function SubjectBadge({ subject }: { subject: QuestionSubject }) {
  return (
    <span className="text-xs font-medium text-[var(--auth-muted)]">{SUBJECT_LABELS[subject]}</span>
  );
}
