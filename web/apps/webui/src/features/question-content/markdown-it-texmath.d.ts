declare module "markdown-it-texmath" {
  import type { KatexOptions } from "katex";
  import type { MarkdownIt, StateBlock, StateInline } from "markdown-it";

  export type TexmathDelimiter =
    "dollars" | "brackets" | "doxygen" | "gitlab" | "julia" | "kramdown" | "beg_end";

  export interface TexmathRule {
    name: string;
    tag: string;
    rex: RegExp;
    tmpl: string;
    displayMode?: boolean;
    outerSpace?: boolean;
    pre?: (src: string, outerSpace: boolean, pos: number) => boolean;
    post?: (src: string, outerSpace: boolean, end: number) => boolean;
  }

  export interface TexmathDelimiterRules {
    inline: TexmathRule[];
    block: TexmathRule[];
  }

  export type TexmathInlineRule = (state: StateInline, silent: boolean) => boolean;

  export type TexmathBlockRule = (
    state: StateBlock,
    startLine: number,
    endLine: number,
    silent: boolean,
  ) => boolean;

  export interface TexmathOptions {
    engine?: {
      renderToString(tex: string, options?: KatexOptions): string;
    };
    delimiters?: TexmathDelimiter | readonly TexmathDelimiter[];
    outerSpace?: boolean;
    katexOptions?: KatexOptions;
    macros?: Record<string, string>;
  }

  export interface TexmathPlugin {
    (md: MarkdownIt, options?: TexmathOptions): void;
    rules: Record<TexmathDelimiter, TexmathDelimiterRules>;
    inline(rule: TexmathRule): TexmathInlineRule;
    block(rule: TexmathRule): TexmathBlockRule;
    render(tex: string, displayMode: boolean, options?: KatexOptions): string;
  }

  const texmath: TexmathPlugin;

  export default texmath;
}
