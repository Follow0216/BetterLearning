"use client";

import { useToast } from "@betterlearning/ui-feedback";
import { useEffect } from "react";

import { useFeedbackRouteScope } from "@/components/feedback/FeedbackProvider";
import {
  discardAuthNavigationNotice,
  takeAuthNavigationNotice,
} from "@/features/auth/auth-navigation-notice";

export function HomeAccountScope({ accountId }: { accountId: string | null }) {
  const toast = useToast();
  const routeScope = useFeedbackRouteScope("/");
  useEffect(() => {
    if (routeScope === null) return;
    // StrictMode cancels its first setup before any record is consumed. The final
    // task runs after Host reattachment and only after RouteScope has initialized.
    const timer = setTimeout(() => {
      if (accountId === null) {
        discardAuthNavigationNotice();
        return;
      }
      toast.onScopeChange(`auth:profile:${accountId}`);
      if (takeAuthNavigationNotice({ event: "login", accountId }) === "login") {
        toast.show(
          { key: "auth:login", type: "success", message: "登录成功" },
          toast.captureScope(),
        );
      }
    }, 0);
    return () => clearTimeout(timer);
  }, [accountId, routeScope, toast]);
  return null;
}
