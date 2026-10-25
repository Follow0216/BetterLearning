import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import {
  AuthError,
  AuthResult,
  AuthSessionData,
  ServerErrorPayload,
  UserProfile,
  UpdateProfileInput
} from './AuthModels';
import {
  AuthFormErrors,
  validate_login_form,
  validate_register_form,
  validate_profile_update,
  validate_change_password
} from './AuthValidation';


/**
 * 调试默认地址。
 *
 * <p>仅用于本机开发：服务端通过 HDC 端口转发暴露到设备侧 127.0.0.1:18081，
 * 例如 {@code hdc rport tcp:18081 tcp:8080}。生产环境必须在构造 {@link AuthApi}
 * 时传入正式 HTTPS 地址，不要复用本常量。</p>
 */
export const DEFAULT_DEV_BASE_URL: string = 'http://127.0.0.1:18081';

/** 连接与读取超时，单位毫秒，两者相同。 */
const REQUEST_TIMEOUT_MILLIS: number = 15000;

/** 请求与响应的 JSON 媒体类型。 */
const JSON_CONTENT_TYPE: string = 'application/json';

/** 注册路径。 */
const REGISTER_PATH: string = '/api/v1/users';

/** 登录路径。 */
const LOGIN_PATH: string = '/api/v1/sessions';

/** 当前用户路径。 */
const CURRENT_USER_PATH: string = '/api/v1/users/me';

/** 当前用户改密路径。 */
const CHANGE_PASSWORD_PATH: string = '/api/v1/users/me/password';

/** 当前会话路径。 */
const CURRENT_SESSION_PATH: string = '/api/v1/sessions/current';

/** 注册与登录成功状态码。 */
const STATUS_CREATED: number = 201;

/** 查询当前用户成功状态码。 */
const STATUS_OK: number = 200;

/** 退出成功状态码，无响应体。 */
const STATUS_NO_CONTENT: number = 204;


/**
 * 注册请求体；只包含服务端接受的三个字段。
 */
interface RegisterPayload {
  username: string;
  password: string;
  confirmPassword: string;
}

/** 改密请求体；口令原样传递。 */
interface ChangePasswordPayload {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** 登录请求体。 */
interface LoginPayload {
  username: string;
  password: string;
}

/**
 * 原始 HTTP 响应；只保留状态码与文本响应体。
 */
class RawHttpResponse {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string) {
    this.status = status;
    this.body = body;
  }
}

/**
 * 单次请求结果：要么有响应，要么有网络错误，二者互斥。
 */
class RequestOutcome {
  readonly response: RawHttpResponse | null;
  readonly networkError: AuthError | null;

  private constructor(response: RawHttpResponse | null, networkError: AuthError | null) {
    this.response = response;
    this.networkError = networkError;
  }

  static received(response: RawHttpResponse): RequestOutcome {
    return new RequestOutcome(response, null);
  }

  static failed(networkError: AuthError): RequestOutcome {
    return new RequestOutcome(null, networkError);
  }
}

/**
 * 去除 baseUrl 尾部斜杠，避免拼接出双斜杠路径。
 *
 * @param baseUrl 调用方传入的根地址
 * @return 可直接与路径拼接的根地址
 */
function normalize_base_url(baseUrl: string): string {
  const trimmed: string = baseUrl.trim();
  if (trimmed.endsWith('/')) {
    return trimmed.substring(0, trimmed.length - 1);
  }
  return trimmed;
}

/**

 * 把任意 JSON 值收窄为普通对象。
 *
 * @param value JSON 解析结果
 * @return 非数组的普通对象；其他值为 null
 */
function as_object_record(value: Object | null): Record<string, Object> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, Object>;
}

/**
 * 解析 JSON 文本为普通对象。
 *
 * @param body 响应体
 * @return 解析后的对象；空文本、非法 JSON 或非对象时为 null
 */
function parse_json_object(body: string): Record<string, Object> | null {
  if (body.length === 0) {
    return null;
  }
  try {
    const parsed: Object = JSON.parse(body) as Object;
    return as_object_record(parsed);
  } catch (error) {
    return null;
  }
}

/**
 * 读取字符串字段。
 *
 * @param record 源对象
 * @param key 字段名
 * @return 字符串值；字段缺失或类型不符时为 null
 */
function read_string(record: Record<string, Object>, key: string): string | null {
  const value: Object | undefined = record[key];
  if (typeof value === 'string') {
    return value as string;
  }
  return null;
}

/**
 * 可空字符串字段的读取结果：合法值、JSON null 或协议错误。
 */
class NullableStringField {
  readonly isValid: boolean;
  readonly value: string | null;

  constructor(isValid: boolean, value: string | null) {
    this.isValid = isValid;
    this.value = value;
  }

  static invalid(): NullableStringField {
    return new NullableStringField(false, null);
  }
}

/**
 * 读取可空字符串字段。
 *
 * <p>只接受 JSON null 或字符串；字段缺失、数字、对象、数组等一律视为协议错误，
 * 不静默转换为 null。</p>
 *
 * @param record 源对象
 * @param key 字段名
 * @return 读取结果，{@code isValid} 为 false 表示类型不符合契约
 */
function read_nullable_string(record: Record<string, Object>, key: string): NullableStringField {
  const value: Object | null | undefined = record[key];
  if (value === undefined) {
    return NullableStringField.invalid();
  }
  if (value === null) {
    return new NullableStringField(true, null);
  }
  if (typeof value === 'string') {
    return new NullableStringField(true, value as string);
  }
  return NullableStringField.invalid();
}

/**
 * 读取数字字段。
 *
 * @param record 源对象
 * @param key 字段名
 * @return 数字值；字段缺失或类型不符时为 null
 */
function read_number(record: Record<string, Object>, key: string): number | null {
  const value: Object | undefined = record[key];
  if (typeof value === 'number') {
    return value as number;
  }
  return null;
}

/**
 * 解析字段级错误映射，忽略非字符串值。
 *
 * @param value 服务端 fieldErrors 字段
 * @return 字段名到错误文案的映射；无有效内容时为空对象
 */
function parse_field_errors(value: Object | null): Record<string, string> {
  const result: Record<string, string> = {};
  const record: Record<string, Object> | null = as_object_record(value);
  if (record === null) {
    return result;
  }
  const keys: string[] = Object.keys(record);
  for (let index: number = 0; index < keys.length; index++) {
    const key: string = keys[index];
    const item: Object | undefined = record[key];
    if (typeof item === 'string') {
      result[key] = item as string;
    }
  }
  return result;
}

/**
 * 解析用户资料，字段类型不符时返回 null。
 *
 * @param value 服务端 user 值
 * @return 用户资料；契约不完整时为 null
 */
function parse_user(value: Object | null): UserProfile | null {
  const record: Record<string, Object> | null = as_object_record(value);
  if (record === null) {
    return null;
  }
  const accountId: string | null = read_string(record, 'accountId');
  const username: string | null = read_string(record, 'username');
  const role: string | null = read_string(record, 'role');
  const createdAt: string | null = read_string(record, 'createdAt');
  const updatedAt: string | null = read_string(record, 'updatedAt');
  const phone: NullableStringField = read_nullable_string(record, 'phone');
  const email: NullableStringField = read_nullable_string(record, 'email');
  if (accountId === null || username === null || role === null || createdAt === null ||
    updatedAt === null || !phone.isValid || !email.isValid) {
    return null;
  }
  return new UserProfile(accountId, username, phone.value, email.value, role, createdAt,
    updatedAt);
}

/**
 * 解析用户资料响应体。
 *
 * @param body 响应文本
 * @return 用户资料；响应体不符合契约时为 null
 */
function parse_user_body(body: string): UserProfile | null {
  return parse_user(parse_json_object(body));
}

/**
 * 解析登录响应体。
 *
 * @param body 响应文本
 * @return 登录数据；响应体不符合契约时为 null
 */
function parse_session_body(body: string): AuthSessionData | null {
  const record: Record<string, Object> | null = parse_json_object(body);
  if (record === null) {
    return null;
  }
  const accessToken: string | null = read_string(record, 'accessToken');
  const tokenType: string | null = read_string(record, 'tokenType');
  const expiresIn: number | null = read_number(record, 'expiresIn');
  const user: UserProfile | null = parse_user(record['user']);
  if (accessToken === null || accessToken.length === 0 || tokenType === null ||
    expiresIn === null || expiresIn <= 0 || user === null) {
    return null;
  }
  return new AuthSessionData(accessToken, tokenType, expiresIn, user);
}

/**
 * 解析服务端错误体；code 与 message 缺失时视为无法解析。
 *
 * @param body 响应文本
 * @return 服务端错误快照；不符合契约时为 null
 */
function parse_error_body(body: string): ServerErrorPayload | null {
  const record: Record<string, Object> | null = parse_json_object(body);
  if (record === null) {
    return null;
  }
  const code: string | null = read_string(record, 'code');
  const message: string | null = read_string(record, 'message');
  if (code === null || message === null) {
    return null;
  }
  const traceId: string | null = read_string(record, 'traceId');
  return new ServerErrorPayload(code, message, traceId === null ? '' : traceId,
    parse_field_errors(record['fieldErrors']));
}

/**
 * 把表单校验结果转换为统一失败信息。
 *
 * @param formErrors 本地字段校验结果
 * @param message 整体提示文案
 * @return 校验失败信息；无错误时为 null
 */
function to_validation_error(formErrors: AuthFormErrors, message: string): AuthError | null {
  if (!formErrors.has_error()) {
    return null;
  }
  const fieldErrors: Record<string, string> = {};
  if (formErrors.username.length > 0) {
    fieldErrors['username'] = formErrors.username;
  }
  if (formErrors.password.length > 0) {
    fieldErrors['password'] = formErrors.password;
  }
  if (formErrors.confirmPassword.length > 0) {
    fieldErrors['confirmPassword'] = formErrors.confirmPassword;
  }
  return AuthError.local_validation(message, fieldErrors);
}

/**
 * 认证接口客户端。
 *
 * <p>实例只保存 baseUrl，不持有令牌，也不读写页面状态。每个请求创建独立的
 * {@code HttpRequest} 并在 finally 中销毁；连接与读取超时均为 15 秒。成功与失败
 * 都以 {@link AuthResult} 返回，不向调用方抛出异常。请求日志不输出密码、令牌或
 * 请求体。</p>
 */
export class AuthApi {
  /** 服务端根地址，不含尾部斜杠。 */
  private readonly baseUrl: string;

  /**
   * 创建认证客户端。
   *
   * @param baseUrl 服务端根地址，例如 {@link DEFAULT_DEV_BASE_URL}
   */
  constructor(baseUrl: string) {
    this.baseUrl = normalize_base_url(baseUrl);
  }

  /**
   * 使用调试默认地址创建客户端。
   *
   * @return 指向 127.0.0.1:18081 的客户端
   */
  static create_default(): AuthApi {
    return new AuthApi(DEFAULT_DEV_BASE_URL);
  }

  /**
   * 注册用户。
   *
   * <p>用户名只做 trim，由服务端完成去空白、转小写与格式校验；密码原样传递，
   * 不做 trim。本地只校验非空、两次密码一致以及服务端的密码长度与字节规则。</p>
   *
   * @param username 用户名
   * @param password 密码
   * @param confirmPassword 确认密码
   * @return 成功时携带 201 响应中的用户资料
   */
  async register(username: string, password: string, confirmPassword: string): Promise<AuthResult<UserProfile>> {
    const normalizedUsername: string = username.trim();
    const formErrors: AuthFormErrors =
      validate_register_form(normalizedUsername, password, confirmPassword);
    const validationError: AuthError | null =
      to_validation_error(formErrors, '请检查注册信息');
    if (validationError !== null) {
      return AuthResult.failure<UserProfile>(validationError);
    }
    const payload: RegisterPayload = {
      username: normalizedUsername,
      password: password,
      confirmPassword: confirmPassword
    };
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.POST, REGISTER_PATH,
      '', JSON.stringify(payload));
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<UserProfile>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_CREATED) {
      return AuthResult.failure<UserProfile>(this.http_error(response));
    }
    const user: UserProfile | null = parse_user_body(response.body);
    if (user === null) {
      return AuthResult.failure<UserProfile>(
        AuthError.protocol_failure('注册响应格式不符合预期'));
    }
    return AuthResult.success<UserProfile>(user);
  }

  /**
   * 登录。
   *
   * <p>用户名只做 trim，密码原样传递且不 trim；本地只检查两者非空。HTTP 401
   * 表示用户名或密码错误，由调用方按登录场景提示。</p>
   *
   * @param username 用户名
   * @param password 密码
   * @return 成功时携带 201 响应中的令牌与用户资料
   */
  async login(username: string, password: string): Promise<AuthResult<AuthSessionData>> {
    const normalizedUsername: string = username.trim();
    const formErrors: AuthFormErrors = validate_login_form(normalizedUsername, password);
    const validationError: AuthError | null =
      to_validation_error(formErrors, '请输入用户名和密码');
    if (validationError !== null) {
      return AuthResult.failure<AuthSessionData>(validationError);
    }
    const payload: LoginPayload = {
      username: normalizedUsername,
      password: password
    };
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.POST, LOGIN_PATH,
      '', JSON.stringify(payload));
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<AuthSessionData>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_CREATED) {
      return AuthResult.failure<AuthSessionData>(this.http_error(response));
    }
    const session: AuthSessionData | null = parse_session_body(response.body);
    if (session === null) {
      return AuthResult.failure<AuthSessionData>(
        AuthError.protocol_failure('登录响应格式不符合预期'));
    }
    return AuthResult.success<AuthSessionData>(session);
  }

  /**
   * 查询当前用户。请求只携带 Bearer 令牌。
   *
   * <p>HTTP 401 表示令牌缺失或失效，不代表登录凭据错误。</p>
   *
   * @param token 访问令牌
   * @return 成功时携带 200 响应中的用户资料
   */
  async getCurrentUser(token: string): Promise<AuthResult<UserProfile>> {
    if (token.length === 0) {
      return AuthResult.failure<UserProfile>(
        AuthError.local_validation('登录状态缺失，请重新登录', {}));
    }
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.GET,
      CURRENT_USER_PATH, token, '');
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<UserProfile>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_OK) {
      return AuthResult.failure<UserProfile>(this.http_error(response));
    }
    const user: UserProfile | null = parse_user_body(response.body);
    if (user === null) {
      return AuthResult.failure<UserProfile>(
        AuthError.protocol_failure('用户资料响应格式不符合预期'));
    }
    return AuthResult.success<UserProfile>(user);
  }

  /** 更新联系方式；缺省保持，null 解绑，不自动更新全局会话或重试。 */
  async updateProfile(token: string, input: UpdateProfileInput): Promise<AuthResult<UserProfile>> {
    if (token.length === 0) {
      return AuthResult.failure<UserProfile>(
        AuthError.local_validation('登录状态缺失，请重新登录', {}));
    }
    const errors: Record<string, string> = validate_profile_update(input);
    if (Object.keys(errors).length > 0) {
      return AuthResult.failure<UserProfile>(AuthError.local_validation('请检查联系方式', errors));
    }
    const payload: UpdateProfileInput = new UpdateProfileInput();
    if (input.phone !== undefined) {
      payload.phone = input.phone;
    }
    if (input.email !== undefined) {
      payload.email = input.email;
    }
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.PATCH,
      CURRENT_USER_PATH, token, JSON.stringify(payload));
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<UserProfile>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_OK) {
      return AuthResult.failure<UserProfile>(this.http_error(response));
    }
    const user: UserProfile | null = parse_user_body(response.body);
    if (user === null) {
      return AuthResult.failure<UserProfile>(AuthError.protocol_failure('用户资料响应格式不符合预期'));
    }
    return AuthResult.success<UserProfile>(user);
  }

  /**
   * 改密成功 204 表示所有旧令牌失效；不签发新 token，不自动改全局会话。
   * 调用方须按请求 token 处理退出，网络失败不能据此判断事务是否已提交。
   */
  async changePassword(token: string, oldPassword: string, newPassword: string,
    confirmPassword: string): Promise<AuthResult<boolean>> {
    if (token.length === 0) {
      return AuthResult.failure<boolean>(
        AuthError.local_validation('登录状态缺失，请重新登录', {}));
    }
    const errors: Record<string, string> =
      validate_change_password(oldPassword, newPassword, confirmPassword);
    if (Object.keys(errors).length > 0) {
      return AuthResult.failure<boolean>(AuthError.local_validation('请检查密码信息', errors));
    }
    const payload: ChangePasswordPayload = {
      oldPassword: oldPassword,
      newPassword: newPassword,
      confirmPassword: confirmPassword
    };
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.PATCH,
      CHANGE_PASSWORD_PATH, token, JSON.stringify(payload));
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<boolean>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_NO_CONTENT) {
      return AuthResult.failure<boolean>(this.http_error(response));
    }
    return AuthResult.success<boolean>(true);
  }

  /**
   * 退出当前会话。请求只携带 Bearer 令牌，204 响应不解析 JSON。
   *
   * @param token 访问令牌
   * @return 成功时携带 true；204 表示服务端已删除当前会话
   */
  async logout(token: string): Promise<AuthResult<boolean>> {
    if (token.length === 0) {
      return AuthResult.failure<boolean>(
        AuthError.local_validation('登录状态缺失，请重新登录', {}));
    }
    const outcome: RequestOutcome = await this.send_request(http.RequestMethod.DELETE,
      CURRENT_SESSION_PATH, token, '');
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<boolean>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== STATUS_NO_CONTENT) {
      return AuthResult.failure<boolean>(this.http_error(response));
    }
    return AuthResult.success<boolean>(true);
  }

  /** M2 单次完整 multipart 保存；保留原 manifest 字节，不自动重试。 */
  async request_multipart(path: string, token: string, parts: http.MultiFormData[],
    readTimeoutMillis: number = 120000, failureMessage: string = '作答保存连接失败，请保留原包重试'): Promise<AuthResult<string>> {
    if (token.length === 0) { return AuthResult.failure<string>(AuthError.local_validation('请重新登录', {})); }
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(this.baseUrl + path, {
        method: http.RequestMethod.POST,
        header: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data' },
        connectTimeout: REQUEST_TIMEOUT_MILLIS, readTimeout: readTimeoutMillis,
        expectDataType: http.HttpDataType.STRING, maxLimit: 16777216, usingCache: false, multiFormDataList: parts
      });
      const body: string = typeof response.result === 'string' ? response.result : '';
      if (response.responseCode !== STATUS_OK) {
        return AuthResult.failure<string>(this.http_error(new RawHttpResponse(response.responseCode, body)));
      }
      return AuthResult.success<string>(body);
    } catch (error) {
      return AuthResult.failure<string>(AuthError.network_failure(failureMessage, ''));
    } finally { request.destroy(); }
  }

  /** 带 Bearer 的二进制读取；PNG 校验由领域文件缓存层完成。 */
  async request_binary(path: string, token: string): Promise<AuthResult<ArrayBuffer>> {
    if (token.length === 0) { return AuthResult.failure<ArrayBuffer>(AuthError.local_validation('请重新登录', {})); }
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(this.baseUrl + path, {
        method: http.RequestMethod.GET, header: { 'Authorization': 'Bearer ' + token },
        connectTimeout: REQUEST_TIMEOUT_MILLIS, readTimeout: 60000,
        expectDataType: http.HttpDataType.ARRAY_BUFFER, maxLimit: 16777216, usingCache: false
      });
      if (!(response.result instanceof ArrayBuffer)) {
        return AuthResult.failure<ArrayBuffer>(AuthError.protocol_failure('文件响应格式无效'));
      }
      if (response.responseCode !== STATUS_OK) {
        let body: string = '';
        try { body = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(response.result)); } catch (error) {}
        return AuthResult.failure<ArrayBuffer>(this.http_error(new RawHttpResponse(response.responseCode, body)));
      }
      return AuthResult.success<ArrayBuffer>(response.result);
    } catch (error) {
      return AuthResult.failure<ArrayBuffer>(AuthError.network_failure('解答文件加载失败，请重试', ''));
    } finally { request.destroy(); }
  }

  /**
   * 共享的受保护资源请求。学习 API 复用认证栈的超时、Bearer、错误解析与资源释放。
   * 成功时返回响应文本，由领域 API 按各自 DTO 解析；不自动刷新、重试或修改会话。
   */
  async request_resource(method: http.RequestMethod, path: string, token: string,
    body: string = '', expectedStatus: number = STATUS_OK,
    readTimeoutMillis: number = REQUEST_TIMEOUT_MILLIS): Promise<AuthResult<string>> {
    if (token.length === 0) {
      return AuthResult.failure<string>(
        AuthError.local_validation('登录状态缺失，请重新登录', {}));
    }
    const outcome: RequestOutcome = await this.send_request(method, path, token, body, readTimeoutMillis);
    const transportError: AuthError | null = this.transport_error(outcome);
    if (transportError !== null) {
      return AuthResult.failure<string>(transportError);
    }
    const response: RawHttpResponse = outcome.response as RawHttpResponse;
    if (response.status !== expectedStatus) {
      return AuthResult.failure<string>(this.http_error(response));
    }
    return AuthResult.success<string>(response.body);
  }

  /**

   * 发送一次请求。令牌只以 Bearer 形式放入 Authorization 头。
   *
   * @param method 请求方法
   * @param path 以斜杠开头的接口路径
   * @param token 访问令牌；空串表示匿名请求
   * @param body JSON 请求体；空串表示无请求体
   * @return 响应或网络错误
   */
  private async send_request(method: http.RequestMethod, path: string, token: string,
    body: string, readTimeoutMillis: number = REQUEST_TIMEOUT_MILLIS): Promise<RequestOutcome> {
    const header: Record<string, string> = {
      'Content-Type': JSON_CONTENT_TYPE
    };
    if (token.length > 0) {
      header['Authorization'] = 'Bearer ' + token;
    }
    const options: http.HttpRequestOptions = {
      method: method,
      header: header,
      connectTimeout: REQUEST_TIMEOUT_MILLIS,
      readTimeout: readTimeoutMillis,
      expectDataType: http.HttpDataType.STRING,
      maxLimit: 16777216,
      usingCache: false
    };
    if (body.length > 0) {
      options.extraData = body;
    }
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(this.baseUrl + path, options);
      const result: string | Object | ArrayBuffer = response.result;
      const responseBody: string = typeof result === 'string' ? result : '';
      return RequestOutcome.received(new RawHttpResponse(response.responseCode, responseBody));
    } catch (error) {
      const businessError: BusinessError = error as BusinessError;
      const rawCode: number = businessError.code;
      const codeText: string = typeof rawCode === 'number' ? rawCode.toString() : 'unknown';
      const rawMessage: string = businessError.message;
      const messageText: string = typeof rawMessage === 'string' ? rawMessage : '';
      const cause: string = `code=${codeText}; message=${messageText}`;
      return RequestOutcome.failed(
        AuthError.network_failure('网络连接失败，请检查网络后重试', cause));
    } finally {
      request.destroy();
    }
  }

  /**
   * 提取网络错误；有响应时返回 null。
   *
   * @param outcome 请求结果
   * @return 网络错误或 null
   */
  private transport_error(outcome: RequestOutcome): AuthError | null {
    if (outcome.networkError !== null) {
      return outcome.networkError;
    }
    if (outcome.response === null) {
      return AuthError.protocol_failure('未收到服务端响应');
    }
    return null;
  }

  /**
   * 把非预期 HTTP 状态转换为错误，保留服务端错误体字段。
   *
   * @param response 原始响应
   * @return HTTP 错误
   */
  private http_error(response: RawHttpResponse): AuthError {
    return AuthError.http_failure(response.status, parse_error_body(response.body));
  }
}