"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { ToastStore, type ToastApi, type ToastItem } from "./toast-store";

export type { ToastApi, ToastOptions, ToastType } from "./toast-store";

const ToastContext = createContext<ToastApi | null>(null);
const subscribeHydration = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;

function ToastCard({ item, store }: { item: ToastItem; store: ToastStore }) {
  useEffect(() => store.present(item.key, item.revision), [item.key, item.revision, store]);
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
        aria-label={`关闭通知：${item.message}`}
        onClick={() => store.dismiss(item.key)}
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>
  );
}

function ToastHost({ store }: { store: ToastStore }) {
  const hydrated = useSyncExternalStore(subscribeHydration, clientSnapshot, serverSnapshot);
  const items = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot);
  useEffect(() => store.attachHost(), [store]);
  if (!hydrated) return null;
  return createPortal(
    <div className="bl-toast-host" aria-label="操作通知">
      {items.map((item) => (
        <ToastCard key={item.key} item={item} store={store} />
      ))}
    </div>,
    document.body,
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new ToastStore());
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
