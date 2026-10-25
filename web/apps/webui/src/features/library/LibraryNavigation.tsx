"use client";

import Link from "next/link";
import { useEffect, type ReactNode } from "react";

const SCROLL_KEY_PREFIX = "betterlearning:library-scroll:";

export function QuestionListLink({
  href,
  sourceHref,
  className,
  children,
}: {
  href: string;
  sourceHref: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Link
      href={href}
      className={className}
      onClick={(event) => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        try {
          sessionStorage.setItem(`${SCROLL_KEY_PREFIX}${sourceHref}`, String(window.scrollY));
        } catch {
          // Navigation still works when browser storage is unavailable.
        }
      }}
    >
      {children}
    </Link>
  );
}

export function LibraryScrollRestoration({ href, restore }: { href: string; restore: boolean }) {
  useEffect(() => {
    if (!restore) return;
    let frame = 0;
    try {
      const key = `${SCROLL_KEY_PREFIX}${href}`;
      const stored = sessionStorage.getItem(key);
      const scrollY = stored === null ? 0 : Number(stored);
      if (Number.isFinite(scrollY) && scrollY >= 0) {
        frame = requestAnimationFrame(() => window.scrollTo({ top: scrollY, behavior: "instant" }));
      }
      window.history.replaceState(window.history.state, "", href);
    } catch {
      // A blocked storage API must not prevent reading the library.
    }
    return () => cancelAnimationFrame(frame);
  }, [href, restore]);
  return null;
}
