import { CanvasEditableDocument, CanvasExtent, CanvasImageBlock, CanvasLocalFile } from '../canvas/CanvasModels';

export enum AnswerState { NOT_STARTED = 'NOT_STARTED', IN_PROGRESS = 'IN_PROGRESS', COMPLETED = 'COMPLETED' }
export interface AnswerQuestionSnapshot { questionId: string; subject: string; bodyMarkdown: string; }
export interface AnswerManifest {
  schemaVersion: number;
  saveRequestId: string;
  baseFileRef: string | null;
  canvas: CanvasExtent;
  document: CanvasEditableDocument;
  blocks: CanvasImageBlock[];
  questionSnapshot?: AnswerQuestionSnapshot;
}
export interface SolutionDetail {
  questionId: string;
  state: AnswerState;
  fileRef: string | null;
  savedAt: string | null;
  completedAt: string | null;
  manifest: AnswerManifest | null;
}
/** manifestJson 和全部文件在一次保存动作中保持不变，重试不得重新导出。 */
export interface FrozenAnswerUpload { manifestJson: string; files: CanvasLocalFile[]; }
