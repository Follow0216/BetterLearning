import { FrozenAnswerUpload } from '../learning/SolutionModels';

export enum TutorTurnStatus {
  WAITING_ANSWER = 'WAITING_ANSWER', GENERATING = 'GENERATING', SUCCEEDED = 'SUCCEEDED', FAILED = 'FAILED'
}
export interface TutorTurn {
  turnId: string;
  requestId: string;
  questionId: string;
  prompt: string;
  status: TutorTurnStatus;
  fileRef: string | null;
  answerMarkdown: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface TutorPending { turn: TutorTurn | null; }
/** 仅客户端本机包；HTTP 只提交 upload 的 manifest/PNG，不提交 accountId 或私有路径。 */
export interface FrozenTutorAnswer { questionId: string; turnId: string; upload: FrozenAnswerUpload; }
export interface OcrResult { bodyMarkdown: string; }
