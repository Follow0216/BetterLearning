import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { trapDialogTab } from "./dialog-focus";

function makeDialog(markup: string): HTMLDialogElement {
  const dialog = document.createElement("dialog");
  dialog.tabIndex = -1;
  dialog.setAttribute("open", "");
  dialog.innerHTML = markup;
  document.body.append(dialog);
  return dialog;
}

function tab(dialog: HTMLDialogElement, options: KeyboardEventInit = {}): KeyboardEvent {
  const event = new KeyboardEvent("keydown", {
    key: "Tab",
    bubbles: true,
    cancelable: true,
    ...options,
  });
  trapDialogTab(event, dialog);
  return event;
}

beforeEach(() => {
  // jsdom has no layout. Give rendered elements a rect while retaining hidden ancestors.
  vi.spyOn(Element.prototype, "getClientRects").mockImplementation(function (this: Element) {
    const visible = !this.closest("[hidden]");
    const rectangles = visible ? [new DOMRect(0, 0, 10, 10)] : [];
    return Object.assign(rectangles, {
      item: (index: number) => rectangles[index] ?? null,
    });
  });
});

afterEach(() => {
  document.body.replaceChildren();
  vi.restoreAllMocks();
});

describe("trapDialogTab", () => {
  it("keeps both Tab directions on the only close button", () => {
    const dialog = makeDialog('<button id="close">关闭引用</button>');
    const close = dialog.querySelector<HTMLButtonElement>("#close")!;
    close.focus();

    expect(tab(dialog).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(close);
    expect(tab(dialog, { shiftKey: true }).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(close);
  });

  it("wraps from last to first and from first to last", () => {
    const dialog = makeDialog('<button id="first">关闭</button><button id="last">重试</button>');
    const first = dialog.querySelector<HTMLButtonElement>("#first")!;
    const last = dialog.querySelector<HTMLButtonElement>("#last")!;

    last.focus();
    expect(tab(dialog).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(first);
    expect(tab(dialog, { shiftKey: true }).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(last);
  });

  it("leaves ordinary Tab navigation from a middle control alone", () => {
    const dialog = makeDialog(
      '<button>关闭</button><button id="middle">详情</button><button>重试</button>',
    );
    const middle = dialog.querySelector<HTMLButtonElement>("#middle")!;
    middle.focus();

    expect(tab(dialog).defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(middle);
    expect(tab(dialog, { shiftKey: true }).defaultPrevented).toBe(false);
  });

  it("excludes disabled, hidden, CSS-hidden and negative-tabindex controls", () => {
    const dialog = makeDialog(`
      <button disabled>禁用</button>
      <div hidden><button>隐藏祖先</button></div>
      <button style="display:none">隐藏样式</button>
      <button tabindex="-1">跳过</button>
      <button id="close">关闭</button>
      <button style="visibility:hidden">不可见</button>
    `);
    const close = dialog.querySelector<HTMLButtonElement>("#close")!;
    close.focus();

    expect(tab(dialog).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(close);
    expect(tab(dialog, { shiftKey: true }).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(close);
  });

  it("focuses the dialog itself when it contains no focusable controls", () => {
    const dialog = makeDialog("<p>只读引用</p>");
    document.body.focus();

    expect(tab(dialog).defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(dialog);
  });

  it.each(["ctrlKey", "altKey", "metaKey"] as const)(
    "does not intercept modified Tab with %s",
    (modifier) => {
      const dialog = makeDialog('<button id="first">关闭</button><button id="last">重试</button>');
      const last = dialog.querySelector<HTMLButtonElement>("#last")!;
      last.focus();

      expect(tab(dialog, { [modifier]: true }).defaultPrevented).toBe(false);
      expect(document.activeElement).toBe(last);
    },
  );
});
