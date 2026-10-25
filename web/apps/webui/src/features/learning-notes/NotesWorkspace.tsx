"use client";

import { useEffect, useRef, useState } from "react";

import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { learningRequest, LearningApiError } from "@/lib/api/learning-v2-client";
import type { CursorPage, Note, Source } from "@/lib/api/learning-v2-contract";

import {
  displayTime,
  errorMessage,
  indexLabels,
  MAX_NOTE_BYTES,
  noteByteLength,
  sourceLabels,
} from "./learning-utils";
import styles from "./learning.module.css";

function NoteEditor({
  noteId,
  onSaved,
  onDeleted,
  onBack,
}: {
  noteId: string | null;
  onSaved: () => void;
  onDeleted: () => void;
  onBack: () => void;
}) {
  const [note, setNote] = useState<Note | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [loading, setLoading] = useState(noteId !== null);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [conflict, setConflict] = useState(false);
  const [latest, setLatest] = useState<{ data: Note; etag: string | null } | null>(null);
  const [reload, setReload] = useState(0);
  const requestKey = useRef<{ payload: string; key: string } | null>(null);
  const [createUncertain, setCreateUncertain] = useState(false);
  const lifetime = useRef<AbortController | null>(null);
  const bytes = noteByteLength(body);
  const dirty = title !== (note?.title ?? "") || body !== (note?.bodyMarkdown ?? "");

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    if (noteId !== null) {
      learningRequest<Note>(`notes/${noteId}`, { signal: controller.signal })
        .then(({ data, etag }) => {
          if (controller.signal.aborted) return;
          setNote(data);
          setEtag(etag);
          setTitle(data.title);
          setBody(data.bodyMarkdown);
          setLoading(false);
          setMessage("");
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted) {
            setMessage(errorMessage(error));
            setLoading(false);
          }
        });
    }
    return () => controller.abort();
  }, [noteId, reload]);

  useEffect(() => {
    if (!dirty && !createUncertain) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    const navigate = (event: MouseEvent) => {
      const link = event.target instanceof Element ? event.target.closest("a[href]") : null;
      if (
        link &&
        !window.confirm(
          createUncertain
            ? "上次新建结果尚未确认，服务器可能已有这篇笔记。离开将放弃确认，确定离开？"
            : "有尚未保存的笔记编辑，确定离开？",
        )
      ) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener("beforeunload", beforeUnload);
    document.addEventListener("click", navigate, true);
    return () => {
      window.removeEventListener("beforeunload", beforeUnload);
      document.removeEventListener("click", navigate, true);
    };
  }, [dirty, createUncertain]);

  async function save() {
    if (pending || bytes > MAX_NOTE_BYTES || !title.trim() || !body.trim() || conflict) return;
    setPending(true);
    setMessage("");
    const payload = JSON.stringify({ title, bodyMarkdown: body.replace(/\r\n/g, "\n") });
    try {
      if (note) {
        if (!etag) throw new Error("尚未获得版本标签，请重新读取后保存");
        const result = await learningRequest<Note>(`notes/${note.noteId}`, {
          method: "PUT",
          headers: { "If-Match": etag },
          body: payload,
          signal: lifetime.current?.signal,
        });
        if (lifetime.current?.signal.aborted) return;
        setNote(result.data);
        setEtag(result.etag);
        setTitle(result.data.title);
        setBody(result.data.bodyMarkdown);
      } else {
        // 未确认的创建请求只能用原键和原请求体重试；改稿在确认 noteId 后另行 PUT。
        requestKey.current ??= { payload, key: crypto.randomUUID() };
        const result = await learningRequest<Note>("notes", {
          method: "POST",
          headers: { "Idempotency-Key": requestKey.current.key },
          body: requestKey.current.payload,
          signal: lifetime.current?.signal,
        });
        if (lifetime.current?.signal.aborted) return;
        // 已确认 noteId，之后即使 GET 失败也只允许继续读取或更新这篇笔记。
        setNote(result.data);
        requestKey.current = null;
        setCreateUncertain(false);
        const loaded = await learningRequest<Note>(`notes/${result.data.noteId}`, {
          signal: lifetime.current?.signal,
        });
        if (lifetime.current?.signal.aborted) return;
        if (result.data.version > 1 || loaded.data.version !== result.data.version) {
          setConflict(true);
          setLatest(loaded);
          setMessage("原笔记已被更新，请核对服务器版本后明确合并本地改稿。");
          return;
        }
        setNote(loaded.data);
        setEtag(loaded.etag);
        if (!loaded.etag) throw new Error("尚未获得版本标签，请重新读取后保存");
        if (
          payload !==
          JSON.stringify({
            title: loaded.data.title,
            bodyMarkdown: loaded.data.bodyMarkdown.replace(/\r\n/g, "\n"),
          })
        ) {
          const updated = await learningRequest<Note>(`notes/${loaded.data.noteId}`, {
            method: "PUT",
            headers: { "If-Match": loaded.etag },
            body: payload,
            signal: lifetime.current?.signal,
          });
          if (lifetime.current?.signal.aborted) return;
          setNote(updated.data);
          setEtag(updated.etag);
          setTitle(updated.data.title);
          setBody(updated.data.bodyMarkdown);
        }
      }
      setMessage("笔记已保存。索引状态可在列表中刷新查看。");
      onSaved();
    } catch (error) {
      if (lifetime.current?.signal.aborted) return;
      if (!note && requestKey.current) setCreateUncertain(true);
      if (error instanceof LearningApiError && error.status === 412) setConflict(true);
      setMessage(errorMessage(error));
    } finally {
      setPending(false);
    }
  }

  async function readLatest() {
    if (!note) return;
    setPending(true);
    try {
      const loaded = await learningRequest<Note>(`notes/${note.noteId}`, {
        signal: lifetime.current?.signal,
      });
      if (lifetime.current?.signal.aborted) return;
      if (!etag && !conflict) {
        if (loaded.data.version !== note.version) {
          setConflict(true);
          setLatest(loaded);
          setMessage("原笔记已被更新，请核对服务器版本后明确合并本地改稿。");
        } else {
          setNote(loaded.data);
          setEtag(loaded.etag);
          setMessage(
            loaded.etag
              ? "已取得版本标签，本地编辑仍保留；请再次保存。"
              : "尚未获得版本标签，请重新读取后保存",
          );
        }
      } else {
        setLatest(loaded);
      }
    } catch (error) {
      if (!lifetime.current?.signal.aborted) setMessage(errorMessage(error));
    } finally {
      setPending(false);
    }
  }

  async function remove() {
    if (!note || !etag || pending || !window.confirm("删除此笔记？已有引用将立即失效。")) return;
    setPending(true);
    setMessage("");
    try {
      await learningRequest(`notes/${note.noteId}`, {
        method: "DELETE",
        headers: { "If-Match": etag },
        signal: lifetime.current?.signal,
      });
      onDeleted();
    } catch (error) {
      if (lifetime.current?.signal.aborted) return;
      if (error instanceof LearningApiError && error.status === 412) setConflict(true);
      setMessage(errorMessage(error));
    } finally {
      setPending(false);
    }
  }

  if (loading) return <p role="status">正在读取笔记…</p>;
  if (noteId && !note)
    return (
      <div className={styles.panel}>
        <p role="alert" className={styles.error}>
          {message}
        </p>
        <button className={styles.button} onClick={() => setReload(reload + 1)}>
          重新读取
        </button>
        <button className={styles.button} onClick={onBack}>
          返回笔记列表
        </button>
      </div>
    );

  return (
    <section className={styles.panel} aria-label="笔记编辑器">
      <div className={styles.toolbar}>
        <h2 className="mr-auto text-lg font-semibold">{note ? "编辑笔记" : "新建笔记"}</h2>
        <button
          className={styles.button}
          disabled={pending}
          onClick={() => {
            if (
              (!dirty && !createUncertain) ||
              window.confirm(
                createUncertain
                  ? "上次新建结果尚未确认，服务器可能已有这篇笔记。放弃确认并返回列表？"
                  : "放弃尚未保存的编辑？",
              )
            )
              onBack();
          }}
        >
          返回列表
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
        <button
          className={`${styles.button} ${styles.primary}`}
          disabled={
            pending ||
            !title.trim() ||
            !body.trim() ||
            bytes > MAX_NOTE_BYTES ||
            conflict ||
            (!!note && !etag)
          }
          onClick={() => void save()}
        >
          {pending ? "处理中…" : createUncertain ? "确认上次创建并保存改稿" : "保存笔记"}
        </button>
      </div>
      {note && (
        <p className={styles.muted}>
          版本 {note.version} · {indexLabels[note.indexStatus]} · {displayTime(note.updatedAt)}
        </p>
      )}
      <label htmlFor="note-title" className={styles.label}>
        标题
      </label>
      <input
        id="note-title"
        className={styles.input}
        value={title}
        disabled={pending}
        onChange={(event) => setTitle(event.target.value)}
      />
      <div className={styles.columns}>
        <div>
          <label htmlFor="note-body" className={styles.label}>
            Markdown 与公式原文
          </label>
          <textarea
            id="note-body"
            className={styles.textarea}
            value={body}
            disabled={pending}
            onChange={(event) => setBody(event.target.value)}
            aria-describedby="note-limit"
          />
          <p id="note-limit" className={bytes > MAX_NOTE_BYTES ? styles.error : styles.muted}>
            {bytes.toLocaleString()} / 65,536 字节（UTF-8）；支持 $…$、$$…$$、\\(…\\) 和 \\[…\\]。
          </p>
        </div>
        <div>
          <h3 className={styles.label}>预览</h3>
          <div className={`${styles.panel} ${styles.preview}`}>
            <QuestionMarkdown bodyMarkdown={body} ariaLabel="笔记公式预览" />
          </div>
        </div>
      </div>
      {message && (
        <p role="status" className={styles.error}>
          {message}
        </p>
      )}
      {createUncertain && (
        <p className={styles.warning} role="alert">
          上次新建请求的结果未知，服务器可能已有这篇笔记。请用原请求确认后保存改稿；放弃确认可能留下原笔记。
        </p>
      )}
      {note && !etag && !conflict && (
        <button className={styles.button} disabled={pending} onClick={() => void readLatest()}>
          重新读取版本标签
        </button>
      )}
      {conflict && (
        <div className={styles.warning} role="alert">
          <h3 className="font-semibold">保存冲突：本地编辑已保留</h3>
          <p>
            服务器版本已改变。先读取最新版本，在下方核对；再明确选择以最新版本为基线，保留本地编辑继续合并。
          </p>
          <button className={styles.button} disabled={pending} onClick={() => void readLatest()}>
            读取服务器最新版本
          </button>
        </div>
      )}
      {latest && (
        <div className={styles.row}>
          <h3 className="font-semibold">
            服务器版本 {latest.data.version}：{latest.data.title}
          </h3>
          <QuestionMarkdown bodyMarkdown={latest.data.bodyMarkdown} ariaLabel="服务器最新笔记" />
          <button
            className={styles.button}
            disabled={!latest.etag}
            onClick={() => {
              setNote(latest.data);
              setEtag(latest.etag);
              setConflict(false);
              setLatest(null);
              setMessage("已更新版本基线，本地编辑仍保留；请合并后再次保存。");
            }}
          >
            使用此版本标签，保留本地编辑
          </button>
        </div>
      )}
    </section>
  );
}

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

export function NotesWorkspace() {
  const [notes, setNotes] = useState<Note[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null | undefined>(undefined);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(true);
  const [revision, setRevision] = useState(0);
  const requests = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    requests.current = controller;
    learningRequest<CursorPage<Note>>("notes?limit=20", { signal: controller.signal })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setNotes(data.items);
        setCursor(data.nextCursor ?? null);
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
  }, [revision]);
  async function more() {
    if (!cursor || pending) return;
    const signal = requests.current?.signal;
    if (!signal || signal.aborted) return;
    setPending(true);
    try {
      const { data } = await learningRequest<CursorPage<Note>>(
        `notes?limit=20&cursor=${encodeURIComponent(cursor)}`,
        { signal },
      );
      if (signal.aborted) return;
      setNotes((items) => [
        ...items,
        ...data.items.filter((item) => !items.some((old) => old.noteId === item.noteId)),
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
    <div className="space-y-5">
      <section className={styles.panel}>
        <div className={styles.toolbar}>
          <h1 className="mr-auto text-xl font-semibold">我的笔记</h1>
          <button
            className={styles.button}
            onClick={() => {
              requests.current?.abort();
              setPending(true);
              setRevision(revision + 1);
            }}
          >
            刷新列表
          </button>
          {selected === undefined && (
            <button
              className={`${styles.button} ${styles.primary}`}
              onClick={() => setSelected(null)}
            >
              新建笔记
            </button>
          )}
        </div>
        <p className={styles.muted}>记录文字、Markdown 与公式；辅导历史请从题目详情进入。</p>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        {pending && <p role="status">正在读取笔记…</p>}
        {selected === undefined && (
          <>
            <ul className={styles.list}>
              {notes.map((note) => (
                <li className={styles.row} key={note.noteId}>
                  <button
                    className="text-left font-semibold hover:underline focus-visible:outline-2"
                    onClick={() => setSelected(note.noteId)}
                  >
                    {note.title}
                  </button>
                  <p className={styles.muted}>
                    版本 {note.version} · {indexLabels[note.indexStatus]} ·{" "}
                    {displayTime(note.updatedAt)}
                  </p>
                </li>
              ))}
            </ul>
            {!pending && !error && notes.length === 0 && (
              <p className={styles.muted}>还没有笔记，创建第一篇笔记吧。</p>
            )}
            {cursor && (
              <button className={styles.button} disabled={pending} onClick={() => void more()}>
                加载更多笔记
              </button>
            )}
          </>
        )}
      </section>
      {selected !== undefined && (
        <NoteEditor
          key={selected ?? "new"}
          noteId={selected}
          onSaved={() => setRevision((value) => value + 1)}
          onDeleted={() => {
            setSelected(undefined);
            setRevision((value) => value + 1);
          }}
          onBack={() => setSelected(undefined)}
        />
      )}
      <SourceList key={revision} />
    </div>
  );
}
