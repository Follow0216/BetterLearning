/**
 * 登录与注册表单的本地校验规则。
 *
 * <p>规则与服务端保持一致：用户名 trim 后按 Unicode 码点计 3~32 个字符且仅含
 * 汉字、ASCII 字母、数字与下划线；密码不 trim，按码点计 8~64 个字符且 UTF-8
 * 编码不超过 72 字节；确认密码必须完全一致。登录只检查非空，不套用注册的 8 位
 * 下限。</p>
 */

/** 用户名最小长度（Unicode 码点）。 */
const USERNAME_MIN_LENGTH: number = 3;

/** 用户名最大长度（Unicode 码点）。 */
const USERNAME_MAX_LENGTH: number = 32;

/** 密码最小长度（Unicode 码点）。 */
const PASSWORD_MIN_LENGTH: number = 8;

/** 密码最大长度（Unicode 码点）。 */
const PASSWORD_MAX_LENGTH: number = 64;

/** 密码 UTF-8 编码上限。 */
const PASSWORD_MAX_BYTES: number = 72;

/** 用户名允许的字符集：汉字、ASCII 字母、数字与下划线。 */
const USERNAME_PATTERN: RegExp = new RegExp('^[\\u4e00-\\u9fffA-Za-z0-9_]+$');

/**
 * 表单错误集合；空串表示该位置没有错误。
 */
export class AuthFormErrors {
  /** 用户名字段错误。 */
  username: string = '';

  /** 密码字段错误。 */
  password: string = '';

  /** 确认密码字段错误。 */
  confirmPassword: string = '';

  /** 与具体字段无关的整体错误。 */
  form: string = '';

  /**
   * 是否存在字段级错误。
   *
   * @return 任一字段错误非空时为 true
   */
  has_field_error(): boolean {
    return this.username.length > 0 || this.password.length > 0 ||
      this.confirmPassword.length > 0;
  }

  /**
   * 是否存在任何错误。
   *
   * @return 字段错误或整体错误非空时为 true
   */
  has_error(): boolean {
    return this.has_field_error() || this.form.length > 0;
  }
}

/**
 * 统计 Unicode 码点数量，与服务端按码点计长度一致。
 *
 * @param value 待统计字符串
 * @return 码点数量
 */
export function count_code_points(value: string): number {
  let count: number = 0;
  for (let index: number = 0; index < value.length; index++) {
    const codeUnit: number = value.charCodeAt(index);
    if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF && index + 1 < value.length) {
      const nextUnit: number = value.charCodeAt(index + 1);
      if (nextUnit >= 0xDC00 && nextUnit <= 0xDFFF) {
        index++;
      }
    }
    count++;
  }
  return count;
}

/**
 * 统计 UTF-8 编码字节数。
 *
 * @param value 待统计字符串
 * @return UTF-8 字节数
 */
export function count_utf8_bytes(value: string): number {
  let bytes: number = 0;
  for (let index: number = 0; index < value.length; index++) {
    const codeUnit: number = value.charCodeAt(index);
    if (codeUnit < 0x80) {
      bytes += 1;
    } else if (codeUnit < 0x800) {
      bytes += 2;
    } else if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF && index + 1 < value.length) {
      const nextUnit: number = value.charCodeAt(index + 1);
      if (nextUnit >= 0xDC00 && nextUnit <= 0xDFFF) {
        bytes += 4;
        index++;
      } else {
        bytes += 3;
      }
    } else {
      bytes += 3;
    }
  }
  return bytes;
}

/**
 * 校验注册用户名；入参应为 trim 后的值。
 *
 * @param trimmedUsername 已 trim 的用户名
 * @return 错误文案；通过时为空串
 */
export function validate_registration_username(trimmedUsername: string): string {
  if (trimmedUsername.length === 0) {
    return '用户名不能为空';
  }
  const length: number = count_code_points(trimmedUsername);
  if (length < USERNAME_MIN_LENGTH || length > USERNAME_MAX_LENGTH) {
    return '用户名需为 3~32 个字符';
  }
  if (!USERNAME_PATTERN.test(trimmedUsername)) {
    return '用户名仅支持汉字、字母、数字和下划线';
  }
  return '';
}

/**
 * 校验注册密码；密码不 trim。
 *
 * @param password 原始密码
 * @return 错误文案；通过时为空串
 */
export function validate_registration_password(password: string): string {
  if (password.length === 0) {
    return '密码不能为空';
  }
  const length: number = count_code_points(password);
  if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
    return '密码需为 8~64 个字符';
  }
  if (count_utf8_bytes(password) > PASSWORD_MAX_BYTES) {
    return '密码 UTF-8 编码不能超过 72 字节';
  }
  return '';
}

/**
 * 校验确认密码；必须与密码完全一致。
 *
 * @param password 原始密码
 * @param confirmPassword 原始确认密码
 * @return 错误文案；通过时为空串
 */
export function validate_confirm_password(password: string, confirmPassword: string): string {
  if (confirmPassword.length === 0) {
    return '请再次输入密码';
  }
  if (password !== confirmPassword) {
    return '两次输入的密码不一致';
  }
  return '';
}

/**
 * 校验注册表单。
 *
 * @param username 原始用户名
 * @param password 原始密码
 * @param confirmPassword 原始确认密码
 * @return 字段错误集合；无错误时各字段为空串
 */
export function validate_register_form(username: string, password: string,
  confirmPassword: string): AuthFormErrors {
  const errors: AuthFormErrors = new AuthFormErrors();
  errors.username = validate_registration_username(username.trim());
  errors.password = validate_registration_password(password);
  errors.confirmPassword = validate_confirm_password(password, confirmPassword);
  return errors;
}

/**
 * 校验登录表单；只检查用户名 trim 后非空与密码非空。
 *
 * @param username 原始用户名
 * @param password 原始密码
 * @return 字段错误集合；无错误时各字段为空串
 */
export function validate_login_form(username: string, password: string): AuthFormErrors {
  const errors: AuthFormErrors = new AuthFormErrors();
  if (username.trim().length === 0) {
    errors.username = '用户名不能为空';
  }
  if (password.length === 0) {
    errors.password = '密码不能为空';
  }
  return errors;
}