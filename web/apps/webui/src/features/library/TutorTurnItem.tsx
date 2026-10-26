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
    className: "text-[var(--auth-primary)]",
  },
  GENERATING: {
    label: "生成中",
    className: "text-[var(--auth-primary)]",
  },
  SUCCEEDED: {
    label: "已完成",
    className: "text-[var(--auth-success)]",
  },
  FAILED: {
    label: "生成失败",
    className: "text-[var(--auth-danger)]",
  },
};

interface TutorTurnItemProps {
  questionId: string;
  turn: TutorTurnSummary;
  turnNumber: number;
  isLatest: boolean;
  hasAnswer: boolean;
  children?: ReactNode;
  focused?: boolean;
}

export function TutorTurnItem({
  questionId,
  turn,
  turnNumber,
  isLatest,
  hasAnswer,
  children,
  focused = false,
}: TutorTurnItemProps) {
  const [expanded, setExpanded] = useState(isLatest || focused);
  const [snapshotExpanded, setSnapshotExpanded] = useState(false);
  const panelId = useId();
  const status = STATUS_META[turn.status];

  return (
    <section className="min-w-0 border-b border-[var(--auth-border)]">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={expanded ? panelId : undefined}
        onClick={() => {
          setExpanded((value) => !value);
        }}
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-2 rounded-md px-1 py-4 text-left transition-colors hover:bg-[var(--auth-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
      >
        <span className="shrink-0 text-sm font-medium text-[var(--auth-text)]">
          {isLatest ? "最新一轮 · " : ""}第 {turnNumber} 轮
        </span>
        <span
          className={`inline-flex shrink-0 items-center text-xs leading-5 font-medium ${status.className}`}
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
        <div id={panelId} className="min-w-0 pb-5">
          <div>
            <p className="text-xs text-[var(--auth-muted)]">提问</p>
            <p className="mt-1 text-sm leading-6 break-words whitespace-pre-wrap text-[var(--auth-text)]">
              {turn.prompt}
            </p>
          </div>

          {turn.status === "FAILED" ? (
            <div
              role="alert"
              className="mt-4 border-l-2 border-[var(--auth-danger)] bg-[var(--auth-bg)] px-3 py-3"
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
            <p className="mt-4 border-l-2 border-[var(--auth-border)] px-3 py-1 text-xs leading-5 text-[var(--auth-muted)]">
              本轮作答已提交，辅导回答尚未完成，请稍后刷新。
            </p>
          ) : null}

          {turn.status === "SUCCEEDED" && !hasAnswer ? (
            <p className="mt-4 text-sm text-[var(--auth-muted)]">该轮没有可显示的回答内容。</p>
          ) : null}

          {hasAnswer ? <div className="mt-4">{children}</div> : null}

          <div className="mt-6 border-t border-[var(--auth-border)] pt-3">
            <button
              type="button"
              aria-expanded={snapshotExpanded}
              aria-controls={snapshotExpanded ? `${panelId}-snapshot` : undefined}
              onClick={() => setSnapshotExpanded((value) => !value)}
              className="inline-flex min-h-9 items-center gap-2 rounded text-left text-[13px] font-medium text-[var(--auth-primary)]"
            >
              <BlIcon
                name="chevron_right"
                size={14}
                className={snapshotExpanded ? "rotate-90" : undefined}
              />
              本轮使用的作答快照
            </button>
            {snapshotExpanded ? (
              <div id={`${panelId}-snapshot`}>
                <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
                  本轮辅导使用的作答快照，与云端当前版本分别保存。
                </p>
                <TutorTurnAnswerViewer
                  questionId={questionId}
                  turnId={turn.turnId}
                  fileRef={turn.fileRef}
                  status={turn.status}
                  updatedAt={turn.updatedAt}
                />
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  );
}
