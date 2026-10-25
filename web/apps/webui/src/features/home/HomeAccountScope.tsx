"use client";

import { useToast } from "@betterlearning/ui-feedback";
import { useEffect } from "react";

export function HomeAccountScope({ accountId }: { accountId: string }) {
  const { onScopeChange } = useToast();
  useEffect(() => {
    onScopeChange(`auth:profile:${accountId}`);
  }, [accountId, onScopeChange]);
  return null;
}
