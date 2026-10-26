/** 批量识别仅产生待校对材料；正式入库仍逐题走 ManualQuestionInput。 */
export enum BatchOcrStatus {
  QUEUED = 'QUEUED', RUNNING = 'RUNNING', COMPLETED = 'COMPLETED', INCOMPLETE = 'INCOMPLETE',
  EMPTY = 'EMPTY', FAILED = 'FAILED', CANCELLED = 'CANCELLED', UNKNOWN = 'UNKNOWN'
}
export interface BatchOcrImage {
  path: string;
  contentType: string;
  width: number;
  height: number;
  byteLength: number;
}
export interface BatchOcrQuestion {
  itemId: string;
  bodyMarkdown: string;
  sourceIds: string[];
  tileIds: string[];
  needsReview: boolean;
  warnings: string[];
}
export interface BatchOcrSource { sourceId: string; order: number; width: number; height: number; }
export interface BatchOcrTile {
  tileId: string; sourceId: string; x: number; y: number; width: number; height: number;
  repair: boolean; markdown: string;
}
export interface BatchOcrResult {
  questions: BatchOcrQuestion[]; sources: BatchOcrSource[]; tiles: BatchOcrTile[];
  warnings: string[]; incomplete: boolean;
}
export interface BatchOcrJob {
  requestId: string; status: BatchOcrStatus; stage: string; completedTiles: number; totalTiles: number;
  result?: BatchOcrResult; errorCode?: string; message?: string;
}
export function batch_ocr_is_active(status: BatchOcrStatus): boolean {
  return status === BatchOcrStatus.QUEUED || status === BatchOcrStatus.RUNNING;
}
export function batch_ocr_uuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
}
function record(value: Object | null | undefined): Record<string, Object> | null {
  return value !== null && value !== undefined && typeof value === 'object' && !Array.isArray(value) ?
    value as Record<string, Object> : null;
}
function integer(value: Object | undefined, minimum: number = 0): boolean {
  return typeof value === 'number' && Number.isSafeInteger(value as number) && (value as number) >= minimum;
}
function text(value: Object | undefined, maximum: number, empty: boolean = true): boolean {
  return typeof value === 'string' && (value as string).length <= maximum && (empty || (value as string).trim().length > 0);
}
function strings(value: Object | undefined, maximum: number, length: number): boolean {
  if (!Array.isArray(value) || (value as Object[]).length > maximum) { return false; }
  for (const entry of value as Object[]) { if (!text(entry, length, false)) { return false; } }
  return true;
}
function parse_result(value: Object | undefined): BatchOcrResult | null {
  const r: Record<string, Object> | null = record(value);
  if (r === null || !Array.isArray(r['sources']) || !Array.isArray(r['questions']) || !Array.isArray(r['tiles']) ||
    typeof r['incomplete'] !== 'boolean' || !strings(r['warnings'], 20, 500)) { return null; }
  const sources: Object[] = r['sources'] as Object[];
  const questions: Object[] = r['questions'] as Object[];
  const tiles: Object[] = r['tiles'] as Object[];
  if (sources.length < 1 || sources.length > 4 || questions.length > 100 || tiles.length > 24) { return null; }
  const sourceMap: Map<string, BatchOcrSource> = new Map<string, BatchOcrSource>();
  for (let i: number = 0; i < sources.length; i++) {
    const s: Record<string, Object> | null = record(sources[i]);
    if (s === null || s['sourceId'] !== 'image-' + i.toString() || s['order'] !== i ||
      !integer(s['width'], 1) || !integer(s['height'], 1) || (s['width'] as number) > 16000 || (s['height'] as number) > 16000 || (s['width'] as number) * (s['height'] as number) > 16000000) { return null; }
    sourceMap.set(s['sourceId'] as string, s as Object as BatchOcrSource);
  }
  const tileIds: Set<string> = new Set<string>();
  for (const entry of tiles) {
    const t: Record<string, Object> | null = record(entry);
    if (t === null || !text(t['tileId'], 256, false) || !text(t['sourceId'], 32, false) ||
      !integer(t['x']) || !integer(t['y']) || !integer(t['width'], 1) || !integer(t['height'], 1) ||
      typeof t['repair'] !== 'boolean' || !text(t['markdown'], 20000)) { return null; }
    const source: BatchOcrSource | undefined = sourceMap.get(t['sourceId'] as string);
    if (source === undefined || (t['x'] as number) + (t['width'] as number) > source.width ||
      (t['y'] as number) + (t['height'] as number) > source.height || tileIds.has(t['tileId'] as string)) { return null; }
    tileIds.add(t['tileId'] as string);
  }
  const itemIds: Set<string> = new Set<string>();
  for (const entry of questions) {
    const q: Record<string, Object> | null = record(entry);
    if (q === null || !text(q['itemId'], 256, false) || !text(q['bodyMarkdown'], 20000, false) ||
      !strings(q['sourceIds'], 4, 32) || (q['sourceIds'] as string[]).length === 0 ||
      !strings(q['tileIds'], 24, 256) || typeof q['needsReview'] !== 'boolean' || !strings(q['warnings'], 20, 500) ||
      itemIds.has(q['itemId'] as string)) { return null; }
    for (const id of q['sourceIds'] as string[]) { if (!sourceMap.has(id)) { return null; } }
    for (const id of q['tileIds'] as string[]) {
      if (!tileIds.has(id)) { return null; }
      const tile: BatchOcrTile | undefined = (tiles as BatchOcrTile[]).find((entry: BatchOcrTile) => entry.tileId === id);
      if (tile === undefined || !(q['sourceIds'] as string[]).includes(tile.sourceId)) { return null; }
    }
    itemIds.add(q['itemId'] as string);
  }
  return r as Object as BatchOcrResult;
}
/** 严格保留 EMPTY/INCOMPLETE/UNKNOWN，格式错误不冒充空结果。 */
export function parse_batch_ocr_job(body: string, requestId: string): BatchOcrJob | null {
  try {
    const r: Record<string, Object> | null = record(JSON.parse(body) as Object);
    if (r === null || !batch_ocr_uuid(requestId) || r['requestId'] !== requestId ||
      !text(r['stage'], 100, false) || !integer(r['completedTiles']) || !integer(r['totalTiles']) ||
      (r['completedTiles'] as number) > (r['totalTiles'] as number)) { return null; }
    const status: Object = r['status'];
    if (status !== BatchOcrStatus.QUEUED && status !== BatchOcrStatus.RUNNING && status !== BatchOcrStatus.COMPLETED &&
      status !== BatchOcrStatus.INCOMPLETE && status !== BatchOcrStatus.EMPTY && status !== BatchOcrStatus.FAILED &&
      status !== BatchOcrStatus.CANCELLED && status !== BatchOcrStatus.UNKNOWN) { return null; }
    if ((r['errorCode'] !== undefined && r['errorCode'] !== null && !text(r['errorCode'], 100)) ||
      (r['message'] !== undefined && r['message'] !== null && !text(r['message'], 2000))) { return null; }
    let result: BatchOcrResult | undefined = undefined;
    if (r['result'] !== undefined && r['result'] !== null) {
      const parsed: BatchOcrResult | null = parse_result(r['result']);
      if (parsed === null) { return null; }
      result = parsed;
    }
    if ((status === BatchOcrStatus.COMPLETED || status === BatchOcrStatus.INCOMPLETE || status === BatchOcrStatus.EMPTY) && result === undefined) { return null; }
    return { requestId: requestId, status: status as BatchOcrStatus, stage: r['stage'] as string,
      completedTiles: r['completedTiles'] as number, totalTiles: r['totalTiles'] as number, result: result,
      errorCode: typeof r['errorCode'] === 'string' ? r['errorCode'] as string : undefined,
      message: typeof r['message'] === 'string' ? r['message'] as string : undefined };
  } catch (error) { return null; }
}
