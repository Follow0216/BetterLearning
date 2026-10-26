"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { learningRequest } from "@/lib/api/learning-v2-client";
import type {
  CursorPage,
  Note,
  Notebook,
  NotebookList,
  Source,
} from "@/lib/api/learning-v2-contract";
import { displayTime, errorMessage, indexLabels, sourceLabels } from "./learning-utils";
import { NoteComposer } from "./NoteComposer";
import { readNoteDrafts, type NoteDraft } from "./note-drafts";
import { useNoteAccount } from "./use-note-account";
import styles from "./learning.module.css";

function SourceList() {
  const [sources, setSources] = useState<Source[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(true);
  const [revision, setRevision] = useState(0);
  const requests = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    requests.current = controller;
    learningRequest<CursorPage<Source>>("sources?limit=20", { signal: controller.signal })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setSources(data.items);
        setCursor(data.nextCursor ?? null);
        setError("");
        setPending(false);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setSources([]);
          setError(errorMessage(error));
          setPending(false);
        }
      });
    return () => controller.abort();
  }, [revision]);
  async function more() {
    if (!cursor || pending) return;
    const signal = requests.current?.signal;
    if (!signal || signal.aborted) return;
    setPending(true);
    try {
      const { data } = await learningRequest<CursorPage<Source>>(
        `sources?limit=20&cursor=${encodeURIComponent(cursor)}`,
        { signal },
      );
      if (signal.aborted) return;
      setSources((items) => [
        ...items,
        ...data.items.filter((item) => !items.some((old) => old.sourceId === item.sourceId)),
      ]);
      setCursor(data.nextCursor ?? null);
      setError("");
    } catch (error) {
      if (!signal.aborted) setError(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  return (
    <section className={styles.panel} aria-label="资料与索引状态">
      <div className={styles.toolbar}>
        <h2 className="mr-auto text-lg font-semibold">资料与索引状态</h2>
        <button
          className={styles.button}
          onClick={() => {
            requests.current?.abort();
            setPending(true);
            setRevision(revision + 1);
          }}
        >
          刷新索引状态
        </button>
      </div>
      <p className={styles.muted}>
        保存成功与可检索是两个状态。新版本处理失败时，不回退检索旧版本；辅导快照不参与普通历史检索。
      </p>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      <ul className={styles.list}>
        {sources.map((source) => (
          <li className={styles.row} key={source.sourceId}>
            {sourceLabels[source.type]} · 版本 {source.currentVersion}
            <p className={styles.muted}>
              {source.status === "DELETED"
                ? "已删除 · 引用已撤销"
                : indexLabels[source.indexStatus]}
              {source.answerState
                ? ` · ${source.answerState === "DRAFT" ? "草稿" : "已完成（不代表正确）"}`
                : ""}
            </p>
          </li>
        ))}
      </ul>
      {pending && <p role="status">正在读取资料状态…</p>}
      {!pending && !error && sources.length === 0 && <p className={styles.muted}>暂无资料</p>}
      {cursor && (
        <button className={styles.button} disabled={pending} onClick={() => void more()}>
          加载更多资料
        </button>
      )}
    </section>
  );
}

function Workspace({ accountId }: { accountId: string }) {
  const [notes, setNotes] = useState<Note[]>([]);
  const [notebooks, setNotebooks] = useState<Notebook[]>([]);
  const [drafts, setDrafts] = useState<NoteDraft[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [selected, setSelected] = useState<{
    noteId: string | null;
    draftId?: string;
    key: string;
  } | null>(null);
  const [queryInput, setQueryInput] = useState("");
  const [query, setQuery] = useState("");
  const [book, setBook] = useState("");
  const [question, setQuestion] = useState("");
  const [bookName, setBookName] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const renameInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    renameInput.current?.focus();
  }, [renaming]);
  const [bookPending, setBookPending] = useState(false);
  const [error, setError] = useState("");
  const [navigationError, setNavigationError] = useState("");
  const [bookError, setBookError] = useState("");
  const [draftError, setDraftError] = useState("");
  const [pending, setPending] = useState(true);
  const [revision, setRevision] = useState(0);
  const requests = useRef<AbortController | null>(null);
  const unsafe = useRef(false);
  const leaveGuard = useRef<(() => boolean) | null>(null);
  const registerLeaveGuard = useCallback((guard: (() => boolean) | null) => {
    leaveGuard.current = guard;
  }, []);
  function select(next: typeof selected) {
    if (leaveGuard.current ? !leaveGuard.current() : unsafe.current) {
      setNavigationError(
        "本机保护不可用，请先保存到云端或复制当前内容；也可在笔记详情中明确丢弃草稿。",
      );
      return;
    }
    setNavigationError("");
    setSelected(next);
  }
  const notebookKey = useRef<{ key: string; name: string } | null>(null);
  const params = new URLSearchParams({ limit: "20" });
  if (query) params.set("q", query);
  if (book) params.set("notebookId", book);
  if (question) params.set("questionId", question);
  const filter = params.toString();

  useEffect(() => {
    queueMicrotask(() => {
      const url = new URL(window.location.href),
        noteId = url.searchParams.get("noteId"),
        questionId = url.searchParams.get("questionId");
      if (noteId && /^[0-9a-f-]{36}$/.test(noteId)) setSelected({ noteId, key: noteId });
      if (questionId && /^[1-9][0-9]{0,18}$/.test(questionId)) setQuestion(questionId);
    });
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    requests.current = controller;
    learningRequest<CursorPage<Note>>(`notes?${filter}`, { signal: controller.signal })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setNotes(data.items);
        setCursor(data.nextCursor);
        setError("");
        setPending(false);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setNotes([]);
          setError(errorMessage(error));
          setPending(false);
        }
      });
    return () => controller.abort();
  }, [filter, revision]);
  useEffect(() => {
    const controller = new AbortController();
    learningRequest<NotebookList>("notebooks", { signal: controller.signal })
      .then(({ data }) => {
        if (!controller.signal.aborted) {
          setNotebooks(data.items);
          setBookError("");
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setBookError(errorMessage(error));
      });
    return () => controller.abort();
  }, [revision]);
  useEffect(() => {
    const refresh = () => {
      const local = readNoteDrafts(accountId);
      setDrafts(local.value);
      setDraftError(local.error);
    };
    refresh();
    const storage = (event: StorageEvent) => {
      if (event.key === null || event.key.startsWith(`bl.notes.draft.v1:${accountId}:`)) refresh();
    };
    window.addEventListener("storage", storage);
    window.addEventListener("focus", refresh);
    return () => {
      window.removeEventListener("storage", storage);
      window.removeEventListener("focus", refresh);
    };
  }, [accountId, selected, revision]);
  function refresh() {
    requests.current?.abort();
    setPending(true);
    setRevision((value) => value + 1);
  }
  async function more() {
    if (!cursor || pending) return;
    const signal = requests.current?.signal;
    if (!signal || signal.aborted) return;
    setPending(true);
    try {
      const { data } = await learningRequest<CursorPage<Note>>(
        `notes?${filter}&cursor=${encodeURIComponent(cursor)}`,
        { signal },
      );
      if (signal.aborted) return;
      setNotes((old) => [
        ...old,
        ...data.items.filter((item) => !old.some((previous) => previous.noteId === item.noteId)),
      ]);
      setCursor(data.nextCursor);
      setError("");
    } catch (error) {
      if (!signal.aborted) setError(errorMessage(error));
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }
  async function createBook(event: FormEvent) {
    event.preventDefault();
    if (!bookName.trim() || bookPending) return;
    const controller = requests.current;
    if (!controller || controller.signal.aborted) return;
    setBookPending(true);
    if (notebookKey.current?.name !== bookName.trim())
      notebookKey.current = { key: crypto.randomUUID(), name: bookName.trim() };
    try {
      await learningRequest<Notebook>("notebooks", {
        method: "POST",
        headers: { "Idempotency-Key": notebookKey.current.key },
        body: JSON.stringify({ name: notebookKey.current.name }),
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      setBookName("");
      notebookKey.current = null;
      setBookPending(false);
      refresh();
    } catch (error) {
      if (!controller.signal.aborted) setBookError(errorMessage(error));
    } finally {
      if (!controller.signal.aborted) setBookPending(false);
    }
  }
  async function changeBook(item: Notebook, remove: boolean, name?: string) {
    if (bookPending) return;
    if (!remove && (!name || !name.trim())) return;
    if (remove && !window.confirm(`删除“${item.name}”？其中笔记会保留并移回未归类。`)) return;
    const controller = requests.current;
    if (!controller || controller.signal.aborted) return;
    setBookPending(true);
    try {
      await learningRequest(`notebooks/${item.notebookId}${remove ? "?confirm=true" : ""}`, {
        method: remove ? "DELETE" : "PUT",
        headers: { "If-Match": `"notebook:${item.notebookId}:${item.revision}"` },
        ...(remove ? {} : { body: JSON.stringify({ name }) }),
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (remove && book === item.notebookId) setBook("");
      setRenaming(null);
      setBookPending(false);
      refresh();
    } catch (error) {
      if (!controller.signal.aborted) setBookError(errorMessage(error));
    } finally {
      if (!controller.signal.aborted) setBookPending(false);
    }
  }
  return (
    <div className={styles.notesSpace}>
      <header className={styles.notesHeader}>
        <div>
          <h1 className={styles.spaceTitle}>我的笔记</h1>
          <p className={styles.muted}>把一道题里的理解，留成自己的知识。</p>
        </div>
        <div className={styles.toolbar}>
          <button className={styles.button} onClick={refresh}>
            刷新列表
          </button>
          <button
            className={`${styles.button} ${styles.primary}`}
            onClick={() => select({ noteId: null, key: crypto.randomUUID() })}
          >
            新建笔记
          </button>
        </div>
      </header>
      {navigationError && (
        <p role="alert" className={styles.error}>
          {navigationError}
        </p>
      )}
      <div className={`${styles.notesLayout} ${selected ? styles.hasSelection : ""}`}>
        <aside className={styles.notesSidebar} aria-label="笔记列表与笔记本">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              requests.current?.abort();
              setPending(true);
              setQuery(queryInput.trim());
              setRevision((value) => value + 1);
            }}
            className={styles.searchForm}
          >
            <label htmlFor="note-search" className={styles.label}>
              搜索笔记
            </label>
            <div className={styles.toolbar}>
              <input
                id="note-search"
                className={styles.input}
                type="search"
                maxLength={200}
                placeholder="标题或正文关键词"
                value={queryInput}
                onChange={(event) => setQueryInput(event.target.value)}
              />
              <button className={styles.button}>搜索</button>
            </div>
          </form>
          <label htmlFor="notebook-filter" className={styles.label}>
            笔记本
          </label>
          <select
            id="notebook-filter"
            className={styles.input}
            value={book}
            onChange={(event) => {
              requests.current?.abort();
              setPending(true);
              setBook(event.target.value);
            }}
          >
            <option value="">全部笔记</option>
            <option value="unfiled">未归类</option>
            {notebooks.map((item) => (
              <option key={item.notebookId} value={item.notebookId}>
                {item.name}
              </option>
            ))}
          </select>
          {question && (
            <p className={styles.muted}>
              仅看题目 #{question}{" "}
              <button
                className={styles.textButton}
                onClick={() => {
                  setQuestion("");
                  setPending(true);
                }}
              >
                查看全部题目
              </button>
            </p>
          )}
          <details className={styles.previewDetails}>
            <summary>管理笔记本</summary>
            <form onSubmit={(event) => void createBook(event)}>
              <label htmlFor="new-notebook" className={styles.label}>
                新笔记本名称
              </label>
              <input
                id="new-notebook"
                className={styles.input}
                maxLength={64}
                disabled={bookPending}
                value={bookName}
                onChange={(event) => setBookName(event.target.value)}
              />
              <button className={styles.button} disabled={!bookName.trim() || bookPending}>
                创建笔记本
              </button>
            </form>
            <ul>
              {notebooks.map((item) => (
                <li key={item.notebookId} className={styles.notebookRow}>
                  {renaming === item.notebookId ? (
                    <form
                      className={styles.renameForm}
                      onSubmit={(event) => {
                        event.preventDefault();
                        void changeBook(item, false, renameValue);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "Escape" && !bookPending) {
                          event.preventDefault();
                          setRenaming(null);
                        }
                      }}
                    >
                      <label className={styles.label} htmlFor={`rename-${item.notebookId}`}>
                        重命名笔记本
                      </label>
                      <input
                        ref={renameInput}
                        id={`rename-${item.notebookId}`}
                        className={styles.input}
                        value={renameValue}
                        disabled={bookPending}
                        maxLength={64}
                        onChange={(event) => setRenameValue(event.target.value)}
                      />
                      <div className={styles.toolbar}>
                        <button
                          className={styles.button}
                          disabled={bookPending || !renameValue.trim()}
                        >
                          保存名称
                        </button>
                        <button
                          type="button"
                          className={styles.button}
                          disabled={bookPending}
                          onClick={() => setRenaming(null)}
                        >
                          取消
                        </button>
                      </div>
                    </form>
                  ) : (
                    <>
                      <span>{item.name}</span>
                      <button
                        className={styles.textButton}
                        disabled={bookPending}
                        onClick={() => {
                          setRenaming(item.notebookId);
                          setRenameValue(item.name);
                          setBookError("");
                        }}
                      >
                        改名
                      </button>
                      <button
                        className={styles.textButton}
                        disabled={bookPending}
                        onClick={() => void changeBook(item, true)}
                      >
                        删除
                      </button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          </details>
          {bookError && (
            <p role="alert" className={styles.error}>
              {bookError}
            </p>
          )}
          {draftError && (
            <p role="alert" className={styles.error}>
              {draftError}
            </p>
          )}
          {drafts.length > 0 && (
            <details className={styles.previewDetails}>
              <summary>本机草稿 · {drafts.length}</summary>
              <ul>
                {drafts.map((item) => (
                  <li key={item.draftId}>
                    <button
                      className={styles.draftLink}
                      onClick={() =>
                        select({
                          noteId: item.noteId,
                          draftId: item.draftId,
                          key: item.draftId,
                        })
                      }
                    >
                      {item.title || "未命名草稿"}
                      <span>
                        {displayTime(item.updatedAt)}
                        {item.createRequest ? " · 创建待核对" : ""}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </details>
          )}
          {error && (
            <p role="alert" className={styles.error}>
              {error}
            </p>
          )}
          {pending && (
            <p role="status" className={styles.muted}>
              正在读取笔记…
            </p>
          )}
          <ul className={styles.noteList}>
            {notes.map((item) => (
              <li key={item.noteId}>
                <button
                  className={styles.noteListItem}
                  aria-label={item.title}
                  aria-pressed={selected?.noteId === item.noteId}
                  onClick={() => select({ noteId: item.noteId, key: item.noteId })}
                >
                  <strong>{item.title}</strong>
                  <span>{item.bodyMarkdown.slice(0, 100).replace(/[#*_`]/g, "")}</span>
                  <time dateTime={item.updatedAt}>
                    {displayTime(item.updatedAt)}
                    {item.context?.questionId ? " · 随题摘记" : ""}
                  </time>
                </button>
              </li>
            ))}
          </ul>
          {!pending && !error && notes.length === 0 && (
            <p className={styles.emptySmall}>
              {query || book || question
                ? "没有找到匹配的笔记，换个关键词或笔记本试试。"
                : "还没有云端笔记。记下第一条自己的理解吧。"}
            </p>
          )}
          {cursor && (
            <button className={styles.button} disabled={pending} onClick={() => void more()}>
              加载更多笔记
            </button>
          )}
        </aside>
        <div className={styles.noteArea}>
          {selected ? (
            <NoteComposer
              key={selected.key}
              accountId={accountId}
              noteId={selected.noteId}
              draftId={selected.draftId}
              notebooks={notebooks}
              onLeaveGuardChange={registerLeaveGuard}
              onProtectionChange={(blocked) => {
                unsafe.current = blocked;
              }}
              onSaved={() => refresh()}
              onDeleted={() => {
                setSelected(null);
                refresh();
              }}
              onBack={() => select(null)}
            />
          ) : (
            <div className={styles.emptyPaper}>
              <span className={styles.paperMark}>记</span>
              <h2>让理解慢慢积累</h2>
              <p>从左侧打开一篇笔记，或记下今天的一点收获。</p>
              <button
                className={styles.button}
                onClick={() => select({ noteId: null, key: crypto.randomUUID() })}
              >
                写一篇自由笔记
              </button>
            </div>
          )}
        </div>
      </div>
      <details className={styles.sourceDetails}>
        <summary>资料与同步状态</summary>
        <SourceList key={revision} />
      </details>
    </div>
  );
}

export function NotesWorkspace() {
  const { accountId, error } = useNoteAccount();
  if (!accountId) return <p role={error ? "alert" : "status"}>{error || "正在核验账号…"}</p>;
  return <Workspace key={accountId} accountId={accountId} />;
}
