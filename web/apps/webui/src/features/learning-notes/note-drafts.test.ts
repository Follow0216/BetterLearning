import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createNoteDraft,
  isNoteDraftSnapshotProtected,
  sameNoteContext,
  readNoteDrafts,
  removeNoteDraft,
  storeNoteDraft,
} from "./note-drafts";

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("account partitioned local note drafts", () => {
  it("reads values only from the authenticated account partition", () => {
    const alice = { ...createNoteDraft(), title: "甲的理解", revision: 1 };
    const bob = { ...createNoteDraft(), title: "乙的理解", revision: 1 };
    storeNoteDraft("7", alice);
    storeNoteDraft("8", bob);
    const read = vi.spyOn(Storage.prototype, "getItem");
    expect(readNoteDrafts("7").value.map((draft) => draft.title)).toEqual(["甲的理解"]);
    expect(read.mock.calls.every(([key]) => key.startsWith("bl.notes.draft.v1:7:"))).toBe(true);
  });
  it("preserves multiple drafts and does not remove a later revision", () => {
    const first = { ...createNoteDraft(), title: "标签甲", revision: 1 };
    const second = { ...createNoteDraft(), title: "标签乙", revision: 1 };
    storeNoteDraft("7", first);
    storeNoteDraft("7", second);
    storeNoteDraft("7", { ...first, title: "甲的后续输入", revision: 2 });
    removeNoteDraft("7", first.draftId, 1);
    expect(readNoteDrafts("7").value).toHaveLength(2);
    removeNoteDraft("7", first.draftId, 2);
    expect(readNoteDrafts("7").value.map((draft) => draft.title)).toEqual(["标签乙"]);
  });
  it("reports quota and security errors without deleting existing contents", () => {
    const initial = { ...createNoteDraft(), title: "仍在本机", revision: 1 };
    storeNoteDraft("7", initial);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    expect(storeNoteDraft("7", { ...initial, title: "写失败", revision: 2 })).toContain("保存失败");
    expect(readNoteDrafts("7").value[0].title).toBe("仍在本机");
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    expect(readNoteDrafts("7").error).toContain("无法读取");
  });
  it("ignores malformed private records and refuses an unconfirmed account", () => {
    localStorage.setItem("bl.notes.draft.v1:7:bad", '{"title":"bad","context":42}');
    const read = vi.spyOn(Storage.prototype, "getItem");
    expect(readNoteDrafts("").error).toBeTruthy();
    expect(read).not.toHaveBeenCalled();
    expect(readNoteDrafts("7").value).toEqual([]);
  });
  it("reuses only the still-protected original snapshot and preserves another tab's later edit", () => {
    const original = { ...createNoteDraft(), title: "已保护的原稿", revision: 1 };
    storeNoteDraft("7", original);
    expect(
      isNoteDraftSnapshotProtected("7", original, {
        ...original,
        draftId: crypto.randomUUID(),
        baseEtag: '"read-tag"',
      }),
    ).toBe(true);
    const changedElsewhere = { ...original, title: "另一个标签的新理解", revision: 2 };
    storeNoteDraft("7", changedElsewhere);
    expect(isNoteDraftSnapshotProtected("7", original, original)).toBe(false);
    expect(readNoteDrafts("7").value).toEqual([changedElsewhere]);
    removeNoteDraft("7", original.draftId, 2);
    expect(isNoteDraftSnapshotProtected("7", original, original)).toBe(false);
  });
  it("matches the same question context independently of object property order", () => {
    expect(
      sameNoteContext(
        { questionId: "9", notebookId: null, excerpt: null },
        { notebookId: null, excerpt: null, questionId: "9" },
      ),
    ).toBe(true);
    expect(
      sameNoteContext(
        { questionId: "9", notebookId: null, excerpt: null },
        { questionId: "10", notebookId: null, excerpt: null },
      ),
    ).toBe(false);
    expect(sameNoteContext(null, { notebookId: null, questionId: null, excerpt: null })).toBe(
      false,
    );
  });
  it("protects an unchanged snapshot even if the context keys were constructed in another order", () => {
    const original = {
      ...createNoteDraft({ questionId: "9", notebookId: null, excerpt: null }),
      title: "语义相同",
      revision: 1,
    };
    storeNoteDraft("7", original);
    expect(
      isNoteDraftSnapshotProtected("7", original, {
        ...original,
        draftId: crypto.randomUUID(),
        context: { excerpt: null, notebookId: null, questionId: "9" },
      }),
    ).toBe(true);
  });
});
