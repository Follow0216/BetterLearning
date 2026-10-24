"use client";

import { useId, useState } from "react";
import type { ReactNode } from "react";

import { BlIcon } from "@/components/icons/BlIcon";
import type { TutorTurnStatus, TutorTurnSummary } from "@/lib/api/tutor-turn-contract";

import { formatQuestionTime } from "./library-params";
import { TutorTurnAnswerViewer } from "./TutorTurnAnswerViewer";

const STATUS_META: Record<TutorTurnStatus, { label: string; className: string }> = {
  WAITING_ANSWER: {
    label: "等待作答",
    className: "border-[#cfe0ff] bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]",
  },
  GENERATING: {
    label: "生成中",
    className: "border-[#cfe0ff] bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]",
  },
  SUCCEEDED: {
    label: "已完成",
    className:
      "border-[var(--auth-success-border)] bg-[var(--auth-success-soft)] text-[var(--auth-success)]",
  },
  FAILED: {
    label: "生成失败",
    className: "border-[var(--auth-border)] bg-[var(--auth-bg)] text-[var(--auth-danger)]",
  },
};

interface TutorTurnItemProps {
  questionId: string;
  turn: TutorTurnSummary;
  turnNumber: number;
  isLatest: boolean;
  hasAnswer: boolean;
  children?: ReactNode;
}

export function TutorTurnItem({
  questionId,
  turn,
  turnNumber,
  isLatest,
  hasAnswer,
  children,
}: TutorTurnItemProps) {
  const [expanded, setExpanded] = useState(isLatest);
  const panelId = useId();
  const status = STATUS_META[turn.status];

  return (
    <section className="rounded-xl border border-[var(--auth-border)] bg-[var(--auth-bg)]">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={expanded ? panelId : undefined}
        onClick={() => {
          setExpanded((value) => !value);
        }}
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-xl px-4 py-3 text-left transition-colors hover:bg-[var(--auth-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
      >
        <span className="shrink-0 text-sm font-medium text-[var(--auth-text)]">
          第 {turnNumber} 轮
        </span>
        <span
          className={`inline-flex shrink-0 items-center rounded-full border px-2 py-0.5 text-[11px] leading-4 font-medium ${status.className}`}
        >
          {status.label}
        </span>
        <span className="min-w-0 flex-1 truncate text-xs text-[var(--auth-muted)]">
          {turn.prompt}
        </span>
        <time dateTime={turn.createdAt} className="shrink-0 text-[11px] text-[var(--auth-muted)]">
          {formatQuestionTime(turn.createdAt)}
        </time>
        <BlIcon
          name="chevron_right"
          size={16}
          className={`shrink-0 text-[var(--auth-muted)] transition-transform ${
            expanded ? "rotate-90" : ""
          }`}
        />
      </button>

      {expanded ? (
        <div id={panelId} className="border-t border-[var(--auth-divider)] bg-white px-4 py-4">
          <div>
            <p className="text-xs text-[var(--auth-muted)]">提问</p>
            <p className="mt-1 text-sm leading-6 break-words whitespace-pre-wrap text-[var(--auth-text)]">
              {turn.prompt}
            </p>
          </div>

          {turn.status === "FAILED" ? (
            <div
              role="alert"
              className="mt-4 rounded-lg border border-[var(--auth-border)] bg-[var(--auth-bg)] px-3 py-3"
            >
              <p className="text-sm leading-6 text-[var(--auth-danger)]">
                {turn.failureMessage ?? "辅导生成失败，请稍后在手机端重试。"}
              </p>
              {turn.failureCode === null ? null : (
                <p className="mt-1 font-mono text-[11px] text-[var(--auth-muted)]">
                  错误码 {turn.failureCode}
                </p>
              )}
            </div>
          ) : null}

          {turn.fileRef !== null &&
          (turn.status === "WAITING_ANSWER" || turn.status === "GENERATING") ? (
            <p className="mt-4 rounded-lg border border-dashed border-[var(--auth-border)] bg-[var(--auth-bg)] px-3 py-3 text-xs leading-5 text-[var(--auth-muted)]">
              该轮已绑定作答文件，辅导回答尚未完成；可稍后手动刷新。
            </p>
          ) : null}

          {turn.status === "SUCCEEDED" && !hasAnswer ? (
            <p className="mt-4 text-sm text-[var(--auth-muted)]">该轮没有可显示的回答内容。</p>
          ) : null}

          {hasAnswer ? <div className="mt-4">{children}</div> : null}

          <TutorTurnAnswerViewer
            questionId={questionId}
            turnId={turn.turnId}
            fileRef={turn.fileRef}
            status={turn.status}
            updatedAt={turn.updatedAt}
          />
        </div>
      ) : null}
    </section>
  );
}
