import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { announceSessionChange } from "@/features/auth/session-events";
import { useNoteAccount } from "./use-note-account";

vi.mock("@/features/auth/session-events", async (original) => ({
  ...(await original<typeof import("@/features/auth/session-events")>()),
  returnToAccount: vi.fn(),
}));
const profile = {
  accountId: "7",
  username: "alice",
  phone: null,
  email: null,
  role: "USER",
  createdAt: "2026-10-25T00:00:00Z",
  updatedAt: "2026-10-25T00:00:00Z",
};
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("verified note account", () => {
  it("waits for a verified account and clears it immediately when the session changes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ user: profile })));
    const hook = renderHook(useNoteAccount);
    expect(hook.result.current.accountId).toBeNull();
    await waitFor(() => expect(hook.result.current.accountId).toBe("7"));
    act(() => {
      announceSessionChange("changed");
    });
    expect(hook.result.current.accountId).toBeNull();
  });
  it("ignores identity that arrives after another account epoch", async () => {
    let resolve!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    const hook = renderHook(useNoteAccount);
    act(() => {
      announceSessionChange("changed");
    });
    await act(async () => resolve(Response.json({ user: profile })));
    expect(hook.result.current.accountId).toBeNull();
  });
  it("never authenticates a malformed profile or a failed response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ user: { accountId: "7" } })));
    const hook = renderHook(useNoteAccount);
    await waitFor(() => expect(hook.result.current.error).toContain("无法核验"));
    expect(hook.result.current.accountId).toBeNull();
  });
});
