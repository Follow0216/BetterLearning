/** 两位补零。 */
function pad_two(value: number): string {
  return value < 10 ? `0${value}` : `${value}`;
}

/**
 * 把服务端 ISO-8601 时间转成本地可读文本。
 *
 * <p>解析失败时原样返回，避免把无效时间显示成 1970 年。</p>
 *
 * @param value 服务端时间字符串
 * @return yyyy-MM-dd HH:mm 文本
 */
export function format_created_at(value: string): string {
  if (value.length === 0) {
    return '';
  }
  const parsed: Date = new Date(value);
  const millis: number = parsed.getTime();
  if (Number.isNaN(millis)) {
    return value;
  }
  return `${parsed.getFullYear()}-${pad_two(parsed.getMonth() + 1)}-${pad_two(parsed.getDate())} ` +
    `${pad_two(parsed.getHours())}:${pad_two(parsed.getMinutes())}`;
}
