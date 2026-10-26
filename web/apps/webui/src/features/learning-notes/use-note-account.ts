"use client";

import { useEffect, useState } from "react";
import { flushSync } from "react-dom";

import {
  announceSessionChange,
  ensureSessionEpoch,
  readSessionEpoch,
  returnToAccount,
  subscribeSessionChanges,
} from "@/features/auth/session-events";
import { parseUserProfile } from "@/lib/api/auth-contract";

/** 身份核验完成前不访问任何本机笔记；换号/过期立即销毁持有正文的子树。 */
export function useNoteAccount() {
  const [accountId, setAccountId] = useState<string | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController(),
      epoch = ensureSessionEpoch();
    const unsubscribe = subscribeSessionChanges(() => {
      controller.abort();
      flushSync(() => setAccountId(null));
    });
    fetch("/api/auth/session", {
      cache: "no-store",
      credentials: "same-origin",
      headers: { Accept: "application/json" },
      signal: controller.signal,
    })
      .then(async (response) => {
        if (controller.signal.aborted || epoch !== readSessionEpoch()) return;
        if (response.status === 401) {
          announceSessionChange("ended");
          returnToAccount();
          return;
        }
        if (!response.ok) throw new Error("身份核验失败，请刷新后重试。");
        const data = (await response.json()) as { user: unknown };
        const user = parseUserProfile(data.user);
        if (!controller.signal.aborted && epoch === readSessionEpoch())
          setAccountId(user.accountId);
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setError("无法核验当前账号，暂不读取本机草稿，请刷新重试。");
      });
    return () => {
      controller.abort();
      unsubscribe();
    };
  }, []);
  return { accountId, error };
}
