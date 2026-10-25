import { V2Citation } from './LearningV2Models';

/** 只含渲染所需的白名单 ID 与显示文字，不含路径或 HTML。 */
export interface V2CitationMarker { citationId: string; label: string; }

const MARKER: RegExp = /\[\[citation:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\]\]/g;
const UUID: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * 仅接受当前已发布 result.citations 白名单中的正文标记，按首次出现顺序去重。
 * 调用方使用 label 作纯文本，不把它拼成 HTML 或可点击资源路径。
 */
export function citation_marker_metadata(markdown: string, citations: V2Citation[]): V2CitationMarker[] {
  const result: V2CitationMarker[] = [];
  if (typeof markdown !== 'string' || !Array.isArray(citations)) { return result; }
  const allowed: Map<string, string> = new Map<string, string>();
  for (let index: number = 0; index < citations.length; index++) {
    const citation: V2Citation = citations[index];
    if (citation && typeof citation.citationId === 'string' && UUID.test(citation.citationId) &&
      typeof citation.label === 'string' && !allowed.has(citation.citationId)) {
      allowed.set(citation.citationId, citation.label);
    }
  }
  const seen: Set<string> = new Set<string>();
  MARKER.lastIndex = 0;
  let match: RegExpExecArray | null = MARKER.exec(markdown);
  while (match !== null) {
    const citationId: string = match[1];
    if (allowed.has(citationId) && !seen.has(citationId)) {
      result.push({ citationId: citationId, label: allowed.get(citationId) as string });
      seen.add(citationId);
    }
    match = MARKER.exec(markdown);
  }
  return result;
}

export function citation_marker_ids(markdown: string, citations: V2Citation[]): string[] {
  const markers: V2CitationMarker[] = citation_marker_metadata(markdown, citations);
  const ids: string[] = [];
  for (let index: number = 0; index < markers.length; index++) {
    ids.push(markers[index].citationId);
  }
  return ids;
}
