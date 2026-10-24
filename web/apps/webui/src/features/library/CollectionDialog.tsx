"use client";

import { useRouter } from "next/navigation";
import { useId, useState, type FormEvent } from "react";

import type { CollectionSummary } from "@/lib/api/library-contract";

import { buildCollectionTree, flattenCollectionTree } from "./collection-tree";

type TriggerVariant = "primary" | "subtle" | "sidebar";

interface CollectionDialogProps {
  mode: "create" | "rename";
  collections: CollectionSummary[];
  collection?: CollectionSummary;
  triggerLabel: string;
  triggerVariant?: TriggerVariant;
  defaultParentCollectionId?: string | null;
}

const TRIGGER_STYLES: Record<TriggerVariant, string> = {
  primary:
    "inline-flex h-9 items-center justify-center rounded-lg bg-[var(--auth-primary)] px-3 text-sm font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:outline-none",
  subtle:
    "inline-flex h-9 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none",
  sidebar:
    "flex h-9 w-full items-center justify-center rounded-lg border border-dashed border-[var(--auth-border)] px-3 text-sm font-medium text-[var(--auth-muted)] transition-colors hover:border-[var(--auth-primary)] hover:text-[var(--auth-primary)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none",
};

const FIELD_CLASS =
  "h-10 w-full rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm text-[var(--auth-text)] outline-none transition-colors focus:border-[var(--auth-primary)] focus:ring-2 focus:ring-[var(--auth-primary-soft)]";

function errorMessage(code: unknown, message: unknown): string {
  if (code === "A0450") {
    return "同层级已存在同名题集，请换一个名称";
  }
  return typeof message === "string" && message.length > 0
    ? message
    : "操作失败，请稍后重试";
}

export function CollectionDialog({
  mode,
  collections,
  collection,
  triggerLabel,
  triggerVariant = "subtle",
  defaultParentCollectionId = null,
}: CollectionDialogProps) {
  const router = useRouter();
  const titleId = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parentOptions = flattenCollectionTree(buildCollectionTree(collections));

  function openDialog() {
    setName(mode === "rename" ? (collection?.name ?? "") : "");
    setParentId(defaultParentCollectionId ?? "");
    setError(null);
    setPending(false);
    setOpen(true);
  }

  function closeDialog() {
    if (!pending) {
      setOpen(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }

    const trimmed = name.trim();
    if (trimmed.length === 0 || trimmed.length > 64) {
      setError("请输入 1–64 个字符的题集名称");
      return;
    }

    setPending(true);
    setError(null);
    try {
      const url =
        mode === "create"
          ? "/api/library/collections"
          : `/api/library/collections/${encodeURIComponent(collection?.collectionId ?? "")}`;
      const payload =
        mode === "create"
          ? { name: trimmed, parentCollectionId: parentId === "" ? null : parentId }
          : { name: trimmed };
      const response = await fetch(url, {
        method: mode === "create" ? "POST" : "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (response.status === 401) {
        window.location.assign("/");
        return;
      }

      const data = (await response.json().catch(() => null)) as Record<string, unknown> | null;
      if (!response.ok) {
        setError(errorMessage(data?.code, data?.message));
        return;
      }

      setOpen(false);
      router.refresh();
    } catch {
      setError("无法连接服务器，请稍后重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <button type="button" onClick={openDialog} className={TRIGGER_STYLES[triggerVariant]}>
        {triggerLabel}
      </button>

      {open ? (
        <div
          role="presentation"
          onMouseDown={closeDialog}
          className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--auth-brand-deep)]/40 px-4"
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            onMouseDown={(event) => event.stopPropagation()}
            className="w-full max-w-[420px] rounded-2xl border border-[var(--auth-border)] bg-white p-5 shadow-xl"
          >
            <h2 id={titleId} className="text-lg leading-7 font-semibold text-[var(--auth-text)]">
              {mode === "create" ? "新建题集" : "题集改名"}
            </h2>
            <p className="mt-1 text-sm leading-6 text-[var(--auth-muted)]">
              {mode === "create"
                ? "可选择父级创建多层题集；待整理不能作为父级。"
                : "仅修改当前题集名称，不改变层级与题目归属。"}
            </p>

            <form onSubmit={submit} className="mt-4 flex flex-col gap-3">
              <label className="flex flex-col gap-1.5 text-sm font-medium text-[var(--auth-text)]">
                名称
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  maxLength={64}
                  autoFocus
                  className={FIELD_CLASS}
                  placeholder="例如：微积分"
                />
              </label>

              {mode === "create" ? (
                <label className="flex flex-col gap-1.5 text-sm font-medium text-[var(--auth-text)]">
                  父级
                  <select
                    value={parentId}
                    onChange={(event) => setParentId(event.target.value)}
                    className={FIELD_CLASS}
                  >
                    <option value="">根级题集</option>
                    {parentOptions.map((item) => (
                      <option key={item.collectionId} value={item.collectionId}>
                        {`${"　".repeat(item.depth)}${item.name}`}
                      </option>
                    ))}
                  </select>
                </label>
              ) : null}

              {error === null ? null : (
                <p role="alert" className="text-sm leading-6 text-[var(--auth-danger)]">
                  {error}
                </p>
              )}

              <div className="mt-2 flex items-center justify-end gap-2">
                <button
                  type="button"
                  onClick={closeDialog}
                  disabled={pending}
                  className={TRIGGER_STYLES.subtle}
                >
                  取消
                </button>
                <button
                  type="submit"
                  disabled={pending}
                  className={`${TRIGGER_STYLES.primary} disabled:cursor-not-allowed disabled:opacity-60`}
                >
                  {pending ? "保存中…" : "保存"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}
    </>
  );
}