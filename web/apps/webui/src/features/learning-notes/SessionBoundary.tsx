"use client";

import { useEffect, useState, type ReactNode } from "react";

/** 所有私有内容只驻留当前页面；离开、退出和跨标签退出均销毁子树。 */
export function SessionBoundary({ children }: { children: ReactNode }) {
  const [active, setActive] = useState(true);
  useEffect(() => {
    const end = () => setActive(false);
    const show = (event: PageTransitionEvent) => {
      if (event.persisted) window.location.reload();
    };
    const channel = new BroadcastChannel("bl-session");
    channel.onmessage = () => {
      end();
      window.location.replace("/");
    };
    window.addEventListener("bl:session-ended", end);
    window.addEventListener("pagehide", end);
    window.addEventListener("pageshow", show);
    return () => {
      channel.close();
      window.removeEventListener("bl:session-ended", end);
      window.removeEventListener("pagehide", end);
      window.removeEventListener("pageshow", show);
    };
  }, []);
  return active ? children : <p role="status">登录状态已结束，正在返回账号页…</p>;
}
