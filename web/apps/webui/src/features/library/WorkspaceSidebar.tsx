import Link from "next/link";

import { BlIcon, type BlIconName } from "@/components/icons/BlIcon";
import type { CollectionSummary } from "@/lib/api/library-contract";
import type { LibraryFailure } from "@/lib/api/library-server";

import { buildCollectionTree, type CollectionNode } from "./collection-tree";
import { CollectionDialog } from "./CollectionDialog";
import {
  ALL_VIEW,
  INBOX_VIEW,
  buildWorkspaceHref,
  isSameView,
  type LibraryView,
} from "./library-params";
import { RefreshButton } from "./RefreshButton";

interface WorkspaceSidebarProps {
  view: LibraryView;
  collections: CollectionSummary[];
  collectionsFailure?: LibraryFailure;
  inboxCount?: number;
  allCount?: number;
}

function NavItem({
  href,
  icon,
  label,
  active,
  count,
  depth = 0,
}: {
  href: string;
  icon: BlIconName;
  label: string;
  active: boolean;
  count?: number;
  depth?: number;
}) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      style={{ paddingLeft: `${12 + depth * 12}px` }}
      className={`flex h-10 items-center gap-2 rounded-lg pr-3 text-sm font-medium transition-colors focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none ${
        active
          ? "bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]"
          : "text-[var(--auth-text)] hover:bg-[var(--auth-hover)]"
      }`}
    >
      <BlIcon name={icon} size={17} />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {count === undefined ? null : (
        <span className="text-xs text-[var(--auth-muted)] tabular-nums">{count}</span>
      )}
    </Link>
  );
}

function TreeNodes({
  nodes,
  view,
  depth,
}: {
  nodes: CollectionNode[];
  view: LibraryView;
  depth: number;
}) {
  return (
    <>
      {nodes.map((node) => {
        const nodeView: LibraryView = { kind: "collection", collectionId: node.collectionId };
        return (
          <div key={node.collectionId}>
            <NavItem
              href={buildWorkspaceHref(nodeView, 1)}
              icon="list"
              label={node.name}
              active={isSameView(view, nodeView)}
              count={node.questionCount}
              depth={depth}
            />
            {node.children.length > 0 ? (
              <TreeNodes nodes={node.children} view={view} depth={depth + 1} />
            ) : null}
          </div>
        );
      })}
    </>
  );
}

export function WorkspaceSidebar({
  view,
  collections,
  collectionsFailure,
  inboxCount,
  allCount,
}: WorkspaceSidebarProps) {
  const nodes = buildCollectionTree(collections);
  const hasFailure = collectionsFailure !== undefined;

  return (
    <aside className="min-w-0">
      <div className="rounded-2xl border border-[var(--auth-border)] bg-white p-2">
        <nav aria-label="题库视图" className="flex flex-col gap-1">
          <NavItem
            href={buildWorkspaceHref(ALL_VIEW, 1)}
            icon="list"
            label="全部题目"
            active={view.kind === "all"}
            count={allCount}
          />
          <NavItem
            href={buildWorkspaceHref(INBOX_VIEW, 1)}
            icon="inbox"
            label="待整理"
            active={view.kind === "inbox"}
            count={inboxCount}
          />
        </nav>

        <div className="my-2 border-t border-[var(--auth-divider)]" />

        <p className="px-2 pb-1 text-[11px] font-medium tracking-wide text-[var(--auth-muted)]">
          我的题集
        </p>
        {collectionsFailure === undefined ? (
          nodes.length === 0 ? (
            <p className="px-2 py-2 text-xs leading-5 text-[var(--auth-muted)]">
              还没有自定义题集。
            </p>
          ) : (
            <nav aria-label="题集导航" className="flex flex-col gap-1">
              <TreeNodes nodes={nodes} view={view} depth={0} />
            </nav>
          )
        ) : (
          <div className="px-2 py-1.5">
            <p role="alert" className="text-xs leading-5 font-medium text-[var(--auth-danger)]">
              题集加载失败
            </p>
            <p className="mt-1 text-[11px] leading-5 text-[var(--auth-muted)]">
              {collectionsFailure.message}
            </p>
            <p className="mt-1 text-[11px] leading-5 text-[var(--auth-muted)]">
              题集数据恢复前暂不能创建、改名或移动题目。
            </p>
            <div className="mt-2">
              <RefreshButton />
            </div>
          </div>
        )}

        {hasFailure ? null : (
          <div className="mt-2 px-1">
            <CollectionDialog
              mode="create"
              collections={collections}
              triggerLabel="新建题集"
              triggerVariant="sidebar"
            />
          </div>
        )}
      </div>
      <p className="mt-3 px-2 text-[11px] leading-5 text-[var(--auth-muted)]">
        题集支持多层创建、改名与单题移动；Web 端不提供删除、题目编辑或筛选。
      </p>
    </aside>
  );
}
