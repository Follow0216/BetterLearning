"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

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
  const [target, setTarget] = useState(currentCollectionId);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const options = flattenCollectionTree(buildCollectionTree(collections));

  async function submit() {
    if (pending || target === currentCollectionId) {
      return;
    }
    setPending(true);
    setError(null);
    setSaved(false);
    try {
      const response = await fetch(
        `/api/library/questions/${encodeURIComponent(questionId)}/collection`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ collectionId: target }),
        },
      );

      if (response.status === 401) {
        router.replace("/");
        return;
      }

      const data = (await response.json().catch(() => null)) as Record<string, unknown> | null;
      if (!response.ok) {
        const message = data?.message;
        setError(
          typeof message === "string" && message.length > 0 ? message : "移动失败，请稍后重试",
        );
        return;
      }

      setSaved(true);
      router.refresh();
    } catch {
      setError("无法连接服务器，请稍后重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <label className="flex items-center gap-2 text-sm text-[var(--auth-muted)]">
        移动到
        <select
          value={target}
          onChange={(event) => {
            setTarget(event.target.value);
            setSaved(false);
            setError(null);
          }}
          className="h-9 rounded-lg border border-[var(--auth-border)] bg-white px-2 text-sm text-[var(--auth-text)] transition-colors outline-none focus:border-[var(--auth-primary)] focus:ring-2 focus:ring-[var(--auth-primary-soft)]"
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
      {saved ? (
        <span role="status" className="text-xs text-[var(--auth-success)]">
          已移动
        </span>
      ) : null}
      {error === null ? null : (
        <span role="alert" className="text-xs text-[var(--auth-danger)]">
          {error}
        </span>
      )}
    </div>
  );
}
