import type { CollectionSummary } from "@/lib/api/library-contract";

export interface CollectionNode extends CollectionSummary {
  children: CollectionNode[];
}

export interface FlatCollectionNode extends CollectionSummary {
  depth: number;
}

export function buildCollectionTree(items: CollectionSummary[]): CollectionNode[] {
  const nodes = new Map<string, CollectionNode>();
  for (const item of items) {
    if (!item.system) {
      nodes.set(item.collectionId, { ...item, children: [] });
    }
  }

  const roots: CollectionNode[] = [];
  for (const node of nodes.values()) {
    const parent =
      node.parentCollectionId === null ? undefined : nodes.get(node.parentCollectionId);
    if (parent === undefined) {
      roots.push(node);
    } else {
      parent.children.push(node);
    }
  }
  return roots;
}

export function flattenCollectionTree(
  nodes: CollectionNode[],
  depth = 0,
  visited = new Set<string>(),
): FlatCollectionNode[] {
  const result: FlatCollectionNode[] = [];
  for (const node of nodes) {
    if (visited.has(node.collectionId)) {
      continue;
    }
    visited.add(node.collectionId);
    result.push({ ...node, depth });
    result.push(...flattenCollectionTree(node.children, depth + 1, visited));
  }
  return result;
}