"use client";

import Link from "next/link";
import { useId, useState } from "react";

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
import styles from "./workspace.module.css";

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
}: {
  href: string;
  icon: BlIconName;
  label: string;
  active: boolean;
  count?: number;
}) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={styles.navItem}
      title={label}
    >
      <BlIcon name={icon} size={16} />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {count === undefined ? null : (
        <span className="text-xs text-[var(--auth-muted)] tabular-nums">{count}</span>
      )}
    </Link>
  );
}

function containsView(node: CollectionNode, view: LibraryView): boolean {
  return (
    view.kind === "collection" &&
    (node.collectionId === view.collectionId ||
      node.children.some((child) => containsView(child, view)))
  );
}

function TreeNode({ node, view }: { node: CollectionNode; view: LibraryView }) {
  const [expanded, setExpanded] = useState(containsView(node, view));
  const childrenId = useId();
  const nodeView: LibraryView = { kind: "collection", collectionId: node.collectionId };
  const hasChildren = node.children.length > 0;
  return (
    <li className={styles.treeBranch}>
      <div className={styles.treeRow}>
        <NavItem
          href={buildWorkspaceHref(nodeView, 1)}
          icon="list"
          label={node.name}
          active={isSameView(view, nodeView)}
          count={node.questionCount}
        />
        {hasChildren ? (
          <button
            type="button"
            className={styles.treeToggle}
            aria-label={`${expanded ? "收起" : "展开"}${node.name}的子题集`}
            aria-expanded={expanded}
            aria-controls={childrenId}
            onClick={() => setExpanded((value) => !value)}
          >
            <BlIcon name="chevron_right" size={14} className={expanded ? "rotate-90" : undefined} />
          </button>
        ) : null}
      </div>
      {hasChildren ? (
        <ul id={childrenId} hidden={!expanded} className={styles.treeChildren}>
          {node.children.map((child) => (
            <TreeNode key={child.collectionId} node={child} view={view} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

export function WorkspaceSidebar({
  view,
  collections,
  collectionsFailure,
  inboxCount,
  allCount,
}: WorkspaceSidebarProps) {
  const [navigationOpen, setNavigationOpen] = useState(false);
  const navigationId = useId();
  const nodes = buildCollectionTree(collections);
  const currentLabel =
    view.kind === "all"
      ? "全部题目"
      : view.kind === "inbox"
        ? "待整理"
        : (collections.find((item) => item.collectionId === view.collectionId)?.name ?? "题集");

  return (
    <aside className={styles.sidebar} aria-label="题库导航">
      <button
        type="button"
        className={styles.sidebarToggle}
        aria-expanded={navigationOpen}
        aria-controls={navigationId}
        onClick={() => setNavigationOpen((value) => !value)}
      >
        <BlIcon name="list" size={18} />
        <span>题库导航</span>
        <span className="min-w-0 flex-1 truncate text-right font-medium text-[var(--auth-text)]">
          {currentLabel}
        </span>
        <BlIcon
          name="chevron_right"
          size={14}
          className={navigationOpen ? "rotate-90" : undefined}
        />
      </button>
      <div
        id={navigationId}
        className={styles.sidebarContent}
        data-open={navigationOpen}
        onClick={(event) => {
          if ((event.target as HTMLElement).closest("a") !== null) setNavigationOpen(false);
        }}
      >
        <p className="mb-2 px-2 text-xs font-medium text-[var(--auth-muted)]">题库</p>
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
        <div className="mt-7 mb-2 flex items-center justify-between px-2">
          <p className="text-xs font-medium text-[var(--auth-muted)]">我的题集</p>
        </div>
        {collectionsFailure === undefined ? (
          nodes.length === 0 ? (
            <p className="px-2 py-3 text-xs leading-5 text-[var(--auth-muted)]">还没有自定义题集</p>
          ) : (
            <nav aria-label="题集导航">
              <ul className="space-y-1">
                {nodes.map((node) => (
                  <TreeNode
                    key={`${node.collectionId}|${view.kind === "collection" ? view.collectionId : view.kind}`}
                    node={node}
                    view={view}
                  />
                ))}
              </ul>
            </nav>
          )
        ) : (
          <div className="px-2 py-2">
            <p role="alert" className="text-xs font-medium text-[var(--auth-danger)]">
              题集加载失败
            </p>
            <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
              {collectionsFailure.message}
            </p>
            <p className="mt-1 text-xs leading-5 text-[var(--auth-muted)]">
              刷新恢复后可继续整理题集。
            </p>
            <div className="mt-2">
              <RefreshButton />
            </div>
          </div>
        )}
        {collectionsFailure === undefined ? (
          <div className="mt-4 px-1">
            <CollectionDialog
              mode="create"
              collections={collections}
              triggerLabel="新建题集"
              triggerVariant="sidebar"
            />
          </div>
        ) : null}
        <p className="mt-6 border-t border-[var(--auth-border)] px-2 pt-4 text-xs leading-6 text-[var(--auth-muted)]">
          手机录题与提问，平板手写作答。网页端集中阅读与整理。
        </p>
      </div>
    </aside>
  );
}
