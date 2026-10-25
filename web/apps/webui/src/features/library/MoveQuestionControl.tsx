"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { useFeedbackRequest } from "@/components/feedback/use-feedback-request";

import {
  INBOX_COLLECTION_ID,
  INBOX_COLLECTION_NAME,
  type CollectionSummary,
} from "@/lib/api/library-contract";

import { buildCollectionTree, flattenCollectionTree } from "./collection-tree";

interface MoveQuestionControlProps {
  questionId: string;
  currentCollectionId: string;
  collections: CollectionSummary[];
}

export function MoveQuestionControl({
  questionId,
  currentCollectionId,
  collections,
}: MoveQuestionControlProps) {
  const router = useRouter();
  const { toast, begin } = useFeedbackRequest();
  const [target, setTarget] = useState(currentCollectionId);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const options = flattenCollectionTree(buildCollectionTree(collections));

  async function submit() {
    if (pending || target === currentCollectionId) {
      return;
    }
    const request = begin();
    if (!request) return;
    setPending(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/library/questions/${encodeURIComponent(questionId)}/collection`,
        {
          signal: request.signal,
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ collectionId: target }),
        },
      );

      if (!request.isCurrent()) return;
      if (response.status === 401) {
        toast.clearScope();
        router.replace("/");
        return;
      }

      const data = (await response.json().catch(() => null)) as Record<string, unknown> | null;
      if (!request.isCurrent()) return;
      if (!response.ok) {
        const message = data?.message;
        setError(
          typeof message === "string" && message.length > 0 ? message : "移动失败，请稍后重试",
        );
        if (response.status >= 500) {
          toast.show(
            {
              key: `question:move:${questionId}`,
              type: "error",
              message: "题目移动失败",
              description: "请稍后重试",
            },
            request.scope,
          );
        }
        return;
      }

      toast.show(
        { key: `question:move:${questionId}`, type: "success", message: "题目已移入题集" },
        request.scope,
      );
      router.refresh();
    } catch {
      if (!request.isCurrent()) return;
      setError("无法连接服务器，请稍后重试");
      toast.show(
        {
          key: `question:move:${questionId}`,
          type: "error",
          message: "题目移动失败",
          description: "请稍后重试",
        },
        request.scope,
      );
    } finally {
      if (request.finish()) setPending(false);
    }
  }

  return (
    <div className="flex max-w-full flex-wrap items-center gap-2">
      <label className="flex max-w-full min-w-0 items-center gap-2 text-[13px] text-[var(--auth-muted)]">
        移动到
        <select
          value={target}
          onChange={(event) => {
            setTarget(event.target.value);
            toast.dismiss(`question:move:${questionId}`);
            setError(null);
          }}
          disabled={pending}
          className="h-9 max-w-[min(100%,18rem)] min-w-0 rounded-lg border border-[var(--auth-border)] bg-white px-2 text-sm text-[var(--auth-text)] transition-colors outline-none focus:border-[var(--auth-primary)] focus:ring-2 focus:ring-[var(--auth-primary-soft)]"
        >
          <option value={INBOX_COLLECTION_ID}>{INBOX_COLLECTION_NAME}</option>
          {options.map((item) => (
            <option key={item.collectionId} value={item.collectionId}>
              {`${"　".repeat(item.depth)}${item.name}`}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        onClick={submit}
        disabled={pending || target === currentCollectionId}
        className="inline-flex h-9 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
      >
        {pending ? "移动中…" : "移动"}
      </button>
      {error === null ? null : (
        <span role="alert" className="text-xs text-[var(--auth-danger)]">
          {error}
        </span>
      )}
    </div>
  );
}
