/**
 * 手机与平板共用的认证领域模型。
 *
 * <p>本文件只描述服务端契约对应的数据类型、请求结果与错误语义，不包含网络请求、
 * 会话存储或页面逻辑。字段名与服务端 JSON 保持一致：{@code accountId} 为十进制
 * 字符串，{@code expiresIn} 单位为秒，时间为 ISO-8601 字符串。</p>
 *
 * <p>错误按来源分为本地校验、网络、HTTP 与协议解析四类。HTTP 401 不在此处统一
 * 解释为登录失败：登录场景表示凭据错误，受保护接口表示令牌缺失或失效，由调用方
 * 结合场景处理。</p>
 */

/**
 * 用户资料；对应服务端 {@code UserVO}。
 */
export class UserProfile {
  /** 账号 ID 的十进制字符串形式。 */
  readonly accountId: string;

  /** 规范化后的用户名。 */
  readonly username: string;

  /** E.164 手机号；未绑定时为 null。 */
  readonly phone: string | null;

  /** 小写邮箱；未绑定时为 null。 */
  readonly email: string | null;

  /** 角色标识，服务端当前取值为 USER 或 ADMIN。 */
  readonly role: string;

  /** 创建时间，ISO-8601 字符串。 */
  readonly createdAt: string;

  /** 最后更新时间，ISO-8601 字符串。 */
  readonly updatedAt: string;

  /**
   * 创建用户资料快照。
   *
   * @param accountId 十进制字符串形式的账号 ID
   * @param username 规范化后的用户名
   * @param phone E.164 手机号，未绑定为 null
   * @param email 小写邮箱，未绑定为 null
   * @param role 角色标识
   * @param createdAt 创建时间
   * @param updatedAt 最后更新时间
   */
  constructor(accountId: string, username: string, phone: string | null, email: string | null,
    role: string, createdAt: string, updatedAt: string) {
    this.accountId = accountId;
    this.username = username;
    this.phone = phone;
    this.email = email;
    this.role = role;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}

/**
 * 登录成功数据；对应服务端 {@code SessionVO}。
 *
 * <p>只包含访问令牌、令牌类型、有效期秒数与用户资料。服务端不提供刷新令牌，
 * 本模型也不包含续期信息。</p>
 */
export class AuthSessionData {
  /** HS256 JWT 访问令牌；敏感值，禁止写入日志。 */
  readonly accessToken: string;

  /** 令牌类型，服务端固定为 Bearer。 */
  readonly tokenType: string;

  /** 有效期，单位为秒。 */
  readonly expiresIn: number;

  /** 登录账号资料。 */
  readonly user: UserProfile;

  /**
   * 创建登录成功数据。
   *
   * @param accessToken 访问令牌
   * @param tokenType 令牌类型
   * @param expiresIn 有效期秒数
   * @param user 登录账号资料
   */
  constructor(accessToken: string, tokenType: string, expiresIn: number, user: UserProfile) {
    this.accessToken = accessToken;
    this.tokenType = tokenType;
    this.expiresIn = expiresIn;
    this.user = user;
  }
}

/**
 * 服务端统一错误体；对应 {@code ApiErrorVO}。
 */
export class ServerErrorPayload {
  /** 5 位错误码，例如 A0400、A0111、A0501、C0001。 */
  readonly code: string;

  /** 可直接展示的中文错误文案。 */
  readonly message: string;

  /** 本次请求的追踪 ID；缺失时为空串。 */
  readonly traceId: string;

  /** 字段级错误；键为服务端字段名，无字段错误时为空对象。 */
  readonly fieldErrors: Record<string, string>;

  /**
   * 创建服务端错误快照。
   *
   * @param code 5 位错误码
   * @param message 可展示的错误文案
   * @param traceId 追踪 ID
   * @param fieldErrors 字段级错误
   */
  constructor(code: string, message: string, traceId: string,
    fieldErrors: Record<string, string>) {
    this.code = code;
    this.message = message;
    this.traceId = traceId;
    this.fieldErrors = fieldErrors;
  }
}

/**
 * 失败来源分类。
 */
export enum AuthFailureKind {
  /** 请求发出前的本地校验失败。 */
  VALIDATION = 'validation',

  /** 未收到 HTTP 响应，例如断网、DNS 失败或连接超时。 */
  NETWORK = 'network',

  /** 收到非预期 HTTP 状态码，可能带有服务端错误体。 */
  HTTP = 'http',

  /** 收到预期状态码，但响应体缺失或不符合契约。 */
  PROTOCOL = 'protocol'
}

/**
 * 认证失败信息。
 *
 * <p>只包含可展示文案与协议字段，不包含密码、令牌或请求体。网络失败的
 * {@code cause} 仅保留底层错误码与系统错误描述，便于排查端口转发问题。</p>
 */
export class AuthError {
  /** 失败来源。 */
  readonly kind: AuthFailureKind;

  /** 可直接展示给用户的文案。 */
  readonly message: string;

  /** HTTP 状态码；未收到响应或本地校验时为 0。 */
  readonly httpStatus: number;

  /** 服务端 5 位错误码；无服务端错误体时为空串。 */
  readonly serverCode: string;

  /** 服务端追踪 ID；无服务端错误体时为空串。 */
  readonly traceId: string;

  /** 字段级错误；无字段错误时为空对象。 */
  readonly fieldErrors: Record<string, string>;

  /** 底层错误摘要；仅网络失败时可能非空，不含敏感信息。 */
  readonly cause: string;

  /**
   * 创建失败信息。
   *
   * @param kind 失败来源
   * @param message 可展示文案
   * @param httpStatus HTTP 状态码，无响应时为 0
   * @param serverCode 服务端错误码，无则为空串
   * @param traceId 服务端追踪 ID，无则为空串
   * @param fieldErrors 字段级错误
   * @param cause 底层错误摘要
   */
  constructor(kind: AuthFailureKind, message: string, httpStatus: number, serverCode: string,
    traceId: string, fieldErrors: Record<string, string>, cause: string) {
    this.kind = kind;
    this.message = message;
    this.httpStatus = httpStatus;
    this.serverCode = serverCode;
    this.traceId = traceId;
    this.fieldErrors = fieldErrors;
    this.cause = cause;
  }

  /**
   * 创建本地校验失败。
   *
   * @param message 可展示文案
   * @param fieldErrors 本地字段错误
   * @return 校验失败信息
   */
  static local_validation(message: string, fieldErrors: Record<string, string>): AuthError {
    return new AuthError(AuthFailureKind.VALIDATION, message, 0, '', '', fieldErrors, '');
  }

  /**
   * 创建网络失败。
   *
   * @param message 可展示文案
   * @param cause 底层错误摘要
   * @return 网络失败信息
   */
  static network_failure(message: string, cause: string): AuthError {
    return new AuthError(AuthFailureKind.NETWORK, message, 0, '', '', {}, cause);
  }

  /**
   * 创建 HTTP 失败；有服务端错误体时保留 code、message、traceId 与 fieldErrors。
   *
   * @param status HTTP 状态码
   * @param payload 服务端错误体，可能为 null
   * @return HTTP 失败信息
   */
  static http_failure(status: number, payload: ServerErrorPayload | null): AuthError {
    if (payload === null) {
      return new AuthError(AuthFailureKind.HTTP, `请求失败（HTTP ${status}）`, status, '', '', {}, '');
    }
    return new AuthError(AuthFailureKind.HTTP, payload.message, status, payload.code,
      payload.traceId, payload.fieldErrors, '');
  }

  /**
   * 创建协议解析失败。
   *
   * @param message 可展示文案
   * @return 协议失败信息
   */
  static protocol_failure(message: string): AuthError {
    return new AuthError(AuthFailureKind.PROTOCOL, message, 0, '', '', {}, '');
  }

  /**
   * 返回指定字段的本地或服务端校验文案。
   *
   * @param field 字段名，与服务端一致
   * @return 字段错误文案；该字段无错误时为 null
   */
  field_error(field: string): string | null {
    const value: string | undefined = this.fieldErrors[field];
    return value === undefined ? null : value;
  }

  /**
   * 是否为请求发出前的本地校验失败。
   *
   * @return 本地校验失败时为 true
   */
  is_validation(): boolean {
    return this.kind === AuthFailureKind.VALIDATION;
  }

  /**
   * 是否为未收到 HTTP 响应的网络失败。
   *
   * @return 网络失败时为 true
   */
  is_network(): boolean {
    return this.kind === AuthFailureKind.NETWORK;
  }

  /**
   * 是否为 401。
   *
   * <p>登录场景表示用户名或密码错误；受保护接口表示令牌缺失、失效或账号不可用。
   * 调用方必须按场景决定提示与后续动作。</p>
   *
   * @return HTTP 状态码为 401 时为 true
   */
  is_unauthorized(): boolean {
    return this.httpStatus === 401;
  }

  /**
   * 是否为用户名重复冲突。
   *
   * @return HTTP 409 且服务端错误码为 A0111 时为 true
   */
  is_username_conflict(): boolean {
    return this.httpStatus === 409 && this.serverCode === 'A0111';
  }

  /**
   * 是否为请求过于频繁。
   *
   * @return HTTP 状态码为 429 时为 true
   */
  is_rate_limited(): boolean {
    return this.httpStatus === 429;
  }

  /**
   * 是否为服务端依赖不可用。
   *
   * @return HTTP 状态码为 503 时为 true
   */
  is_service_unavailable(): boolean {
    return this.httpStatus === 503;
  }
}

/**
 * 认证请求结果。
 *
 * <p>成功时 {@code data} 非 null 且 {@code error} 为 null；失败时相反。
 * 调用方应先检查 {@code isSuccess}，再读取对应字段。</p>
 *
 * @param T 成功时携带的数据类型
 */
export class AuthResult<T> {
  /** 请求是否成功。 */
  readonly isSuccess: boolean;

  /** 成功数据；失败时为 null。 */
  readonly data: T | null;

  /** 失败信息；成功时为 null。 */
  readonly error: AuthError | null;

  private constructor(isSuccess: boolean, data: T | null, error: AuthError | null) {
    this.isSuccess = isSuccess;
    this.data = data;
    this.error = error;
  }

  /**
   * 创建成功结果。
   *
   * @param data 成功数据
   * @return 成功结果
   */
  static success<T>(data: T): AuthResult<T> {
    return new AuthResult<T>(true, data, null);
  }

  /**
   * 创建失败结果。
   *
   * @param error 失败信息
   * @return 失败结果
   */
  static failure<T>(error: AuthError): AuthResult<T> {
    return new AuthResult<T>(false, null, error);
  }
}