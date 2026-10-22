/**
 * 表单校验：与 server 的 UserInputNormalizer 保持同一规则。
 *
 * 用户名按 trim 后的小写值计算码点；密码不 trim，空白字符也计入长度。
 */

export type AuthFieldName = "username" | "password" | "confirmPassword";

const USERNAME_MIN_POINTS = 3;
const USERNAME_MAX_POINTS = 32;
const PASSWORD_MIN_POINTS = 8;
const PASSWORD_MAX_POINTS = 64;
const PASSWORD_MAX_BYTES = 72;
const USERNAME_PATTERN = /^[\u4e00-\u9fffA-Za-z0-9_]+$/;

export function normalizeUsername(username: string): string {
  return username.trim().toLowerCase();
}

export function countCodePoints(value: string): number {
  return Array.from(value).length;
}

export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

/** 注册用户名：3~32 码点，仅中文、ASCII 字母、数字与下划线。 */
export function validateRegistrationUsername(username: string): string | null {
  const normalized = normalizeUsername(username);
  if (normalized.length === 0) {
    return "请输入用户名";
  }
  const length = countCodePoints(normalized);
  if (length < USERNAME_MIN_POINTS || length > USERNAME_MAX_POINTS) {
    return "用户名需为3~32个字符";
  }
  if (!USERNAME_PATTERN.test(normalized)) {
    return "仅支持中文、字母、数字和下划线";
  }
  return null;
}

/** 注册密码：8~64 码点，UTF-8 不超过 72 字节，不 trim。 */
export function validateRegistrationPassword(password: string): string | null {
  if (password.length === 0) {
    return "请输入密码";
  }
  const length = countCodePoints(password);
  if (length < PASSWORD_MIN_POINTS || length > PASSWORD_MAX_POINTS) {
    return "密码需为8~64个字符";
  }
  if (utf8ByteLength(password) > PASSWORD_MAX_BYTES) {
    return "密码不能超过72字节";
  }
  return null;
}

export function validateConfirmPassword(password: string, confirmPassword: string): string | null {
  if (confirmPassword.length === 0) {
    return "请再次输入密码";
  }
  if (password !== confirmPassword) {
    return "两次输入的密码不一致";
  }
  return null;
}

/** 登录只要求用户名与密码非空，不套用注册的 8 位下限。 */
export function validateLoginForm(
  username: string,
  password: string,
): Partial<Record<AuthFieldName, string>> {
  const errors: Partial<Record<AuthFieldName, string>> = {};
  if (normalizeUsername(username).length === 0) {
    errors.username = "请输入用户名";
  }
  if (password.length === 0) {
    errors.password = "请输入密码";
  }
  return errors;
}

export function validateRegisterForm(
  username: string,
  password: string,
  confirmPassword: string,
): Partial<Record<AuthFieldName, string>> {
  const errors: Partial<Record<AuthFieldName, string>> = {};
  const usernameError = validateRegistrationUsername(username);
  if (usernameError) {
    errors.username = usernameError;
  }
  const passwordError = validateRegistrationPassword(password);
  if (passwordError) {
    errors.password = passwordError;
  }
  const confirmError = validateConfirmPassword(password, confirmPassword);
  if (confirmError) {
    errors.confirmPassword = confirmError;
  }
  return errors;
}
