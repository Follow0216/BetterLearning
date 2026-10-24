"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";

import { BlIcon } from "@/components/icons/BlIcon";

export function RefreshButton() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();

  return (
    <button
      type="button"
      onClick={() => {
        startTransition(() => {
          router.refresh();
        });
      }}
      disabled={pending}
      aria-busy={pending}
      className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-text)] transition-colors hover:bg-[var(--auth-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
    >
      <BlIcon name="refresh" size={15} />
      {pending ? "刷新中…" : "刷新"}
    </button>
  );
}