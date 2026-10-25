import { describe, expect, it } from "vitest";

import { renderQuestionMarkdown } from "./question-markdown-renderer";

const id = "11111111-1111-4111-8111-111111111111";
const otherId = "22222222-2222-4222-8222-222222222222";
const citations = [{ label: "7", citationId: id }];
const marker = `[[citation:${id}]]`;

function html(markdown: string, items?: typeof citations): string {
  const result = renderQuestionMarkdown(markdown, { citations: items });
  if (!result.ok) throw new Error("render failed");
  return result.html;
}

describe("Markdown citation rendering", () => {
  it("activates published UUID markers using the metadata label and preserves math", () => {
    const rendered = html(`先用 $x^2$。${marker}`, citations);
    expect(rendered.match(/data-citation-id=/g)).toHaveLength(1);
    expect(rendered).toContain(`data-citation-id="${id}"`);
    expect(rendered).toContain('aria-label="查看引用 7">[7]</button>');
    expect(rendered).not.toContain(marker);
    expect(rendered).toContain('class="katex"');
  });

  it("leaves unknown IDs, draft evidence, malformed UUIDs and legacy labels inert", () => {
    const unknown = `[[citation:${otherId}]]`;
    const draft = `[[evidence:${id}]]`;
    const malformed = "[[citation:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa]]";
    const rendered = html(`${unknown} ${draft} ${malformed} [7] [999]`, citations);
    expect(rendered).not.toContain("data-citation-id=");
    expect(rendered).toContain(unknown);
    expect(rendered).toContain(draft);
    expect(rendered).toContain(malformed);
    expect(rendered).toContain("[7]");
  });

  it("does not activate uppercase UUID markers even if metadata contains a matching uppercase ID", () => {
    const uppercase = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";
    expect(
      html(`[[citation:${uppercase}]]`, [{ citationId: uppercase, label: "1" }]),
    ).not.toContain("data-citation-id=");
  });

  it("uses UUID identity for repeated markers and matching labels", () => {
    const rendered = html(`${marker} [[citation:${otherId}]] ${marker}`, [
      { citationId: id, label: "同标签" },
      { citationId: otherId, label: "同标签" },
    ]);
    expect(rendered.match(/data-citation-id=/g)).toHaveLength(3);
    expect(rendered.match(new RegExp(`data-citation-id="${id}"`, "g"))).toHaveLength(2);
    expect(rendered).toContain(`data-citation-id="${otherId}"`);
    expect(rendered.match(/>\[同标签\]<\/button>/g)).toHaveLength(3);
  });

  it("escapes the display label rather than interpreting it as markup", () => {
    const rendered = html(marker, [{ citationId: id, label: '\"><img src=x onerror=alert(1)>' }]);
    expect(rendered).toContain("&lt;img");
    expect(rendered).not.toContain("<img");
    expect(rendered.match(/<button /g)).toHaveLength(1);
  });

  it("keeps code, raw HTML, images and links inert", () => {
    const rendered = html(
      `\`${marker}\` <script>alert(1)</script> ![图](https://private.example/image) [链接](javascript:alert(1))`,
      citations,
    );
    expect(rendered).not.toContain("data-citation-id=");
    expect(rendered).not.toContain("<script>");
    expect(rendered).not.toContain("<img");
    expect(rendered).not.toContain("href=");
    expect(rendered).toContain("&lt;script&gt;");
  });

  it("does not share citation state across documents", () => {
    html(marker, citations);
    expect(html(marker)).not.toContain("data-citation-id=");
  });

  it("rebases preview headings without losing citation identity or changing other documents", () => {
    const preview = renderQuestionMarkdown(`# 标题\n\n${marker}`, { headingLevel: 3, citations });
    expect(preview.ok && preview.html).toContain("<h3>标题</h3>");
    expect(preview.ok && preview.html).toContain(`data-citation-id="${id}"`);
    const detail = renderQuestionMarkdown("# 标题");
    expect(detail.ok && detail.html).toContain("<h1>标题</h1>");
  });

  it("renders bracket block math immediately after a paragraph", () => {
    const rendered = html("先代入\n\\[x^2 + y^2 = 1\\]");
    expect(rendered).toContain('class="math-block"');
    expect(rendered).toContain('class="katex"');
  });
});
