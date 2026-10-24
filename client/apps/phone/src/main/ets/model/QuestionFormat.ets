import {
  AnswerState,
  AuthError,
  AuthFailureKind,
  TutorTurn,
  TutorTurnStatus
} from 'common';

/**
 * 把服务端作答状态转换为中文标签。
 *
 * @param state 作答状态
 * @return 未开始、进行中或已完成
 */
export function describe_answer_state(state: AnswerState): string {
  if (state === AnswerState.IN_PROGRESS) {
    return '进行中';
  }
  if (state === AnswerState.COMPLETED) {
    return '已完成';
  }
  return '未开始';
}

/**
 * 两位补零。
 *
 * @param value 非负整数
 * @return 至少两位的十进制文本
 */
function pad_two(value: number): string {
  return value < 10 ? '0' + value.toString() : value.toString();
}

/**
 * 把服务端 ISO 8601 时间转换为本地可读时间。
 *
 * @param iso 服务端时间字符串
 * @return yyyy-MM-dd HH:mm；无法解析时原样返回
 */
export function format_question_time(iso: string): string {
  const millis: number = Date.parse(iso);
  if (!Number.isFinite(millis)) {
    return iso;
  }
  const date: Date = new Date(millis);
  const text: string = date.getFullYear().toString() + '-' +
    pad_two(date.getMonth() + 1) + '-' + pad_two(date.getDate()) + ' ' +
    pad_two(date.getHours()) + ':' + pad_two(date.getMinutes());
  return text;
}

/**
 * 把题库接口失败转换为可展示文案；401 由调用方单独处理。
 *
 * @param error 接口错误，可能为 null
 * @return 中文错误文案
 */
export function describe_learning_error(error: AuthError | null): string {
  if (error === null) {
    return '服务响应异常，请稍后重试';
  }
  if (error.is_network()) {
    return '网络连接失败，请检查网络后重试';
  }
  if (error.is_service_unavailable()) {
    return '题库服务暂时不可用，请稍后重试';
  }
  if (error.is_rate_limited()) {
    return '操作过于频繁，请稍后重试';
  }
  if (error.httpStatus === 404) {
    return '题目不存在或不属于当前账号';
  }
  if (error.kind === AuthFailureKind.PROTOCOL) {
    return '服务响应异常，请稍后重试';
  }
  if (error.message.length > 0) {
    return error.message;
  }
  return '加载失败，请稍后重试';
}

/**
 * 把题集接口失败转换为可展示文案；401 由调用方单独处理。
 *
 * @param error 接口错误，可能为 null
 * @return 中文错误文案
 */
export function describe_collection_error(error: AuthError | null): string {
  if (error === null) {
    return '题集服务响应异常，请稍后重试';
  }
  if (error.is_network()) {
    return '网络连接失败，请检查网络后重试';
  }
  if (error.is_service_unavailable()) {
    return '题集服务暂时不可用，请稍后重试';
  }
  if (error.is_rate_limited()) {
    return '操作过于频繁，请稍后重试';
  }
  if (error.kind === AuthFailureKind.PROTOCOL) {
    return '题集服务响应异常，请稍后重试';
  }
  if (error.message.length > 0) {
    return error.message;
  }
  return '题集加载失败，请稍后重试';
}

/**
 * 把手工确认失败转换为可展示文案；401 由调用方单独处理。
 *
 * @param error 接口错误，可能为 null
 * @return 中文错误文案
 */
export function describe_confirm_error(error: AuthError | null): string {
  if (error !== null && error.serverCode === 'A0445') {
    return '该确认请求已用于其他内容，请重新编辑后提交';
  }
  return describe_learning_error(error);
}
export function describe_tutor_status(status: TutorTurnStatus): string {
  if (status === TutorTurnStatus.WAITING_ANSWER) {
    return '等待平板作答';
  }
  if (status === TutorTurnStatus.GENERATING) {
    return '正在生成解答';
  }
  if (status === TutorTurnStatus.SUCCEEDED) {
    return '已完成';
  }
  return '生成失败';
}

export function describe_tutor_failure(turn: TutorTurn): string {
  if (turn.failureCode === 'TABLET_CANCELLED') {
    return '已取消等待平板作答';
  }
  if (turn.failureMessage !== null && turn.failureMessage.length > 0) {
    return turn.failureMessage;
  }
  return '本轮未能生成解答，可重试本轮';
}

export function describe_tutor_error(error: AuthError | null): string {
  if (error === null) {
    return '辅导服务响应异常，请稍后重试';
  }
  if (error.is_network()) {
    return '网络连接失败，请检查网络后重试';
  }
  if (error.is_service_unavailable()) {
    return '辅导模型暂时不可用，请稍后重试';
  }
  if (error.is_rate_limited()) {
    return '操作过于频繁，请稍后重试';
  }
  if (error.httpStatus === 409) {
    return '当前账号已有进行中的辅导轮次，请等待其结束';
  }
  if (error.httpStatus === 404) {
    return '辅导轮次不存在或不属于当前账号';
  }
  if (error.httpStatus === 400) {
    return '提问无效，请修改后重试';
  }
  if (error.kind === AuthFailureKind.PROTOCOL) {
    return '辅导服务响应异常，请稍后重试';
  }
  if (error.message.length > 0) {
    return error.message;
  }
  return '辅导请求失败，请稍后重试';
}