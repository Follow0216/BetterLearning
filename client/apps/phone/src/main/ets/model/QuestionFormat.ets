import { AuthError, AuthFailureKind } from 'common';

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