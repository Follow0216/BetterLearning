import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SessionBoundary } from "./SessionBoundary";

class FakeBroadcastChannel {
  onmessage: (() => void) | null = null;
  close = vi.fn();
}

describe("SessionBoundary", () => {
  beforeEach(() => vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel));
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("removes private content as soon as the session ends", () => {
    render(
      <SessionBoundary>
        <span>私人笔记内容</span>
      </SessionBoundary>,
    );
    expect(screen.getByText("私人笔记内容")).toBeTruthy();

    act(() => window.dispatchEvent(new Event("bl:session-ended")));

    expect(screen.queryByText("私人笔记内容")).toBeNull();
    expect(screen.getByRole("status").textContent).toContain("登录状态已结束");
  });
});
