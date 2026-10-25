import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const learningRequestMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/learning-v2-client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/learning-v2-client")>()),
  learningRequest: learningRequestMock,
}));
vi.mock("@/features/question-content/QuestionMarkdown", () => ({
  QuestionMarkdown: ({ bodyMarkdown }: { bodyMarkdown: string }) => <div>{bodyMarkdown}</div>,
}));

import { LearningApiError } from "@/lib/api/learning-v2-client";

import { NotesWorkspace } from "./NotesWorkspace";

const id = "11111111-1111-4111-8111-111111111111";
const note = (title: string, version = 1) => ({
  noteId: id,
  sourceId: "22222222-2222-4222-8222-222222222222",
  version,
  title,
  bodyMarkdown: `${title}正文`,
  indexStatus: "READY",
  updatedAt: "2026-10-25T12:00:00+08:00",
});
const page = (items: ReturnType<typeof note>[], nextCursor: string | null = null) => ({
  data: { items, nextCursor },
  etag: null,
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

afterEach(() => {
  cleanup();
  learningRequestMock.mockReset();
});

describe("NotesWorkspace", () => {
  it.each([400, 422])(
    "uses a new key and revised payload after a definite first-create %i rejection",
    async (status) => {
      const creates: Array<{ key: string; body: string }> = [];
      learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
        if (path === "sources?limit=20" || path === "notes?limit=20")
          return Promise.resolve(page([]));
        if (path === "notes" && options?.method === "POST") {
          creates.push({
            key: (options.headers as Record<string, string>)["Idempotency-Key"],
            body: options.body as string,
          });
          return creates.length === 1
            ? Promise.reject(new LearningApiError(status, "A0400", "创建请求无效", "trace-1"))
            : Promise.resolve({
                data: { ...note("修订稿"), bodyMarkdown: "修订正文" },
                etag: null,
              });
        }
        if (path === `notes/${id}`)
          return Promise.resolve({
            data: { ...note("修订稿"), bodyMarkdown: "修订正文" },
            etag: '"v1"',
          });
        throw new Error(`unexpected request: ${path}`);
      });

      render(<NotesWorkspace />);
      fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
      const title = screen.getByLabelText("标题") as HTMLInputElement;
      const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
      fireEvent.change(title, { target: { value: "原稿" } });
      fireEvent.change(body, { target: { value: "原正文" } });
      fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
      await screen.findByText("创建请求无效");
      expect(screen.queryByRole("button", { name: "确认上次创建并保存改稿" })).toBeNull();
      expect(title.value).toBe("原稿");
      expect(body.value).toBe("原正文");
      fireEvent.change(title, { target: { value: "修订稿" } });
      fireEvent.change(body, { target: { value: "修订正文" } });
      fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
      await waitFor(() => expect(screen.getByText(/笔记已保存/)).toBeTruthy());
      expect(creates).toHaveLength(2);
      expect(creates[0]).toEqual({
        key: expect.any(String),
        body: JSON.stringify({ title: "原稿", bodyMarkdown: "原正文" }),
      });
      expect(creates[1]).toEqual({
        key: expect.any(String),
        body: JSON.stringify({ title: "修订稿", bodyMarkdown: "修订正文" }),
      });
      expect(creates[1].key).not.toBe(creates[0].key);
    },
  );

  it.each([0, 503])(
    "keeps the original key and payload after an uncertain first-create %i failure",
    async (status) => {
      const creates: Array<{ key: string; body: string }> = [];
      learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
        if (path === "sources?limit=20" || path === "notes?limit=20")
          return Promise.resolve(page([]));
        if (path === "notes" && options?.method === "POST") {
          creates.push({
            key: (options.headers as Record<string, string>)["Idempotency-Key"],
            body: options.body as string,
          });
          return creates.length === 1
            ? Promise.reject(new LearningApiError(status, "W0001", "创建结果未知", "trace-1"))
            : Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
        }
        if (path === `notes/${id}` && options?.method === "PUT")
          return Promise.resolve({
            data: { ...note("改稿", 2), bodyMarkdown: "改正文" },
            etag: '"v2"',
          });
        if (path === `notes/${id}`)
          return Promise.resolve({
            data: { ...note("原稿"), bodyMarkdown: "原正文" },
            etag: '"v1"',
          });
        throw new Error(`unexpected request: ${path}`);
      });

      render(<NotesWorkspace />);
      fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
      const title = screen.getByLabelText("标题") as HTMLInputElement;
      const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
      fireEvent.change(title, { target: { value: "原稿" } });
      fireEvent.change(body, { target: { value: "原正文" } });
      fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
      await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
      fireEvent.change(title, { target: { value: "改稿" } });
      fireEvent.change(body, { target: { value: "改正文" } });
      fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
      await waitFor(() => expect(screen.getByText(/笔记已保存/)).toBeTruthy());
      expect(creates).toHaveLength(2);
      expect(creates[1]).toEqual(creates[0]);
    },
  );

  it("does not release the original key when a replay is rejected with 400", async () => {
    const creates: Array<{ key: string; body: string }> = [];
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST") {
        creates.push({
          key: (options.headers as Record<string, string>)["Idempotency-Key"],
          body: options.body as string,
        });
        if (creates.length === 1) return Promise.reject(new Error("response lost"));
        if (creates.length === 2)
          return Promise.reject(new LearningApiError(400, "A0400", "重放请求被拒绝", "trace-2"));
        return Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
      }
      if (path === `notes/${id}` && options?.method === "PUT")
        return Promise.resolve({
          data: { ...note("改稿", 2), bodyMarkdown: "改正文" },
          etag: '"v2"',
        });
      if (path === `notes/${id}`)
        return Promise.resolve({
          data: { ...note("原稿"), bodyMarkdown: "原正文" },
          etag: '"v1"',
        });
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
    fireEvent.change(title, { target: { value: "改稿" } });
    fireEvent.change(body, { target: { value: "改正文" } });
    fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
    await screen.findByText("重放请求被拒绝");
    expect(screen.getByRole("button", { name: "确认上次创建并保存改稿" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
    await waitFor(() => expect(screen.getByText(/笔记已保存/)).toBeTruthy());
    expect(creates).toHaveLength(3);
    expect(creates[1]).toEqual(creates[0]);
    expect(creates[2]).toEqual(creates[0]);
  });

  it.each([
    [409, "创建请求冲突"],
    [401, "登录状态已变化"],
  ])("blocks creation on %i without discarding the local draft", async (status, alertText) => {
    const creates: RequestInit[] = [];
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST") {
        creates.push(options);
        return Promise.reject(new LearningApiError(status, "A0409", "请求已阻断", "trace-1"));
      }
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByText(new RegExp(alertText));
    expect(screen.queryByRole("button", { name: "确认上次创建并保存改稿" })).toBeNull();
    expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(true);
    expect(title.value).toBe("原稿");
    expect(body.value).toBe("原正文");
    fireEvent.change(title, { target: { value: "仍保留的改稿" } });
    expect(title.value).toBe("仍保留的改稿");
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    expect(creates).toHaveLength(1);
  });

  it("retries an uncertain creation with its original key and body, then saves later edits with ETag", async () => {
    const createCalls: Array<{ key: string; body: string }> = [];
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST") {
        const key = (options.headers as Record<string, string>)["Idempotency-Key"];
        createCalls.push({ key, body: options.body as string });
        return createCalls.length === 1
          ? Promise.reject(new Error("response lost"))
          : Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
      }
      if (path === `notes/${id}` && options?.method === "PUT")
        return Promise.resolve({
          data: { ...note("改稿", 2), bodyMarkdown: "改正文" },
          etag: '"v2"',
        });
      if (path === `notes/${id}`)
        return Promise.resolve({
          data: { ...note("原稿"), bodyMarkdown: "原正文" },
          etag: '"v1"',
        });
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
    fireEvent.change(title, { target: { value: "改稿" } });
    fireEvent.change(body, { target: { value: "改正文" } });
    fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));

    await waitFor(() => expect(screen.getByText(/笔记已保存/)).toBeTruthy());
    expect(createCalls).toHaveLength(2);
    expect(createCalls[0].key).toBe(createCalls[1].key);
    expect(createCalls[0].body).toBe(JSON.stringify({ title: "原稿", bodyMarkdown: "原正文" }));
    expect(createCalls[1].body).toBe(createCalls[0].body);
    expect(learningRequestMock).toHaveBeenCalledWith(`notes/${id}`, {
      method: "PUT",
      headers: { "If-Match": '"v1"' },
      body: JSON.stringify({ title: "改稿", bodyMarkdown: "改正文" }),
      signal: expect.any(AbortSignal),
    });
    expect(title.value).toBe("改稿");
    expect(body.value).toBe("改正文");
  });

  it.each([1, 2])(
    "requires explicit merge when replay returns version %i and its ETag GET is at version 2",
    async (replayVersion) => {
      let creates = 0;
      const puts: Array<{ body: string; etag: string }> = [];
      learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
        if (path === "sources?limit=20" || path === "notes?limit=20")
          return Promise.resolve(page([]));
        if (path === "notes" && options?.method === "POST") {
          creates += 1;
          return creates === 1
            ? Promise.reject(new Error("response lost"))
            : Promise.resolve({
                data: { ...note("原稿", replayVersion), bodyMarkdown: "原正文" },
                etag: null,
              });
        }
        if (path === `notes/${id}` && options?.method === "PUT") {
          puts.push({
            body: options.body as string,
            etag: (options.headers as Record<string, string>)["If-Match"],
          });
          return Promise.resolve({
            data: { ...note("改稿", 3), bodyMarkdown: "改正文" },
            etag: '"v3"',
          });
        }
        if (path === `notes/${id}`)
          return Promise.resolve({
            data: { ...note("他人更新", 2), bodyMarkdown: "他人正文" },
            etag: '"v2"',
          });
        throw new Error(`unexpected request: ${path}`);
      });

      render(<NotesWorkspace />);
      fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
      const title = screen.getByLabelText("标题") as HTMLInputElement;
      const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
      fireEvent.change(title, { target: { value: "原稿" } });
      fireEvent.change(body, { target: { value: "原正文" } });
      fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
      await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
      fireEvent.change(title, { target: { value: "改稿" } });
      fireEvent.change(body, { target: { value: "改正文" } });
      fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));

      await screen.findByText(/服务器版本 2：他人更新/);
      expect(puts).toHaveLength(0);
      expect(title.value).toBe("改稿");
      expect(body.value).toBe("改正文");
      expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(true);
      fireEvent.click(screen.getByRole("button", { name: "使用此版本标签，保留本地编辑" }));
      expect(title.value).toBe("改稿");
      expect(body.value).toBe("改正文");
      fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
      await waitFor(() => expect(puts).toHaveLength(1));
      expect(puts[0]).toEqual({
        body: JSON.stringify({ title: "改稿", bodyMarkdown: "改正文" }),
        etag: '"v2"',
      });
      expect(creates).toBe(2);
    },
  );

  it("recovers a known created note after its ETag GET fails without another POST", async () => {
    let reads = 0;
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST")
        return Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
      if (path === `notes/${id}` && options?.method === "PUT")
        return Promise.resolve({
          data: { ...note("改稿", 2), bodyMarkdown: "改正文" },
          etag: '"v2"',
        });
      if (path === `notes/${id}`) {
        reads += 1;
        return reads === 1
          ? Promise.reject(new Error("GET failed"))
          : Promise.resolve({
              data: { ...note("原稿"), bodyMarkdown: "原正文" },
              etag: '"v1"',
            });
      }
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByRole("button", { name: "重新读取版本标签" });
    fireEvent.change(title, { target: { value: "改稿" } });
    fireEvent.change(body, { target: { value: "改正文" } });
    fireEvent.click(screen.getByRole("button", { name: "重新读取版本标签" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(false),
    );
    expect(title.value).toBe("改稿");
    expect(body.value).toBe("改正文");
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await waitFor(() => expect(screen.getByText(/笔记已保存/)).toBeTruthy());
    expect(
      learningRequestMock.mock.calls.filter(
        ([path, options]) => path === "notes" && options?.method === "POST",
      ),
    ).toHaveLength(1);
  });

  it("requires explicit merge if an ETag GET retry finds a newer version", async () => {
    let reads = 0;
    const puts: RequestInit[] = [];
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST")
        return Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
      if (path === `notes/${id}` && options?.method === "PUT") {
        puts.push(options);
        return Promise.resolve({ data: note("改稿", 3), etag: '"v3"' });
      }
      if (path === `notes/${id}`) {
        reads += 1;
        return reads === 1
          ? Promise.reject(new Error("GET failed"))
          : Promise.resolve({
              data: { ...note("他人更新", 2), bodyMarkdown: "他人正文" },
              etag: '"v2"',
            });
      }
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByRole("button", { name: "重新读取版本标签" });
    fireEvent.change(title, { target: { value: "改稿" } });
    fireEvent.change(body, { target: { value: "改正文" } });
    fireEvent.click(screen.getByRole("button", { name: "重新读取版本标签" }));

    await screen.findByText(/服务器版本 2：他人更新/);
    expect(title.value).toBe("改稿");
    expect(body.value).toBe("改正文");
    expect(puts).toHaveLength(0);
    expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "使用此版本标签，保留本地编辑" }));
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect((puts[0].headers as Record<string, string>)["If-Match"]).toBe('"v2"');
    expect(
      learningRequestMock.mock.calls.filter(
        ([path, options]) => path === "notes" && options?.method === "POST",
      ),
    ).toHaveLength(1);
  });

  it("retains later edits when the PUT after creation returns 412", async () => {
    let creates = 0;
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20" || path === "notes?limit=20")
        return Promise.resolve(page([]));
      if (path === "notes" && options?.method === "POST") {
        creates += 1;
        return creates === 1
          ? Promise.reject(new Error("response lost"))
          : Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: null });
      }
      if (path === `notes/${id}` && options?.method === "PUT")
        return Promise.reject(new LearningApiError(412, "A0412", "版本冲突", "trace-2"));
      if (path === `notes/${id}`)
        return Promise.resolve({ data: { ...note("原稿"), bodyMarkdown: "原正文" }, etag: '"v1"' });
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
    const title = screen.getByLabelText("标题") as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "原稿" } });
    fireEvent.change(body, { target: { value: "原正文" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));
    await screen.findByRole("button", { name: "确认上次创建并保存改稿" });
    fireEvent.change(title, { target: { value: "改稿" } });
    fireEvent.change(body, { target: { value: "改正文" } });
    fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
    await screen.findByText(/保存冲突：本地编辑已保留/);
    expect(title.value).toBe("改稿");
    expect(body.value).toBe("改正文");
    expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(true);
    expect(creates).toBe(2);
  });

  it("ignores a stale pagination response after refresh, preserving the new list and cursor", async () => {
    const oldMore = deferred<ReturnType<typeof page>>();
    let firstPage = 0;
    learningRequestMock.mockImplementation((path: string) => {
      if (path === "sources?limit=20") return Promise.resolve(page([]));
      if (path === "notes?limit=20") {
        firstPage += 1;
        return Promise.resolve(
          firstPage === 1
            ? page([note("旧笔记")], "old-cursor")
            : page([note("新笔记")], "fresh-cursor"),
        );
      }
      if (path.includes("cursor=old-cursor")) return oldMore.promise;
      if (path.includes("cursor=fresh-cursor")) return Promise.resolve(page([]));
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    await screen.findByRole("button", { name: "旧笔记" });
    fireEvent.click(screen.getByRole("button", { name: "加载更多笔记" }));
    await waitFor(() =>
      expect(learningRequestMock).toHaveBeenCalledWith(
        "notes?limit=20&cursor=old-cursor",
        expect.any(Object),
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "刷新列表" }));
    await screen.findByRole("button", { name: "新笔记" });

    await act(async () => oldMore.resolve(page([note("迟到旧页")], "stale-cursor")));
    expect(screen.queryByRole("button", { name: "旧笔记" })).toBeNull();
    expect(screen.queryByRole("button", { name: "迟到旧页" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "加载更多笔记" }));
    await waitFor(() =>
      expect(learningRequestMock).toHaveBeenCalledWith(
        "notes?limit=20&cursor=fresh-cursor",
        expect.any(Object),
      ),
    );
  });

  it("keeps local title and Markdown after a 412 until the user explicitly adopts a new ETag", async () => {
    let reads = 0;
    learningRequestMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "sources?limit=20") return Promise.resolve(page([]));
      if (path === "notes?limit=20") return Promise.resolve(page([note("原始笔记")]));
      if (path === `notes/${id}` && options?.method === "PUT") {
        return Promise.reject(new LearningApiError(412, "A0412", "版本冲突", "trace-1"));
      }
      if (path === `notes/${id}`) {
        reads += 1;
        return Promise.resolve({
          data: reads === 1 ? note("原始笔记") : note("服务器新版本", 2),
          etag: reads === 1 ? '"v1"' : '"v2"',
        });
      }
      throw new Error(`unexpected request: ${path}`);
    });

    render(<NotesWorkspace />);
    fireEvent.click(await screen.findByRole("button", { name: "原始笔记" }));
    const title = (await screen.findByLabelText("标题")) as HTMLInputElement;
    const body = screen.getByLabelText("Markdown 与公式原文") as HTMLTextAreaElement;
    fireEvent.change(title, { target: { value: "我的本地标题" } });
    fireEvent.change(body, { target: { value: "我的本地公式 $x^2$" } });
    fireEvent.click(screen.getByRole("button", { name: "保存笔记" }));

    await screen.findByRole("alert", { name: "" });
    expect(title.value).toBe("我的本地标题");
    expect(body.value).toBe("我的本地公式 $x^2$");
    expect(screen.getByRole("button", { name: "保存笔记" }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "读取服务器最新版本" }));
    await screen.findByText(/服务器版本 2/);
    expect(title.value).toBe("我的本地标题");
    expect(body.value).toBe("我的本地公式 $x^2$");
    fireEvent.click(screen.getByRole("button", { name: "使用此版本标签，保留本地编辑" }));
    expect(title.value).toBe("我的本地标题");
    expect(body.value).toBe("我的本地公式 $x^2$");
  });
});
