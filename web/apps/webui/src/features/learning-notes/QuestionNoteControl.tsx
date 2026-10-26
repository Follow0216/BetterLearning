"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";

import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { trapDialogTab } from "@/features/tutoring/dialog-focus";
import { learningRequest } from "@/lib/api/learning-v2-client";
import type {
  Citation,
  Note,
  NoteContextWrite,
  Notebook,
  NotebookList,
} from "@/lib/api/learning-v2-contract";

import { errorMessage, excerptDisplayMarkdown } from "./learning-utils";
import { NoteComposer } from "./NoteComposer";
import { useNoteAccount } from "./use-note-account";
import styles from "./learning.module.css";

function CaptureBody({
  questionId,
  questionMarkdown,
  initialContext,
  onClose,
  onProtectionChange,
  onLeaveGuardChange,
}: {
  questionId: string;
  questionMarkdown?: string;
  initialContext: NoteContextWrite;
  onClose: () => void;
  onProtectionChange: (blocked: boolean) => void;
  onLeaveGuardChange: (guard: (() => boolean) | null) => void;
}) {
  const { accountId, error } = useNoteAccount();
  const [notebooks, setNotebooks] = useState<Notebook[]>([]);
  const [loadError, setLoadError] = useState("");
  const [saved, setSaved] = useState<Note | null>(null);
  useEffect(() => {
    if (!accountId) return;
    const controller = new AbortController();
    learningRequest<NotebookList>("notebooks", { signal: controller.signal })
      .then(({ data }) => {
        if (!controller.signal.aborted) setNotebooks(data.items);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setLoadError(errorMessage(error));
      });
    return () => controller.abort();
  }, [accountId]);
  if (!accountId) return <p role={error ? "alert" : "status"}>{error || "正在核验账号…"}</p>;
  return (
    <>
      {questionMarkdown && (
        <details className={styles.captureQuestion} open>
          <summary>当前题目</summary>
          <QuestionMarkdown bodyMarkdown={questionMarkdown} ariaLabel="摘记时的题面上下文" />
        </details>
      )}
      {loadError && (
        <p role="alert" className={styles.error}>
          笔记本暂不可用：{loadError}；可先保存为未归类。
        </p>
      )}
      <NoteComposer
        accountId={accountId}
        noteId={null}
        initialContext={initialContext}
        notebooks={notebooks}
        onProtectionChange={onProtectionChange}
        onLeaveGuardChange={onLeaveGuardChange}
        onSaved={setSaved}
        onDeleted={onClose}
        onBack={onClose}
      />
      {saved && (
        <p className={styles.sourceContext}>
          已收入我的笔记。
          <Link href={`/workspace/notes?noteId=${saved.noteId}`}>打开这篇笔记 →</Link>
        </p>
      )}
      <Link className={styles.textButton} href={`/workspace/notes?questionId=${questionId}`}>
        查看这道题的全部笔记 →
      </Link>
    </>
  );
}

export function CaptureNoteDialog({
  questionId,
  questionMarkdown,
  context,
  onClose,
}: {
  questionId: string;
  questionMarkdown?: string;
  context: NoteContextWrite;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const unsafe = useRef(false);
  const leaveGuard = useRef<(() => boolean) | null>(null);
  const registerLeaveGuard = useCallback((guard: (() => boolean) | null) => {
    leaveGuard.current = guard;
  }, []);
  const [closeError, setCloseError] = useState("");
  const protect = useCallback((blocked: boolean) => {
    unsafe.current = blocked;
  }, []);
  const close = useCallback(() => {
    if (leaveGuard.current ? !leaveGuard.current() : unsafe.current) {
      setCloseError("本机保护不可用，请先保存到云端或复制内容；也可明确丢弃草稿后关闭。");
      return;
    }
    onClose();
  }, [onClose]);
  useEffect(() => {
    const element = dialog.current,
      previous = document.activeElement;
    const scrollX = window.scrollX,
      scrollY = window.scrollY,
      oldOverflow = document.body.style.overflow,
      oldPadding = document.body.style.paddingRight;
    const trap = (event: KeyboardEvent) => {
      if (element) trapDialogTab(event, element);
    };
    const scrollbar = window.innerWidth - document.documentElement.clientWidth;
    if (scrollbar > 0)
      document.body.style.paddingRight = `${parseFloat(getComputedStyle(document.body).paddingRight) + scrollbar}px`;
    document.body.style.overflow = "hidden";
    element?.addEventListener("keydown", trap, true);
    element?.showModal();
    return () => {
      element?.removeEventListener("keydown", trap, true);
      element?.close();
      document.body.style.overflow = oldOverflow;
      document.body.style.paddingRight = oldPadding;
      window.scrollTo(scrollX, scrollY);
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus({ preventScroll: true });
    };
  }, []);
  return (
    <dialog
      className={styles.captureDialog}
      ref={dialog}
      aria-labelledby="capture-note-heading"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
    >
      <header className={styles.captureHeader}>
        <h2 id="capture-note-heading">记下自己的理解</h2>
        <span className={styles.muted}>随题摘记</span>
        <button className={styles.button} onClick={close} aria-label="关闭随题摘记">
          关闭
        </button>
      </header>
      <div className={styles.captureContent}>
        {closeError && (
          <p role="alert" className={styles.error}>
            {closeError}
          </p>
        )}
        <CaptureBody
          questionId={questionId}
          questionMarkdown={questionMarkdown}
          initialContext={context}
          onClose={close}
          onProtectionChange={protect}
          onLeaveGuardChange={registerLeaveGuard}
        />
      </div>
    </dialog>
  );
}

export function QuestionNoteControl({
  questionId,
  questionMarkdown,
}: {
  questionId: string;
  questionMarkdown: string;
}) {
  const [opened, setOpened] = useState(false);
  const close = useCallback(() => setOpened(false), []);
  return (
    <>
      <button
        type="button"
        className={`${styles.button} ${styles.primary}`}
        onClick={() => setOpened(true)}
      >
        记一笔
      </button>
      {opened && (
        <CaptureNoteDialog
          questionId={questionId}
          questionMarkdown={questionMarkdown}
          context={{ questionId, notebookId: null, excerpt: null }}
          onClose={close}
        />
      )}
    </>
  );
}

export function TutorExcerptControl({
  questionId,
  resultId = null,
  turnId = null,
  bodyMarkdown,
  citations = [],
  onCitationClick,
}: {
  citations?: Citation[];
  onCitationClick?: (citationId: string) => void;
  questionId: string;
  resultId?: string | null;
  turnId?: string | null;
  bodyMarkdown: string;
}) {
  const [text, setText] = useState<string | null>(null);
  const close = useCallback(() => setText(null), []);
  const paragraphs = bodyMarkdown
    .replace(/\r\n/g, "\n")
    .split(/\n{2,}/)
    .filter((part) => part.trim());
  return (
    <>
      <details className={styles.previewDetails}>
        <summary>摘录辅导段落到笔记</summary>
        <p>先选定原文段落，再写下自己的理解。摘录保留当前辅导出处。</p>
        <ol className={styles.excerptChoices}>
          {paragraphs.map((paragraph, index) => (
            <li key={index} className={styles.row}>
              <QuestionMarkdown
                bodyMarkdown={excerptDisplayMarkdown(
                  paragraph,
                  citations.map((item) => item.citationId),
                )}
                citations={citations}
                onCitationClick={onCitationClick}
                ariaLabel={`可摘录段落 ${index + 1}`}
              />
              <button
                type="button"
                className={styles.button}
                disabled={new TextEncoder().encode(paragraph).length > 16384}
                onClick={() => setText(paragraph)}
              >
                摘录第 {index + 1} 段
              </button>
            </li>
          ))}
        </ol>
      </details>
      {text !== null && (
        <CaptureNoteDialog
          questionId={questionId}
          context={{ questionId, notebookId: null, excerpt: { resultId, turnId, text } }}
          onClose={close}
        />
      )}
    </>
  );
}
