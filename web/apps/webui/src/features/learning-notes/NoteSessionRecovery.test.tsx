import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { announceSessionChange, returnToAccount } from "@/features/auth/session-events";
import { readNoteDrafts } from "./note-drafts";

vi.mock("@/features/auth/session-events", async (original) => ({
  ...(await original<typeof import("@/features/auth/session-events")>()),
  returnToAccount: vi.fn(),
}));
vi.mock("@betterlearning/ui-feedback", () => ({
  useToast: () => ({ captureScope: () => 1, show: vi.fn() }),
}));
vi.mock("@/features/question-content/QuestionMarkdown", () => ({
  QuestionMarkdown: ({ bodyMarkdown }: { bodyMarkdown: string }) => <div>{bodyMarkdown}</div>,
}));
import { NotesWorkspace } from "./NotesWorkspace";
import { SessionBoundary } from "./SessionBoundary";

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("keeps an expired account's original create request private and restores it after reauthentication", async () => {
  let accountId = "7";
  const noteId = "11111111-1111-4111-8111-111111111111";
  const posts: { key: string | null; body: string }[] = [];
  const cloud = {
    noteId,
    sourceId: "22222222-2222-4222-8222-222222222222",
    version: 1,
    title: "账号甲的理解",
    bodyMarkdown: "甲的原稿 $x^2$",
    indexStatus: "READY",
    updatedAt: "2026-10-25T00:00:00Z",
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string, options?: RequestInit) => {
      if (input === "/api/auth/session")
        return Response.json({
          user: {
            accountId,
            username: `user_${accountId}`,
            phone: null,
            email: null,
            role: "USER",
            createdAt: cloud.updatedAt,
            updatedAt: cloud.updatedAt,
          },
        });
      if (input === "/api/learning/notebooks") return Response.json({ items: [] });
      if (options?.method === "POST") {
        posts.push({
          key: new Headers(options.headers).get("Idempotency-Key"),
          body: options.body as string,
        });
        return posts.length === 1
          ? Response.json({ code: "A0200", message: "会话过期", traceId: "" }, { status: 401 })
          : Response.json(cloud, { status: 201 });
      }
      if (input === `/api/learning/notes/${noteId}`)
        return Response.json(cloud, { headers: { ETag: `"${noteId}:1"` } });
      return Response.json({ items: [], nextCursor: null });
    }),
  );
  const page = () => (
    <SessionBoundary>
      <NotesWorkspace />
    </SessionBoundary>
  );
  let mounted = render(page());
  await screen.findByRole("button", { name: "新建笔记" });
  fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
  await screen.findByLabelText("标题");
  fireEvent.change(screen.getByLabelText("标题"), { target: { value: cloud.title } });
  fireEvent.change(screen.getByLabelText("Markdown 与公式原文"), {
    target: { value: cloud.bodyMarkdown },
  });
  fireEvent.click(screen.getByRole("button", { name: "保存到云端" }));
  await waitFor(() => expect(returnToAccount).toHaveBeenCalled());
  expect(screen.queryByDisplayValue(cloud.title)).toBeNull();
  expect(readNoteDrafts("7").value[0].createRequest?.payload).toBe(posts[0].body);
  mounted.unmount();

  accountId = "8";
  act(() => {
    announceSessionChange("changed");
  });
  const reads = vi.spyOn(Storage.prototype, "getItem");
  mounted = render(page());
  await screen.findByRole("button", { name: "新建笔记" });
  expect(reads.mock.calls.some(([key]) => key.startsWith("bl.notes.draft.v1:7:"))).toBe(false);
  expect(screen.queryByText(cloud.title)).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "新建笔记" }));
  await screen.findByLabelText("标题");
  expect((screen.getByLabelText("标题") as HTMLInputElement).value).toBe("");
  fireEvent.change(screen.getByLabelText("标题"), { target: { value: "账号乙的独立草稿" } });
  mounted.unmount();
  reads.mockRestore();

  accountId = "7";
  act(() => {
    announceSessionChange("changed");
  });
  render(page());
  await screen.findByText("本机草稿 · 1");
  fireEvent.click(screen.getByText("本机草稿 · 1"));
  fireEvent.click(screen.getByRole("button", { name: /账号甲的理解/ }));
  await screen.findByDisplayValue(cloud.title);
  expect(screen.queryByText("账号乙的独立草稿")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "确认上次创建并保存改稿" }));
  await screen.findByText(/笔记已保存。/);
  expect(posts).toHaveLength(2);
  expect(posts[1]).toEqual(posts[0]);
  expect(readNoteDrafts("7").value).toEqual([]);
  expect(readNoteDrafts("8").value[0].title).toBe("账号乙的独立草稿");
});
