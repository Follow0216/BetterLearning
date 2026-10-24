import { util } from '@kit.ArkTS';
import { AnswerState } from './SolutionModels';

/** M1 正式学科值；不可传中文名称或演示数据 ID。 */
export enum LearningSubject {
  HIGHER_MATHEMATICS = 'HIGHER_MATHEMATICS',
  OTHER = 'OTHER'
}

export const INBOX_COLLECTION_ID: string = 'inbox';
export const MAX_QUESTION_MARKDOWN_LENGTH: number = 20000;

export function learning_subject_label(subject: LearningSubject): string {
  return subject === LearningSubject.HIGHER_MATHEMATICS ? '高等数学' : '其他';
}

/** 题库文本摘要。summary 由服务端生成，客户端不重算或假定它是完整题面。 */
export class QuestionSummary {
  readonly questionId: string;
  readonly collectionId: string;
  readonly subject: LearningSubject;
  readonly summary: string;
  readonly createdAt: string;
  readonly answerState: AnswerState;

  constructor(questionId: string, collectionId: string, subject: LearningSubject,
    summary: string, createdAt: string, answerState: AnswerState = AnswerState.NOT_STARTED) {
    this.questionId = questionId;
    this.collectionId = collectionId;
    this.subject = subject;
    this.summary = summary;
    this.createdAt = createdAt;
    this.answerState = answerState;
  }
}

/** 题目详情及确认结果；正文保留服务端原文。 */
export class QuestionDetail extends QuestionSummary {
  readonly bodyMarkdown: string;

  constructor(questionId: string, collectionId: string, subject: LearningSubject,
    summary: string, createdAt: string, bodyMarkdown: string, answerState: AnswerState = AnswerState.NOT_STARTED) {
    super(questionId, collectionId, subject, summary, createdAt, answerState);
    this.bodyMarkdown = bodyMarkdown;
  }
}

export class QuestionPage {
  readonly items: QuestionSummary[];
  readonly page: number;
  readonly pageSize: number;
  readonly total: number;

  constructor(items: QuestionSummary[], page: number, pageSize: number, total: number) {
    this.items = items;
    this.page = page;
    this.pageSize = pageSize;
    this.total = total;
  }
}

export class QuestionCollection {
  readonly collectionId: string;
  readonly name: string;
  readonly system: boolean;
  readonly questionCount: number;
  readonly parentCollectionId: string | null;

  constructor(collectionId: string, name: string, system: boolean, questionCount: number, parentCollectionId: string | null = null) {
    this.collectionId = collectionId;
    this.name = name;
    this.system = system;
    this.questionCount = questionCount;
    this.parentCollectionId = parentCollectionId;
  }
}

/** 页码从 1 开始；collectionId=null 表示当前账号所有题目。 */
export class QuestionListQuery {
  readonly page: number;
  readonly pageSize: number;
  readonly collectionId: string | null;

  constructor(page: number = 1, pageSize: number = 20, collectionId: string | null = null) {
    this.page = page;
    this.pageSize = pageSize;
    this.collectionId = collectionId;
  }
}

/**
 * 一次人工确认动作的不可变快照。首次确认时用 create 创建；双击、超时或断网重试
 * 复用同一对象。正文或学科改变后重新 create，不能只替换原对象的 requestId。
 */
export class ManualQuestionInput {
  readonly requestId: string;
  readonly subject: LearningSubject;
  readonly bodyMarkdown: string;

  constructor(requestId: string, subject: LearningSubject, bodyMarkdown: string) {
    this.requestId = requestId;
    this.subject = subject;
    this.bodyMarkdown = bodyMarkdown;
  }

  static create(subject: LearningSubject, bodyMarkdown: string): ManualQuestionInput {
    return new ManualQuestionInput(util.generateRandomUUID(true).toLowerCase(), subject, bodyMarkdown);
  }
}
