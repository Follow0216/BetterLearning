import { describe, expect, it } from "vitest";

import { renderQuestionMarkdown } from "./question-markdown-renderer";

const citations = [{ label: "1", citationId: "11111111-1111-4111-8111-111111111111" }];

function html(markdown: string, items?: typeof citations): string {
  const result = renderQuestionMarkdown(markdown, items);
  if (!result.ok) throw new Error("render failed");
  return result.html;
}

describe("Markdown citation rendering", () => {
  it("activates only published metadata labels and preserves math", () => {
    const rendered = html("先用 $x^2$。[1] 无效编号 [999]。", citations);
    expect(rendered.match(/data-citation-id=/g)).toHaveLength(1);
    expect(rendered).toContain("查看引用 1");
    expect(rendered).toContain("[999]");
    expect(rendered).toContain('class="katex"');
  });

  it("keeps code, raw HTML, images and links inert", () => {
    const rendered = html(
      "`[1]` <script>alert(1)</script> ![图](https://private.example/image) [链接](javascript:alert(1))",
      citations,
    );
    expect(rendered).not.toContain("data-citation-id=");
    expect(rendered).not.toContain("<script>");
    expect(rendered).not.toContain("<img");
    expect(rendered).not.toContain("href=");
    expect(rendered).toContain("&lt;script&gt;");
  });

  it("does not share citation state across documents", () => {
    html("[1]", citations);
    expect(html("[1]")).not.toContain("data-citation-id=");
  });

  it("renders bracket block math immediately after a paragraph", () => {
    const rendered = html("先代入\n\\[x^2 + y^2 = 1\\]");
    expect(rendered).toContain('class="math-block"');
    expect(rendered).toContain('class="katex"');
  });
});
