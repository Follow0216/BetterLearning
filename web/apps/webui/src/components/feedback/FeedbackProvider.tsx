"use client";

import { ToastProvider, useToast } from "@betterlearning/ui-feedback";
import { usePathname, useSearchParams } from "next/navigation";
import { Suspense, useEffect, type ReactNode } from "react";

function RouteScope() {
  const pathname = usePathname();
  const params = useSearchParams();
  const { onScopeChange } = useToast();
  // Only public library context, never arbitrary URL tokens or credentials.
  const context = ["view", "collectionId", "collection", "page", "tab", "turnId"]
    .map((key) => `${key}:${params.get(key) ?? ""}`)
    .join("|");
  useEffect(() => {
    onScopeChange(`route:${pathname}:${context}`);
  }, [pathname, context, onScopeChange]);
  return null;
}

export function FeedbackProvider({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <Suspense fallback={null}>
        <RouteScope />
      </Suspense>
      {children}
    </ToastProvider>
  );
}
