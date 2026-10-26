"use client";

import { useToast } from "@betterlearning/ui-feedback";
import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";

import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { learningRequest, LearningApiError } from "@/lib/api/learning-v2-client";
import type { Note, NoteContextWrite, Notebook } from "@/lib/api/learning-v2-contract";

import {
  displayTime,
  excerptDisplayMarkdown,
  errorMessage,
  indexLabels,
  MAX_NOTE_BYTES,
  noteByteLength,
} from "./learning-utils";
import {
  createNoteDraft,
  isNoteDraftSnapshotProtected,
  readNoteDrafts,
  sameNoteContext,
  removeNoteDraft,
  storeNoteDraft,
  type NoteDraft,
} from "./note-drafts";
import styles from "./learning.module.css";

const DEFINITE_REJECTIONS = new Set([400, 413, 415, 422]);
const plainPayload = (title: string, body: string) =>
  JSON.stringify({ title, bodyMarkdown: body.replace(/\r\n/g, "\n") });
const EMPTY_CONTEXT: NoteContextWrite = { notebookId: null, questionId: null, excerpt: null };

export function NoteComposer({
  accountId,
  noteId,
  draftId,
  initialContext = null,
  notebooks = [],
  onSaved,
  onDeleted,
  onBack,
  onProtectionChange,
  onLeaveGuardChange,
}: {
  accountId: string;
  noteId: string | null;
  draftId?: string;
  initialContext?: NoteContextWrite | null;
  notebooks?: Notebook[];
  onSaved: (note: Note) => void;
  onDeleted: () => void;
  onBack: () => void;
  onProtectionChange?: (blocked: boolean) => void;
  onLeaveGuardChange?: (guard: (() => boolean) | null) => void;
}) {
  const toast = useToast();
  const formId = useId();
  const [note, setNote] = useState<Note | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [context, setContext] = useState<NoteContextWrite | null>(initialContext);
  const [editing, setEditing] = useState(noteId === null);
  const [targetId, setTargetId] = useState(noteId);
  const [ready, setReady] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [storageError, setStorageError] = useState("");
  const [createUncertain, setCreateUncertain] = useState(false);
  const [createLocked, setCreateLocked] = useState(false);
  const [createBlocked, setCreateBlocked] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [latest, setLatest] = useState<{ data: Note; etag: string | null } | null>(null);
  const [reload, setReload] = useState(0);
  const current = useRef({ title: "", body: "" });
  const baseline = useRef<{ note: Note | null; etag: string | null }>({ note: null, etag: null });
  const editBase = useRef<{ version: number | null; etag: string | null }>({
    version: null,
    etag: null,
  });
  const requestKey = useRef<{ key: string; payload: string } | null>(null);
  const draft = useRef<NoteDraft>(createNoteDraft(initialContext));
  const restored = useRef<NoteDraft | null>(null);
  const lifetime = useRef<AbortController | null>(null);
  const uncertain = useRef(false);
  const abandoned = useRef(false);
  const contextRef = useRef(context);
  const dirty = title !== (note?.title ?? "") || body !== (note?.bodyMarkdown ?? "");
  const bytes = noteByteLength(body);

  function persist(overrides: Partial<NoteDraft> = {}) {
    const next: NoteDraft = {
      ...draft.current,
      ...current.current,
      context: contextRef.current,
      noteId: baseline.current.note?.noteId ?? draft.current.noteId,
      baseVersion: editBase.current.version,
      baseEtag: editBase.current.etag,
      createRequest: requestKey.current,
      createUncertain: uncertain.current,
      ...overrides,
      revision: draft.current.revision + 1,
      updatedAt: new Date().toISOString(),
    };
    if (
      draft.current.revision === 0 &&
      restored.current &&
      isNoteDraftSnapshotProtected(accountId, restored.current, next)
    ) {
      setStorageError("");
      return true;
    }
    draft.current = next;
    const error = storeNoteDraft(accountId, next);
    setStorageError(error);
    return !error;
  }
  function fields(nextTitle: string, nextBody: string) {
    current.current = { title: nextTitle, body: nextBody };
    setTitle(nextTitle);
    setBody(nextBody);
  }

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    queueMicrotask(() => {
      if (controller.signal.aborted) return;
      const found = readNoteDrafts(accountId);
      setStorageError(found.error);
      const local = found.value.find((item) =>
        draftId
          ? item.draftId === draftId
          : item.noteId === noteId &&
            (noteId !== null || sameNoteContext(item.context, initialContext)),
      );
      // 每个编辑会话分叉，两个标签恢复同稿也不会写同一个 key。
      draft.current = {
        ...(local ?? createNoteDraft(initialContext)),
        draftId: crypto.randomUUID(),
        revision: 0,
      };
      restored.current = local ?? null;
      editBase.current = { version: local?.baseVersion ?? null, etag: local?.baseEtag ?? null };
      requestKey.current = local?.createRequest ?? null;
      setCreateLocked(!!local?.createRequest);
      uncertain.current = local?.createUncertain ?? false;
      setCreateUncertain(uncertain.current);
      if (local) {
        fields(local.title, local.body);
        contextRef.current = local.context;
        setContext(local.context);
        setEditing(true);
        setMessage("已恢复本机草稿；保存到云端前请核对内容。");
      }
      const target = local?.noteId ?? noteId;
      setTargetId(target);
      if (target) {
        learningRequest<Note>(`notes/${target}`, { signal: controller.signal })
          .then((loaded) => {
            if (controller.signal.aborted) return;
            baseline.current = { note: loaded.data, etag: loaded.etag };
            if (!local || local.baseVersion === loaded.data.version)
              editBase.current = { version: loaded.data.version, etag: loaded.etag };
            setNote(loaded.data);
            setEtag(loaded.etag);
            if (!local) fields(loaded.data.title, loaded.data.bodyMarkdown);
            else if (local.baseVersion !== loaded.data.version) {
              setConflict(true);
              setLatest(loaded);
              setMessage("云端已有更新，本机草稿作为待合并稿保留；请明确选择版本基线。");
            }
            setReady(true);
          })
          .catch((error: unknown) => {
            if (!controller.signal.aborted) {
              setMessage(errorMessage(error));
              setReady(true);
            }
          });
      } else setReady(true);
    });
    return () => controller.abort();
    // 初始入口固定，重新读取显式触发；输入变化不会重置编辑器。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, noteId, draftId, reload]);

  useEffect(() => {
    if (ready && (dirty || requestKey.current)) persist();
    // persist 总读取当前 refs，避免 effect 闭包覆写用户的后续输入。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, body, context, note, etag, ready, dirty, createUncertain]);

  useEffect(() => {
    onProtectionChange?.(!!storageError && (dirty || createUncertain));
    return () => onProtectionChange?.(false);
  }, [storageError, dirty, createUncertain, onProtectionChange]);

  function protectBeforeLeave() {
    if (abandoned.current) return true;
    const saved = baseline.current.note;
    const changed =
      plainPayload(current.current.title, current.current.body) !==
      plainPayload(saved?.title ?? "", saved?.bodyMarkdown ?? "");
    return !changed && !requestKey.current && !restored.current ? true : persist();
  }

  useEffect(() => {
    if (!ready) return;
    onLeaveGuardChange?.(protectBeforeLeave);
    const navigate = (event: MouseEvent) => {
      if (
        event.target instanceof Element &&
        event.target.closest("a[href]") &&
        !protectBeforeLeave()
      ) {
        event.preventDefault();
        event.stopPropagation();
        setMessage("本机保护不可用，请先保存到云端或复制内容；也可明确丢弃本机草稿后离开。");
      }
    };
    const unload = (event: BeforeUnloadEvent) => {
      if (!protectBeforeLeave()) event.preventDefault();
    };
    // 先于 Next 的 bubble 路由监听同步保护；不阻止或接管浏览器历史导航。
    const historyLeave = () => {
      protectBeforeLeave();
    };
    document.addEventListener("click", navigate, true);
    window.addEventListener("beforeunload", unload);
    window.addEventListener("popstate", historyLeave, true);
    return () => {
      onLeaveGuardChange?.(null);
      document.removeEventListener("click", navigate, true);
      window.removeEventListener("beforeunload", unload);
      window.removeEventListener("popstate", historyLeave, true);
    };
    // 离开核对读取实时输入/版本/原快照 refs，不因输入重建监听器。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, ready, onLeaveGuardChange]);

  function back() {
    if (!protectBeforeLeave()) setMessage("本机保护不可用，请先保存到云端或复制内容，再返回列表。");
    else onBack();
  }

  function applySaved(saved: Note, savedTag: string | null, sent: string) {
    baseline.current = { note: saved, etag: savedTag };
    editBase.current = { version: saved.version, etag: savedTag };
    setNote(saved);
    setEtag(savedTag);
    if (plainPayload(current.current.title, current.current.body) === sent)
      fields(saved.title, saved.bodyMarkdown);
    if (
      plainPayload(current.current.title, current.current.body) ===
      plainPayload(saved.title, saved.bodyMarkdown)
    ) {
      const error = removeNoteDraft(accountId, draft.current.draftId, draft.current.revision);
      if (restored.current) {
        const restoreError = removeNoteDraft(
          accountId,
          restored.current.draftId,
          restored.current.revision,
        );
        if (restoreError) setStorageError(restoreError);
        restored.current = null;
      }
      draft.current = {
        ...draft.current,
        draftId: crypto.randomUUID(),
        revision: 0,
        noteId: saved.noteId,
      };
      if (error) setStorageError(error);
    } else persist({ noteId: saved.noteId, baseVersion: saved.version, baseEtag: savedTag });
  }

  async function save() {
    if (
      pending ||
      !ready ||
      conflict ||
      createBlocked ||
      bytes > MAX_NOTE_BYTES ||
      !title.trim() ||
      !body.trim()
    )
      return;
    const signal = lifetime.current?.signal;
    const scope = toast.captureScope(),
      payload = plainPayload(title, body),
      wasUncertain = createUncertain;
    setPending(true);
    setMessage("");
    let didSendCreate = false;
    try {
      let saved: { data: Note; etag: string | null };
      if (baseline.current.note) {
        if (!baseline.current.etag) throw new Error("尚未获得版本标签，请重新读取后保存");
        saved = await learningRequest<Note>(`notes/${baseline.current.note.noteId}`, {
          method: "PUT",
          headers: { "If-Match": baseline.current.etag },
          body: payload,
          signal,
        });
        if (signal?.aborted) return;
      } else {
        requestKey.current ??= {
          key: crypto.randomUUID(),
          payload: JSON.stringify({
            ...(JSON.parse(payload) as object),
            ...(contextRef.current ? { context: contextRef.current } : {}),
          }),
        };
        setCreateLocked(true);
        // 必须在发网请求之前落盘：刷新、401 或结果未知时沿用原 key + 原 payload。
        uncertain.current = true;
        if (!persist({ createUncertain: true }))
          throw new Error(
            "无法安全保留原创建请求，暂未发起云端创建。请允许浏览器存储或释放空间后重试。",
          );
        didSendCreate = true;
        const created = await learningRequest<Note>("notes", {
          method: "POST",
          headers: { "Idempotency-Key": requestKey.current.key },
          body: requestKey.current.payload,
          signal,
        });
        if (signal?.aborted) return;
        baseline.current = { note: created.data, etag: null };
        // 创建正文从版本 1 起；幂等重放可能已经返回另一端更新的版本。
        editBase.current = { version: 1, etag: null };
        if (created.data.version > 1) setConflict(true);
        setNote(created.data);
        setEtag(null);
        requestKey.current = null;
        setCreateLocked(false);
        uncertain.current = false;
        setCreateUncertain(false);
        persist({
          noteId: created.data.noteId,
          baseVersion: 1,
          baseEtag: null,
          createUncertain: false,
        });
        saved = await learningRequest<Note>(`notes/${created.data.noteId}`, { signal });
        if (signal?.aborted) return;
        if (created.data.version > 1 || saved.data.version !== created.data.version) {
          setConflict(true);
          setLatest(saved);
          setMessage("原笔记已被更新，请核对服务器版本后明确合并本地改稿。");
          return;
        }
        baseline.current = { note: saved.data, etag: saved.etag };
        editBase.current = { version: saved.data.version, etag: saved.etag };
        setNote(saved.data);
        setEtag(saved.etag);
        if (!saved.etag) throw new Error("尚未获得版本标签，请重新读取后保存");
        if (payload !== plainPayload(saved.data.title, saved.data.bodyMarkdown)) {
          saved = await learningRequest<Note>(`notes/${saved.data.noteId}`, {
            method: "PUT",
            headers: { "If-Match": saved.etag },
            body: payload,
            signal,
          });
          if (signal?.aborted) return;
        }
      }
      applySaved(saved.data, saved.etag, payload);
      setMessage(
        plainPayload(current.current.title, current.current.body) ===
          plainPayload(saved.data.title, saved.data.bodyMarkdown)
          ? "笔记已保存。索引状态可在列表中刷新查看。"
          : "本次内容已保存到云端；后续输入仍是本机草稿，请再次保存。",
      );
      toast.show({ key: "note:save", type: "success", message: "笔记已保存" }, scope);
      onSaved(saved.data);
    } catch (error) {
      if (signal?.aborted) return;
      if (!baseline.current.note && requestKey.current) {
        const status = error instanceof LearningApiError ? error.status : 0;
        if (!didSendCreate) {
          uncertain.current = wasUncertain;
          setCreateUncertain(wasUncertain);
        } else if (DEFINITE_REJECTIONS.has(status) && !wasUncertain) {
          requestKey.current = null;
          setCreateLocked(false);
          uncertain.current = false;
          setCreateUncertain(false);
        } else if ([401, 403, 404, 409, 412].includes(status)) {
          setCreateBlocked(
            status === 401
              ? "登录状态已变化"
              : status === 409 || status === 412
                ? "创建请求冲突"
                : "创建请求已被拒绝",
          );
          // 会话过期也保留原请求，原账号再次认证后可继续核对。
          uncertain.current = wasUncertain;
          setCreateUncertain(wasUncertain);
        } else {
          uncertain.current = true;
          setCreateUncertain(true);
        }
      } else if (error instanceof LearningApiError && error.status === 412) setConflict(true);
      persist();
      setMessage(errorMessage(error));
      if (!(error instanceof LearningApiError && error.status === 401))
        toast.show(
          { key: "note:save", type: "error", message: "笔记保存未完成，请查看编辑器提示" },
          scope,
        );
    } finally {
      if (!signal?.aborted) setPending(false);
    }
  }

  async function readLatest() {
    const known = baseline.current.note;
    if (!known) return;
    const signal = lifetime.current?.signal;
    setPending(true);
    try {
      const loaded = await learningRequest<Note>(`notes/${known.noteId}`, { signal });
      if (signal?.aborted) return;
      if (!conflict && !baseline.current.etag && loaded.data.version === known.version) {
        baseline.current = { note: loaded.data, etag: loaded.etag };
        editBase.current = { version: loaded.data.version, etag: loaded.etag };
        setNote(loaded.data);
        setEtag(loaded.etag);
        setMessage("已取得版本标签，本地编辑仍保留；请再次保存。");
        persist();
      } else {
        setConflict(true);
        setLatest(loaded);
      }
    } catch (error) {
      if (!signal?.aborted) setMessage(errorMessage(error));
    } finally {
      if (!signal?.aborted) setPending(false);
    }
  }
  async function remove() {
    if (!note || !etag || pending || !window.confirm("删除此笔记？已有引用将立即失效。")) return;
    const signal = lifetime.current?.signal,
      scope = toast.captureScope();
    setPending(true);
    try {
      await learningRequest(`notes/${note.noteId}`, {
        method: "DELETE",
        headers: { "If-Match": etag },
        signal,
      });
      if (signal?.aborted) return;
      removeNoteDraft(accountId, draft.current.draftId, draft.current.revision);
      if (restored.current)
        removeNoteDraft(accountId, restored.current.draftId, restored.current.revision);
      toast.show({ key: "note:delete", type: "success", message: "笔记已删除" }, scope);
      abandoned.current = true;
      onProtectionChange?.(false);
      onDeleted();
    } catch (error) {
      if (signal?.aborted) return;
      if (error instanceof LearningApiError && error.status === 412) setConflict(true);
      setMessage(errorMessage(error));
      if (!(error instanceof LearningApiError && error.status === 401))
        toast.show(
          { key: "note:delete", type: "error", message: "笔记删除未完成，请查看编辑器提示" },
          scope,
        );
    } finally {
      if (!signal?.aborted) setPending(false);
    }
  }
  function discard() {
    if (
      !window.confirm(
        createUncertain
          ? "丢弃本机草稿？原创建请求可能已成功，云端笔记会保留。"
          : "丢弃本机草稿？云端已保存的内容会保留。",
      )
    )
      return;
    const error = removeNoteDraft(accountId, draft.current.draftId, draft.current.revision);
    const otherError = restored.current
      ? removeNoteDraft(accountId, restored.current.draftId, restored.current.revision)
      : "";
    if (error || otherError) {
      setStorageError(error || otherError);
      return;
    }
    abandoned.current = true;
    onProtectionChange?.(false);
    onBack();
  }
  async function file(notebookId: string) {
    if (!note) {
      const next = { ...(context ?? EMPTY_CONTEXT), notebookId: notebookId || null };
      contextRef.current = next;
      setContext(next);
      return;
    }
    const signal = lifetime.current?.signal;
    setPending(true);
    try {
      const loaded = await learningRequest<Note>(`notes/${note.noteId}/context`, {
        method: "PUT",
        headers: { "If-Match": `"context:${note.noteId}:${note.context?.revision ?? 1}"` },
        body: JSON.stringify({ notebookId: notebookId || null }),
        signal,
      });
      if (signal?.aborted) return;
      // 归类响应可能附带另一端刚保存的正文，不能据此推进本机正文基线。
      setNote((previous) => (previous ? { ...previous, context: loaded.data.context } : previous));
      if (baseline.current.note)
        baseline.current.note = { ...baseline.current.note, context: loaded.data.context };
      onSaved(loaded.data);
      setMessage("已更新笔记本归类。");
    } catch (error) {
      if (!signal?.aborted) setMessage(`${errorMessage(error)}；请重新打开笔记核对归类。`);
    } finally {
      if (!signal?.aborted) setPending(false);
    }
  }

  if (!ready) return <p role="status">正在读取笔记与本机草稿…</p>;
  if (targetId && !note)
    return (
      <div className={styles.panel}>
        <p role="alert" className={styles.error}>
          {message}
        </p>
        <button className={styles.button} onClick={() => setReload(reload + 1)}>
          重新读取
        </button>
        <button className={styles.button} onClick={back}>
          返回笔记列表
        </button>
        {title || body ? (
          <div className={styles.warning}>
            本机草稿仍保留，可以复制；云端内容恢复访问后可以继续合并。
            <p>{title}</p>
            <textarea
              className={styles.textarea}
              aria-label="保留的本机草稿原文"
              readOnly
              value={body}
            />
          </div>
        ) : null}
      </div>
    );
  const linked = note?.context?.questionId ?? context?.questionId;
  const excerpt = note?.context?.excerpt ?? context?.excerpt;
  const backQuestion = linked
    ? `/workspace/questions/${linked}?tab=${excerpt ? "tutor" : "answer"}${note?.context?.excerpt?.requestId && !note.context.excerpt.turnId ? `&requestId=${note.context.excerpt.requestId}` : ""}${note?.context?.excerpt?.turnId ? `&turnId=${note.context.excerpt.turnId}` : ""}`
    : null;
  return (
    <article className={styles.paper} aria-label={editing ? "笔记编辑器" : "笔记阅读区"}>
      <div className={styles.toolbar}>
        <button className={styles.button} onClick={back}>
          返回列表
        </button>
        <span className={`${styles.muted} mr-auto`} role="status">
          {pending
            ? "保存中…"
            : storageError
              ? "本机保护不可用 · 请保留页面"
              : dirty || createUncertain
                ? "本机草稿 · 尚未保存到云端"
                : note
                  ? "云端已保存"
                  : "本机草稿"}
        </span>
        {!editing && (
          <button className={styles.button} onClick={() => setEditing(true)}>
            编辑
          </button>
        )}
        {editing && (
          <button
            className={`${styles.button} ${styles.primary}`}
            disabled={
              pending ||
              !title.trim() ||
              !body.trim() ||
              bytes > MAX_NOTE_BYTES ||
              conflict ||
              !!createBlocked ||
              (!!note && !etag)
            }
            onClick={() => void save()}
          >
            {pending ? "保存中…" : createUncertain ? "确认上次创建并保存改稿" : "保存到云端"}
          </button>
        )}
      </div>
      {editing ? (
        <>
          <label className={styles.label} htmlFor={`${formId}-title`}>
            标题
          </label>
          <input
            id={`${formId}-title`}
            className={styles.input}
            value={title}
            onChange={(event) => {
              fields(event.target.value, current.current.body);
              persist();
            }}
          />
          <label className={styles.label} htmlFor={`${formId}-body`}>
            Markdown 与公式原文
          </label>
          <textarea
            id={`${formId}-body`}
            className={styles.textarea}
            value={body}
            aria-describedby={`${formId}-help`}
            onChange={(event) => {
              fields(current.current.title, event.target.value);
              persist();
            }}
          />
          <p id={`${formId}-help`} className={styles.muted}>
            用自己的话记下理解；支持 Markdown 与数学公式。
          </p>
          <details className={styles.previewDetails}>
            <summary>查看公式预览</summary>
            <QuestionMarkdown bodyMarkdown={body} ariaLabel="笔记公式预览" />
          </details>
          {bytes > MAX_NOTE_BYTES && (
            <p id="note-limit" role="alert" className={styles.error}>
              正文超过 64 KiB，请精简后保存。
            </p>
          )}
        </>
      ) : (
        <>
          <h2 className={styles.noteTitle}>{title}</h2>
          <div className={styles.reading}>
            <QuestionMarkdown bodyMarkdown={body} ariaLabel="笔记正文" />
          </div>
        </>
      )}
      <div className={styles.noteFooter}>
        <label className={styles.muted} htmlFor={`${formId}-notebook`}>
          笔记本
        </label>
        <select
          id={`${formId}-notebook`}
          className={styles.input}
          disabled={pending || (!note && (createUncertain || !!createBlocked || createLocked))}
          value={note?.context?.notebookId ?? context?.notebookId ?? ""}
          onChange={(event) => void file(event.target.value)}
        >
          <option value="">未归类</option>
          {notebooks.map((book) => (
            <option value={book.notebookId} key={book.notebookId}>
              {book.name}
            </option>
          ))}
        </select>
        {!note && createUncertain && <p className={styles.muted}>先核对上次创建，再调整笔记本。</p>}
        {linked && (
          <div className={styles.sourceContext}>
            <p>随题摘记 · 题目 #{linked}</p>
            {backQuestion && <Link href={backQuestion}>查看原题与辅导 →</Link>}
          </div>
        )}
        {excerpt && (
          <div className={styles.sourceContext}>
            <p>
              摘录自辅导 ·{" "}
              {"version" in excerpt && excerpt.version
                ? `来源版本 ${excerpt.version}`
                : "冻结的原文段落"}
            </p>
            {"available" in excerpt && !excerpt.available ? (
              <p>原出处已撤权或清除，摘录暂不可访问。</p>
            ) : (
              <QuestionMarkdown
                bodyMarkdown={excerptDisplayMarkdown(excerpt.text ?? "")}
                ariaLabel="辅导摘录"
              />
            )}
          </div>
        )}
      </div>
      {message && (
        <p role="status" className={styles.muted}>
          {message}
        </p>
      )}
      {storageError && (
        <p role="alert" className={styles.error}>
          {storageError}
        </p>
      )}
      {createUncertain && (
        <p className={styles.warning} role="alert">
          上次新建请求的结果未知，服务器可能已有这篇笔记。将使用原请求确认，原账号重新登录后仍可继续核对。
        </p>
      )}
      {createBlocked && (
        <p role="alert" className={styles.error}>
          {createBlocked}
          ：当前创建操作已停止，请核对账号、已有笔记及访问权限；本机草稿与原请求仍保留。
        </p>
      )}
      {note && !etag && !conflict && (
        <button className={styles.button} disabled={pending} onClick={() => void readLatest()}>
          重新读取版本标签
        </button>
      )}
      {conflict && (
        <div role="alert" className={styles.warning}>
          <h3>保存冲突：本地编辑已保留</h3>
          <p>请核对最新版本，再明确选择版本基线；不会自动覆盖云端。</p>
          <button className={styles.button} disabled={pending} onClick={() => void readLatest()}>
            读取服务器最新版本
          </button>
        </div>
      )}
      {latest && (
        <div className={styles.row}>
          <h3>
            服务器版本 {latest.data.version}：{latest.data.title}
          </h3>
          <QuestionMarkdown bodyMarkdown={latest.data.bodyMarkdown} ariaLabel="服务器最新笔记" />
          <button
            className={styles.button}
            disabled={!latest.etag || pending}
            onClick={() => {
              baseline.current = { note: latest.data, etag: latest.etag };
              editBase.current = { version: latest.data.version, etag: latest.etag };
              setNote(latest.data);
              setEtag(latest.etag);
              setConflict(false);
              setLatest(null);
              setEditing(true);
              setMessage("已更新版本基线，本地编辑仍保留；请合并后再次保存。");
              persist();
            }}
          >
            使用此版本标签，保留本地编辑
          </button>
        </div>
      )}
      <details className={styles.previewDetails}>
        <summary>笔记详情与操作</summary>
        {note && (
          <p className={styles.muted}>
            版本 {note.version} · {indexLabels[note.indexStatus]} · {displayTime(note.updatedAt)}
            <br />
            来源 {note.sourceId}
          </p>
        )}
        <p className={styles.muted}>{bytes.toLocaleString()} / 65,536 字节（UTF-8）</p>
        <div className={styles.toolbar}>
          <button className={styles.button} disabled={pending} onClick={discard}>
            丢弃本机草稿
          </button>
          {note && (
            <button
              className={`${styles.button} ${styles.danger}`}
              disabled={pending || !etag || conflict}
              onClick={() => void remove()}
            >
              删除笔记
            </button>
          )}
        </div>
      </details>
    </article>
  );
}
