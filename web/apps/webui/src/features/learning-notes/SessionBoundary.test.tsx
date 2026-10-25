import { act, cleanup, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const navigate = vi.hoisted(() => vi.fn());
vi.mock("@/features/auth/session-events", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/auth/session-events")>()),
  returnToAccount: navigate,
}));

import { SESSION_EPOCH_STORAGE_KEY } from "@/features/auth/session-events";

import { SessionBoundary } from "./SessionBoundary";

const first = "11111111-1111-4111-8111-111111111111";
const second = "22222222-2222-4222-8222-222222222222";

class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = [];
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  constructor(readonly name: string) {
    FakeBroadcastChannel.instances.push(this);
  }
  close() {}
  static emit(value: unknown) {
    for (const instance of FakeBroadcastChannel.instances)
      instance.onmessage?.({ data: value } as MessageEvent<unknown>);
  }
}

describe("SessionBoundary", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, first);
    FakeBroadcastChannel.instances = [];
    navigate.mockReset();
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
  });
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("removes private content for two mounted instances on a cross-tab change message", () => {
    render(
      <>
        <SessionBoundary>
          <span>第一处私人内容</span>
        </SessionBoundary>
        <SessionBoundary>
          <span>第二处私人内容</span>
        </SessionBoundary>
      </>,
    );
    expect(FakeBroadcastChannel.instances).toHaveLength(2);
    act(() => FakeBroadcastChannel.emit({ kind: "changed", epoch: second }));
    expect(screen.queryByText("第一处私人内容")).toBeNull();
    expect(screen.queryByText("第二处私人内容")).toBeNull();
    expect(screen.getAllByRole("status")).toHaveLength(2);
    expect(navigate).toHaveBeenCalledTimes(2);
  });

  it("clears private content on focus when a broadcast was missed", () => {
    render(
      <SessionBoundary>
        <span>私人笔记内容</span>
      </SessionBoundary>,
    );
    window.localStorage.setItem(SESSION_EPOCH_STORAGE_KEY, second);
    act(() => window.dispatchEvent(new Event("focus")));
    expect(screen.queryByText("私人笔记内容")).toBeNull();
    expect(navigate).toHaveBeenCalledOnce();
  });

  it("still handles the legacy session-ended event", () => {
    render(
      <SessionBoundary>
        <span>私人笔记内容</span>
      </SessionBoundary>,
    );
    act(() => window.dispatchEvent(new Event("bl:session-ended")));
    expect(screen.queryByText("私人笔记内容")).toBeNull();
  });

  it("runs private child cleanup before returning to the account page", () => {
    const abort = vi.fn();
    function PrivateContent() {
      useEffect(() => () => abort(), []);
      return <span>正在加载的私有资料</span>;
    }
    navigate.mockImplementation(() => expect(abort).toHaveBeenCalledOnce());
    render(
      <SessionBoundary>
        <PrivateContent />
      </SessionBoundary>,
    );

    act(() => window.dispatchEvent(new Event("bl:session-ended")));

    expect(abort).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledOnce();
  });
});
