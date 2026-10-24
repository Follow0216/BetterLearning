import { http } from '@kit.NetworkKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult } from '../auth/AuthModels';
import { AnswerState } from './SolutionModels';
import {
  INBOX_COLLECTION_ID,
  LearningSubject,
  ManualQuestionInput,
  MAX_QUESTION_MARKDOWN_LENGTH,
  QuestionCollection,
  QuestionDetail,
  QuestionListQuery,
  QuestionPage,
  QuestionSummary
} from './LearningModels';

const QUESTIONS_PATH: string = '/api/v1/learning/questions';
const COLLECTIONS_PATH: string = '/api/v1/learning/collections';
const MAX_LONG_ID: string = '9223372036854775807';

interface ManualQuestionPayload {
  requestId: string;
  subject: string;
  bodyMarkdown: string;
}

function is_id(value: string): boolean {
  return /^[1-9][0-9]*$/.test(value) && (value.length < MAX_LONG_ID.length ||
    (value.length === MAX_LONG_ID.length && value <= MAX_LONG_ID));
}

function is_collection_id(value: string): boolean {
  return value === INBOX_COLLECTION_ID || is_id(value);
}

function as_record(value: Object | null | undefined): Record<string, Object> | null {
  if (value === null || value === undefined || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, Object>;
}

function parse_record(body: string): Record<string, Object> | null {
  try {
    return as_record(JSON.parse(body) as Object | null);
  } catch (error) {
    return null;
  }
}

function read_string(record: Record<string, Object>, key: string): string | null {
  const value: Object | undefined = record[key];
  return typeof value === 'string' ? value as string : null;
}

function read_integer(record: Record<string, Object>, key: string): number | null {
  const value: Object | undefined = record[key];
  if (typeof value !== 'number') {
    return null;
  }
  const numberValue: number = value as number;
  return Number.isSafeInteger(numberValue) && numberValue >= 0 ? numberValue : null;
}

function read_subject(record: Record<string, Object>): LearningSubject | null {
  const value: string | null = read_string(record, 'subject');
  if (value === LearningSubject.HIGHER_MATHEMATICS) {
    return LearningSubject.HIGHER_MATHEMATICS;
  }
  return value === LearningSubject.OTHER ? LearningSubject.OTHER : null;
}

function parse_summary(value: Object | null | undefined): QuestionSummary | null {
  const record: Record<string, Object> | null = as_record(value);
  if (record === null) {
    return null;
  }
  const questionId: string | null = read_string(record, 'questionId');
  const collectionId: string | null = read_string(record, 'collectionId');
  const subject: LearningSubject | null = read_subject(record);
  const summary: string | null = read_string(record, 'summary');
  const createdAt: string | null = read_string(record, 'createdAt');
  if (questionId === null || !is_id(questionId) || collectionId === null ||
    !is_collection_id(collectionId) || subject === null || summary === null || createdAt === null ||
    createdAt.length === 0) {
    return null;
  }
  const answerValue: Object | undefined = record['answerState'];
  let answerState: AnswerState = AnswerState.NOT_STARTED;
  if (answerValue !== undefined) {
    if (answerValue !== AnswerState.NOT_STARTED && answerValue !== AnswerState.IN_PROGRESS &&
      answerValue !== AnswerState.COMPLETED) { return null; }
    answerState = answerValue as AnswerState;
  }
  return new QuestionSummary(questionId, collectionId, subject, summary, createdAt, answerState);
}

function parse_detail(body: string): QuestionDetail | null {
  const record: Record<string, Object> | null = parse_record(body);
  const summary: QuestionSummary | null = parse_summary(record);
  if (record === null || summary === null) {
    return null;
  }
  const markdown: string | null = read_string(record, 'bodyMarkdown');
  if (markdown === null || markdown.trim().length === 0 || markdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
    return null;
  }
  return new QuestionDetail(summary.questionId, summary.collectionId, summary.subject,
    summary.summary, summary.createdAt, markdown, summary.answerState);
}

function parse_page(body: string): QuestionPage | null {
  const record: Record<string, Object> | null = parse_record(body);
  if (record === null) {
    return null;
  }
  const page: number | null = read_integer(record, 'page');
  const pageSize: number | null = read_integer(record, 'pageSize');
  const total: number | null = read_integer(record, 'total');
  const itemsValue: Object | undefined = record['items'];
  if (page === null || page < 1 || page > 1000000 || pageSize === null || pageSize < 1 ||
    pageSize > 100 || total === null || !Array.isArray(itemsValue)) {
    return null;
  }
  const values: Object[] = itemsValue as Object[];
  if (values.length > pageSize) {
    return null;
  }
  const items: QuestionSummary[] = [];
  for (let index: number = 0; index < values.length; index++) {
    const item: QuestionSummary | null = parse_summary(values[index]);
    if (item === null) {
      return null;
    }
    items.push(item);
  }
  return new QuestionPage(items, page, pageSize, total);
}

function parse_collections(body: string): QuestionCollection[] | null {
  const record: Record<string, Object> | null = parse_record(body);
  if (record === null || !Array.isArray(record['items'])) {
    return null;
  }
  const values: Object[] = record['items'] as Object[];
  const items: QuestionCollection[] = [];
  for (let index: number = 0; index < values.length; index++) {
    const item: Record<string, Object> | null = as_record(values[index]);
    if (item === null) {
      return null;
    }
    const collectionId: string | null = read_string(item, 'collectionId');
    const name: string | null = read_string(item, 'name');
    const systemValue: Object | undefined = item['system'];
    const questionCount: number | null = read_integer(item, 'questionCount');
    if (collectionId === null || !is_collection_id(collectionId) || name === null ||
      typeof systemValue !== 'boolean' || questionCount === null) {
      return null;
    }
    const parentValue: Object | null | undefined = item['parentCollectionId'];
    let parentId: string | null = null;
    if (parentValue !== undefined && parentValue !== null) {
      if (typeof parentValue !== 'string' || !is_id(parentValue as string)) { return null; }
      parentId = parentValue as string;
    }
    items.push(new QuestionCollection(collectionId, name, systemValue as boolean, questionCount, parentId));
  }
  if (items.length === 0 || items[0].collectionId !== INBOX_COLLECTION_ID || !items[0].system) {
    return null;
  }
  return items;
}

function failure_error(result: AuthResult<string>): AuthError {
  return result.error === null ? AuthError.protocol_failure('未收到服务端响应') : result.error;
}

/**
 * M1 轻量学习 API。复用 AuthApi 的实际 HTTP 栈，不持有令牌，不自动重试确认请求，
 * 不把网络/503/解析错误当成空数据。调用方以 token guard 处理异步回写及 401。
 * 协议来源：C1 docs/design/mvp-m1-contract.md（2026-10-24）。
 */
export class LearningApi {
  private readonly transport: AuthApi;

  constructor(baseUrl: string) {
    this.transport = new AuthApi(baseUrl);
  }

  static create_default(): LearningApi {
    return new LearningApi(DEFAULT_DEV_BASE_URL);
  }

  async listCollections(token: string): Promise<AuthResult<QuestionCollection[]>> {
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      COLLECTIONS_PATH, token);
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<QuestionCollection[]>(failure_error(result));
    }
    const items: QuestionCollection[] | null = parse_collections(result.data);
    return items === null ? AuthResult.failure<QuestionCollection[]>(
      AuthError.protocol_failure('题集响应格式不符合预期')) : AuthResult.success<QuestionCollection[]>(items);
  }

  async listQuestions(token: string, query: QuestionListQuery = new QuestionListQuery()):
    Promise<AuthResult<QuestionPage>> {
    if (!Number.isInteger(query.page) || query.page < 1 || query.page > 1000000 ||
      !Number.isInteger(query.pageSize) || query.pageSize < 1 || query.pageSize > 100 ||
      (query.collectionId !== null && !is_collection_id(query.collectionId))) {
      return AuthResult.failure<QuestionPage>(AuthError.local_validation('题库分页或题集参数无效', {}));
    }
    let path: string = QUESTIONS_PATH + '?page=' + query.page.toString() + '&pageSize=' + query.pageSize.toString();
    if (query.collectionId !== null) {
      path += '&collectionId=' + encodeURIComponent(query.collectionId);
    }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET, path, token);
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<QuestionPage>(failure_error(result));
    }
    const page: QuestionPage | null = parse_page(result.data);
    if (page === null || page.page !== query.page || page.pageSize !== query.pageSize) {
      return AuthResult.failure<QuestionPage>(AuthError.protocol_failure('题库响应格式不符合预期'));
    }
    return AuthResult.success<QuestionPage>(page);
  }

  async getQuestion(token: string, questionId: string): Promise<AuthResult<QuestionDetail>> {
    if (!is_id(questionId)) {
      return AuthResult.failure<QuestionDetail>(AuthError.local_validation('题目 ID 无效', {}));
    }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      QUESTIONS_PATH + '/' + encodeURIComponent(questionId), token);
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<QuestionDetail>(failure_error(result));
    }
    const detail: QuestionDetail | null = parse_detail(result.data);
    if (detail === null || detail.questionId !== questionId) {
      return AuthResult.failure<QuestionDetail>(AuthError.protocol_failure('题目详情响应格式不符合预期'));
    }
    return AuthResult.success<QuestionDetail>(detail);
  }

  async confirmManualQuestion(token: string, input: ManualQuestionInput): Promise<AuthResult<QuestionDetail>> {
    const fields: Record<string, string> = {};
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(input.requestId)) {
      fields['requestId'] = '确认请求标识无效，请重新发起确认';
    }
    if (input.subject !== LearningSubject.HIGHER_MATHEMATICS && input.subject !== LearningSubject.OTHER) {
      fields['subject'] = '请选择学科';
    }
    if (input.bodyMarkdown.trim().length === 0 || input.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
      fields['bodyMarkdown'] = '请输入题目正文，最多 20000 字符';
    }
    if (Object.keys(fields).length > 0) {
      return AuthResult.failure<QuestionDetail>(AuthError.local_validation('请检查确认内容', fields));
    }
    const payload: ManualQuestionPayload = {
      requestId: input.requestId,
      subject: input.subject,
      bodyMarkdown: input.bodyMarkdown
    };
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.POST,
      QUESTIONS_PATH, token, JSON.stringify(payload), 201);
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<QuestionDetail>(failure_error(result));
    }
    const detail: QuestionDetail | null = parse_detail(result.data);
    if (detail === null || detail.collectionId !== INBOX_COLLECTION_ID || detail.subject !== input.subject ||
      detail.bodyMarkdown !== input.bodyMarkdown) {
      return AuthResult.failure<QuestionDetail>(AuthError.protocol_failure('题目确认响应格式不符合预期'));
    }
    return AuthResult.success<QuestionDetail>(detail);
  }
}
