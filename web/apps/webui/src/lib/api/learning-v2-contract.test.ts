import { describe, expect, it } from "vitest";

import {
  LearningContractError,
  parseCitationResolution,
  parseCursorPage,
  parseNote,
  parseTutorDetail,
} from "./learning-v2-contract";

const id = "11111111-1111-4111-8111-111111111111";
const sourceId = "22222222-2222-4222-8222-222222222222";
const anchorId = "33333333-3333-4333-8333-333333333333";
const now = "2026-10-25T12:00:00+08:00";

const citation = {
  citationId: id,
  sourceId,
  version: 2,
  anchorId,
  label: "[1]",
  sourceType: "NOTE",
};
const resolution = {
  citation,
  sourceTitle: "笔记",
  sourceCreatedAt: now,
  sourceVersionCreatedAt: now,
  currentVersion: 3,
  answerState: null,
  anchorKind: "MARKDOWN_BLOCK",
  contentSha256: "a".repeat(64),
  markdown: "A😀公式",
  markdownLocator: { blockOrdinal: 0, startOffset: 1, endOffset: 3 },
};

describe("learning response parsers", () => {
  it("rejects malformed note versions and index statuses", () => {
    const note = {
      noteId: id,
      sourceId,
      version: 1,
      title: "笔记",
      bodyMarkdown: "x",
      indexStatus: "READY",
      updatedAt: now,
    };
    expect(parseNote(note)).toEqual(note);
    expect(() => parseNote({ ...note, version: 0 })).toThrow(LearningContractError);
    expect(() => parseNote({ ...note, indexStatus: "UNKNOWN" })).toThrow(LearningContractError);
    expect(() => parseCursorPage({ items: [note], nextCursor: 42 }, parseNote)).toThrow(
      LearningContractError,
    );
  });

  it("allows a valid UTF-16 emoji range but rejects offsets splitting its surrogate pair", () => {
    expect(parseCitationResolution(resolution).markdownLocator).toEqual(resolution.markdownLocator);
    expect(() =>
      parseCitationResolution({
        ...resolution,
        markdownLocator: { blockOrdinal: 0, startOffset: 2, endOffset: 3 },
      }),
    ).toThrow(LearningContractError);
  });

  it("rejects a citation asset URL outside its own authorized citation path", () => {
    const handwriting = {
      ...resolution,
      anchorKind: "HANDWRITING_STEP",
      markdown: undefined,
      markdownLocator: undefined,
      handwritingLocator: {
        segments: [
          {
            blockIndex: 0,
            assetUrl: `/api/v2/learning/citations/${id}/assets/0`,
            blockWorldBounds: { x: -100, y: 0, width: 100, height: 100 },
            regionWorldBounds: { x: -50, y: 10, width: 20, height: 20 },
            pixelWidth: 200,
            pixelHeight: 200,
          },
        ],
      },
    };
    expect(parseCitationResolution(handwriting).handwritingLocator?.segments).toHaveLength(1);
    const absolute = parseCitationResolution({
      ...handwriting,
      handwritingLocator: {
        segments: [
          {
            ...handwriting.handwritingLocator.segments[0],
            assetUrl: `https://example.invalid/api/v2/learning/citations/${id}/assets/0`,
          },
        ],
      },
    });
    expect(absolute.handwritingLocator?.segments[0].assetUrl).toBe(
      `/api/v2/learning/citations/${id}/assets/0`,
    );
    expect(() =>
      parseCitationResolution({
        ...handwriting,
        handwritingLocator: {
          segments: [
            {
              ...handwriting.handwritingLocator.segments[0],
              assetUrl: "https://evil.example/asset",
            },
          ],
        },
      }),
    ).toThrow(LearningContractError);
    expect(() =>
      parseCitationResolution({
        ...handwriting,
        handwritingLocator: {
          segments: [
            {
              ...handwriting.handwritingLocator.segments[0],
              assetUrl: `/api/v2/learning/citations/${id}/assets/0?token=unsafe`,
            },
          ],
        },
      }),
    ).toThrow(LearningContractError);
    expect(() =>
      parseCitationResolution({
        ...handwriting,
        handwritingLocator: {
          segments: [
            {
              ...handwriting.handwritingLocator.segments[0],
              regionWorldBounds: { x: 10, y: 10, width: 20, height: 20 },
            },
          ],
        },
      }),
    ).toThrow(LearningContractError);
  });

  it("rejects a retry detail when the current run differs from the request pointer", () => {
    const request = {
      requestId: id,
      questionId: "1",
      prompt: "提示",
      mode: "HINT",
      snapshotInput: { kind: "NEW_CAPTURE" },
      snapshotOriginRequestId: id,
      snapshotFileRef: sourceId,
      currentRunId: sourceId,
      status: "FAILED",
      revision: 2,
      createdAt: now,
      updatedAt: now,
    };
    const run = {
      runId: sourceId,
      requestId: id,
      runNo: 1,
      status: "FAILED",
      revision: 2,
      steps: [],
    };
    expect(parseTutorDetail({ request, run, runs: [run], result: null }).run?.runId).toBe(sourceId);
    expect(() =>
      parseTutorDetail({ request, run: { ...run, runId: anchorId }, runs: [run], result: null }),
    ).toThrow(LearningContractError);
  });

  it("rejects FULL_SOLUTION results for a HINT request", () => {
    const request = {
      requestId: id,
      questionId: "1",
      prompt: "只给提示",
      mode: "HINT",
      snapshotInput: { kind: "NEW_CAPTURE" },
      snapshotOriginRequestId: id,
      snapshotFileRef: sourceId,
      currentRunId: sourceId,
      status: "SUCCEEDED",
      revision: 2,
      createdAt: now,
      updatedAt: now,
    };
    const run = {
      runId: sourceId,
      requestId: id,
      runNo: 1,
      status: "SUCCEEDED",
      revision: 2,
      steps: [],
    };
    const result = {
      resultId: anchorId,
      mode: "FULL_SOLUTION",
      status: "VERIFIED",
      bodyMarkdown: "完整答案",
      citations: [],
      createdAt: now,
    };
    expect(() => parseTutorDetail({ request, run, runs: [run], result })).toThrow(
      LearningContractError,
    );
  });
});
