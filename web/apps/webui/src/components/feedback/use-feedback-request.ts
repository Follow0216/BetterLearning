"use client";

import { useToast } from "@betterlearning/ui-feedback";
import { useCallback, useEffect, useRef } from "react";

/** Synchronous submit lock plus late-result guards; business state stays local. */
export function useFeedbackRequest() {
  const toast = useToast();
  const state = useRef({
    alive: true,
    busy: false,
    sequence: 0,
    controller: new AbortController(),
  });
  useEffect(() => {
    const current = state.current;
    current.alive = true;
    return () => {
      current.alive = false;
      current.sequence++;
      current.controller.abort();
    };
  }, []);

  const begin = useCallback(() => {
    const current = state.current;
    if (current.busy || !current.alive) return null;
    current.busy = true;
    current.controller.abort();
    current.controller = new AbortController();
    const sequence = ++current.sequence;
    const scope = toast.captureScope();
    return {
      scope,
      signal: current.controller.signal,
      isCurrent: () =>
        current.alive && current.sequence === sequence && toast.captureScope() === scope,
      finish: () => {
        if (!current.alive || current.sequence !== sequence) return false;
        current.busy = false;
        return true;
      },
    };
  }, [toast]);
  return { toast, begin };
}
