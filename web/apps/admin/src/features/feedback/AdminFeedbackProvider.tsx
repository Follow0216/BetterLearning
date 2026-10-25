"use client";

import { ToastProvider, useToast } from "@betterlearning/ui-feedback";
import { usePathname } from "next/navigation";
import { useEffect, type ReactNode } from "react";

function AdminToastScope() {
  const pathname = usePathname();
  const { onScopeChange } = useToast();

  useEffect(() => {
    onScopeChange(`admin:route:${pathname}`);
  }, [onScopeChange, pathname]);

  return null;
}

export function AdminFeedbackProvider({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <AdminToastScope />
      {children}
    </ToastProvider>
  );
}
