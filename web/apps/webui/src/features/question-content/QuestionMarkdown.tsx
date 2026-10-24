export interface QuestionMarkdownProps {
  bodyMarkdown: string;
  className?: string;
  ariaLabel?: string;
}

/**
 * 题目正文渲染边界。
 *
 * 依赖审批完成前不引入 Markdown/公式库，也不自行实现解析器，仅按原文展示，
 * 并在界面中明确标记渲染接入尚未完成。
 */
export function QuestionMarkdown({
  bodyMarkdown,
  className,
  ariaLabel = "题目正文",
}: QuestionMarkdownProps) {
  return (
    <div
      className={`flex flex-col gap-3 ${className ?? ""}`}
      aria-label={ariaLabel}
      data-renderer="pending"
    >
      <p className="text-[15px] leading-7 break-words whitespace-pre-wrap text-[var(--auth-text)]">
        {bodyMarkdown}
      </p>
      <p
        role="note"
        className="rounded-lg border border-dashed border-[var(--auth-border)] bg-[var(--auth-bg)] px-3 py-2 text-[12px] leading-5 text-[var(--auth-muted)]"
      >
        Markdown 与公式渲染接入尚未完成，当前显示服务端返回的原文。
      </p>
    </div>
  );
}
