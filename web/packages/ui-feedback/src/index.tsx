"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  useRef,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { ToastStore, type ToastApi, type ToastItem } from "./toast-store";

export type { ToastApi, ToastOptions, ToastType } from "./toast-store";

const ToastContext = createContext<ToastApi | null>(null);
function subscribePortalTarget(listener: () => void) {
  const observer = new MutationObserver(listener);
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["open"],
  });
  return () => observer.disconnect();
}

// An existing native dialog makes its outside content inert. Reuse its layer so
// notifications stay visible and dismissible without opening another modal.
const portalTarget = () => document.querySelector("dialog[open]") ?? document.body;
const serverPortalTarget = () => null;

function ToastCard({ item, store }: { item: ToastItem; store: ToastStore }) {
  useEffect(() => {
    store.present(item.key, item.revision);
    return () => {
      store.setPaused(item.key, "hover", false);
      store.setPaused(item.key, "focus", false);
    };
  }, [item.key, item.revision, store]);
  return (
    <div
      className="bl-toast"
      data-type={item.type}
      onPointerEnter={() => store.setPaused(item.key, "hover", true)}
      onPointerLeave={() => store.setPaused(item.key, "hover", false)}
      onFocusCapture={() => store.setPaused(item.key, "focus", true)}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          store.setPaused(item.key, "focus", false);
        }
      }}
    >
      <span className="bl-toast__icon" aria-hidden="true">
        {item.type === "success" ? "✓" : item.type === "error" ? "!" : "i"}
      </span>
      <div
        className="bl-toast__content"
        role={item.type === "error" ? "alert" : "status"}
        aria-atomic="true"
      >
        <p className="bl-toast__message" data-description={Boolean(item.description)}>
          {item.message}
        </p>
        {item.description ? <p className="bl-toast__description">{item.description}</p> : null}
      </div>
      <button
        type="button"
        className="bl-toast__close"
        aria-label="关闭通知"
        onClick={() => store.dismiss(item.key)}
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>
  );
}

function ToastHost({ store }: { store: ToastStore }) {
  const target = useSyncExternalStore(subscribePortalTarget, portalTarget, serverPortalTarget);
  const items = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot);
  useEffect(() => store.attachHost(), [store]);
  if (!target) return null;
  return createPortal(
    <div className="bl-toast-host" aria-label="操作通知">
      {items.map((item) => (
        <ToastCard key={item.key} item={item} store={store} />
      ))}
    </div>,
    target,
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new ToastStore());
  const lifecycle = useRef(0);
  useEffect(() => {
    const version = ++lifecycle.current;
    return () => {
      // StrictMode immediately sets up the same instance again. A real unmount
      // has no later setup and permanently disposes captured async callbacks.
      queueMicrotask(() => {
        if (lifecycle.current === version) store.dispose();
      });
    };
  }, [store]);
  return (
    <ToastContext.Provider value={store}>
      {children}
      <ToastHost store={store} />
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const toast = useContext(ToastContext);
  if (!toast) throw new Error("useToast 必须在 ToastProvider 内调用");
  return toast;
}
