import katex from "katex";
import MarkdownIt from "markdown-it";
import texmath from "markdown-it-texmath";

import type { KatexOptions } from "katex";
import type { Env, MarkdownIt as MarkdownItInstance, Token } from "markdown-it";
import type { TexmathBlockRule, TexmathRule } from "markdown-it-texmath";

/**
 * 题面渲染与 C2 原生端保持同一配置：
 * - 关闭原始 HTML、linkify、typographer，保留换行；
 * - texmath 识别美元行内/块公式与 `\(...\)`、`\[...\]`；
 * - 每次公式渲染使用独立 macros，避免跨题共用可变宏。
 */
const KATEX_BASE_OPTIONS: KatexOptions = {
  throwOnError: true,
  trust: false,
  strict: "ignore",
  maxSize: 10,
  maxExpand: 1000,
  output: "htmlAndMathml",
};

type RenderEnvironment = Env;

export interface QuestionRenderOptions {
  headingLevel?: 2 | 3 | 4 | 5 | 6;
  citations?: readonly { citationId: string; label: string }[];
}

export type QuestionRenderResult =
  { ok: true; html: string; formulaErrors: number } | { ok: false };

let parser: MarkdownItInstance | null = null;

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function formulaError(tex: string): string {
  return `<span class="formula-error" role="note">公式格式有误：<code>${escapeHtml(tex)}</code></span>`;
}

function recordFormulaError(env: RenderEnvironment): void {
  const current = env.formulaErrors;
  env.formulaErrors = typeof current === "number" ? current + 1 : 1;
}

function renderMath(
  tokens: Token[],
  index: number,
  displayMode: boolean,
  inline: boolean,
  numbered: boolean,
  env: RenderEnvironment,
): string {
  const token = tokens[index];
  let html: string;
  try {
    html = katex.renderToString(token.content, {
      ...KATEX_BASE_OPTIONS,
      displayMode,
      macros: {},
    });
  } catch {
    recordFormulaError(env);
    return formulaError(token.content);
  }

  if (inline) {
    return `<eq>${html}</eq>`;
  }

  const number =
    numbered && token.info.length > 0
      ? `<span class="equation-number">(${escapeHtml(token.info)})</span>`
      : "";
  return `<section class="math-block${number.length > 0 ? " has-number" : ""}">${html}${number}</section>`;
}

/**
 * texmath 安装块规则时未声明 `alt: ["paragraph"]`，紧跟正文且无空行的
 * `\[...\]` 会被段落规则吞掉并当作普通方括号输出。这里重新注册括号块规则，
 * 补齐段落链并校验代码缩进与容器边界，内联括号与美元公式行为保持不变。
 */
function bracketBlockRule(rule: TexmathRule): TexmathBlockRule {
  const parse = texmath.block(rule);
  return (state, startLine, endLine, silent) => {
    if (state.sCount[startLine] - state.blkIndent >= 4) {
      return false;
    }
    const pos = state.bMarks[startLine] + state.tShift[startLine];
    if (!state.src.startsWith(rule.tag, pos)) {
      return false;
    }
    rule.rex.lastIndex = pos;
    if (!rule.rex.exec(state.src)) {
      return false;
    }
    const last = rule.rex.lastIndex - 1;
    for (let line = startLine; line < endLine; line += 1) {
      if (!state.isEmpty(line) && state.sCount[line] < state.blkIndent) {
        return false;
      }
      if (last <= state.eMarks[line]) {
        return parse(state, startLine, endLine, silent);
      }
    }
    return false;
  };
}

function createParser(): MarkdownItInstance {
  const md = new MarkdownIt({
    html: false,
    linkify: false,
    typographer: false,
    breaks: true,
  });

  md.use(texmath, { engine: katex, delimiters: ["dollars"] });

  for (const rule of texmath.rules.brackets.inline) {
    md.inline.ruler.before("escape", `brackets_${rule.name}`, texmath.inline(rule));
  }
  for (const rule of texmath.rules.brackets.block) {
    md.block.ruler.before("fence", `brackets_${rule.name}`, bracketBlockRule(rule), {
      alt: ["paragraph"],
    });
  }

  md.renderer.rules.math_inline = (tokens, index, _options, env) =>
    renderMath(tokens, index, false, true, false, env ?? {});
  md.renderer.rules.math_inline_double = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, true, false, env ?? {});
  md.renderer.rules.math_block = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, false, false, env ?? {});
  md.renderer.rules.math_block_eqno = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, false, true, env ?? {});

  md.renderer.rules.text = (tokens, index, _options, env) => {
    const content = tokens[index].content;
    const citations = env?.citations as ReadonlyMap<string, string> | undefined;
    if (!citations?.size) return md.utils.escapeHtml(content);
    let offset = 0;
    let html = "";
    for (const match of content.matchAll(
      /\[\[citation:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\]\]/g,
    )) {
      const id = match[1];
      const label = citations.get(id);
      if (label === undefined) continue;
      const start = match.index!;
      html += md.utils.escapeHtml(content.slice(offset, start));
      html += `<button type="button" class="citation-marker" data-citation-id="${md.utils.escapeHtml(id)}" aria-label="查看引用 ${md.utils.escapeHtml(label)}">[${md.utils.escapeHtml(label)}]</button>`;
      offset = start + match[0].length;
    }
    return html + md.utils.escapeHtml(content.slice(offset));
  };

  md.renderer.rules.link_open = () => '<span class="markdown-link">';
  md.renderer.rules.link_close = () => "</span>";
  md.renderer.rules.image = (tokens, index) => {
    const description = md.utils.escapeHtml(tokens[index].content || "图片");
    return `<span class="image-description">[图片：${description}]</span>`;
  };

  return md;
}

export function renderQuestionMarkdown(
  bodyMarkdown: string,
  options: QuestionRenderOptions = {},
): QuestionRenderResult {
  try {
    const instance = parser ?? (parser = createParser());
    const env: Env = {
      formulaErrors: 0,
      citations: new Map(options.citations?.map((item) => [item.citationId, item.label])),
    };
    let html: string;
    if (options.headingLevel === undefined) {
      html = instance.render(bodyMarkdown, env);
    } else {
      // Headings in a preview are a document fragment. Rebase actual tags per render,
      // without changing the shared parser, formula rules, or full-detail output.
      const tokens = instance.parse(bodyMarkdown, env);
      const headingLevels = tokens
        .filter((token) => token.type === "heading_open")
        .map((token) => Number(token.tag.slice(1)));
      const firstLevel = Math.min(6, ...headingLevels);
      for (const token of tokens) {
        if (token.type === "heading_open" || token.type === "heading_close") {
          token.tag = `h${Math.min(6, options.headingLevel + Number(token.tag.slice(1)) - firstLevel)}`;
        }
      }
      html = instance.renderer.render(tokens, instance.options, env);
    }
    const formulaErrors = env.formulaErrors;
    return {
      ok: true,
      html,
      formulaErrors: typeof formulaErrors === "number" ? formulaErrors : 0,
    };
  } catch {
    return { ok: false };
  }
}
