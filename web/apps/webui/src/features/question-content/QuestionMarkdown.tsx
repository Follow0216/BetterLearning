import "katex/dist/katex.min.css";
import "markdown-it-texmath/css/texmath.css";

import { renderQuestionMarkdown } from "./question-markdown-renderer";
import styles from "./question-markdown.module.css";

export interface QuestionMarkdownProps {
  bodyMarkdown: string;
  className?: string;
  ariaLabel?: string;
}

function joinClassNames(...values: Array<string | undefined>): string {
  return values
    .filter((value): value is string => value !== undefined && value.length > 0)
    .join(" ");
}

export function QuestionMarkdown({
  bodyMarkdown,
  className,
  ariaLabel = "题目正文",
}: QuestionMarkdownProps) {
  const rendered = renderQuestionMarkdown(bodyMarkdown);
  const rootClassName = joinClassNames(styles.content, className);

  if (!rendered.ok) {
    return (
      <div className={rootClassName} aria-label={ariaLabel} data-render-state="error">
        <p role="note" className={styles.failure}>
          题面暂时无法显示，请稍后重试。
        </p>
      </div>
    );
  }

  return (
    <div
      className={rootClassName}
      aria-label={ariaLabel}
      data-render-state="ready"
      data-formula-errors={String(rendered.formulaErrors)}
      dangerouslySetInnerHTML={{ __html: rendered.html }}
    />
  );
}
