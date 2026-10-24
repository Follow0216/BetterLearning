import { AuthSessionData, UserProfile } from './AuthModels';

/**
 * 认证会话的进程内状态。
 *
 * <p>只保存访问令牌、用户资料与按 {@code expiresIn} 计算出的到期时刻，不做任何
 * 持久化，不注册定时器，也不自动续期或刷新令牌。进程退出后状态自然丢失；
 * 令牌到期后读取返回 null，是否清除由调用方决定。</p>
 *
 * <p>本类不负责登录、注册或修改密码流程；这些流程由 {@code AuthApi} 完成，
 * 成功后由调用方调用 {@link save_session}。</p>
 */
export class AuthSession {
  /** 访问令牌；未登录或已清除时为空串。 */
  private accessToken: string = '';

  /** 当前用户资料；未登录或已清除时为 null。 */
  private user: UserProfile | null = null;

  /** 到期时刻，Unix 毫秒；0 表示无会话。 */
  private expiresAtMillis: number = 0;

  /**
   * 保存登录成功数据，并按 expiresIn 计算到期时刻。
   *
   * @param session 登录成功数据
   * @param nowMillis 当前时刻，Unix 毫秒；默认取系统时间，便于测试注入
   */
  save_session(session: AuthSessionData, nowMillis: number = Date.now()): void {
    this.accessToken = session.accessToken;
    this.user = session.user;
    const lifetimeMillis: number = session.expiresIn > 0 ? session.expiresIn * 1000 : 0;
    this.expiresAtMillis = nowMillis + lifetimeMillis;
  }

  /**
   * 读取访问令牌。
   *
   * @param nowMillis 当前时刻，Unix 毫秒
   * @return 未过期时为令牌；无会话或已过期时为 null
   */
  read_access_token(nowMillis: number = Date.now()): string | null {
    if (!this.has_valid_session(nowMillis)) {
      return null;
    }
    return this.accessToken;
  }

  /**
   * 读取当前用户资料。
   *
   * @param nowMillis 当前时刻，Unix 毫秒
   * @return 未过期时为用户资料；无会话或已过期时为 null
   */
  read_user(nowMillis: number = Date.now()): UserProfile | null {
    if (!this.has_valid_session(nowMillis)) {
      return null;
    }
    return this.user;
  }

  /**
   * 判断当前是否有未过期的会话。
   *
   * @param nowMillis 当前时刻，Unix 毫秒
   * @return 令牌、用户均存在且未到期时为 true
   */
  has_valid_session(nowMillis: number = Date.now()): boolean {
    return this.accessToken.length > 0 && this.user !== null && nowMillis < this.expiresAtMillis;
  }

  /**
   * 返回到期时刻。
   *
   * @return Unix 毫秒；0 表示无会话
   */
  expires_at_millis(): number {
    return this.expiresAtMillis;
  }

  /** 判断异步请求是否仍属于当前会话；即使令牌已本地到期也可识别其归属。 */
  matches_access_token(token: string): boolean {
    return token.length > 0 && this.accessToken === token;
  }

  /** 只清除发出请求时的会话，防止旧请求的迟到 401 清除新登录。 */
  clear_session_if_token(token: string): boolean {
    if (!this.matches_access_token(token)) {
      return false;
    }
    this.clear_session();
    return true;
  }

  /**
   * 清除内存中的令牌、用户与到期时刻。
   */
  clear_session(): void {
    this.accessToken = '';
    this.user = null;
    this.expiresAtMillis = 0;
  }
}

/** 手机与平板页面共用的进程内会话实例。 */
export const authSession: AuthSession = new AuthSession();