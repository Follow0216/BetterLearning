"use client";

import { useEffect, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";

import { returnToAccount, subscribeSessionChanges } from "@/features/auth/session-events";

/** 会话世代变化立即销毁私有子树，触发其请求取消和 blob URL 清理。 */
export function SessionBoundary({ children }: { children: ReactNode }) {
  const [active, setActive] = useState(true);
  useEffect(() => {
    let ended = false;
    const hide = () => flushSync(() => setActive(false));
    const end = () => {
      if (ended) return;
      ended = true;
      hide();
      returnToAccount();
    };
    const unsubscribe = subscribeSessionChanges(end);
    const shown = (event: PageTransitionEvent) => {
      if (!ended && event.persisted) window.location.reload();
    };
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", shown);
    return () => {
      unsubscribe();
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", shown);
    };
  }, []);
  return active ? children : <p role="status">登录状态已结束，正在返回账号页…</p>;
}
