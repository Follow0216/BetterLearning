"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { BlIcon } from "@/components/icons/BlIcon";
import { AnswerTileViewer } from "@/features/answer-viewer/AnswerTileViewer";
import type { AnswerTileImage } from "@/features/answer-viewer/answer-tile-geometry";
import type { AnswerManifest } from "@/lib/api/answer-contract";
import { parseTutorReadManifest, type TutorTurnStatus } from "@/lib/api/tutor-turn-contract";

interface TutorTurnAnswerViewerProps {
  questionId: string;
  turnId: string;
  fileRef: string | null;
  status: TutorTurnStatus;
  updatedAt: string;
}

type ManifestOutcome =
  | { status: "ready"; manifest: AnswerManifest }
  | { status: "failed"; message: string; unauthenticated: boolean };

interface StoredOutcome {
  key: string;
  outcome: ManifestOutcome;
}

interface StoredTileError {
  key: string;
  index: number;
}

function blockSource(questionId: string, turnId: string, fileRef: string, index: number): string {
  return `/api/library/questions/${encodeURIComponent(questionId)}/tutor-turns/${encodeURIComponent(
    turnId,
  )}/answer/files/${encodeURIComponent(fileRef)}/blocks/${index}`;
}

function readFailure(status: number): { message: string; unauthenticated: boolean } {
  if (status === 401) {
    return { message: "登录状态已失效，请重新登录", unauthenticated: true };
  }
  if (status === 404) {
    return { message: "未找到该轮冻结作答", unauthenticated: false };
  }
  if (status === 503) {
    return { message: "作答服务暂不可用，请稍后重试", unauthenticated: false };
  }
  return { message: "加载该轮作答失败，请稍后重试", unauthenticated: false };
}

export function TutorTurnAnswerViewer({
  questionId,
  turnId,
  fileRef,
  status,
  updatedAt,
}: TutorTurnAnswerViewerProps) {
  const [attempt, setAttempt] = useState(0);
  const [storedOutcome, setStoredOutcome] = useState<StoredOutcome | null>(null);
  const [storedTileError, setStoredTileError] = useState<StoredTileError | null>(null);
  const requestKey = `${questionId}|${turnId}|${fileRef ?? "none"}|${updatedAt}|${attempt}`;
  const outcome: ManifestOutcome | { status: "loading" } =
    storedOutcome !== null && storedOutcome.key === requestKey
      ? storedOutcome.outcome
      : { status: "loading" };
  const tileError =
    storedTileError !== null && storedTileError.key === requestKey ? storedTileError.index : null;

  useEffect(() => {
    if (fileRef === null) {
      return;
    }
    const controller = new AbortController();

    void (async () => {
      try {
        const response = await fetch(
          `/api/library/questions/${encodeURIComponent(questionId)}/tutor-turns/${encodeURIComponent(
            turnId,
          )}/answer`,
          {
            cache: "no-store",
            credentials: "same-origin",
            headers: { Accept: "application/json" },
            signal: controller.signal,
          },
        );
        if (!response.ok) {
          setStoredOutcome({
            key: requestKey,
            outcome: { status: "failed", ...readFailure(response.status) },
          });
          return;
        }
        const payload: unknown = await response.json();
        const manifest = parseTutorReadManifest(payload, turnId);
        setStoredOutcome({ key: requestKey, outcome: { status: "ready", manifest } });
      } catch {
        if (controller.signal.aborted) {
          return;
        }
        setStoredOutcome({
          key: requestKey,
          outcome: {
            status: "failed",
            message: "无法加载该轮作答，请检查网络后重试",
            unauthenticated: false,
          },
        });
      }
    })();

    return () => {
      controller.abort();
    };
  }, [fileRef, questionId, requestKey, turnId]);

  const reload = useCallback(() => {
    setAttempt((value) => value + 1);
  }, []);

  const reportTileError = useCallback(
    (index: number) => {
      setStoredTileError({ key: requestKey, index });
    },
    [requestKey],
  );

  if (fileRef === null) {
    return <NoFrozenAnswer status={status} />;
  }

  return (
    <div className="mt-4">
      {outcome.status === "loading" ? (
        <p role="status" className="text-sm text-[var(--auth-muted)]">
          正在读取本轮作答快照…
        </p>
      ) : null}

      {outcome.status === "failed" ? (
        <div className="border-l-2 border-[var(--auth-danger)] bg-[var(--auth-bg)] px-4 py-4">
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
              <Link
                href="/"
                className="inline-flex h-9 items-center justify-center rounded-lg bg-[var(--auth-primary)] px-3 text-sm font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:outline-none"
              >
                重新登录
              </Link>
            ) : null}
          </div>
        </div>
      ) : null}

      {outcome.status === "ready" ? (
        <FrozenAnswerBody
          questionId={questionId}
          turnId={turnId}
          fileRef={fileRef}
          updatedAt={updatedAt}
          manifest={outcome.manifest}
          attempt={attempt}
          tileError={tileError}
          onTileError={reportTileError}
          onReload={reload}
        />
      ) : null}
    </div>
  );
}

function NoFrozenAnswer({ status }: { status: TutorTurnStatus }) {
  const copy =
    status === "WAITING_ANSWER"
      ? {
          title: "等待平板提交作答",
          hint: "平板提交后，可查看本轮使用的作答快照。",
        }
      : status === "GENERATING"
        ? {
            title: "正在生成回答",
            hint: "本轮还没有可查看的作答快照，请稍后刷新。",
          }
        : {
            title: "本轮没有作答快照",
            hint: "未保存本轮作答图片；当前作答可在另一个页签查看。",
          };

  return (
    <div className="mt-4 rounded-lg bg-[var(--auth-bg)] px-4 py-5 text-center">
      <p className="text-sm font-medium text-[var(--auth-text)]">{copy.title}</p>
      <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">{copy.hint}</p>
    </div>
  );
}

function FrozenAnswerBody({
  questionId,
  turnId,
  fileRef,
  updatedAt,
  manifest,
  attempt,
  tileError,
  onTileError,
  onReload,
}: {
  questionId: string;
  turnId: string;
  fileRef: string;
  updatedAt: string;
  manifest: AnswerManifest;
  attempt: number;
  tileError: number | null;
  onTileError: (index: number) => void;
  onReload: () => void;
}) {
  const bounds = { x: 0, y: 0, width: manifest.canvas.width, height: manifest.canvas.height };
  const tiles: AnswerTileImage[] = manifest.blocks.map((block) => ({
    index: block.index,
    x: block.x,
    y: block.y,
    width: block.width,
    height: block.height,
    source: blockSource(questionId, turnId, fileRef, block.index),
  }));

  return (
    <>
      {tileError === null ? null : (
        <p role="alert" className="mt-3 text-xs leading-5 text-[var(--auth-danger)]">
          本轮作答图片加载失败，请重新加载。
        </p>
      )}

      <div className="mt-3 min-h-[360px] w-full">
        <AnswerTileViewer
          documentKey={`${turnId}|${fileRef}|${updatedAt}|${attempt}`}
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
