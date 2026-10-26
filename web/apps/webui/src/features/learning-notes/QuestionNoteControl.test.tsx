import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QuestionDetailTabs } from "@/features/library/QuestionDetailTabs";
import { createNoteDraft, readNoteDrafts, removeNoteDraft, storeNoteDraft } from "./note-drafts";
import { QuestionNoteControl, TutorExcerptControl } from "./QuestionNoteControl";

vi.mock("@betterlearning/ui-feedback", () => ({
  useToast: () => ({ captureScope: () => 1, show: vi.fn() }),
}));
vi.mock("./use-note-account", () => ({ useNoteAccount: () => ({ accountId: "7", error: "" }) }));
vi.mock("@/lib/api/learning-v2-client", async (original) => ({
  ...(await original<typeof import("@/lib/api/learning-v2-client")>()),
  learningRequest: vi.fn().mockResolvedValue({ data: { items: [] }, etag: null }),
}));
vi.mock("@/features/question-content/QuestionMarkdown", () => ({
  QuestionMarkdown: ({
    bodyMarkdown,
    citations,
    onCitationClick,
  }: {
    bodyMarkdown: string;
    citations?: { citationId: string }[];
    onCitationClick?: (id: string) => void;
  }) => (
    <div>
      {bodyMarkdown}
      {citations?.map((item) => (
        <button
          key={item.citationId}
          aria-label="段落引用"
          onClick={() => onCitationClick?.(item.citationId)}
        >
          引用
        </button>
      ))}
    </div>
  ),
}));
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true;
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = false;
    },
  });
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});
describe("question notes preserve the question context", () => {
  it("keeps the original tab and mounted answer view after a capture closes", async () => {
    render(
      <>
        <QuestionDetailTabs
          answer={<input aria-label="原作答视图" defaultValue="已有视图状态" />}
          tutor={<p>原辅导记录</p>}
        />
        <QuestionNoteControl questionId="9" questionMarkdown="题面 $x^2$" />
      </>,
    );
    const answer = screen.getByLabelText("原作答视图");
    fireEvent.click(screen.getByRole("tab", { name: "AI 辅导" }));
    const entry = screen.getByRole("button", { name: "记一笔" });
    entry.focus();
    fireEvent.click(entry);
    await screen.findByLabelText("标题");
    expect(screen.getByText("题面 $x^2$")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("标题"), { target: { value: "当前理解" } });
    fireEvent.click(screen.getByRole("button", { name: "关闭随题摘记" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByRole("tab", { name: "AI 辅导" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByLabelText("原作答视图")).toBe(answer);
    expect(document.activeElement).toBe(entry);
  });
  it("blocks both close button and Escape while storage failure leaves input only in memory", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    render(<QuestionNoteControl questionId="9" questionMarkdown="原题" />);
    fireEvent.click(screen.getByRole("button", { name: "记一笔" }));
    await screen.findByLabelText("标题");
    fireEvent.change(screen.getByLabelText("标题"), { target: { value: "不能丢的理解" } });
    fireEvent.click(screen.getByRole("button", { name: "关闭随题摘记" }));
    expect(screen.getByRole("dialog")).toBeTruthy();
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { bubbles: true, cancelable: true }));
    expect((screen.getByLabelText("标题") as HTMLInputElement).value).toBe("不能丢的理解");
    expect(screen.getByText(/先保存到云端或复制内容；也可明确丢弃草稿后关闭/)).toBeTruthy();
  });
  it("passes the original citation dictionary and click handler into excerpt selection", () => {
    const id = "11111111-1111-4111-8111-111111111111",
      click = vi.fn();
    render(
      <TutorExcerptControl
        questionId="9"
        resultId={id}
        bodyMarkdown={`原段落 [[citation:${id}]]。`}
        citations={[
          {
            citationId: id,
            sourceId: id,
            version: 1,
            anchorId: id,
            label: "1",
            sourceType: "NOTE",
          },
        ]}
        onCitationClick={click}
      />,
    );
    fireEvent.click(screen.getByText("摘录辅导段落到笔记"));
    fireEvent.click(screen.getByRole("button", { name: "段落引用" }));
    expect(click).toHaveBeenCalledWith(id);
  });
  it.each(["button", "escape"])(
    "protects a recovered snapshot on modal %s after its original disappears",
    async (method) => {
      const original = {
        ...createNoteDraft({ notebookId: null, questionId: "9", excerpt: null }),
        title: "弹层中恢复的理解",
        body: "不能因关闭丢掉",
        revision: 1,
      };
      storeNoteDraft("7", original);
      render(<QuestionNoteControl questionId="9" questionMarkdown="原题" />);
      fireEvent.click(screen.getByRole("button", { name: "记一笔" }));
      await screen.findByDisplayValue(original.title);
      expect(readNoteDrafts("7").value).toEqual([original]);
      removeNoteDraft("7", original.draftId, 1);
      if (method === "button")
        fireEvent.click(screen.getByRole("button", { name: "关闭随题摘记" }));
      else
        fireEvent(
          screen.getByRole("dialog"),
          new Event("cancel", { bubbles: true, cancelable: true }),
        );
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      const protectedCopy = readNoteDrafts("7").value[0];
      expect(protectedCopy.body).toBe(original.body);
      expect(protectedCopy.draftId).not.toBe(original.draftId);
    },
  );

  it("blocks modal close if its recovered original disappeared and the replacement cannot be stored", async () => {
    const original = {
      ...createNoteDraft({ notebookId: null, questionId: "9", excerpt: null }),
      title: "仍在弹层中的稿",
      body: "还需要保存",
      revision: 1,
    };
    storeNoteDraft("7", original);
    render(<QuestionNoteControl questionId="9" questionMarkdown="原题" />);
    fireEvent.click(screen.getByRole("button", { name: "记一笔" }));
    await screen.findByDisplayValue(original.title);
    removeNoteDraft("7", original.draftId, 1);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    fireEvent.click(screen.getByRole("button", { name: "关闭随题摘记" }));
    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(screen.getByDisplayValue(original.title)).toBeTruthy();
    expect(screen.getByText(/先保存到云端或复制内容；也可明确丢弃草稿后关闭/)).toBeTruthy();
  });
});
