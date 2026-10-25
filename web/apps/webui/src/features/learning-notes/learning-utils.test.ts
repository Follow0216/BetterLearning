import { describe, expect, it } from "vitest";

import { highlightPercent, MAX_NOTE_BYTES, noteByteLength } from "./learning-utils";

describe("noteByteLength", () => {
  it("counts UTF-8 bytes at the 64 KiB boundary, including multibyte text", () => {
    const valid = "中".repeat(21_845) + "a";
    expect(noteByteLength(valid)).toBe(MAX_NOTE_BYTES);
    expect(noteByteLength(valid + "a")).toBe(MAX_NOTE_BYTES + 1);
    expect(noteByteLength("😀")).toBe(4);
  });

  it("normalizes CRLF before counting but keeps lone CR", () => {
    expect(noteByteLength("a\r\nb")).toBe(3);
    expect(noteByteLength("a\rb")).toBe(3);
  });
});

describe("highlightPercent", () => {
  it("positions a target within a block whose world origin is negative", () => {
    expect(
      highlightPercent(
        { x: -100, y: -50, width: 200, height: 100 },
        { x: -50, y: -25, width: 50, height: 25 },
      ),
    ).toEqual({ left: "25%", top: "25%", width: "25%", height: "25%" });
  });

  it("clips each segment of a step spanning adjacent image blocks", () => {
    const region = { x: 90, y: 10, width: 20, height: 20 };
    expect(highlightPercent({ x: 0, y: 0, width: 100, height: 100 }, region)).toEqual({
      left: "90%",
      top: "10%",
      width: "10%",
      height: "20%",
    });
    expect(highlightPercent({ x: 100, y: 0, width: 100, height: 100 }, region)).toEqual({
      left: "0%",
      top: "10%",
      width: "10%",
      height: "20%",
    });
  });

  it("rejects a nonintersecting region instead of rendering a false highlight", () => {
    expect(() =>
      highlightPercent(
        { x: 0, y: 0, width: 100, height: 100 },
        { x: 100, y: 10, width: 20, height: 20 },
      ),
    ).toThrow("引用区域不在原图内");
  });
});
