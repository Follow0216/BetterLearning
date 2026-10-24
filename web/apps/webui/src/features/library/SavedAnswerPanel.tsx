"use client";

import { useCallback, useEffect, useState } from "react";

import { BlIcon } from "@/components/icons/BlIcon";
import { AnswerTileViewer } from "@/features/answer-viewer/AnswerTileViewer";
import type { AnswerTileImage } from "@/features/answer-viewer/answer-tile-geometry";
import { parseSavedAnswer, type SavedAnswer } from "@/lib/api/answer-contract";

import { AnswerStateBadge } from "./AnswerStateBadge";
import { formatQuestionTime } from "./library-params";

interface SavedAnswerPanelProps {
  questionId: string;
}

type AnswerOutcome =
  | { status: "ready"; answer: SavedAnswer }
  | { status: "failed"; message: string; unauthenticated: boolean };

interface StoredOutcome {
  key: string;
  outcome: AnswerOutcome;
}

interface StoredTileError {
  key: string;
  index: number;
}

function blockSource(questionId: string, fileRef: string, index: number): string {
  return `/api/library/questions/${encodeURIComponent(questionId)}/answer-files/${encodeURIComponent(
    fileRef,
  )}/blocks/${index}`;
}

async function readFailure(
  response: Response,
): Promise<{ message: string; unauthenticated: boolean }> {
  if (response.status === 401) {
    return { message: "登录状态已失效，请重新登录", unauthenticated: true };
  }
  if (response.status === 404) {
    return { message: "未找到该题目的当前作答", unauthenticated: false };
  }
  try {
    const payload: unknown = await response.json();
    if (typeof payload === "object" && payload !== null && "message" in payload) {
      const message = (payload as { message?: unknown }).message;
      if (typeof message === "string" && message.length > 0) {
        return { message, unauthenticated: false };
      }
    }
  } catch {
    // 非 JSON 错误体按状态码给出通用提示。
  }
  return { message: "加载已保存作答失败，请稍后重试", unauthenticated: false };
}

export function SavedAnswerPanel({ questionId }: SavedAnswerPanelProps) {
  const [attempt, setAttempt] = useState(0);
  const [storedOutcome, setStoredOutcome] = useState<StoredOutcome | null>(null);
  const [storedTileError, setStoredTileError] = useState<StoredTileError | null>(null);
  const requestKey = `${questionId}|${attempt}`;
  const outcome: AnswerOutcome | { status: "loading" } =
    storedOutcome !== null && storedOutcome.key === requestKey
      ? storedOutcome.outcome
      : { status: "loading" };
  const tileError =
    storedTileError !== null && storedTileError.key === requestKey ? storedTileError.index : null;

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      try {
        const response = await fetch(
          `/api/library/questions/${encodeURIComponent(questionId)}/answer`,
          {
            cache: "no-store",
            credentials: "same-origin",
            headers: { Accept: "application/json" },
            signal: controller.signal,
          },
        );
        if (!response.ok) {
          const failure = await readFailure(response);
          setStoredOutcome({ key: requestKey, outcome: { status: "failed", ...failure } });
          return;
        }
        const payload: unknown = await response.json();
        const answer = parseSavedAnswer(payload, questionId);
        setStoredOutcome({ key: requestKey, outcome: { status: "ready", answer } });
      } catch {
        if (controller.signal.aborted) {
          return;
        }
        setStoredOutcome({
          key: requestKey,
          outcome: {
            status: "failed",
            message: "无法加载已保存作答，请检查网络后重试",
            unauthenticated: false,
          },
        });
      }
    })();

    return () => {
      controller.abort();
    };
  }, [questionId, requestKey]);

  const reload = useCallback(() => {
    setAttempt((value) => value + 1);
  }, []);

  const reportTileError = useCallback(
    (index: number) => {
      setStoredTileError({ key: requestKey, index });
    },
    [requestKey],
  );

  return (
    <section
      aria-labelledby="saved-answer-heading"
      className="mt-4 rounded-2xl border border-[var(--auth-border)] bg-white p-5 md:p-7"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2
            id="saved-answer-heading"
            className="text-base leading-6 font-semibold text-[var(--auth-text)]"
          >
            已保存作答
          </h2>
          <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
            只读查看云端当前作答；Web 端不保存、不标记完成。
          </p>
        </div>
        {outcome.status === "ready" ? <AnswerStateBadge state={outcome.answer.state} /> : null}
      </div>

      {outcome.status === "loading" ? (
        <p role="status" className="mt-4 text-sm text-[var(--auth-muted)]">
          正在读取当前作答…
        </p>
      ) : null}

      {outcome.status === "failed" ? (
        <div className="mt-4 rounded-xl border border-[var(--auth-border)] bg-[var(--auth-bg)] px-4 py-4">
          <p role="alert" className="text-sm leading-6 text-[var(--auth-danger)]">
            {outcome.message}
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={reload}
              className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-text)] transition-colors hover:bg-[var(--auth-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
            >
              <BlIcon name="refresh" size={15} />
              重试
            </button>
            {outcome.unauthenticated ? (
              <a
                href="/"
                className="inline-flex h-9 items-center justify-center rounded-lg bg-[var(--auth-primary)] px-3 text-sm font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:outline-none"
              >
                重新登录
              </a>
            ) : null}
          </div>
        </div>
      ) : null}

      {outcome.status === "ready" ? (
        <SavedAnswerBody
          answer={outcome.answer}
          questionId={questionId}
          attempt={attempt}
          tileError={tileError}
          onTileError={reportTileError}
          onReload={reload}
        />
      ) : null}
    </section>
  );
}

function SavedAnswerBody({
  answer,
  questionId,
  attempt,
  tileError,
  onTileError,
  onReload,
}: {
  answer: SavedAnswer;
  questionId: string;
  attempt: number;
  tileError: number | null;
  onTileError: (index: number) => void;
  onReload: () => void;
}) {
  const { fileRef, savedAt, completedAt, manifest } = answer;
  if (fileRef === null || savedAt === null || manifest === null) {
    return (
      <div className="mt-4 rounded-xl border border-dashed border-[var(--auth-border)] bg-[var(--auth-bg)] px-5 py-8 text-center">
        <p className="text-sm font-medium text-[var(--auth-text)]">尚未保存作答</p>
        <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
          当前没有云端已保存的完整作答；在平板完成并导出保存后，这里会显示只读图片。
        </p>
      </div>
    );
  }

  const bounds = { x: 0, y: 0, width: manifest.canvas.width, height: manifest.canvas.height };
  const tiles: AnswerTileImage[] = manifest.blocks.map((block) => ({
    index: block.index,
    x: block.x,
    y: block.y,
    width: block.width,
    height: block.height,
    source: blockSource(questionId, fileRef, block.index),
  }));

  return (
    <>
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--auth-muted)]">
        <span>保存于 {formatQuestionTime(savedAt)}</span>
        {completedAt === null ? null : <span>完成于 {formatQuestionTime(completedAt)}</span>}
        <span>图块 {manifest.blocks.length} 块</span>
      </div>

      {tileError === null ? null : (
        <p role="alert" className="mt-3 text-xs leading-5 text-[var(--auth-danger)]">
          第 {tileError + 1} 块作答图片加载失败。重新加载会重新读取当前作答，不会混用旧图块。
        </p>
      )}

      <div className="mt-3 min-h-[360px] w-full">
        <AnswerTileViewer
          documentKey={`${fileRef}|${attempt}`}
          bounds={bounds}
          tiles={tiles}
          className="w-full"
          onTileError={onTileError}
          onRetry={onReload}
        />
      </div>
    </>
  );
}
