"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { useFeedbackRequest } from "@/components/feedback/use-feedback-request";

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
    "flex min-h-9 w-full items-center justify-start rounded-lg px-2 text-[13px] font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none",
};

const FIELD_CLASS =
  "h-10 w-full rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm text-[var(--auth-text)] outline-none transition-colors focus:border-[var(--auth-primary)] focus:ring-2 focus:ring-[var(--auth-primary-soft)]";

function errorMessage(code: unknown, message: unknown): string {
  if (code === "A0450") {
    return "同层级已存在同名题集，请换一个名称";
  }
  return typeof message === "string" && message.length > 0 ? message : "操作失败，请稍后重试";
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
  const { toast, begin } = useFeedbackRequest();
  const [errorAnnounced, setErrorAnnounced] = useState(false);
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parentOptions = flattenCollectionTree(buildCollectionTree(collections));

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog === null) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  function openDialog() {
    toast.dismiss(`collection:${mode}:${collection?.collectionId ?? "new"}`);
    setErrorAnnounced(false);
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

    setErrorAnnounced(false);
    const trimmed = name.trim();
    if (trimmed.length === 0 || trimmed.length > 64) {
      setError("请输入 1–64 个字符的题集名称");
      return;
    }

    const request = begin();
    if (!request) return;
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
        signal: request.signal,
        method: mode === "create" ? "POST" : "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!request.isCurrent()) return;
      if (response.status === 401) {
        toast.clearScope();
        router.replace("/");
        return;
      }

      const data = (await response.json().catch(() => null)) as Record<string, unknown> | null;
      if (!request.isCurrent()) return;
      if (!response.ok) {
        setError(errorMessage(data?.code, data?.message));
        if (response.status >= 500) {
          setErrorAnnounced(true);
          toast.show(
            {
              key: `collection:${mode}:${collection?.collectionId ?? "new"}`,
              type: "error",
              message: "题集保存失败",
              description: "输入已保留，请稍后重试",
            },
            request.scope,
          );
        }
        return;
      }

      setOpen(false);
      toast.show(
        {
          key: `collection:${mode}:${collection?.collectionId ?? "new"}`,
          type: "success",
          message: mode === "create" ? "题集已创建" : "题集已改名",
        },
        request.scope,
      );
      router.refresh();
    } catch {
      if (!request.isCurrent()) return;
      setError("无法连接服务器，请稍后重试");
      setErrorAnnounced(true);
      toast.show(
        {
          key: `collection:${mode}:${collection?.collectionId ?? "new"}`,
          type: "error",
          message: "题集保存失败",
          description: "输入已保留，请稍后重试",
        },
        request.scope,
      );
    } finally {
      if (request.finish()) setPending(false);
    }
  }

  return (
    <>
      <button type="button" onClick={openDialog} className={TRIGGER_STYLES[triggerVariant]}>
        {triggerVariant === "sidebar" ? (
          <span aria-hidden="true" className="mr-2 text-lg leading-none">
            +
          </span>
        ) : null}
        {triggerLabel}
      </button>
      <dialog
        ref={dialogRef}
        aria-labelledby={titleId}
        aria-describedby={`${titleId}-description`}
        aria-modal="true"
        onCancel={(event) => {
          event.preventDefault();
          closeDialog();
        }}
        onClose={() => setOpen(false)}
        onMouseDown={(event) => {
          if (event.target !== event.currentTarget) return;
          const bounds = event.currentTarget.getBoundingClientRect();
          if (
            event.clientX < bounds.left ||
            event.clientX > bounds.right ||
            event.clientY < bounds.top ||
            event.clientY > bounds.bottom
          )
            closeDialog();
        }}
        className="fixed inset-0 m-auto max-h-[90dvh] w-[calc(100%-32px)] max-w-[420px] overflow-y-auto rounded-xl border border-[var(--auth-border)] bg-white p-6 text-[var(--auth-text)] shadow-xl backdrop:bg-[var(--auth-brand-deep)]/40"
      >
        {open ? (
          <>
            <h2 id={titleId} className="text-xl leading-7 font-semibold">
              {mode === "create" ? "新建题集" : "题集改名"}
            </h2>
            <p
              id={`${titleId}-description`}
              className="mt-2 text-sm leading-6 text-[var(--auth-muted)]"
            >
              {mode === "create" ? "为题集命名，并选择它所在的位置。" : "输入新的题集名称。"}
            </p>
            <form onSubmit={submit} className="mt-5 flex flex-col gap-4">
              <label className="flex flex-col gap-2 text-sm font-medium">
                名称
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  maxLength={64}
                  autoFocus
                  disabled={pending}
                  className={FIELD_CLASS}
                  placeholder="例如：微积分"
                />
                <span className="text-xs font-normal text-[var(--auth-muted)]">1–64 个字符</span>
              </label>
              {mode === "create" ? (
                <label className="flex flex-col gap-2 text-sm font-medium">
                  所在位置
                  <select
                    value={parentId}
                    onChange={(event) => setParentId(event.target.value)}
                    disabled={pending}
                    className={FIELD_CLASS}
                  >
                    <option value="">根级题集</option>
                    {parentOptions.map((item) => (
                      <option
                        key={item.collectionId}
                        value={item.collectionId}
                      >{`${"　".repeat(item.depth)}${item.name}`}</option>
                    ))}
                  </select>
                </label>
              ) : null}
              {error === null ? null : (
                <p
                  role={errorAnnounced ? undefined : "alert"}
                  className="text-sm leading-6 text-[var(--auth-danger)]"
                >
                  {error}
                </p>
              )}
              <div className="mt-2 flex items-center justify-end gap-2 border-t border-[var(--auth-border)] pt-4">
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
          </>
        ) : null}
      </dialog>
    </>
  );
}
