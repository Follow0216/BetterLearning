import "server-only";

import katex from "katex";
import MarkdownIt from "markdown-it";
import texmath from "markdown-it-texmath";

import type { KatexOptions } from "katex";
import type { Env, MarkdownIt as MarkdownItInstance, Token } from "markdown-it";

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

export type QuestionRenderResult =
  | { ok: true; html: string; formulaErrors: number }
  | { ok: false };

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

function createParser(): MarkdownItInstance {
  const md = new MarkdownIt({
    html: false,
    linkify: false,
    typographer: false,
    breaks: true,
  });

  md.use(texmath, { engine: katex, delimiters: ["dollars", "brackets"] });

  md.renderer.rules.math_inline = (tokens, index, _options, env) =>
    renderMath(tokens, index, false, true, false, env ?? {});
  md.renderer.rules.math_inline_double = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, true, false, env ?? {});
  md.renderer.rules.math_block = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, false, false, env ?? {});
  md.renderer.rules.math_block_eqno = (tokens, index, _options, env) =>
    renderMath(tokens, index, true, false, true, env ?? {});

  md.renderer.rules.link_open = () => '<span class="markdown-link">';
  md.renderer.rules.link_close = () => "</span>";
  md.renderer.rules.image = (tokens, index) => {
    const description = md.utils.escapeHtml(tokens[index].content || "图片");
    return `<span class="image-description">[图片：${description}]</span>`;
  };

  return md;
}

export function renderQuestionMarkdown(bodyMarkdown: string): QuestionRenderResult {
  try {
    const instance = parser ?? (parser = createParser());
    const env: Env = { formulaErrors: 0 };
    const html = instance.render(bodyMarkdown, env);
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