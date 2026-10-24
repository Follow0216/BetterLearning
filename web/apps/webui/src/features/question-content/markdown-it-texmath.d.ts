declare module "markdown-it-texmath" {
  import type { KatexOptions } from "katex";
  import type { MarkdownIt } from "markdown-it";

  export type TexmathDelimiter =
    | "dollars"
    | "brackets"
    | "doxygen"
    | "gitlab"
    | "julia"
    | "kramdown"
    | "beg_end";

  export interface TexmathOptions {
    engine?: {
      renderToString(tex: string, options?: KatexOptions): string;
    };
    delimiters?: TexmathDelimiter | readonly TexmathDelimiter[];
    outerSpace?: boolean;
    katexOptions?: KatexOptions;
    macros?: Record<string, string>;
  }

  export default function texmath(md: MarkdownIt, options?: TexmathOptions): void;
}