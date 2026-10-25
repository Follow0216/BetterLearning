"use client";

import { ToastProvider, useToast } from "@betterlearning/ui-feedback";
import { usePathname, useSearchParams } from "next/navigation";
import { createContext, Suspense, useContext, useEffect, useState, type ReactNode } from "react";

interface ReadyRoute {
  pathname: string;
  key: string;
}

const ReadyRouteContext = createContext<ReadyRoute | null>(null);

function RouteScope({ onReady }: { onReady: (route: ReadyRoute) => void }) {
  const pathname = usePathname();
  const params = useSearchParams();
  const { onScopeChange } = useToast();
  // Only public library context, never arbitrary URL tokens or credentials.
  const context = ["view", "collectionId", "collection", "page", "tab", "turnId"]
    .map((key) => `${key}:${params.get(key) ?? ""}`)
    .join("|");
  useEffect(() => {
    const key = `route:${pathname}:${context}`;
    onScopeChange(key);
    onReady({ pathname, key });
  }, [pathname, context, onScopeChange, onReady]);
  return null;
}

/** A destination must wait for its own route scope, including delayed hydration. */
export function useFeedbackRouteScope(pathname: string): string | null {
  const route = useContext(ReadyRouteContext);
  const currentPathname = usePathname();
  return currentPathname === pathname && route?.pathname === currentPathname ? route.key : null;
}

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [readyRoute, setReadyRoute] = useState<ReadyRoute | null>(null);
  return (
    <ToastProvider>
      <ReadyRouteContext.Provider value={readyRoute}>
        <Suspense fallback={null}>
          <RouteScope onReady={setReadyRoute} />
        </Suspense>
        {children}
      </ReadyRouteContext.Provider>
    </ToastProvider>
  );
}
