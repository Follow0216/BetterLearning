/**
 * 认证接口契约：与 server 的 UserVO / SessionVO / ApiErrorVO 一一对应。
 *
 * 仅包含纯类型与解析函数，不含任何服务端密钥或请求逻辑，可同时被 Route Handler
 * 与客户端组件引用（客户端只使用类型）。
 */

export type AuthRole = "USER" | "ADMIN";

export interface AuthUserProfile {
  accountId: string;
  username: string;
  phone: string | null;
  email: string | null;
  role: AuthRole;
  createdAt: string;
  updatedAt: string;
}

export interface AuthSessionPayload {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  user: AuthUserProfile;
}

export interface AuthErrorPayload {
  code: string;
  message: string;
  traceId: string;
  fieldErrors?: Record<string, string>;
}

export class AuthContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthContractError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new AuthContractError(`响应字段 ${key} 缺失或类型错误`);
  }
  return value;
}

function readNullableString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== "string") {
    throw new AuthContractError(`响应字段 ${key} 类型错误`);
  }
  return value;
}

function readTimestamp(source: Record<string, unknown>, key: string): string {
  const value = readString(source, key);
  if (Number.isNaN(Date.parse(value))) {
    throw new AuthContractError(`响应字段 ${key} 不是合法时间`);
  }
  return value;
}

function readFieldErrors(value: unknown): Record<string, string> | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!isRecord(value)) {
    throw new AuthContractError("响应字段 fieldErrors 类型错误");
  }
  const result: Record<string, string> = {};
  for (const [key, message] of Object.entries(value)) {
    if (typeof message !== "string") {
      throw new AuthContractError("响应字段 fieldErrors 类型错误");
    }
    result[key] = message;
  }
  return result;
}

export function parseUserProfile(value: unknown): AuthUserProfile {
  if (!isRecord(value)) {
    throw new AuthContractError("用户信息格式错误");
  }
  const accountId = readString(value, "accountId");
  if (!/^\d+$/.test(accountId)) {
    throw new AuthContractError("用户信息 accountId 格式错误");
  }
  const role = readString(value, "role");
  if (role !== "USER" && role !== "ADMIN") {
    throw new AuthContractError("用户信息 role 格式错误");
  }
  return {
    accountId,
    username: readString(value, "username"),
    phone: readNullableString(value, "phone"),
    email: readNullableString(value, "email"),
    role,
    createdAt: readTimestamp(value, "createdAt"),
    updatedAt: readTimestamp(value, "updatedAt"),
  };
}

export function parseSessionPayload(value: unknown): AuthSessionPayload {
  if (!isRecord(value)) {
    throw new AuthContractError("登录响应格式错误");
  }
  const accessToken = readString(value, "accessToken");
  const tokenType = readString(value, "tokenType");
  if (tokenType !== "Bearer") {
    throw new AuthContractError("登录响应 tokenType 不是 Bearer");
  }
  const expiresIn = value.expiresIn;
  if (typeof expiresIn !== "number" || !Number.isFinite(expiresIn) || expiresIn <= 0) {
    throw new AuthContractError("登录响应 expiresIn 格式错误");
  }
  return {
    accessToken,
    tokenType: "Bearer",
    expiresIn: Math.floor(expiresIn),
    user: parseUserProfile(value.user),
  };
}

export function parseErrorPayload(value: unknown): AuthErrorPayload {
  if (!isRecord(value)) {
    throw new AuthContractError("错误响应格式错误");
  }
  const payload: AuthErrorPayload = {
    code: readString(value, "code"),
    message: readString(value, "message"),
    traceId: readString(value, "traceId"),
  };
  const fieldErrors = readFieldErrors(value.fieldErrors);
  if (fieldErrors) {
    payload.fieldErrors = fieldErrors;
  }
  return payload;
}

export function tryParseErrorPayload(value: unknown): AuthErrorPayload | null {
  try {
    return parseErrorPayload(value);
  } catch {
    return null;
  }
}
