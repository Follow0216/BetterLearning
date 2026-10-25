"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { KeyboardEvent, PointerEvent } from "react";

import {
  calculateAnswerGeometry,
  clampAnswerScale,
  fitAnswerView,
  panAnswerView,
  zoomAnswerViewAt,
} from "./answer-tile-geometry";
import type {
  AnswerTileBounds,
  AnswerTileGeometry,
  AnswerTileImage,
  AnswerViewTransform,
} from "./answer-tile-geometry";
import styles from "./answer-tile-viewer.module.css";

export interface AnswerTileViewerProps {
  documentKey: string;
  bounds: AnswerTileBounds;
  tiles: readonly AnswerTileImage[];
  className?: string;
  onTileError?: (index: number) => void;
  onRetry?: () => void;
}

interface Point {
  x: number;
  y: number;
}
interface FrameProps extends AnswerTileViewerProps {
  geometry: AnswerTileGeometry;
}

function pointerCenter(points: Map<number, Point>): Point {
  const values = [...points.values()];
  return values.length === 1
    ? values[0]
    : {
        x: (values[0].x + values[1].x) / 2,
        y: (values[0].y + values[1].y) / 2,
      };
}

function pointerDistance(points: Map<number, Point>): number {
  const values = [...points.values()];
  return values.length < 2 ? 0 : Math.hypot(values[1].x - values[0].x, values[1].y - values[0].y);
}

/** The resource key remounts image/gesture state; it is not a history version. */
export function AnswerTileViewer(props: AnswerTileViewerProps) {
  const geometry = calculateAnswerGeometry(props.bounds, [...props.tiles]);
  const resourceKey = JSON.stringify([props.documentKey, props.bounds, props.tiles]);
  return <AnswerTileViewerFrame key={resourceKey} {...props} geometry={geometry} />;
}

function AnswerTileViewerFrame({ geometry, className, onTileError, onRetry }: FrameProps) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const pointers = useRef(new Map<number, Point>());
  const currentView = useRef<AnswerViewTransform>({ scale: 1, offsetX: 0, offsetY: 0 });
  const viewportSize = useRef({ width: 0, height: 0 });
  const fitScale = useRef(1);
  const live = useRef(true);
  const [view, setView] = useState<AnswerViewTransform>({ scale: 1, offsetX: 0, offsetY: 0 });
  const [measured, setMeasured] = useState(false);
  const [loaded, setLoaded] = useState<number[]>([]);
  const [failed, setFailed] = useState<number[]>([]);
  const hintId = useId();
  const empty = geometry.tiles.length === 0 && geometry.error.length === 0;
  const usable = !empty && geometry.error.length === 0;

  const applyView = useCallback((next: AnswerViewTransform) => {
    if (
      !Number.isFinite(next.scale) ||
      next.scale <= 0 ||
      !Number.isFinite(next.offsetX) ||
      !Number.isFinite(next.offsetY)
    )
      return;
    currentView.current = next;
    setView(next);
  }, []);

  const resetView = useCallback(() => {
    const size = viewportSize.current;
    const next = fitAnswerView(geometry.bounds, size.width, size.height);
    fitScale.current = next.scale;
    applyView(next);
  }, [geometry.bounds, applyView]);

  useEffect(() => {
    live.current = true;
    const activePointers = pointers.current;
    return () => {
      live.current = false;
      activePointers.clear();
    };
  }, []);

  useEffect(() => {
    const element = viewportRef.current;
    if (!element || !usable) return;
    const observer = new ResizeObserver(() => {
      const width = element.clientWidth;
      const height = element.clientHeight;
      if (
        width <= 0 ||
        height <= 0 ||
        (width === viewportSize.current.width && height === viewportSize.current.height)
      )
        return;
      viewportSize.current = { width, height };
      pointers.current.clear();
      setMeasured(true);
      resetView();
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [usable, resetView]);

  useEffect(() => {
    const element = viewportRef.current;
    if (!element || !usable) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const box = element.getBoundingClientRect();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? element.clientHeight : 1;
      const factor = Math.exp(-Math.max(-500, Math.min(500, event.deltaY * unit)) * 0.002);
      const nextScale = clampAnswerScale(currentView.current.scale * factor, fitScale.current);
      applyView(
        zoomAnswerViewAt(
          currentView.current,
          nextScale,
          event.clientX - box.left,
          event.clientY - box.top,
        ),
      );
    };
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => element.removeEventListener("wheel", onWheel);
  }, [usable, applyView]);

  function zoom(factor: number) {
    const size = viewportSize.current;
    const nextScale = clampAnswerScale(currentView.current.scale * factor, fitScale.current);
    applyView(zoomAnswerViewAt(currentView.current, nextScale, size.width / 2, size.height / 2));
  }

  function point(event: PointerEvent<HTMLDivElement>): Point {
    const box = event.currentTarget.getBoundingClientRect();
    return { x: event.clientX - box.left, y: event.clientY - box.top };
  }

  function startPointer(event: PointerEvent<HTMLDivElement>) {
    if (
      !usable ||
      (event.pointerType === "mouse" && event.button !== 0) ||
      pointers.current.size >= 2
    )
      return;
    event.preventDefault();
    event.currentTarget.focus({ preventScroll: true });
    event.currentTarget.setPointerCapture(event.pointerId);
    pointers.current.set(event.pointerId, point(event));
  }

  function movePointer(event: PointerEvent<HTMLDivElement>) {
    const active = pointers.current;
    if (!active.has(event.pointerId)) return;
    event.preventDefault();
    const previousCenter = pointerCenter(active);
    const previousDistance = pointerDistance(active);
    active.set(event.pointerId, point(event));
    const center = pointerCenter(active);
    let next = panAnswerView(
      currentView.current,
      center.x - previousCenter.x,
      center.y - previousCenter.y,
    );
    const distance = pointerDistance(active);
    if (active.size === 2 && previousDistance > 0 && distance > 0) {
      const scale = clampAnswerScale((next.scale * distance) / previousDistance, fitScale.current);
      next = zoomAnswerViewAt(next, scale, center.x, center.y);
    }
    applyView(next);
  }

  function finishPointer(event: PointerEvent<HTMLDivElement>) {
    pointers.current.delete(event.pointerId);
  }

  function handleKey(event: KeyboardEvent<HTMLDivElement>) {
    if (!usable) return;
    const delta = event.shiftKey ? 120 : 40;
    switch (event.key) {
      case "ArrowLeft":
        applyView(panAnswerView(currentView.current, delta, 0));
        break;
      case "ArrowRight":
        applyView(panAnswerView(currentView.current, -delta, 0));
        break;
      case "ArrowUp":
        applyView(panAnswerView(currentView.current, 0, delta));
        break;
      case "ArrowDown":
        applyView(panAnswerView(currentView.current, 0, -delta));
        break;
      case "+":
      case "=":
        zoom(1.25);
        break;
      case "-":
      case "_":
        zoom(0.8);
        break;
      case "Home":
        resetView();
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  function imageLoaded(index: number) {
    if (!live.current) return;
    setLoaded((previous) => (previous.includes(index) ? previous : [...previous, index]));
    setFailed((previous) => previous.filter((value) => value !== index));
  }

  function imageFailed(index: number) {
    if (!live.current) return;
    setFailed((previous) => (previous.includes(index) ? previous : [...previous, index]));
    onTileError?.(index);
  }

  const state = empty
    ? "empty"
    : geometry.error || failed.length > 0
      ? "error"
      : loaded.length < geometry.tiles.length
        ? "loading"
        : "ready";
  const percent = (view.scale * 100).toLocaleString("zh-CN", { maximumSignificantDigits: 3 });

  return (
    <section
      className={[styles.viewer, className].filter(Boolean).join(" ")}
      data-viewer-state={state}
    >
      <div className={styles.toolbar} role="group" aria-label="作答查看工具">
        <span className={styles.title}>作答图像</span>
        <span className={styles.spacer} />
        <button
          type="button"
          disabled={!usable || !measured}
          onClick={() => zoom(0.8)}
          aria-label="缩小作答"
        >
          −
        </button>
        <output className={styles.scale} aria-label="当前缩放">
          {percent}%
        </output>
        <button
          type="button"
          disabled={!usable || !measured}
          onClick={() => zoom(1.25)}
          aria-label="放大作答"
        >
          +
        </button>
        <button type="button" disabled={!usable || !measured} onClick={resetView}>
          适应全部
        </button>
      </div>
      <div
        ref={viewportRef}
        className={styles.viewport}
        tabIndex={usable ? 0 : -1}
        role="region"
        aria-label="作答图像"
        aria-describedby={hintId}
        onPointerDown={startPointer}
        onPointerMove={movePointer}
        onPointerUp={finishPointer}
        onPointerCancel={finishPointer}
        onLostPointerCapture={finishPointer}
        onKeyDown={handleKey}
      >
        {usable ? (
          <div
            className={styles.canvas}
            style={{
              visibility: measured ? "visible" : "hidden",
              transform: `translate(${view.offsetX}px, ${view.offsetY}px) scale(${view.scale})`,
            }}
          >
            {geometry.tiles.map((tile) => (
              // These authenticated composite blocks must keep their logical coordinates and original bytes.
              // eslint-disable-next-line @next/next/no-img-element
              <img
                key={tile.index}
                src={tile.source}
                alt={`作答图块 ${tile.index + 1}`}
                draggable={false}
                className={styles.tile}
                data-tile-index={tile.index}
                style={{ left: tile.x, top: tile.y, width: tile.width, height: tile.height }}
                onLoad={() => imageLoaded(tile.index)}
                onError={() => imageFailed(tile.index)}
              />
            ))}
          </div>
        ) : (
          <p className={styles.centerState} role={geometry.error ? "alert" : "status"}>
            {geometry.error || "暂无已保存的作答"}
          </p>
        )}
        {usable && loaded.length + failed.length < geometry.tiles.length ? (
          <span className={styles.loading} role="status">
            正在加载作答图像… {loaded.length}/{geometry.tiles.length}
          </span>
        ) : null}
      </div>
      {geometry.error || failed.length > 0 ? (
        <div className={styles.error} role="alert">
          <span>{geometry.error || "部分作答图像加载失败，请重新加载。"}</span>
          {onRetry ? (
            <button type="button" onClick={onRetry}>
              重新加载
            </button>
          ) : null}
        </div>
      ) : null}
      <p id={hintId} className={styles.hint}>
        拖动平移，双指或滚轮缩放；方向键移动，Home 适应全部。
      </p>
    </section>
  );
}
