"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  displayTime,
  errorMessage,
  highlightPercent,
  sourceLabels,
} from "@/features/learning-notes/learning-utils";
import styles from "@/features/learning-notes/learning.module.css";
import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { learningRequest, LearningApiError } from "@/lib/api/learning-v2-client";
import {
  citationAssetPath,
  type Citation,
  type CitationResolution,
} from "@/lib/api/learning-v2-contract";

interface LoadedCitation {
  resolution: CitationResolution;
  images: string[];
}

async function loadCitation(citation: Citation, signal: AbortSignal): Promise<LoadedCitation> {
  const { data } = await learningRequest<CitationResolution>(
    `citations/${citation.citationId}/resolve`,
    { signal },
  );
  const resolved = data.citation;
  if (
    resolved.citationId !== citation.citationId ||
    resolved.sourceId !== citation.sourceId ||
    resolved.version !== citation.version ||
    resolved.anchorId !== citation.anchorId ||
    resolved.sourceType !== citation.sourceType
  )
    throw new Error("引用版本校验失败，请刷新辅导记录");
  if (data.markdown !== undefined) {
    const bytes = new TextEncoder().encode(data.markdown.replace(/\r\n/g, "\n"));
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), (byte) =>
      byte.toString(16).padStart(2, "0"),
    ).join("");
    if (hash !== data.contentSha256) throw new Error("引用原文摘要不匹配，无法安全展示");
  }
  const images: string[] = [];
  try {
    for (const segment of data.handwritingLocator?.segments ?? []) {
      const response = await fetch(citationAssetPath(citation.citationId, segment.blockIndex), {
        cache: "no-store",
        credentials: "same-origin",
        signal,
        redirect: "error",
      });
      if (!response.ok) {
        if (response.status === 401) {
          window.dispatchEvent(new Event("bl:session-ended"));
          window.location.replace("/");
        }
        throw new LearningApiError(
          response.status,
          "",
          response.status === 404 ? "原引用已删除或失去访问权限" : "无法读取原版本图片，请重试",
          "",
        );
      }
      if (response.headers.get("content-type")?.split(";")[0] !== "image/png")
        throw new Error("引用图片格式错误");
      const url = URL.createObjectURL(await response.blob());
      images.push(url);
      const image = new Image();
      image.src = url;
      await image.decode();
      if (image.naturalWidth !== segment.pixelWidth || image.naturalHeight !== segment.pixelHeight)
        throw new Error("原图尺寸与引用定位不一致");
    }
    signal.throwIfAborted();
    return { resolution: data, images };
  } catch (error) {
    images.forEach(URL.revokeObjectURL);
    throw error;
  }
}

export function CitationDialog({ citation, onClose }: { citation: Citation; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const target = useRef<HTMLElement | null>(null);
  const [loaded, setLoaded] = useState<LoadedCitation | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [zoom, setZoom] = useState(1);
  const close = useCallback(() => {
    if (history.state?.blCitation === citation.citationId) history.back();
    else onClose();
  }, [citation.citationId, onClose]);

  useEffect(() => {
    const previousFocus = document.activeElement;
    const element = dialog.current;
    history.pushState(
      { ...history.state, blCitation: citation.citationId },
      "",
      `${location.pathname}${location.search}#citation`,
    );
    const pop = () => onClose();
    window.addEventListener("popstate", pop);
    element?.showModal();
    return () => {
      window.removeEventListener("popstate", pop);
      element?.close();
      if (history.state?.blCitation === citation.citationId) {
        const { blCitation: _citation, ...rest } = history.state;
        void _citation;
        history.replaceState(rest, "", `${location.pathname}${location.search}`);
      }
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [citation.citationId, onClose]);

  useEffect(() => {
    const controller = new AbortController();
    let current: LoadedCitation | null = null;
    loadCitation(citation, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          result.images.forEach(URL.revokeObjectURL);
          return;
        }
        current = result;
        setLoaded(result);
        setError("");
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setLoaded(null);
          setError(errorMessage(error));
        }
      });
    return () => {
      controller.abort();
      current?.images.forEach(URL.revokeObjectURL);
    };
  }, [citation, revision]);

  useEffect(() => {
    if (!loaded) return;
    const frame = requestAnimationFrame(() => {
      target.current?.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
    });
    return () => cancelAnimationFrame(frame);
  }, [loaded, zoom]);

  const resolution = loaded?.resolution;
  const markdown = resolution?.markdown;
  const locator = resolution?.markdownLocator;
  const segments = resolution?.handwritingLocator?.segments ?? [];
  const canvasX = Math.min(...segments.map((segment) => segment.blockWorldBounds.x));
  const canvasY = Math.min(...segments.map((segment) => segment.blockWorldBounds.y));
  const canvasWidth =
    Math.max(
      ...segments.map((segment) => segment.blockWorldBounds.x + segment.blockWorldBounds.width),
    ) - canvasX;
  const canvasHeight =
    Math.max(
      ...segments.map((segment) => segment.blockWorldBounds.y + segment.blockWorldBounds.height),
    ) - canvasY;
  return (
    <dialog
      className={styles.dialog}
      ref={dialog}
      aria-labelledby="citation-title"
      aria-describedby="citation-description"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          const rect = event.currentTarget.getBoundingClientRect();
          if (
            event.clientX < rect.left ||
            event.clientX > rect.right ||
            event.clientY < rect.top ||
            event.clientY > rect.bottom
          )
            close();
        }
      }}
    >
      <div className={styles.toolbar}>
        <h2 id="citation-title" className="mr-auto text-lg font-semibold">
          引用 {citation.label}
        </h2>
        <button autoFocus className={styles.button} onClick={close}>
          关闭引用
        </button>
      </div>
      <p id="citation-description" className={styles.muted}>
        展示被引用时保存的版本；黄色区域为引用段落、公式或手写步骤。
      </p>
      {!loaded && !error && <p role="status">正在验证引用并加载原文…</p>}
      {error && (
        <div role="alert">
          <p className={styles.error}>{error}</p>
          <button
            className={styles.button}
            onClick={() => {
              setError("");
              setRevision(revision + 1);
            }}
          >
            重新验证引用
          </button>
        </div>
      )}
      {resolution && (
        <>
          <h3 className="mt-4 font-semibold">{resolution.sourceTitle}</h3>
          <p className={styles.muted}>
            {sourceLabels[resolution.citation.sourceType]}
            {resolution.answerState
              ? ` · ${resolution.answerState === "DRAFT" ? "草稿" : "已完成（不代表正确）"}`
              : ""}{" "}
            · 被引版本 {resolution.citation.version} · 当前版本 {resolution.currentVersion}
          </p>
          <p className={styles.muted}>
            来源创建于 {displayTime(resolution.sourceCreatedAt)} · 被引版本保存于{" "}
            {displayTime(resolution.sourceVersionCreatedAt)}
          </p>
          {resolution.currentVersion !== resolution.citation.version && (
            <p className={styles.warning}>
              来源已有新版本，当前显示的是版本 {resolution.citation.version} 的原始证据。
            </p>
          )}
          {markdown !== undefined && locator && (
            <div className={styles.highlight}>
              <p className={styles.muted}>精确引用原文</p>
              <pre className={styles.raw} aria-label="精确引用范围">
                {locator.startOffset > 64 ? "…" : ""}
                {markdown.slice(Math.max(0, locator.startOffset - 64), locator.startOffset)}
                <mark
                  ref={(element) => {
                    target.current = element;
                  }}
                  data-citation-target="markdown"
                >
                  {markdown.slice(locator.startOffset, locator.endOffset)}
                </mark>
                {markdown.slice(locator.endOffset, locator.endOffset + 64)}
                {markdown.length > locator.endOffset + 64 ? "…" : ""}
              </pre>
              <h4 className="mt-3 text-sm font-medium">段落与公式上下文</h4>
              <QuestionMarkdown bodyMarkdown={markdown} ariaLabel="被引用的原文段落或公式" />
            </div>
          )}
          {resolution.handwritingLocator && (
            <>
              <div className={`${styles.toolbar} mt-4`}>
                <button
                  className={styles.button}
                  disabled={zoom <= 0.5}
                  onClick={() => setZoom(Math.max(0.5, zoom - 0.25))}
                >
                  缩小
                </button>
                <span aria-live="polite">{Math.round(zoom * 100)}%</span>
                <button
                  className={styles.button}
                  disabled={zoom >= 2}
                  onClick={() => setZoom(Math.min(2, zoom + 0.25))}
                >
                  放大
                </button>
                <p className={styles.muted}>
                  原图上下文 · {resolution.handwritingLocator.segments.length} 个图块
                </p>
              </div>
              <div className={styles.assetViewport} tabIndex={0} aria-label="可滚动的手写步骤原图">
                <div
                  className={styles.canvas}
                  style={{ width: canvasWidth * zoom, height: canvasHeight * zoom }}
                >
                  {segments.map((segment, index) => (
                    <figure
                      key={`${segment.blockIndex}-${index}`}
                      className={styles.asset}
                      style={{
                        left: (segment.blockWorldBounds.x - canvasX) * zoom,
                        top: (segment.blockWorldBounds.y - canvasY) * zoom,
                        width: segment.blockWorldBounds.width * zoom,
                        height: segment.blockWorldBounds.height * zoom,
                      }}
                    >
                      <figcaption className="sr-only">图块 {segment.blockIndex + 1}</figcaption>
                      {/* 原始授权 blob 必须按 manifest 实际像素尺寸解码；不经过 Next 图片缓存。 */}
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={loaded.images[index]}
                        width={segment.pixelWidth}
                        height={segment.pixelHeight}
                        alt={`引用手写步骤，图块 ${segment.blockIndex + 1}`}
                      />
                      <div
                        ref={
                          index === 0
                            ? (element) => {
                                target.current = element;
                              }
                            : undefined
                        }
                        data-citation-target="handwriting"
                        className={styles.region}
                        style={highlightPercent(
                          segment.blockWorldBounds,
                          segment.regionWorldBounds,
                        )}
                      />
                    </figure>
                  ))}
                </div>
              </div>
            </>
          )}
        </>
      )}
    </dialog>
  );
}
