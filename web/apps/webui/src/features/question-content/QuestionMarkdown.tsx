import "katex/dist/katex.min.css";
import "markdown-it-texmath/css/texmath.css";

import { renderQuestionMarkdown } from "./question-markdown-renderer";
import styles from "./question-markdown.module.css";

export interface QuestionMarkdownProps {
  bodyMarkdown: string;
  className?: string;
  ariaLabel?: string;
  failureMessage?: string;
  citations?: readonly { citationId: string; label: string }[];
  onCitationClick?: (citationId: string) => void;
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
  failureMessage = "题面暂时无法显示，请稍后重试。",
  citations,
  onCitationClick,
}: QuestionMarkdownProps) {
  const rendered = renderQuestionMarkdown(bodyMarkdown, citations);
  const rootClassName = joinClassNames(styles.content, className);

  if (!rendered.ok) {
    return (
      <div className={rootClassName} aria-label={ariaLabel} data-render-state="error">
        <p role="note" className={styles.failure}>
          {failureMessage}
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
      onClick={
        onCitationClick
          ? (event) => {
              const button =
                event.target instanceof Element
                  ? event.target.closest<HTMLButtonElement>("button[data-citation-id]")
                  : null;
              const id = button?.dataset.citationId;
              if (id && citations?.some((citation) => citation.citationId === id))
                onCitationClick(id);
            }
          : undefined
      }
      dangerouslySetInnerHTML={{ __html: rendered.html }}
    />
  );
}
