import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Note, Notebook } from "@/lib/api/learning-v2-contract";
import { createNoteDraft, readNoteDrafts, removeNoteDraft, storeNoteDraft } from "./note-drafts";

const api = vi.hoisted(() => vi.fn());
const show = vi.hoisted(() => vi.fn());
vi.mock("@betterlearning/ui-feedback", () => ({
  useToast: () => ({ captureScope: () => 3, show }),
}));
vi.mock("@/lib/api/learning-v2-client", async (original) => ({
  ...(await original<typeof import("@/lib/api/learning-v2-client")>()),
  learningRequest: api,
}));
vi.mock("@/features/question-content/QuestionMarkdown", () => ({
  QuestionMarkdown: ({ bodyMarkdown }: { bodyMarkdown: string }) => <div>{bodyMarkdown}</div>,
}));
import { LearningApiError } from "@/lib/api/learning-v2-client";
import { NoteComposer } from "./NoteComposer";

const noteId = "11111111-1111-4111-8111-111111111111";
const saved = (title = "原稿", bodyMarkdown = "原正文", version = 1): Note => ({
  noteId,
  sourceId: "22222222-2222-4222-8222-222222222222",
  title,
  bodyMarkdown,
  version,
  indexStatus: "READY",
  updatedAt: "2026-10-25T12:00:00Z",
});
const tag = (version = 1) => `"${noteId}:${version}"`;
const callbacks = () => ({ onSaved: vi.fn(), onDeleted: vi.fn(), onBack: vi.fn() });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function input(title = "原稿", body = "原正文") {
  fireEvent.change(screen.getByLabelText("标题"), { target: { value: title } });
  fireEvent.change(screen.getByLabelText("Markdown 与公式原文"), { target: { value: body } });
}
afterEach(() => {
  cleanup();
  localStorage.clear();
  api.mockReset();
  show.mockReset();
  vi.restoreAllMocks();
});

describe("NoteComposer durable local protection", () => {
  it("persists original key before POST and keeps input made while save is in flight", async () => {
    const flight = deferred<{ data: Note; etag: string }>();
    api.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "notes") {
        const local = readNoteDrafts("7").value[0];
        expect(local.createRequest?.key).toBe(
          (options?.headers as Record<string, string>)["Idempotency-Key"],
        );
        expect(local.createRequest?.payload).toBe(options?.body);
        expect(local.createUncertain).toBe(true);
        return flight.promise;
      }
      return Promise.resolve({ data: saved(), etag: tag() });
    });
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByLabelText("标题");
    input();
    expect(api).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
    input("后续输入", "请求飞行时的新理解");
    await act(async () => flight.resolve({ data: saved(), etag: tag() }));
    await screen.findByText(/后续输入仍是本机草稿/);
    expect((screen.getByLabelText("标题") as HTMLInputElement).value).toBe("后续输入");
    expect(readNoteDrafts("7").value[0]).toMatchObject({
      title: "后续输入",
      body: "请求飞行时的新理解",
      noteId,
      baseVersion: 1,
      createRequest: null,
    });
    expect(api.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
  });
  it("allows refresh after synchronous draft protection and warns only when protection fails", async () => {
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByLabelText("标题");
    input();
    const protectedUnload = new Event("beforeunload", { cancelable: true });
    act(() => {
      window.dispatchEvent(protectedUnload);
    });
    expect(protectedUnload.defaultPrevented).toBe(false);
    expect(readNoteDrafts("7").value[0].title).toBe("原稿");
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    const failedUnload = new Event("beforeunload", { cancelable: true });
    act(() => {
      window.dispatchEvent(failedUnload);
    });
    expect(failedUnload.defaultPrevented).toBe(true);
  });
  it.each([false, true])(
    "reuses an unchanged recovered snapshot on leave and another login (uncertain=%s)",
    async (pendingCreate) => {
      const original = {
        ...createNoteDraft(),
        title: "原账号的恢复稿",
        body: "没有修改的理解",
        revision: 1,
        createUncertain: pendingCreate,
        createRequest: pendingCreate
          ? {
              key: "33333333-3333-4333-8333-333333333333",
              payload: JSON.stringify({ title: "原账号的恢复稿", bodyMarkdown: "没有修改的理解" }),
            }
          : null,
      };
      storeNoteDraft("7", original);
      for (let visit = 0; visit < 3; visit++) {
        const page = render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
        await screen.findByDisplayValue(original.title);
        expect(readNoteDrafts("7").value.map((item) => item.draftId)).toEqual([original.draftId]);
        const leave = new Event("beforeunload", { cancelable: true });
        act(() => {
          window.dispatchEvent(leave);
        });
        expect(leave.defaultPrevented).toBe(false);
        expect(readNoteDrafts("7").value).toEqual([original]);
        page.unmount();
      }
      expect(api).not.toHaveBeenCalled();
    },
  );

  it("persists an independent fork only after a recovered note is actually edited", async () => {
    const original = { ...createNoteDraft(), title: "原恢复稿", body: "原理解", revision: 1 };
    storeNoteDraft("7", original);
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByDisplayValue(original.title);
    expect(readNoteDrafts("7").value).toEqual([original]);
    fireEvent.change(screen.getByLabelText("Markdown 与公式原文"), {
      target: { value: "真正新增的理解" },
    });
    const copies = readNoteDrafts("7").value;
    expect(copies).toHaveLength(2);
    expect(copies.find((item) => item.draftId === original.draftId)).toEqual(original);
    expect(copies.find((item) => item.body === "真正新增的理解")?.draftId).not.toBe(
      original.draftId,
    );
    act(() => {
      window.dispatchEvent(new Event("beforeunload", { cancelable: true }));
    });
    expect(readNoteDrafts("7").value).toHaveLength(2);
  });

  it("keeps the old content baseline through two refreshes until explicit merge", async () => {
    const original = {
      ...createNoteDraft(),
      noteId,
      title: "旧基线上的本机理解",
      body: "尚待合并的正文",
      baseVersion: 1,
      baseEtag: tag(),
      revision: 1,
    };
    storeNoteDraft("7", original);
    api.mockResolvedValue({ data: saved("云端版本二", "新的云端正文", 2), etag: tag(2) });
    for (let load = 0; load < 3; load++) {
      const page = render(<NoteComposer accountId="7" noteId={noteId} {...callbacks()} />);
      await screen.findByText(/服务器版本 2：云端版本二/);
      expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(
        true,
      );
      expect(readNoteDrafts("7").value).toEqual([original]);
      act(() => {
        window.dispatchEvent(new Event("beforeunload", { cancelable: true }));
      });
      expect(readNoteDrafts("7").value[0].baseVersion).toBe(1);
      if (load < 2) page.unmount();
    }
    fireEvent.change(screen.getByLabelText("Markdown 与公式原文"), {
      target: { value: "第三次恢复后继续改写，但尚未合并" },
    });
    expect(
      readNoteDrafts("7").value.find((item) => item.body === "第三次恢复后继续改写，但尚未合并")
        ?.baseVersion,
    ).toBe(1);
    expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "使用此版本标签，保留本地编辑" }));
    expect(readNoteDrafts("7").value.some((item) => item.baseVersion === 2)).toBe(true);
    expect(api.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
  });

  it.each(["changed", "removed"])(
    "preserves an unchanged editor snapshot when its original is %s elsewhere",
    async (state) => {
      const original = {
        ...createNoteDraft(),
        title: "当前标签仍在查看",
        body: "原本的理解",
        revision: 1,
      };
      storeNoteDraft("7", original);
      render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
      await screen.findByDisplayValue(original.title);
      if (state === "changed")
        storeNoteDraft("7", { ...original, body: "另一标签新改的内容", revision: 2 });
      else removeNoteDraft("7", original.draftId, 1);
      const leave = new Event("beforeunload", { cancelable: true });
      act(() => {
        window.dispatchEvent(leave);
      });
      expect(leave.defaultPrevented).toBe(false);
      const protectedCopy = readNoteDrafts("7").value.find((item) => item.body === original.body);
      expect(protectedCopy?.draftId).not.toBe(original.draftId);
      if (state === "changed")
        expect(
          readNoteDrafts("7").value.find((item) => item.draftId === original.draftId)?.body,
        ).toBe("另一标签新改的内容");
    },
  );

  it.each(["changed", "removed"])(
    "protects the current snapshot before internal back when its original was %s",
    async (state) => {
      const original = {
        ...createNoteDraft(),
        title: "内部离开前的恢复稿",
        body: "仍需保护的理解",
        revision: 1,
      };
      storeNoteDraft("7", original);
      const onBack = vi.fn();
      render(<NoteComposer accountId="7" noteId={null} {...callbacks()} onBack={onBack} />);
      await screen.findByDisplayValue(original.title);
      if (state === "changed")
        storeNoteDraft("7", { ...original, body: "另标签的版本", revision: 2 });
      else removeNoteDraft("7", original.draftId, 1);
      fireEvent.click(screen.getByRole("button", { name: "返回列表" }));
      expect(onBack).toHaveBeenCalledOnce();
      const protectedCopy = readNoteDrafts("7").value.find((item) => item.body === original.body);
      expect(protectedCopy?.draftId).not.toBe(original.draftId);
      if (state === "changed")
        expect(
          readNoteDrafts("7").value.find((item) => item.draftId === original.draftId)?.body,
        ).toBe("另标签的版本");
    },
  );

  it.each([false, true])(
    "keeps creation baseline one after replay version two when GET fails=%s, including refresh",
    async (getFails) => {
      const original = {
        ...createNoteDraft(),
        title: "原创建改稿",
        body: "我待确认的理解",
        revision: 1,
        createUncertain: true,
        createRequest: {
          key: "33333333-3333-4333-8333-333333333333",
          payload: JSON.stringify({ title: "原创建", bodyMarkdown: "原创建正文" }),
        },
      };
      storeNoteDraft("7", original);
      const remote = saved("另一端更新的版本二", "另一端正文", 2);
      api.mockImplementation((path: string) =>
        path !== "notes" && getFails
          ? Promise.reject(new Error("读取暂时失败"))
          : Promise.resolve({ data: remote, etag: tag(2) }),
      );
      const first = render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
      await screen.findByDisplayValue(original.title);
      fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
      if (getFails) await screen.findByText("读取暂时失败");
      else await screen.findByText(/服务器版本 2：另一端更新的版本二/);
      expect(screen.getByText("保存冲突：本地编辑已保留")).toBeTruthy();
      expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(
        true,
      );
      const known = readNoteDrafts("7").value.find((item) => item.noteId === noteId)!;
      expect(known.baseVersion).toBe(1);
      expect(known.createRequest).toBeNull();
      first.unmount();
      api.mockResolvedValue({ data: remote, etag: tag(2) });
      render(
        <NoteComposer accountId="7" noteId={noteId} draftId={known.draftId} {...callbacks()} />,
      );
      await screen.findByText(/服务器版本 2：另一端更新的版本二/);
      expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(
        true,
      );
      expect((screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement).value).toBe(
        original.body,
      );
      expect(api.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
    },
  );

  it.each(["changed", "removed"])(
    "protects a recovered snapshot in popstate capture before an earlier router listener unmounts it (%s)",
    async (state) => {
      const original = {
        ...createNoteDraft(),
        title: "历史返回前的恢复稿",
        body: "当前快照需要保留",
        revision: 1,
      };
      storeNoteDraft("7", original);
      let page: ReturnType<typeof render>;
      let seenBeforeUnmount: ReturnType<typeof readNoteDrafts>["value"] = [];
      const router = () => {
        seenBeforeUnmount = readNoteDrafts("7").value;
        page.unmount();
      };
      // 模拟先挂载的 Next bubble 监听，再挂载编辑器自己的 capture 监听。
      window.addEventListener("popstate", router);
      try {
        page = render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
        await screen.findByDisplayValue(original.title);
        if (state === "changed")
          storeNoteDraft("7", { ...original, body: "另一标签更新的正文", revision: 2 });
        else removeNoteDraft("7", original.draftId, 1);
        act(() => {
          window.dispatchEvent(new PopStateEvent("popstate"));
        });
        const protectedCopy = seenBeforeUnmount.find((item) => item.body === original.body);
        expect(protectedCopy?.draftId).not.toBe(original.draftId);
        expect(protectedCopy?.body).toBe(original.body);
        if (state === "changed")
          expect(seenBeforeUnmount.find((item) => item.draftId === original.draftId)?.body).toBe(
            "另一标签更新的正文",
          );
      } finally {
        window.removeEventListener("popstate", router);
      }
    },
  );

  it("does not create a copy on popstate when the recovered original is still protected", async () => {
    const original = {
      ...createNoteDraft(),
      title: "历史前进仍无修改",
      body: "原快照",
      revision: 1,
    };
    storeNoteDraft("7", original);
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByDisplayValue(original.title);
    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(readNoteDrafts("7").value).toEqual([original]);
  });

  it("does not resurrect an explicitly discarded draft on a later popstate", async () => {
    const original = {
      ...createNoteDraft(),
      title: "已明确丢弃的稿",
      body: "不应复活",
      revision: 1,
    };
    storeNoteDraft("7", original);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByDisplayValue(original.title);
    fireEvent.click(screen.getByText("笔记详情与操作"));
    fireEvent.click(screen.getByRole("button", { name: "丢弃本机草稿" }));
    expect(readNoteDrafts("7").value).toEqual([]);
    act(() => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(readNoteDrafts("7").value).toEqual([]);
  });

  it("does not intercept browser history when replacement persistence fails", async () => {
    const original = {
      ...createNoteDraft(),
      title: "历史离开前存储失效",
      body: "仍在内存",
      revision: 1,
    };
    storeNoteDraft("7", original);
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByDisplayValue(original.title);
    removeNoteDraft("7", original.draftId, 1);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    const event = new PopStateEvent("popstate", { cancelable: true });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(false);
    expect((screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement).value).toBe(
      original.body,
    );
    expect(screen.getByText("本机保护不可用 · 请保留页面")).toBeTruthy();
  });

  it("replays the same original request after refresh, then explicitly saves the revised draft", async () => {
    const creates: RequestInit[] = [];
    api.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "notes") {
        creates.push(options!);
        return Promise.reject(new Error("lost"));
      }
      return Promise.resolve({ data: saved(), etag: tag() });
    });
    const first = render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByLabelText("标题");
    input();
    fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
    await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
    input("改稿", "改正文");
    first.unmount();
    api.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "notes") {
        creates.push(options!);
        return Promise.resolve({ data: saved(), etag: null });
      }
      return Promise.resolve({
        data: options?.method === "PUT" ? saved("改稿", "改正文", 2) : saved(),
        etag: options?.method === "PUT" ? tag(2) : tag(),
      });
    });
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByDisplayValue("改稿");
    fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
    await screen.findByText(/笔记已保存。/);
    expect(creates).toHaveLength(2);
    expect(creates[1].body).toBe(creates[0].body);
    expect(creates[1].headers).toEqual(creates[0].headers);
    expect(readNoteDrafts("7").value).toEqual([]);
    expect(api.mock.calls.filter(([, options]) => options?.method === "PUT")).toHaveLength(1);
  });
  it("blocks first POST and internal departure when the original key cannot be stored", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    const onBack = vi.fn();
    render(<NoteComposer accountId="7" noteId={null} {...callbacks()} onBack={onBack} />);
    await screen.findByLabelText("标题");
    input();
    fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
    await screen.findByText(/暂未发起云端创建/);
    expect(api).not.toHaveBeenCalled();
    expect(screen.queryByText(/上次新建请求的结果未知/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "返回列表" }));
    expect(onBack).not.toHaveBeenCalled();
    expect((screen.getByLabelText("标题") as HTMLInputElement).value).toBe("原稿");
    expect(screen.getByText("本机保护不可用 · 请保留页面")).toBeTruthy();
  });
  it("keeps restored stale drafts pending merge until the user chooses the latest baseline", async () => {
    storeNoteDraft("7", {
      ...createNoteDraft(),
      noteId,
      title: "本机理解",
      body: "本机正文",
      baseVersion: 1,
      baseEtag: tag(),
      revision: 1,
    });
    api.mockResolvedValue({ data: saved("云端新稿", "云端正文", 2), etag: tag(2) });
    render(<NoteComposer accountId="7" noteId={noteId} {...callbacks()} />);
    await screen.findByText(/服务器版本 2：云端新稿/);
    expect((screen.getByLabelText("标题") as HTMLInputElement).value).toBe("本机理解");
    expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(true);
    expect(api.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "使用此版本标签，保留本地编辑" }));
    expect((screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement).value).toBe(
      "本机正文",
    );
  });
  it("forks separate keys when two tabs restore the same draft and avoids cleanup ABA", async () => {
    const original = {
      ...createNoteDraft(),
      noteId,
      title: "共同原稿",
      body: "共同正文",
      baseVersion: 1,
      baseEtag: tag(),
      revision: 5,
    };
    storeNoteDraft("7", original);
    api.mockImplementation((_path: string, options?: RequestInit) =>
      Promise.resolve({
        data: options?.method === "PUT" ? saved("标签甲", "共同正文", 2) : saved(),
        etag: options?.method === "PUT" ? tag(2) : tag(),
      }),
    );
    const first = render(
      <NoteComposer accountId="7" noteId={noteId} draftId={original.draftId} {...callbacks()} />,
    );
    await within(first.container).findByLabelText("标题");
    const second = render(
      <NoteComposer accountId="7" noteId={noteId} draftId={original.draftId} {...callbacks()} />,
    );
    await within(second.container).findByLabelText("标题");
    fireEvent.change(within(first.container).getByLabelText("标题"), {
      target: { value: "标签甲" },
    });
    fireEvent.change(within(second.container).getByLabelText("标题"), {
      target: { value: "标签乙" },
    });
    expect(new Set(readNoteDrafts("7").value.map((d) => d.draftId)).size).toBe(3);
    const priorFirst = readNoteDrafts("7").value.find((d) => d.title === "标签甲")!;
    fireEvent.click(within(first.container).getByRole("button", { name: "保存到云端" }));
    await within(first.container).findByText(/笔记已保存。/);
    fireEvent.change(within(first.container).getByLabelText("标题"), {
      target: { value: "甲保存后的新输入" },
    });
    const later = readNoteDrafts("7").value.find((d) => d.title === "甲保存后的新输入")!;
    expect(later.draftId).not.toBe(priorFirst.draftId);
    removeNoteDraft("7", original.draftId, original.revision);
    expect(
      readNoteDrafts("7")
        .value.map((d) => d.title)
        .sort(),
    ).toEqual(["甲保存后的新输入", "标签乙"].sort());
    expect((within(second.container).getByLabelText("标题") as HTMLInputElement).value).toBe(
      "标签乙",
    );
  });
  it("does not change the original notebook while a create outcome is uncertain", async () => {
    const books: Notebook[] = [
      "33333333-3333-4333-8333-333333333333",
      "44444444-4444-4444-8444-444444444444",
    ].map((id, index) => ({
      notebookId: id,
      name: `笔记本${index}`,
      revision: 1,
      updatedAt: "2026-10-25T00:00:00Z",
    }));
    api.mockRejectedValue(new LearningApiError(0, "W0001", "结果未知", ""));
    render(<NoteComposer accountId="7" noteId={null} notebooks={books} {...callbacks()} />);
    await screen.findByLabelText("标题");
    input();
    fireEvent.change(screen.getByLabelText("笔记本"), { target: { value: books[0].notebookId } });
    fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
    await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
    expect((screen.getByLabelText("笔记本") as HTMLSelectElement).disabled).toBe(true);
    expect(screen.getByText("先核对上次创建，再调整笔记本。")).toBeTruthy();
    expect(JSON.parse(readNoteDrafts("7").value[0].createRequest!.payload).context.notebookId).toBe(
      books[0].notebookId,
    );
  });
  it("shows a readable excerpt citation fallback while preserving exact raw Markdown in its draft", async () => {
    const raw = `原辅导的一整段 [[citation:${noteId}]]。`;
    render(
      <NoteComposer
        accountId="7"
        noteId={null}
        initialContext={{
          notebookId: null,
          questionId: "9",
          excerpt: { resultId: noteId, turnId: null, text: raw },
        }}
        {...callbacks()}
      />,
    );
    await screen.findByLabelText("标题");
    input();
    expect(screen.getByText("原辅导的一整段 [引用见原辅导]。")).toBeTruthy();
    expect(readNoteDrafts("7").value[0].context?.excerpt?.text).toBe(raw);
  });
  it("does not adopt a newer content baseline from an organization-only response", async () => {
    const bookId = "33333333-3333-4333-8333-333333333333";
    const latest = {
      ...saved("另一端的新正文", "另一端保存的内容", 2),
      context: { notebookId: bookId, questionId: null, revision: 2, excerpt: null },
    };
    api.mockImplementation((path: string) =>
      Promise.resolve({ data: path.endsWith("/context") ? latest : saved(), etag: tag() }),
    );
    const first = render(
      <NoteComposer
        accountId="7"
        noteId={noteId}
        notebooks={[
          { notebookId: bookId, name: "复习", revision: 1, updatedAt: "2026-10-25T00:00:00Z" },
        ]}
        {...callbacks()}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
    input("我的待保存理解", "我自己的改稿");
    fireEvent.change(screen.getByLabelText("笔记本"), { target: { value: bookId } });
    await screen.findByText("已更新笔记本归类。");
    expect(readNoteDrafts("7").value[0]).toMatchObject({
      baseVersion: 1,
      baseEtag: tag(),
      body: "我自己的改稿",
    });
    first.unmount();
    api.mockResolvedValue({ data: latest, etag: tag(2) });
    render(<NoteComposer accountId="7" noteId={noteId} {...callbacks()} />);
    await screen.findByText(/服务器版本 2：另一端的新正文/);
    expect((screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement).value).toBe(
      "我自己的改稿",
    );
    expect(screen.getByRole("button", { name: "保存到云端" }).hasAttribute("disabled")).toBe(true);
  });
  it("ignores a late saved response and toast after its account editor is destroyed", async () => {
    const flight = deferred<{ data: Note; etag: string }>();
    api.mockReturnValue(flight.promise);
    const page = render(<NoteComposer accountId="7" noteId={null} {...callbacks()} />);
    await screen.findByLabelText("标题");
    input();
    fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
    page.unmount();
    await act(async () => flight.resolve({ data: saved(), etag: tag() }));
    expect(show).not.toHaveBeenCalled();
    expect(readNoteDrafts("7").value[0].createRequest).not.toBeNull();
  });
});
