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
