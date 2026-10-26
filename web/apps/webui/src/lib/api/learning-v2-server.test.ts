import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { handleLearningRequest } from "./learning-v2-server";

const id = "11111111-1111-4111-8111-111111111111";
const note = {
  noteId: id,
  sourceId: "22222222-2222-4222-8222-222222222222",
  version: 1,
  title: "学习笔记",
  bodyMarkdown: "正文",
  indexStatus: "INDEX_PENDING",
  updatedAt: "2026-10-25T12:00:00+08:00",
};

afterEach(() => vi.unstubAllGlobals());

function request(path: string): NextRequest {
  return new NextRequest(`http://localhost:3000/api/learning/${path}`);
}

function mutation(
  path: string,
  method: "POST" | "PUT" | "DELETE",
  extraHeaders: Record<string, string>,
): NextRequest {
  return new NextRequest(`http://localhost:3000/api/learning/${path}`, {
    method,
    headers: {
      Host: "localhost:3000",
      Origin: "http://localhost:3000",
      Cookie: "bl_session=private-token",
      ...(method === "DELETE" ? {} : { "Content-Type": "application/json" }),
      ...extraHeaders,
    },
    body:
      method === "DELETE" ? undefined : JSON.stringify({ title: "学习笔记", bodyMarkdown: "正文" }),
  });
}

describe("learning BFF route allowlist", () => {
  it("recognizes an allowed note route before requiring a session", async () => {
    const response = await handleLearningRequest(request("notes"), ["notes"], "GET");
    expect(response.status).toBe(401);
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(response.headers.get("set-cookie")).toContain("bl_session=");
  });

  it.each([
    [["notes", "../sources"], "GET"],
    [["citations", id, "assets", "../0"], "GET"],
    [["citations", id, "assets", "99999999999999999999999"], "GET"],
    [["questions", "0", "tutor-requests"], "GET"],
    [["questions", "1", "tutor-requests", id, "retry"], "POST"],
  ] as const)("rejects an unapproved path or method: %j %s", async (segments, method) => {
    const response = await handleLearningRequest(
      request(segments.join("/")),
      [...segments],
      method,
    );
    expect(response.status).toBe(404);
  });

  it("rejects unexpected and duplicate query parameters", async () => {
    const unexpected = await handleLearningRequest(request("notes?accountId=1"), ["notes"], "GET");
    const duplicate = await handleLearningRequest(
      request("notes?limit=20&limit=100"),
      ["notes"],
      "GET",
    );
    expect(unexpected.status).toBe(404);
    expect(duplicate.status).toBe(404);
  });

  it("recognizes only a safe citation asset index", async () => {
    const response = await handleLearningRequest(
      request(`citations/${id}/assets/0`),
      ["citations", id, "assets", "0"],
      "GET",
    );
    expect(response.status).toBe(401);
  });

  it("forwards the same client idempotency key on repeated note creates", async () => {
    const fetchMock = vi.fn().mockImplementation(
      async () =>
        new Response(JSON.stringify(note), {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const first = await handleLearningRequest(
      mutation("notes", "POST", { "Idempotency-Key": id }),
      ["notes"],
      "POST",
    );
    const second = await handleLearningRequest(
      mutation("notes", "POST", { "Idempotency-Key": id }),
      ["notes"],
      "POST",
    );
    expect(first.status).toBe(201);
    expect(second.status).toBe(201);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(new Headers(init.headers).get("Idempotency-Key")).toBe(id);
      expect(new Headers(init.headers).get("Authorization")).toBe("Bearer private-token");
      expect(init.cache).toBe("no-store");
    }
  });

  it.each(["PUT", "DELETE"] as const)(
    "forwards %s If-Match and preserves backend 412",
    async (method) => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "A0412", message: "版本冲突", traceId: "trace-1" }), {
          status: 412,
          headers: { "Content-Type": "application/json" },
        }),
      );
      vi.stubGlobal("fetch", fetchMock);
      const response = await handleLearningRequest(
        mutation(`notes/${id}`, method, { "If-Match": '"v1"' }),
        ["notes", id],
        method,
      );
      expect(response.status).toBe(412);
      expect((await response.json()).traceId).toBe("trace-1");
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get("If-Match")).toBe(
        '"v1"',
      );
    },
  );

  it.each([413, 422] as const)(
    "preserves backend %s for an authenticated valid note create",
    async (status) => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ code: "A0400", message: "笔记正文不符合要求", traceId: "trace-1" }),
            { status, headers: { "Content-Type": "application/json" } },
          ),
        );
      vi.stubGlobal("fetch", fetchMock);
      const response = await handleLearningRequest(
        mutation("notes", "POST", { "Idempotency-Key": id }),
        ["notes"],
        "POST",
      );
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(response.status).toBe(status);
      expect(response.headers.get("cache-control")).toContain("no-store");
      expect((await response.json()).traceId).toBe("trace-1");
    },
  );

  it("never fetches an unknown route even with an authenticated cookie", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await handleLearningRequest(
      mutation(`notes/${id}/export`, "DELETE", { "If-Match": '"v1"' }),
      ["notes", id, "export"],
      "DELETE",
    );
    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("clears the session cookie when the backend rejects the token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "A0200", message: "未登录", traceId: "" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    const response = await handleLearningRequest(
      new NextRequest(`http://localhost:3000/api/learning/notes`, {
        headers: { Cookie: "bl_session=private-token" },
      }),
      ["notes"],
      "GET",
    );
    expect(response.status).toBe(401);
    expect(response.headers.get("set-cookie")).toContain("bl_session=");
    expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("rejects a backend note whose noteId differs from the requested path", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            ...note,
            noteId: "33333333-3333-4333-8333-333333333333",
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    const response = await handleLearningRequest(
      new NextRequest(`http://localhost:3000/api/learning/notes/${id}`, {
        headers: { Cookie: "bl_session=private-token" },
      }),
      ["notes", id],
      "GET",
    );
    expect(response.status).toBe(502);
    expect(response.headers.get("cache-control")).toContain("no-store");
  });
});

function workspaceMutation(
  path: string,
  method: "POST" | "PUT" | "DELETE",
  body: unknown,
  headers: Record<string, string> = {},
) {
  return new NextRequest(`http://localhost:3000/api/learning/${path}`, {
    method,
    headers: {
      Host: "localhost:3000",
      Origin: "http://localhost:3000",
      Cookie: "bl_session=private-token",
      ...(method === "DELETE" ? {} : { "Content-Type": "application/json" }),
      ...headers,
    },
    ...(method === "DELETE" ? {} : { body: JSON.stringify(body) }),
  });
}
describe("note workspace BFF contract", () => {
  it("forwards real keyword and combination filters but rejects duplicate or foreign query fields", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(async () => Response.json({ items: [note], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    const response = await handleLearningRequest(
      new NextRequest(
        `http://localhost:3000/api/learning/notes?q=100%25_%5C&notebookId=${id}&questionId=9&limit=1`,
        { headers: { Cookie: "bl_session=private-token" } },
      ),
      ["notes"],
      "GET",
    );
    expect(response.status).toBe(200);
    const upstream = new URL(fetchMock.mock.calls[0][0]);
    expect(upstream.searchParams.get("q")).toBe("100%_\\");
    expect(upstream.searchParams.get("notebookId")).toBe(id);
    expect(upstream.searchParams.get("questionId")).toBe("9");
    expect((await handleLearningRequest(request("notes?q=a&q=b"), ["notes"], "GET")).status).toBe(
      404,
    );
    expect(
      (await handleLearningRequest(request("notes?q=a&accountId=7"), ["notes"], "GET")).status,
    ).toBe(404);
  });
  it("preserves the exact old dual-field payload and forwards optional provenance without credentials", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => Response.json(note, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);
    const old = { title: "学习笔记", bodyMarkdown: "正文" };
    expect(
      (
        await handleLearningRequest(
          workspaceMutation("notes", "POST", old, { "Idempotency-Key": id }),
          ["notes"],
          "POST",
        )
      ).status,
    ).toBe(201);
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify(old));
    const context = {
      notebookId: id,
      questionId: "9",
      excerpt: { resultId: id, turnId: null, text: "原文段落" },
    };
    expect(
      (
        await handleLearningRequest(
          workspaceMutation("notes", "POST", { ...old, context }, { "Idempotency-Key": id }),
          ["notes"],
          "POST",
        )
      ).status,
    ).toBe(201);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).context).toEqual(context);
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notes/${id}`, "PUT", { ...old, context }, { "If-Match": '"old"' }),
          ["notes", id],
          "PUT",
        )
      ).status,
    ).toBe(400);
  });
  it("forwards independent filing CAS and requires explicit notebook deletion confirmation", async () => {
    const notebook = { notebookId: id, name: "高数", revision: 2, updatedAt: note.updatedAt };
    const fetchMock = vi
      .fn()
      .mockImplementation(async (_url, options) =>
        options.method === "DELETE"
          ? new Response(null, { status: 204 })
          : Response.json(options.body.includes("notebookId") ? note : notebook),
      );
    vi.stubGlobal("fetch", fetchMock);
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(
            `notes/${id}/context`,
            "PUT",
            { notebookId: null },
            { "If-Match": `"context:${id}:1"` },
          ),
          ["notes", id, "context"],
          "PUT",
        )
      ).status,
    ).toBe(200);
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get("If-Match")).toBe(
      `"context:${id}:1"`,
    );
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notebooks/${id}`, "PUT", { name: "高数" }),
          ["notebooks", id],
          "PUT",
        )
      ).status,
    ).toBe(400);
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notebooks/${id}?confirm=false`, "DELETE", null, {
            "If-Match": `"notebook:${id}:2"`,
          }),
          ["notebooks", id],
          "DELETE",
        )
      ).status,
    ).toBe(404);
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notebooks/${id}?confirm=true`, "DELETE", null, {
            "If-Match": `"notebook:${id}:2"`,
          }),
          ["notebooks", id],
          "DELETE",
        )
      ).status,
    ).toBe(204);
  });
  it("preserves notebook precondition errors and rejects a response for another notebook", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json({ code: "A0400", traceId: "revision-1" }, { status: 412 }),
        ),
    );
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notebooks/${id}`, "PUT", { name: "改名" }, { "If-Match": '"old"' }),
          ["notebooks", id],
          "PUT",
        )
      ).status,
    ).toBe(412);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          notebookId: note.sourceId,
          name: "错本",
          revision: 1,
          updatedAt: note.updatedAt,
        }),
      ),
    );
    expect(
      (
        await handleLearningRequest(
          workspaceMutation(`notebooks/${id}`, "PUT", { name: "改名" }, { "If-Match": '"old"' }),
          ["notebooks", id],
          "PUT",
        )
      ).status,
    ).toBe(502);
  });
});
