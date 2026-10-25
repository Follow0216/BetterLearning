"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  displayTime,
  errorMessage,
  tutorStatusLabels,
} from "@/features/learning-notes/learning-utils";
import styles from "@/features/learning-notes/learning.module.css";
import { SessionBoundary } from "@/features/learning-notes/SessionBoundary";
import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { learningRequest } from "@/lib/api/learning-v2-client";
import type {
  Citation,
  CursorPage,
  TutorDetail,
  TutorRequest,
} from "@/lib/api/learning-v2-contract";

import { CitationDialog } from "./CitationDialog";

function RequestDetail({ questionId, requestId }: { questionId: string; requestId: string }) {
  const [detail, setDetail] = useState<TutorDetail | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [citation, setCitation] = useState<Citation | null>(null);
  const close = useCallback(() => setCitation(null), []);
  useEffect(() => {
    const controller = new AbortController();
    learningRequest<TutorDetail>(`questions/${questionId}/tutor-requests/${requestId}`, {
      signal: controller.signal,
    })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setDetail(data);
        setError("");
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setDetail(null);
          setError(errorMessage(error));
        }
      });
    return () => controller.abort();
  }, [questionId, requestId, revision]);
  return (
    <div className="mt-3 border-t border-[var(--auth-divider)] pt-3">
      <button
        className={styles.button}
        onClick={() => {
          setDetail(null);
          setCitation(null);
          setRevision(revision + 1);
        }}
      >
        刷新此记录
      </button>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {!detail && !error && <p role="status">正在读取运行历史…</p>}
      {detail && (
        <>
          <p className={styles.muted}>
            快照：
            {detail.request.snapshotInput.kind === "REUSE_REQUEST"
              ? "复用同题已保存的冻结时刻"
              : "本次独立冻结"}
            ；后续笔迹不会进入此快照。
          </p>
          <ol className="my-3 space-y-2" aria-label="运行历史">
            {detail.runs.map((run) => (
              <li key={run.runId} className={styles.muted}>
                运行 {run.runNo} · {tutorStatusLabels[run.status]}
                {run.reason ? ` · ${run.reason}` : ""}
                <ul>
                  {run.steps.map((step) => (
                    <li key={step.stepId}>
                      {step.role} · {step.status}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
          {detail.result ? (
            <>
              <QuestionMarkdown
                bodyMarkdown={detail.result.bodyMarkdown}
                ariaLabel="已审核的辅导回答"
                citations={detail.result.citations}
                onCitationClick={(id) => {
                  const selected = detail.result?.citations.find((item) => item.citationId === id);
                  if (selected) setCitation(selected);
                }}
              />
              <div className={`${styles.toolbar} mt-3`} aria-label="回答引用">
                {detail.result.citations.map((item) => (
                  <button
                    key={item.citationId}
                    className={styles.button}
                    onClick={() => setCitation(item)}
                    aria-label={`引用来源 ${item.label}`}
                  >
                    [{item.label}]
                  </button>
                ))}
              </div>
              {detail.result.citations.length === 0 && (
                <p className={styles.muted}>本轮没有引用个人历史资料。</p>
              )}
            </>
          ) : (
            <p className={styles.muted}>暂无已发布回答。请在手机端查看进度或处理失败。</p>
          )}
        </>
      )}
      {citation && <CitationDialog key={citation.citationId} citation={citation} onClose={close} />}
    </div>
  );
}

function History({ questionId }: { questionId: string }) {
  const [items, setItems] = useState<TutorRequest[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(true);
  const [revision, setRevision] = useState(0);
  const requests = useRef<AbortController | null>(null);
  const [opened, setOpened] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    requests.current = controller;
    learningRequest<CursorPage<TutorRequest>>(`questions/${questionId}/tutor-requests?limit=20`, {
      signal: controller.signal,
    })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setItems(data.items);
        setCursor(data.nextCursor ?? null);
        setError("");
        setPending(false);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setItems([]);
          setError(errorMessage(error));
          setPending(false);
        }
      });
    return () => controller.abort();
  }, [questionId, revision]);
  async function more() {
    if (pending || !cursor) return;
    const signal = requests.current?.signal;
    if (!signal || signal.aborted) return;
    setPending(true);
    try {
      const { data } = await learningRequest<CursorPage<TutorRequest>>(
        `questions/${questionId}/tutor-requests?limit=20&cursor=${encodeURIComponent(cursor)}`,
        { signal },
      );
      if (signal.aborted) return;
      setItems((old) => [
        ...old,
        ...data.items.filter(
          (item) => !old.some((previous) => previous.requestId === item.requestId),
        ),
      ]);
      setCursor(data.nextCursor ?? null);
      setError("");
    } catch (error) {
      if (!signal.aborted) setError(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <section className={`${styles.panel} mt-4`} aria-labelledby="v2-history-heading">
      <div className={styles.toolbar}>
        <h2 id="v2-history-heading" className="mr-auto text-base font-semibold">
          多 Agent 辅导记录
        </h2>
        <button
          className={styles.button}
          onClick={() => {
            requests.current?.abort();
            setPending(true);
            setOpened(null);
            setItems([]);
            setRevision(revision + 1);
          }}
        >
          刷新辅导历史
        </button>
      </div>
      <p className={styles.muted}>
        V2 只读历史；提问、追问、重试与取消请在手机端操作。旧版记录在下方独立保留。
      </p>
      {pending && <p role="status">正在读取辅导历史…</p>}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {!pending && !error && items.length === 0 && (
        <p className="mt-4 text-sm">暂无多 Agent 辅导记录。</p>
      )}
      <ol className={styles.list}>
        {items.map((item) => (
          <li key={item.requestId} className={styles.row}>
            <button
              className="text-left font-medium hover:underline focus-visible:outline-2"
              onClick={() => setOpened(opened === item.requestId ? null : item.requestId)}
              aria-expanded={opened === item.requestId}
            >
              {item.prompt}
            </button>
            <p className={styles.muted}>
              {displayTime(item.createdAt)} · {item.mode === "FULL_SOLUTION" ? "完整解答" : "提示"}{" "}
              · {tutorStatusLabels[item.status]}
            </p>
            {opened === item.requestId && (
              <RequestDetail
                key={item.requestId}
                questionId={questionId}
                requestId={item.requestId}
              />
            )}
          </li>
        ))}
      </ol>
      {cursor && (
        <button className={styles.button} disabled={pending} onClick={() => void more()}>
          加载更多辅导记录
        </button>
      )}
    </section>
  );
}

export function TutorRequestHistory({ questionId }: { questionId: string }) {
  return (
    <SessionBoundary>
      <History key={questionId} questionId={questionId} />
    </SessionBoundary>
  );
}
