import { redirect } from "next/navigation";

import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { getTutorTurns } from "@/lib/api/library-server";
import { toTutorTurnSummary } from "@/lib/api/tutor-turn-contract";

import { RefreshButton } from "./RefreshButton";
import { TutorTurnItem } from "./TutorTurnItem";

export async function TutorHistoryPanel({ questionId }: { questionId: string }) {
  const result = await getTutorTurns(questionId);
  if (!result.ok) {
    if (result.failure.kind === "unauthenticated") {
      redirect("/account");
    }
    const message =
      result.failure.kind === "forbidden"
        ? "当前账号没有访问该题目辅导记录的权限"
        : result.failure.kind === "unavailable"
          ? "辅导记录暂不可用，请稍后重试"
          : "加载辅导记录失败，请稍后重试";

    return (
      <section aria-labelledby="tutor-history-heading" className="min-w-0">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2
              id="tutor-history-heading"
              className="text-base leading-6 font-semibold text-[var(--auth-text)]"
            >
              辅导记录
            </h2>
            <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
              最近 50 轮，只读查看。提问与重试请在手机端完成。
            </p>
          </div>
          <RefreshButton />
        </div>
        <p role="alert" className="mt-4 text-sm leading-6 text-[var(--auth-danger)]">
          {message}
        </p>
      </section>
    );
  }

  const turns = result.data.items;
  const ordered = turns.map((turn, index) => ({ turn, index })).reverse();

  return (
    <section aria-labelledby="tutor-history-heading" className="min-w-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2
            id="tutor-history-heading"
            className="text-base leading-6 font-semibold text-[var(--auth-text)]"
          >
            辅导记录
          </h2>
          <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
            最近 50 轮，最新在前。提问、重试与取消请在手机端完成。
          </p>
        </div>
        <RefreshButton />
      </div>

      {turns.length === 0 ? (
        <div className="mt-4 rounded-lg bg-[var(--auth-bg)] px-5 py-8 text-center">
          <p className="text-sm font-medium text-[var(--auth-text)]">暂无辅导记录</p>
          <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
            在手机端发起提问后，可以在这里查看。
          </p>
        </div>
      ) : (
        <ol className="mt-5">
          {ordered.map(({ turn, index }) => {
            const hasAnswer =
              turn.status === "SUCCEEDED" &&
              turn.answerMarkdown !== null &&
              turn.answerMarkdown.trim().length > 0;

            return (
              <li key={turn.turnId}>
                <TutorTurnItem
                  key={`${turn.turnId}|${index === turns.length - 1 ? "latest" : "history"}`}
                  questionId={questionId}
                  turn={toTutorTurnSummary(turn)}
                  turnNumber={index + 1}
                  isLatest={index === turns.length - 1}
                  hasAnswer={hasAnswer}
                >
                  {hasAnswer ? (
                    <QuestionMarkdown
                      bodyMarkdown={turn.answerMarkdown ?? ""}
                      ariaLabel={`第 ${index + 1} 轮辅导回答`}
                      failureMessage="辅导内容暂时无法显示"
                    />
                  ) : null}
                </TutorTurnItem>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
