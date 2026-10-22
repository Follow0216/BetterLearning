import { mediaquery } from '@kit.ArkUI';
import { AuthApi } from './AuthApi';
import {
  AuthError,
  AuthFailureKind,
  AuthResult,
  AuthSessionData,
  UserProfile
} from './AuthModels';
import { authSession } from './AuthSession';
import {
  AUTH_COLOR_BACKGROUND,
  AUTH_COLOR_BORDER,
  AUTH_COLOR_BRAND_DEEP,
  AUTH_COLOR_DANGER,
  AUTH_COLOR_DANGER_SOFT,
  AUTH_COLOR_MUTED,
  AUTH_COLOR_PRIMARY,
  AUTH_COLOR_PRIMARY_SOFT,
  AUTH_COLOR_TEXT,
  AUTH_COLOR_WHITE,
  AUTH_CONTROL_HEIGHT,
  AUTH_FORM_MAX_WIDTH,
  AUTH_RADIUS,
  AUTH_SHORT_QUERY,
  AUTH_SPACE,
  AUTH_WIDE_QUERY
} from './AuthTheme';
import {
  AuthFormErrors,
  validate_login_form,
  validate_register_form
} from './AuthValidation';

/** 表单模式。 */
enum AuthMode {
  LOGIN = 'login',
  REGISTER = 'register'
}

/** 页面阶段；表单之外只用于登录后的资料检查与展示。 */
enum AuthStage {
  FORM = 'form',
  CHECKING = 'checking',
  PROFILE = 'profile',
  RETRY = 'retry'
}

/** 输入字段标识，用于统一分发变更事件。 */
enum AuthField {
  USERNAME = 'username',
  PASSWORD = 'password',
  CONFIRM_PASSWORD = 'confirmPassword'
}

/**
 * 单个表单输入项。
 *
 * <p>独立组件而非带参 @Builder：@Builder 按值传参不会随状态刷新，输入回显、
 * 字段错误与预填值都会失效。这里用 @Link 双向绑定值、@Prop 同步错误与禁用态。</p>
 */
@Component
struct AuthInputField {
  label: string = '';
  inputId: string = '';
  placeholder: string = '';
  isPassword: boolean = false;
  @Link value: string;
  @Prop errorText: string = '';
  @Prop fieldEnabled: boolean = true;
  onValueChange: (next: string) => void = () => {
  };

  build() {
    Column() {
      Text(this.label)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor(AUTH_COLOR_TEXT)
        .margin({ bottom: 8 })
      TextInput({ text: this.value, placeholder: this.placeholder })
        .id(this.inputId)
        .type(this.isPassword ? InputType.Password : InputType.Normal)
        .showPasswordIcon(this.isPassword)
        .height(AUTH_CONTROL_HEIGHT)
        .width('100%')
        .borderRadius(AUTH_RADIUS)
        .backgroundColor(AUTH_COLOR_BACKGROUND)
        .border({ width: 1, color: AUTH_COLOR_BORDER })
        .padding({ left: 16, right: 16 })
        .fontSize(16)
        .fontColor(AUTH_COLOR_TEXT)
        .placeholderColor(AUTH_COLOR_MUTED)
        .caretColor(AUTH_COLOR_PRIMARY)
        .enabled(this.fieldEnabled)
        .accessibilityText(this.label + '输入框')
        .onChange((changed: string) => {
          this.value = changed;
          this.onValueChange(changed);
        })
      if (this.errorText.length > 0) {
        Text(this.errorText)
          .fontSize(12)
          .fontColor(AUTH_COLOR_DANGER)
          .margin({ top: 6 })
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ top: 16 })
  }
}

/**
 * 手机与平板共用的登录 / 最小注册页面。
 *
 * <p>窄窗口使用单列可滚动布局，窗口宽度达到 840vp 后切换为左右两栏；断点来自
 * 实际窗口宽度，不依赖设备型号。页面只调用 {@link AuthApi} 与 {@link authSession}，
 * 不持久化令牌，不自动登录，也不在日志中输出密码或令牌。</p>
 */
@Component
export struct AuthScreen {
  @State mode: AuthMode = AuthMode.LOGIN;
  @State stage: AuthStage = AuthStage.FORM;
  @State username: string = '';
  @State password: string = '';
  @State confirmPassword: string = '';
  @State usernameError: string = '';
  @State passwordError: string = '';
  @State confirmPasswordError: string = '';
  @State formError: string = '';
  @State formNotice: string = '';
  @State isSubmitting: boolean = false;
  @State isWide: boolean = false;
  @State isShort: boolean = false;
  @State profile: UserProfile | null = null;
  @State profileError: string = '';
  @State profileLoading: boolean = false;
  @State isLoggingOut: boolean = false;

  private api: AuthApi = AuthApi.create_default();
  private wideQuery: mediaquery.MediaQueryListener | null = null;
  private shortQuery: mediaquery.MediaQueryListener | null = null;

  aboutToAppear(): void {
    this.wideQuery = this.getUIContext().getMediaQuery().matchMediaSync(AUTH_WIDE_QUERY);
    this.isWide = this.wideQuery.matches;
    this.wideQuery.on('change', (result: mediaquery.MediaQueryResult) => {
      this.isWide = result.matches;
    });
    this.shortQuery = this.getUIContext().getMediaQuery().matchMediaSync(AUTH_SHORT_QUERY);
    this.isShort = this.shortQuery.matches;
    this.shortQuery.on('change', (result: mediaquery.MediaQueryResult) => {
      this.isShort = result.matches;
    });
  }

  aboutToDisappear(): void {
    if (this.wideQuery !== null) {
      this.wideQuery.off('change');
      this.wideQuery = null;
    }
    if (this.shortQuery !== null) {
      this.shortQuery.off('change');
      this.shortQuery = null;
    }
  }

  build() {
    Column() {
      if (this.stage === AuthStage.FORM) {
        if (this.isWide) {
          this.wide_form_layout()
        } else {
          this.narrow_form_layout()
        }
      } else {
        this.session_layout()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AUTH_COLOR_BACKGROUND)
  }

  /**
   * 窄窗口单列布局，内容整体可滚动，键盘弹出时可继续滚动到输入框。
   */
  @Builder
  narrow_form_layout() {
    Scroll() {
      Column() {
        this.brand_mark()
        Image($r('app.media.login_illustration'))
          .width('100%')
          .height(this.illustration_height())
          .objectFit(ImageFit.Contain)
          .margin({ top: 12, bottom: 4 })
        this.form_panel()
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: AUTH_SPACE, right: AUTH_SPACE, top: 20, bottom: 40 })
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
    .edgeEffect(EdgeEffect.Spring)
  }

  /**
   * 宽窗口左右两栏：左侧浅蓝品牌区，右侧白色表单区，表单最大宽度 420vp。
   */
  @Builder
  wide_form_layout() {
    Row() {
      Column() {
        this.brand_mark()
        Image($r('app.media.login_illustration'))
          .width('82%')
          .height(this.isShort ? 160 : 200)
          .objectFit(ImageFit.Contain)
          .margin({ top: 24, bottom: 24 })
        Text('拍照提题 · 手写演算 · AI 解答')
          .fontSize(16)
          .fontColor(AUTH_COLOR_MUTED)
          .textAlign(TextAlign.Center)
      }
      .layoutWeight(1)
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)
      .padding({ left: 48, right: 48 })
      .backgroundColor(AUTH_COLOR_BACKGROUND)

      Scroll() {
        Column() {
          Column() {
            this.form_panel()
          }
          .width('100%')
          .constraintSize({ maxWidth: AUTH_FORM_MAX_WIDTH })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Center)
        .padding({ left: 48, right: 48, top: 32, bottom: 32 })
      }
      .layoutWeight(1)
      .height('100%')
      .backgroundColor(AUTH_COLOR_WHITE)
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%')
    .height('100%')
  }

  /**
   * 品牌字标：全尺寸双色，水平居中。
   */
  @Builder
  brand_mark() {
    Text() {
      Span('Better')
        .fontColor(AUTH_COLOR_BRAND_DEEP)
      Span('Learning')
        .fontColor(AUTH_COLOR_PRIMARY)
    }
    .width('100%')
    .fontSize(this.isWide ? 40 : 22)
    .fontWeight(FontWeight.Bold)
    .letterSpacing(0)
    .maxLines(1)
    .minFontSize(this.isWide ? 28 : 22)
    .maxFontSize(this.isWide ? 40 : 22)
    .heightAdaptivePolicy(TextHeightAdaptivePolicy.MAX_LINES_FIRST)
    .textAlign(TextAlign.Center)
    .accessibilityText('BetterLearning')
  }

  /**
   * 标题、字段、按钮与模式切换。
   */
  @Builder
  form_panel() {
    Column() {
      Text(this.mode === AuthMode.LOGIN ? '欢迎回来' : '创建账号')
        .fontSize(32)
        .fontWeight(FontWeight.Bold)
        .fontColor(AUTH_COLOR_TEXT)
        .margin({ top: 8 })
      if (this.mode === AuthMode.LOGIN) {
        Text('登录你的学习账号')
          .fontSize(16)
          .fontColor(AUTH_COLOR_MUTED)
          .margin({ top: 8 })
      }
      if (this.formNotice.length > 0) {
        Text(this.formNotice)
          .fontSize(13)
          .fontColor(AUTH_COLOR_PRIMARY)
          .width('100%')
          .padding(12)
          .backgroundColor(AUTH_COLOR_PRIMARY_SOFT)
          .borderRadius(12)
          .margin({ top: 16 })
      }
      AuthInputField({
        label: '用户名',
        inputId: 'auth_username',
        placeholder: '请输入用户名',
        isPassword: false,
        value: $username,
        errorText: this.usernameError,
        fieldEnabled: !this.isSubmitting,
        onValueChange: (next: string) => {
          this.handle_field_change(AuthField.USERNAME, next);
        }
      })
      AuthInputField({
        label: '密码',
        inputId: 'auth_password',
        placeholder: '请输入密码',
        isPassword: true,
        value: $password,
        errorText: this.passwordError,
        fieldEnabled: !this.isSubmitting,
        onValueChange: (next: string) => {
          this.handle_field_change(AuthField.PASSWORD, next);
        }
      })
      if (this.mode === AuthMode.REGISTER) {
        AuthInputField({
          label: '确认密码',
          inputId: 'auth_confirm_password',
          placeholder: '请再次输入密码',
          isPassword: true,
          value: $confirmPassword,
          errorText: this.confirmPasswordError,
          fieldEnabled: !this.isSubmitting,
          onValueChange: (next: string) => {
            this.handle_field_change(AuthField.CONFIRM_PASSWORD, next);
          }
        })
      }
      if (this.formError.length > 0) {
        Text(this.formError)
          .fontSize(13)
          .fontColor(AUTH_COLOR_DANGER)
          .width('100%')
          .padding(12)
          .backgroundColor(AUTH_COLOR_DANGER_SOFT)
          .borderRadius(12)
          .margin({ top: 16 })
      }
      Button(this.submit_label())
        .id('auth_submit')
        .width('100%')
        .height(AUTH_CONTROL_HEIGHT)
        .backgroundColor(AUTH_COLOR_PRIMARY)
        .fontColor(AUTH_COLOR_WHITE)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .borderRadius(AUTH_RADIUS)
        .margin({ top: 24 })
        .enabled(!this.isSubmitting)
        .opacity(this.isSubmitting ? 0.6 : 1)
        .accessibilityText(this.submit_label())
        .onClick(() => {
          this.submit_form();
        })
      Row() {
        Text(this.mode === AuthMode.LOGIN ? '还没有账号？' : '已有账号？')
          .fontSize(14)
          .fontColor(AUTH_COLOR_MUTED)
        Button(this.mode === AuthMode.LOGIN ? '立即注册' : '返回登录')
          .id('auth_switch')
          .type(ButtonType.Normal)
          .fontSize(14)
          .fontColor(AUTH_COLOR_PRIMARY)
          .backgroundColor(Color.Transparent)
          .padding({ left: 4, right: 4 })
          .enabled(!this.isSubmitting)
          .accessibilityText(this.mode === AuthMode.LOGIN ? '立即注册' : '返回登录')
          .onClick(() => {
            this.switch_mode();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.Center)
      .margin({ top: 16 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ top: 16 })
  }

  /**

   * 登录后的资料检查、已登录状态卡与失败重试。
   */
  @Builder
  session_layout() {
    Scroll() {
      Column() {
        this.brand_mark()
        Column() {
          if (this.stage === AuthStage.CHECKING) {
            LoadingProgress()
              .width(36)
              .height(36)
              .color(AUTH_COLOR_PRIMARY)
            Text('正在加载账号信息…')
              .fontSize(15)
              .fontColor(AUTH_COLOR_MUTED)
              .margin({ top: 16 })
          } else {
            Text('已登录')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor(AUTH_COLOR_TEXT)
            if (this.stage === AuthStage.PROFILE && this.profile !== null) {
              Text(this.profile.username)
                .fontSize(20)
                .fontWeight(FontWeight.Medium)
                .fontColor(AUTH_COLOR_TEXT)
                .margin({ top: 20 })
              Text('账号 ID · ' + this.profile.accountId)
                .fontSize(14)
                .fontColor(AUTH_COLOR_MUTED)
                .margin({ top: 8 })
            }
            if (this.stage === AuthStage.RETRY) {
              Text('已登录，但暂时无法加载账号资料')
                .fontSize(15)
                .fontColor(AUTH_COLOR_TEXT)
                .margin({ top: 20 })
              Text(this.profileError)
                .fontSize(13)
                .fontColor(AUTH_COLOR_MUTED)
                .margin({ top: 8 })
              Button(this.profileLoading ? '重试中…' : '重试')
                .id('auth_retry')
                .width('100%')
                .height(AUTH_CONTROL_HEIGHT)
                .backgroundColor(AUTH_COLOR_PRIMARY)
                .fontColor(AUTH_COLOR_WHITE)
                .borderRadius(AUTH_RADIUS)
                .margin({ top: 24 })
                .enabled(!this.profileLoading && !this.isLoggingOut)
                .accessibilityText('重试加载资料')
                .onClick(() => {
                  this.retry_profile();
                })
            } else {
              if (this.profileError.length > 0) {
                Text(this.profileError)
                  .fontSize(13)
                  .fontColor(AUTH_COLOR_DANGER)
                  .margin({ top: 16 })
              }
              Button(this.profileLoading ? '刷新中…' : '刷新资料')
                .id('auth_refresh')
                .width('100%')
                .height(AUTH_CONTROL_HEIGHT)
                .backgroundColor(AUTH_COLOR_PRIMARY)
                .fontColor(AUTH_COLOR_WHITE)
                .borderRadius(AUTH_RADIUS)
                .margin({ top: 24 })
                .enabled(!this.profileLoading && !this.isLoggingOut)
                .accessibilityText('刷新资料')
                .onClick(() => {
                  this.refresh_profile();
                })
            }
            Button(this.isLoggingOut ? '退出中…' : '退出登录')
              .id('auth_logout')
              .width('100%')
              .height(AUTH_CONTROL_HEIGHT)
              .backgroundColor(AUTH_COLOR_WHITE)
              .fontColor(AUTH_COLOR_PRIMARY)
              .border({ width: 1, color: AUTH_COLOR_BORDER })
              .borderRadius(AUTH_RADIUS)
              .margin({ top: 12 })
              .enabled(!this.isLoggingOut && !this.profileLoading)
              .accessibilityText('退出登录')
              .onClick(() => {
                this.perform_logout();
              })
          }
        }
        .width('100%')
        .constraintSize({ maxWidth: AUTH_FORM_MAX_WIDTH })
        .padding(24)
        .backgroundColor(AUTH_COLOR_WHITE)
        .borderRadius(24)
        .alignItems(HorizontalAlign.Start)
        .margin({ top: 40 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .padding({ left: AUTH_SPACE, right: AUTH_SPACE, top: 20, bottom: 40 })
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
    .edgeEffect(EdgeEffect.Spring)
  }

  /**
   * 返回装饰图高度；低高度窗口缩小，避免表单被挤出首屏。
   *
   * @return 单位 vp 的高度
   */
  private illustration_height(): number {
    if (this.isWide) {
      return this.isShort ? 160 : 200;
    }
    return this.isShort ? 140 : 190;
  }

  /**
   * 返回主按钮文案。
   *
   * @return 当前模式的按钮文案
   */
  private submit_label(): string {
    if (this.isSubmitting) {
      return this.mode === AuthMode.LOGIN ? '登录中…' : '注册中…';
    }
    return this.mode === AuthMode.LOGIN ? '登录' : '注册';
  }

  /**
   * 统一处理输入变更，并清除对应字段错误。
   *
   * @param field 字段标识
   * @param value 新值
   */
  private handle_field_change(field: AuthField, value: string): void {
    if (field === AuthField.USERNAME) {
      this.username = value;
      this.usernameError = '';
    } else if (field === AuthField.PASSWORD) {
      this.password = value;
      this.passwordError = '';
    } else {
      this.confirmPassword = value;
      this.confirmPasswordError = '';
    }
    this.formError = '';
  }

  /**
   * 分发提交动作；提交中直接忽略重复点击。
   */
  private submit_form(): void {
    if (this.isSubmitting) {
      return;
    }
    if (this.mode === AuthMode.LOGIN) {
      this.submit_login();
    } else {
      this.submit_register();
    }
  }

  /**
   * 登录：记录请求开始时刻，成功后用该时刻保存会话，再请求当前用户资料。
   */
  private async submit_login(): Promise<void> {
    this.clear_errors();
    this.formNotice = '';
    const formErrors: AuthFormErrors = validate_login_form(this.username, this.password);
    if (formErrors.has_error()) {
      this.apply_local_errors(formErrors);
      return;
    }
    const startedAt: number = Date.now();
    this.isSubmitting = true;
    try {
      const result: AuthResult<AuthSessionData> = await this.api.login(this.username, this.password);
      if (!result.isSuccess || result.data === null) {
        this.apply_auth_error(result.error, true);
        return;
      }
      authSession.save_session(result.data, startedAt);
      this.password = '';
      this.confirmPassword = '';
      this.formError = '';
      this.stage = AuthStage.CHECKING;
      this.profileError = '';
      await this.load_profile(result.data.accessToken, false);
    } catch (error) {
      this.formError = '登录请求异常，请稍后重试';
    } finally {
      this.isSubmitting = false;
    }
  }

  /**
   * 注册：成功后回到登录模式，预填用户名、清空密码并提示，不自动登录。
   */
  private async submit_register(): Promise<void> {
    this.clear_errors();
    this.formNotice = '';
    const formErrors: AuthFormErrors =
      validate_register_form(this.username, this.password, this.confirmPassword);
    if (formErrors.has_error()) {
      this.apply_local_errors(formErrors);
      return;
    }
    const trimmedUsername: string = this.username.trim();
    this.isSubmitting = true;
    try {
      const result: AuthResult<UserProfile> =
        await this.api.register(trimmedUsername, this.password, this.confirmPassword);
      if (!result.isSuccess || result.data === null) {
        this.apply_auth_error(result.error, false);
        return;
      }
      this.mode = AuthMode.LOGIN;
      this.username = trimmedUsername;
      this.password = '';
      this.confirmPassword = '';
      this.clear_errors();
      this.formNotice = '注册成功，请登录';
    } catch (error) {
      this.formError = '注册请求异常，请稍后重试';
    } finally {
      this.isSubmitting = false;
    }
  }

  /**
   * 登录模式与注册模式互换，清空密码并清除所有错误。
   */
  private switch_mode(): void {
    if (this.isSubmitting) {
      return;
    }
    this.mode = this.mode === AuthMode.LOGIN ? AuthMode.REGISTER : AuthMode.LOGIN;
    this.password = '';
    this.confirmPassword = '';
    this.clear_errors();
    this.formNotice = '';
  }

  /**
   * 把本地校验结果写入字段错误。
   *
   * @param errors 本地校验结果
   */
  private apply_local_errors(errors: AuthFormErrors): void {
    this.usernameError = errors.username;
    this.passwordError = errors.password;
    this.confirmPasswordError = errors.confirmPassword;
    this.formError = errors.form;
  }

  /**
   * 把接口错误映射为字段错误或整体错误；不展示技术堆栈。
   *
   * @param error 接口错误
   * @param isLogin 是否为登录场景
   */
  private apply_auth_error(error: AuthError | null, isLogin: boolean): void {
    if (error === null) {
      this.formError = '请求失败，请稍后重试';
      return;
    }
    const usernameMessage: string = error.field_error('username') ?? '';
    const passwordMessage: string = error.field_error('password') ?? '';
    const confirmMessage: string = error.field_error('confirmPassword') ?? '';
    if (usernameMessage.length > 0) {
      this.usernameError = usernameMessage;
    }
    if (passwordMessage.length > 0) {
      this.passwordError = passwordMessage;
    }
    if (confirmMessage.length > 0) {
      this.confirmPasswordError = confirmMessage;
    }
    if (error.is_network()) {
      this.formError = '网络连接失败，请检查网络后重试';
      return;
    }
    if (error.is_rate_limited()) {
      this.formError = '操作过于频繁，请稍后重试';
      return;
    }
    if (error.is_service_unavailable()) {
      this.formError = '服务暂时不可用，请稍后重试';
      return;
    }
    if (error.is_username_conflict()) {
      this.usernameError = '该用户名已存在';
      this.formError = '';
      return;
    }
    if (error.is_unauthorized()) {
      this.formError = isLogin ? '用户名或密码错误' : '登录状态已失效，请重新登录';
      return;
    }
    if (error.kind === AuthFailureKind.PROTOCOL) {
      this.formError = '服务响应异常，请稍后重试';
      return;
    }
    if (usernameMessage.length > 0 || passwordMessage.length > 0 ||
      confirmMessage.length > 0) {
      this.formError = '';
      return;
    }
    this.formError = error.message;
  }

  /**
   * 加载当前用户资料；401 清空会话并回到登录，网络失败保留会话并提供重试。
   *
   * @param token 访问令牌
   * @param isRefresh 是否为已登录状态下的刷新
   */
  private async load_profile(token: string, isRefresh: boolean): Promise<void> {
    this.profileLoading = true;
    this.profileError = '';
    try {
      const result: AuthResult<UserProfile> = await this.api.getCurrentUser(token);
      if (!result.isSuccess || result.data === null) {
        this.handle_profile_failure(result.error, isRefresh);
        return;
      }
      this.profile = result.data;
      this.stage = AuthStage.PROFILE;
    } catch (error) {
      this.handle_profile_failure(null, isRefresh);
    } finally {
      this.profileLoading = false;
    }
  }

  /**
   * 处理资料加载失败。
   *
   * @param error 接口错误，可能为 null
   * @param isRefresh 是否为已登录状态下的刷新
   */
  private handle_profile_failure(error: AuthError | null, isRefresh: boolean): void {
    if (error !== null && error.is_unauthorized()) {
      authSession.clear_session();
      this.return_to_login('登录状态已失效，请重新登录');
      return;
    }
    const message: string = this.profile_failure_message(error);
    if (isRefresh && this.profile !== null) {
      this.profileError = message;
      return;
    }
    this.profile = null;
    this.stage = AuthStage.RETRY;
    this.profileError = message;
  }

  /**
   * 生成资料加载失败的可读文案。
   *
   * @param error 接口错误，可能为 null
   * @return 中文文案
   */
  private profile_failure_message(error: AuthError | null): string {
    if (error !== null && error.is_network()) {
      return '网络连接失败，请检查网络后重试';
    }
    if (error !== null && error.is_service_unavailable()) {
      return '服务暂时不可用，请稍后重试';
    }
    if (error !== null && error.is_rate_limited()) {
      return '操作过于频繁，请稍后重试';
    }
    return '无法加载账号资料，请稍后重试';
  }

  /**
   * 重试加载资料；令牌过期时回到登录。
   */
  private async retry_profile(): Promise<void> {
    if (this.profileLoading) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.return_to_login('登录状态已失效，请重新登录');
      return;
    }
    await this.load_profile(token, false);
  }

  /**
   * 刷新资料；网络失败保留当前资料与内存会话。
   */
  private async refresh_profile(): Promise<void> {
    if (this.profileLoading) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.return_to_login('登录状态已失效，请重新登录');
      return;
    }
    await this.load_profile(token, true);
  }

  /**
   * 退出登录；仅 204 或 401 视为服务端已确认，其他失败保留会话并提示重试。
   */
  private async perform_logout(): Promise<void> {
    if (this.isLoggingOut) {
      return;
    }
    this.profileError = '';
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      authSession.clear_session();
      this.return_to_login('登录状态已失效，请重新登录');
      return;
    }
    this.isLoggingOut = true;
    try {
      const result: AuthResult<boolean> = await this.api.logout(token);
      if (result.isSuccess ||
        (result.error !== null && result.error.is_unauthorized())) {
        authSession.clear_session();
        this.return_to_login('已退出登录');
        return;
      }
      this.profileError = '无法确认退出结果，请重试';
    } catch (error) {
      this.profileError = '无法确认退出结果，请重试';
    } finally {
      this.isLoggingOut = false;
    }
  }

  /**
   * 清除表单层状态并回到登录表单。
   *
   * @param notice 回到表单后展示的提示
   */
  private return_to_login(notice: string): void {
    this.profile = null;
    this.profileError = '';
    this.stage = AuthStage.FORM;
    this.mode = AuthMode.LOGIN;
    this.password = '';
    this.confirmPassword = '';
    this.clear_errors();
    this.formNotice = notice;
  }

  /**
   * 清除所有字段与整体错误。
   */
  private clear_errors(): void {
    this.usernameError = '';
    this.passwordError = '';
    this.confirmPasswordError = '';
    this.formError = '';
  }
}