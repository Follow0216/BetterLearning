import { afterEach, describe, expect, it, vi } from "vitest";

import { LearningApiError, learningRequest } from "./learning-v2-client";

const id = "11111111-1111-4111-8111-111111111111";
const note = {
  noteId: id,
  sourceId: "22222222-2222-4222-8222-222222222222",
  version: 1,
  title: "学习笔记",
  bodyMarkdown: "公式 $x$",
  indexStatus: "INDEX_PENDING",
  updatedAt: "2026-10-25T12:00:00+08:00",
};

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe("learningRequest", () => {
  it("preserves the caller's idempotency key and sends a same-origin, uncached create", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(note), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await learningRequest("notes", {
      method: "POST",
      headers: { "Idempotency-Key": id },
      body: JSON.stringify({ title: note.title, bodyMarkdown: note.bodyMarkdown }),
    });

    expect(result.data).toEqual(note);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe("/api/learning/notes");
    expect(init.cache).toBe("no-store");
    expect(init.credentials).toBe("same-origin");
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(id);
  });

  it("preserves If-Match and exposes a 412 conflict without retrying the write", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: "A0412", message: "版本已变化", traceId: "trace-1" }), {
        status: 412,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      learningRequest(`notes/${id}`, {
        method: "PUT",
        headers: { "If-Match": '"v1"' },
        body: JSON.stringify({ title: "新标题", bodyMarkdown: "正文" }),
      }),
    ).rejects.toMatchObject({
      status: 412,
      code: "A0412",
      message: "版本已变化",
      traceId: "trace-1",
    });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get("If-Match")).toBe(
      '"v1"',
    );
  });

  it("rejects an in-flight response after a different account epoch replaces the original", async () => {
    localStorage.setItem("bl.session.epoch", id);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => {
        localStorage.setItem("bl.session.epoch", "33333333-3333-4333-8333-333333333333");
        return new Response(JSON.stringify(note), { status: 200 });
      }),
    );
    await expect(learningRequest(`notes/${id}`)).rejects.toMatchObject({
      status: 401,
      message: "账号已变更，请重新加载资料",
    });
  });

  it("initializes the epoch before fetching in a new browser context", async () => {
    localStorage.clear();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => {
        expect(localStorage.getItem("bl.session.epoch")).toMatch(/^[0-9a-f-]{36}$/);
        return new Response(JSON.stringify(note), { status: 200 });
      }),
    );
    await expect(learningRequest(`notes/${id}`)).resolves.toMatchObject({ data: note });
  });

  it("rejects encoded path traversal before sending a request", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(learningRequest("notes/%2e%2e/sources")).rejects.toBeInstanceOf(LearningApiError);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
