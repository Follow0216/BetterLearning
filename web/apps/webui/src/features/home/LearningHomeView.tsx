import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { BlIcon } from "@/components/icons/BlIcon";
import { SessionBoundary } from "@/features/learning-notes/SessionBoundary";
import { AnswerStateBadge } from "@/features/library/AnswerStateBadge";
import {
  buildHomeQuestionHref,
  buildWorkspaceHref,
  formatQuestionTime,
  INBOX_VIEW,
} from "@/features/library/library-params";
import { RefreshButton } from "@/features/library/RefreshButton";
import workspace from "@/features/library/workspace.module.css";
import { WorkspaceHeader } from "@/features/library/WorkspaceHeader";
import { QuestionMarkdown } from "@/features/question-content/QuestionMarkdown";
import { AuthContractError } from "@/lib/api/auth-contract";
import { SESSION_COOKIE_NAME } from "@/lib/api/auth-response";
import {
  AuthBackendError,
  AuthBackendUnavailableError,
  getProfileFromBackend,
} from "@/lib/api/auth-server";
import {
  INBOX_COLLECTION_ID,
  SUBJECT_LABELS,
  type CollectionSummary,
  type QuestionDetail,
  type QuestionSummary,
} from "@/lib/api/library-contract";
import {
  getCollections,
  getQuestionDetail,
  getQuestionPage,
  type LibraryFailure,
  type LibraryResult,
} from "@/lib/api/library-server";

import { HomeAccountScope } from "./HomeAccountScope";
import { requiresHomeSignIn, selectHomeCollections, type HomeProfileResult } from "./home-data";
import styles from "./learning-home.module.css";

async function readProfile(token: string): Promise<HomeProfileResult> {
  try {
    return { ok: true, user: await getProfileFromBackend(token) };
  } catch (error) {
    if (error instanceof AuthBackendError) {
      return { ok: false, status: error.status, message: error.payload.message };
    }
    if (error instanceof AuthBackendUnavailableError) {
      return { ok: false, status: 503, message: "账号服务暂时无法连接，请稍后重试。" };
    }
    return {
      ok: false,
      status: error instanceof AuthContractError ? 502 : 500,
      message: "暂时无法读取账号信息，请重试。",
    };
  }
}

function SectionFailure({
  title,
  message,
  traceId,
  headingLevel = 3,
}: {
  title: string;
  message: string;
  traceId?: string;
  headingLevel?: 2 | 3;
}) {
  const Heading = headingLevel === 2 ? "h2" : "h3";
  return (
    <div className={styles.failure}>
      <Heading className={styles.failureTitle}>{title}</Heading>
      <p role="alert" className="mt-2 text-[13px] leading-6 text-[var(--auth-muted)]">
        {message}
      </p>
      {traceId ? (
        <p className="mt-1 font-mono text-[11px] break-all text-[var(--auth-muted)]">
          追踪号 {traceId}
        </p>
      ) : null}
      <div className="mt-3">
        <RefreshButton />
      </div>
    </div>
  );
}

function QuestionMeta({ question }: { question: QuestionSummary }) {
  return (
    <div className={styles.meta}>
      <span>{SUBJECT_LABELS[question.subject]}</span>
      <AnswerStateBadge state={question.answerState} />
      <time dateTime={question.createdAt}>{formatQuestionTime(question.createdAt)}</time>
    </div>
  );
}

function FeaturedQuestion({
  question,
  preview,
}: {
  question: QuestionSummary;
  preview: LibraryResult<QuestionDetail> | null;
}) {
  const href = buildHomeQuestionHref(question.questionId);
  return (
    <article className={styles.paper} aria-labelledby="featured-question-heading">
      <header className={styles.paperHead}>
        <div className="min-w-0">
          <h3 id="featured-question-heading" className={styles.paperLabel}>
            最新加入
          </h3>
          <div className="mt-2">
            <QuestionMeta question={question} />
          </div>
        </div>
        <Link href={href} className={styles.primaryLink}>
          查看完整题目
          <BlIcon name="chevron_right" size={14} />
        </Link>
      </header>
      {preview?.ok ? (
        <div
          className={styles.preview}
          role="region"
          aria-label="最新加入题目的正文预览，可滚动阅读"
          tabIndex={0}
        >
          <QuestionMarkdown
            bodyMarkdown={preview.data.bodyMarkdown}
            headingLevel={4}
            ariaLabel="题目正文预览"
          />
        </div>
      ) : (
        <div className="py-6">
          <p role="alert" className="text-sm leading-7 text-[var(--auth-muted)]">
            {preview !== null && !preview.ok
              ? preview.failure.message
              : "题目预览暂不可用，请打开完整题目查看。"}
          </p>
        </div>
      )}
      <footer className={styles.paperFooter}>
        <Link href={href} className={styles.textLink}>
          查看作答与辅导
          <BlIcon name="chevron_right" size={14} />
        </Link>
      </footer>
    </article>
  );
}

function RecentQuestions({
  items,
  total,
  preview,
  failure,
}: {
  items: QuestionSummary[];
  total?: number;
  preview: LibraryResult<QuestionDetail> | null;
  failure?: LibraryFailure;
}) {
  const [first, ...remaining] = items;
  return (
    <section className={styles.reading} aria-labelledby="recent-questions-heading">
      <div className={styles.sectionHead}>
        <h2 id="recent-questions-heading" className={styles.sectionTitle}>
          最近加入
        </h2>
        <span className={styles.caption}>按加入时间排列</span>
      </div>
      {failure !== undefined ? (
        <SectionFailure
          title="最近题目暂时无法加载"
          message={failure.message}
          traceId={failure.traceId}
        />
      ) : first === undefined ? (
        <div className={styles.empty}>
          <h3 className="text-lg font-medium">
            {total === 0 ? "还没有加入题目" : "题目列表已更新"}
          </h3>
          <p className="mt-3 max-w-md text-sm leading-7 text-[var(--auth-muted)]">
            {total === 0
              ? "在手机端拍照录题并确认保存后，就能在这里阅读题目，查看作答与辅导记录。"
              : "打开题库查看最新题目，或刷新后重试。"}
          </p>
          <Link href="/workspace" className={`${styles.textLink} mt-3`}>
            打开题库
            <BlIcon name="chevron_right" size={14} />
          </Link>
        </div>
      ) : (
        <>
          <FeaturedQuestion question={first} preview={preview} />
          {remaining.length > 0 ? (
            <>
              <h3 className={styles.moreTitle}>更多最近加入</h3>
              <ul>
                {remaining.map((question) => (
                  <li key={question.questionId} className={styles.recentItem}>
                    <Link
                      href={buildHomeQuestionHref(question.questionId)}
                      className={styles.recentLink}
                    >
                      <span className={styles.summary}>{question.summary || "查看题目正文"}</span>
                      <QuestionMeta question={question} />
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          <Link href="/workspace" className={`${styles.textLink} mt-3`}>
            打开题库
            {total === undefined ? "" : ` · 共 ${new Intl.NumberFormat("zh-CN").format(total)} 题`}
            <BlIcon name="chevron_right" size={14} />
          </Link>
        </>
      )}
    </section>
  );
}

function HomeCollections({
  collections,
  failure,
}: {
  collections: CollectionSummary[];
  failure?: LibraryFailure;
}) {
  const visible = selectHomeCollections(collections);
  const inbox = collections.find((collection) => collection.collectionId === INBOX_COLLECTION_ID);
  const names = new Map(
    collections.map((collection) => [collection.collectionId, collection.name]),
  );
  return (
    <aside className={styles.aside} aria-label="学习入口与题集">
      <Link href={buildWorkspaceHref(INBOX_VIEW, 1)} className={styles.quickLink}>
        <BlIcon name="inbox" size={18} className="text-[var(--auth-muted)]" />
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium">待整理</span>
          <span className={styles.caption}>
            {inbox === undefined
              ? "把题目归拢到自己的题集"
              : `${new Intl.NumberFormat("zh-CN").format(inbox.questionCount)} 题`}
          </span>
        </span>
        <BlIcon name="chevron_right" size={14} className="text-[var(--auth-muted)]" />
      </Link>
      <section className={styles.collections} aria-labelledby="home-collections-heading">
        <h2 id="home-collections-heading" className={styles.asideTitle}>
          我的题集
        </h2>
        {failure !== undefined ? (
          <SectionFailure
            title="题集暂时无法加载"
            message={failure.message}
            traceId={failure.traceId}
          />
        ) : visible.length === 0 ? (
          <p className="text-[13px] leading-7 text-[var(--auth-muted)]">
            还没有自己的题集。打开题库，新建一个题集来整理题目。
          </p>
        ) : (
          <ul>
            {visible.map((collection) => {
              const parent =
                collection.parentCollectionId === null
                  ? undefined
                  : names.get(collection.parentCollectionId);
              return (
                <li key={collection.collectionId}>
                  <Link
                    href={buildWorkspaceHref(
                      { kind: "collection", collectionId: collection.collectionId },
                      1,
                    )}
                    className={styles.collectionLink}
                    title={
                      parent === undefined ? collection.name : `${parent} / ${collection.name}`
                    }
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{collection.name}</span>
                      {parent === undefined ? null : (
                        <span className={`${styles.caption} block truncate`}>{parent}</span>
                      )}
                    </span>
                    <span className={`${styles.caption} shrink-0 tabular-nums`}>
                      {new Intl.NumberFormat("zh-CN").format(collection.questionCount)} 题
                    </span>
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
        <Link href="/workspace" className={`${styles.textLink} mt-3`}>
          打开题库
          <BlIcon name="chevron_right" size={14} />
        </Link>
      </section>
      <p className={styles.deviceNote}>
        手机端录题与提问，平板端手写作答。网页端集中阅读与整理，查看已保存的作答和辅导。
      </p>
    </aside>
  );
}

export async function LearningHomeView() {
  const token = (await cookies()).get(SESSION_COOKIE_NAME)?.value;
  if (!token) redirect("/account");
  const [profile, questions, collections] = await Promise.all([
    readProfile(token),
    getQuestionPage({ page: 1, pageSize: 6 }),
    getCollections(),
  ]);
  if (requiresHomeSignIn({ profile, questions, collections })) redirect("/account");
  if (!profile.ok)
    return (
      <SessionBoundary>
        <div className={workspace.root}>
          <HomeAccountScope accountId={null} />
          <WorkspaceHeader active="home" showAccount={false} />
          <main tabIndex={-1} id="workspace-content" className={styles.main}>
            <h1 className={`${styles.title} mb-7`}>暂时无法打开学习空间</h1>
            <SectionFailure title="账号信息读取失败" message={profile.message} headingLevel={2} />
            <Link href="/account" className={`${styles.textLink} mt-6`}>
              前往账号页
              <BlIcon name="chevron_right" size={14} />
            </Link>
          </main>
        </div>
      </SessionBoundary>
    );

  const first = questions.ok ? questions.data.items[0] : undefined;
  const preview = first === undefined ? null : await getQuestionDetail(first.questionId);
  if (requiresHomeSignIn({ profile, questions, collections, preview })) redirect("/account");

  return (
    <SessionBoundary>
      <div className={workspace.root}>
        <HomeAccountScope accountId={profile.user.accountId} />
        <WorkspaceHeader active="home" username={profile.user.username} />
        <main tabIndex={-1} id="workspace-content" className={styles.main}>
          <header className={styles.intro}>
            <div className="min-w-0">
              <p className={styles.eyebrow}>
                <span className="font-medium">{profile.user.username}</span> 的学习空间
              </p>
              <h1 className={styles.title}>从一道题开始。</h1>
              <p className={styles.subtitle}>最近加入的题目、保存的作答，都在这里。</p>
            </div>
            <Link href="/workspace" className={styles.textLink}>
              打开题库
              <BlIcon name="chevron_right" size={15} />
            </Link>
          </header>
          <div className={styles.grid}>
            <RecentQuestions
              items={questions.ok ? questions.data.items : []}
              total={questions.ok ? questions.data.total : undefined}
              preview={preview}
              failure={questions.ok ? undefined : questions.failure}
            />
            <HomeCollections
              collections={collections.ok ? collections.data.items : []}
              failure={collections.ok ? undefined : collections.failure}
            />
          </div>
        </main>
      </div>
    </SessionBoundary>
  );
}
