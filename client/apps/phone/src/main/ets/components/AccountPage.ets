import {
  authSession, AuthApi, AuthError, AuthResult, UserProfile, UpdateProfileInput, ToastStore,
  validate_profile_update, validate_change_password
} from 'common';
import { format_question_time } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/**
 * 我的页：真实账号资料、明确解绑的联系方式编辑及密码修改。
 *
 * <p>退出语义与共享登录页一致：仅 204 或 401 视为服务端已确认，其余失败
 * 保留会话并提示重试。退出成功后由外层清除本端题库与录题状态。</p>
 */
@Component
export struct AccountPage {
  onBack: () => void = () => {
  };
  onSignedOut: (token: string) => void = () => {
  };

  @Prop @Watch('on_active_changed') active: boolean = true;
  toast?: ToastStore;
  onAccountSectionChange: (section: string) => void = () => {
  };
  onPasswordChanged: (token: string) => void = () => {
  };

  @State phoneInput: string = '';
  @State emailInput: string = '';
  @State unbindPhone: boolean = false;
  @State unbindEmail: boolean = false;
  @State phoneError: string = '';
  @State emailError: string = '';
  @State contactError: string = '';
  @State savingContact: boolean = false;
  @State oldPassword: string = '';
  @State newPassword: string = '';
  @State confirmPassword: string = '';
  @State oldPasswordError: string = '';
  @State newPasswordError: string = '';
  @State confirmPasswordError: string = '';
  @State passwordError: string = '';
  @State changingPassword: boolean = false;

  @State user: UserProfile | null = null;
  @State errorText: string = '';
  @State loggingOut: boolean = false;
  @State profileLoading: boolean = false;
  @State profileError: string = '';
  @State activeSection: string = '';

  private api: AuthApi = AuthApi.create_default();
  private requestSeq: number = 0;
  private profileSeq: number = 0;
  private sectionSeq: number = 0;
  private alive: boolean = false;
  private contactTouched: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    if (this.active) {
      this.onAccountSectionChange(this.activeSection.length > 0 ? this.activeSection : 'account');
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.errorText = '登录状态已失效，请重新登录';
      this.onSignedOut(token === null ? '' : token);
      return;
    }
    this.user = user;
    this.reset_contact_inputs(user);
    if (this.active) {
      this.load_profile();
    }
  }

  private on_active_changed(): void {
    if (!this.active) {
      this.sectionSeq++;
      this.clear_password_inputs();
      return;
    }
    if (this.alive) {
      this.onAccountSectionChange(this.activeSection.length > 0 ? this.activeSection : 'account');
      this.load_profile();
    }
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.sectionSeq++;
    this.clear_password_inputs();
    this.requestSeq++;
    this.profileSeq++;
    this.profileLoading = false;
    this.loggingOut = false;
    this.savingContact = false;
    this.changingPassword = false;
  }

  build() {
    Column() {
      this.header()
      Scroll() {
        Column() {
          if (this.user !== null) {
            this.profile_card(this.user)
          }
          this.settings_list()
          if (this.activeSection.length > 0) {
            this.section_detail()
          }
          if (this.errorText.length > 0) {
            Text(this.errorText)
              .fontSize(14)
              .fontColor(PHONE_COLOR_DANGER)
              .lineHeight(21)
              .width('100%')
              .padding(14)
              .backgroundColor(PHONE_COLOR_DANGER_SOFT)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .margin({ top: 16 })
          }
          Button({ type: ButtonType.Normal }) {
            Row({ space: 14 }) {
              Image($r('app.media.bl_reading_logout'))
                .width(24)
                .height(24)
              Text(this.loggingOut ? '正在退出…' : '退出登录')
                .fontSize(16)
                .fontColor(PHONE_COLOR_DANGER)
                .layoutWeight(1)
              Image($r('app.media.bl_chevron_right'))
                .width(18)
                .height(18)
                .opacity(0.5)
            }
            .width('100%')
          }
          .width('100%')
          .height(64)
          .padding({ left: 18, right: 18 })
          .backgroundColor('#FFF9F9')
          .border({ width: 1, color: '#F8DDE1' })
          .borderRadius(PHONE_RADIUS_CARD)
          .enabled(!this.account_busy())
          .opacity(this.loggingOut ? 0.5 : 1)
          .margin({ top: 18 })
          .onClick(() => this.logout())
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .padding({ left: 18, right: 18, bottom: 28 })
      }
      .layoutWeight(1)
      .width('100%')
      .align(Alignment.TopStart)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Row({ space: 10 }) {
      Button({ type: ButtonType.Normal }) {
        Image($r('app.media.bl_back')).width(22).height(22)
      }
      .width(44).height(44).padding(10).backgroundColor(Color.Transparent)
      .accessibilityText('返回AI首页').onClick(() => { this.onBack(); })
      Text('我的')
        .fontSize(26)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
    }
    .width('100%')
    .height(76)
    .padding({ left: 8, right: 20, top: 12, bottom: 12 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  profile_card(user: UserProfile) {
    Row({ space: 16 }) {
      Image($r('app.media.bl_reading_profile'))
        .width(64)
        .height(64)
        .padding(14)
        .backgroundColor('#DCEAFF')
        .borderRadius(32)
        .accessibilityText('个人账号')
      Column({ space: 7 }) {
        Text(user.username)
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('当前已登录')
          .fontSize(14)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .width('100%')
    .padding({ left: 20, right: 20, top: 24, bottom: 24 })
    .backgroundColor(PHONE_COLOR_SOFT)
    .borderRadius(PHONE_RADIUS_CARD)
  }

  @Builder
  settings_list() {
    Column() {
      this.setting_row('账号信息', 'account', $r('app.media.bl_user'))
      Divider().color(PHONE_COLOR_BORDER).margin({ left: 16, right: 16 })
      this.setting_row('联系方式', 'contact', $r('app.media.bl_reading_contact'))
      Divider().color(PHONE_COLOR_BORDER).margin({ left: 16, right: 16 })
      this.setting_row('修改密码', 'password', $r('app.media.bl_reading_lock'))
    }
    .width('100%')
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(PHONE_RADIUS_CARD)
    .clip(true)
    .margin({ top: 16 })
  }

  @Builder
  setting_row(label: string, section: string, icon: Resource) {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 14 }) {
        Image(icon).width(24).height(24)
        Text(label)
          .fontSize(16)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Image($r('app.media.bl_chevron_right'))
          .width(18)
          .height(18)
          .rotate({ angle: this.activeSection === section ? 90 : 0 })
      }
      .width('100%')
    }
    .width('100%')
    .height(68)
    .padding({ left: 18, right: 18 })
    .backgroundColor(this.activeSection === section ? '#F7FAFF' : PHONE_COLOR_CARD)
    .accessibilityText(label + (this.activeSection === section ? '，已展开' : '，查看'))
    .onClick(() => {
      this.select_section(section);
    })
  }

  @Builder
  section_detail() {
    Column({ space: 12 }) {
      Row() {
        Text(this.activeSection === 'password' ? '修改密码' :
          (this.activeSection === 'contact' ? '联系方式' : '账号信息'))
          .fontSize(17).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
        Button('关闭')
          .height(44).fontSize(13).fontColor(PHONE_COLOR_MUTED)
          .backgroundColor(Color.Transparent)
          .accessibilityText('关闭当前表单，不提交修改')
          .onClick(() => this.select_section(this.activeSection))
      }
      .width('100%')
      if (this.activeSection === 'password') {
        this.password_form()
      } else {
        Button(this.profileLoading ? '刷新中…' : '刷新资料')
          .height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY)
          .backgroundColor(PHONE_COLOR_SOFT).borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(!this.account_busy() && !this.profileLoading)
          .onClick(() => this.load_profile(true))
        if (this.profileError.length > 0) {
          Text(this.profileError + '，当前显示上次获取的资料。')
            .fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_DANGER)
        }
        if (this.user !== null) {
          if (this.activeSection === 'account') {
            this.info_row('用户名', this.user.username)
            this.info_row('账号 ID', this.user.accountId)
            this.info_row('注册时间', format_question_time(this.user.createdAt))
          } else {
            this.contact_form()
          }
        }
      }
    }
    .width('100%').padding(18).backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD)
    .alignItems(HorizontalAlign.Start).margin({ top: 12 })
  }

  @Builder
  contact_form() {
    Text('修改后保存到账号资料；此操作不代表已验证手机号或邮箱归属。')
      .fontSize(13).fontColor(PHONE_COLOR_MUTED).lineHeight(21)
    if (this.user !== null) {
      this.info_row('当前手机号', this.contact_value(this.user.phone))
      this.info_row('当前邮箱', this.contact_value(this.user.email))
    }
    Text('手机号').fontSize(14).fontColor(PHONE_COLOR_TITLE)
    TextInput({ text: this.phoneInput, placeholder: '国内手机号或 + 开头的国际号码' })
      .height(48).fontSize(15).backgroundColor(PHONE_COLOR_BACKGROUND)
      .borderRadius(PHONE_RADIUS_CONTROL).enabled(!this.account_busy() && !this.unbindPhone)
      .accessibilityText('手机号')
      .onChange((value: string) => {
        this.phoneInput = value;
        this.phoneError = '';
        this.contactTouched = true;
      })
    this.field_error(this.phoneError)
    if (this.user !== null && this.user.phone !== null) {
      Row({ space: 8 }) {
        Checkbox({ name: 'unbind-phone' }).select(this.unbindPhone)
          .enabled(!this.account_busy()).onChange((selected: boolean) => {
            this.unbindPhone = selected;
            this.phoneError = '';
            this.contactTouched = true;
          })
          .accessibilityText('明确解绑当前手机号')
        Text('解绑当前手机号').fontSize(14).fontColor(PHONE_COLOR_TITLE)
      }
      .width('100%')
    }
    Text('邮箱').fontSize(14).fontColor(PHONE_COLOR_TITLE)
    TextInput({ text: this.emailInput, placeholder: '请输入邮箱' })
      .height(48).fontSize(15).backgroundColor(PHONE_COLOR_BACKGROUND)
      .borderRadius(PHONE_RADIUS_CONTROL).type(InputType.Email)
      .enabled(!this.account_busy() && !this.unbindEmail).accessibilityText('邮箱')
      .onChange((value: string) => {
        this.emailInput = value;
        this.emailError = '';
        this.contactTouched = true;
      })
    this.field_error(this.emailError)
    if (this.user !== null && this.user.email !== null) {
      Row({ space: 8 }) {
        Checkbox({ name: 'unbind-email' }).select(this.unbindEmail)
          .enabled(!this.account_busy()).onChange((selected: boolean) => {
            this.unbindEmail = selected;
            this.emailError = '';
            this.contactTouched = true;
          })
          .accessibilityText('明确解绑当前邮箱')
        Text('解绑当前邮箱').fontSize(14).fontColor(PHONE_COLOR_TITLE)
      }
      .width('100%')
    }
    this.field_error(this.contactError)
    Button(this.savingContact ? '保存中…' : '保存联系方式')
      .width('100%').height(48).fontSize(15).fontColor(Color.White)
      .backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.account_busy() && !this.profileLoading)
      .onClick(() => this.save_contact())
    Text('清空输入不会自动解绑；解绑需勾选后保存。')
      .fontSize(12).lineHeight(19).fontColor(PHONE_COLOR_MUTED)
  }

  @Builder
  password_form() {
    Text('修改成功后需在所有设备重新登录。密码只在本次表单中使用。')
      .fontSize(13).lineHeight(21).fontColor(PHONE_COLOR_MUTED)
    this.password_field('原密码', this.oldPassword, this.oldPasswordError, 'oldPassword')
    this.password_field('新密码', this.newPassword, this.newPasswordError, 'newPassword')
    this.password_field('确认新密码', this.confirmPassword, this.confirmPasswordError, 'confirmPassword')
    Text('新密码为 8～64 个字符，UTF-8 编码不超过 72 字节。')
      .fontSize(12).lineHeight(19).fontColor(PHONE_COLOR_MUTED)
    this.field_error(this.passwordError)
    Button(this.changingPassword ? '提交中…' : '确认修改密码')
      .width('100%').height(48).fontSize(15).fontColor(Color.White)
      .backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.account_busy())
      .onClick(() => this.change_password())
  }

  @Builder
  password_field(label: string, value: string, errorText: string, field: string) {
    Column({ space: 6 }) {
      Text(label).fontSize(14).fontColor(PHONE_COLOR_TITLE)
      TextInput({ text: value, placeholder: '请输入' + label })
        .height(48).fontSize(15).type(InputType.Password)
        .backgroundColor(PHONE_COLOR_BACKGROUND).borderRadius(PHONE_RADIUS_CONTROL)
        .enabled(!this.account_busy()).accessibilityText(label)
        .onChange((next: string) => this.edit_password(field, next))
      this.field_error(errorText)
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  field_error(message: string) {
    if (message.length > 0) {
      Text(message).fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_DANGER).width('100%')
    }
  }

  @Builder
  info_row(label: string, value: string) {
    Row({ space: 10 }) {
      Text(label)
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .width(68)
      Text(value)
        .fontSize(14)
        .lineHeight(21)
        .fontColor(PHONE_COLOR_TITLE)
        .layoutWeight(1)
    }
    .width('100%')
    .alignItems(VerticalAlign.Top)
  }

  private account_busy(): boolean {
    return this.loggingOut || this.savingContact || this.changingPassword;
  }

  private select_section(section: string): void {
    const next: string = this.activeSection === section ? '' : section;
    this.sectionSeq++;
    this.activeSection = next;
    this.clear_password_inputs();
    this.onAccountSectionChange(next.length > 0 ? next : 'account');
  }

  private reset_contact_inputs(user: UserProfile): void {
    this.phoneInput = user.phone === null ? '' : user.phone;
    this.emailInput = user.email === null ? '' : user.email;
    this.unbindPhone = false;
    this.unbindEmail = false;
    this.phoneError = '';
    this.emailError = '';
    this.contactError = '';
    this.contactTouched = false;
  }

  private clear_password_inputs(): void {
    this.oldPassword = '';
    this.newPassword = '';
    this.confirmPassword = '';
    this.oldPasswordError = '';
    this.newPasswordError = '';
    this.confirmPasswordError = '';
    this.passwordError = '';
  }

  private edit_password(field: string, value: string): void {
    if (field === 'oldPassword') {
      this.oldPassword = value;
      this.oldPasswordError = '';
    } else if (field === 'newPassword') {
      this.newPassword = value;
      this.newPasswordError = '';
    } else {
      this.confirmPassword = value;
      this.confirmPasswordError = '';
    }
  }

  private require_relogin(token: string, error: AuthError | null): boolean {
    if (!authSession.matches_access_token(token)) {
      return true;
    }
    if (!authSession.has_valid_session() || (error !== null &&
      (error.is_unauthorized() || (error.httpStatus === 409 && error.serverCode === 'A0444')))) {
      if (authSession.clear_session_if_token(token)) {
        this.clear_password_inputs();
        this.onSignedOut(token);
      }
      return true;
    }
    return false;
  }

  private show_error(key: string, message: string, scope: number | undefined): void {
    this.toast?.show({ key: key, type: 'error', message: message }, scope);
  }

  private async save_contact(): Promise<void> {
    if (!this.active || this.account_busy() || this.profileLoading || this.user === null) {
      return;
    }
    const input: UpdateProfileInput = new UpdateProfileInput();
    if (this.unbindPhone) {
      input.phone = null;
    } else if (this.phoneInput !== (this.user.phone === null ? '' : this.user.phone)) {
      input.phone = this.phoneInput;
    }
    if (this.unbindEmail) {
      input.email = null;
    } else if (this.emailInput !== (this.user.email === null ? '' : this.user.email)) {
      input.email = this.emailInput;
    }
    const errors: Record<string, string> = validate_profile_update(input);
    this.phoneError = errors['phone'] ?? '';
    this.emailError = errors['email'] ?? '';
    this.contactError = '';
    if (this.phoneError.length > 0 || this.emailError.length > 0) {
      return;
    }
    if (input.phone === undefined && input.email === undefined) {
      this.contactError = '请先修改联系方式或勾选需要解绑的项目';
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.requestSeq;
    const section: number = this.sectionSeq;
    const scope: number | undefined = this.toast?.captureScope();
    this.profileSeq++;
    this.savingContact = true;
    try {
      const result: AuthResult<UserProfile> = await this.api.updateProfile(token, input);
      if (this.require_relogin(token, result.error)) {
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (this.alive && seq === this.requestSeq && section === this.sectionSeq) {
          this.apply_contact_error(result.error, scope);
        }
        return;
      }
      if (!authSession.update_user_if_token(token, result.data)) {
        return;
      }
      if (!this.alive || seq !== this.requestSeq) {
        return;
      }
      this.user = result.data;
      this.reset_contact_inputs(result.data);
      // PATCH 的真实返回先更新资料，再以 GET 确认当前服务端资料。
      const refreshed: AuthResult<UserProfile> = await this.api.getCurrentUser(token);
      if (this.require_relogin(token, refreshed.error) || !this.alive || seq !== this.requestSeq) {
        return;
      }
      if (refreshed.isSuccess && refreshed.data !== null &&
        authSession.update_user_if_token(token, refreshed.data)) {
        this.user = refreshed.data;
        this.reset_contact_inputs(refreshed.data);
        if (section === this.sectionSeq) {
          this.toast?.show({ key: 'account-contact', type: 'success', message: '联系方式已保存' }, scope);
        }
      } else if (section === this.sectionSeq) {
        this.contactError = '修改已保存，最新资料刷新失败，请使用刷新资料重试';
        this.show_error('account-contact', this.contactError, scope);
      }
    } catch (error) {
      if (!this.require_relogin(token, null) && this.alive &&
        seq === this.requestSeq && section === this.sectionSeq) {
        this.contactError = '暂时无法确认保存结果，请刷新资料确认后重试';
        this.show_error('account-contact', this.contactError, scope);
      }
    } finally {
      if (seq === this.requestSeq) {
        this.savingContact = false;
      }
    }
  }

  private apply_contact_error(error: AuthError | null, scope: number | undefined): void {
    this.phoneError = error?.field_error('phone') ?? '';
    this.emailError = error?.field_error('email') ?? '';
    if (error?.serverCode === 'A0154') {
      this.phoneError = '该手机号已被其他账号使用';
    }
    if (error?.serverCode === 'A0155') {
      this.emailError = '该邮箱已被其他账号使用';
    }
    this.contactError = error !== null && error.is_network() ?
      '暂时无法确认保存结果，请刷新资料确认后重试' : '联系方式保存失败，请检查后重试';
    this.show_error('account-contact', this.contactError, scope);
  }

  private async change_password(): Promise<void> {
    if (!this.active || this.account_busy()) {
      return;
    }
    const errors: Record<string, string> =
      validate_change_password(this.oldPassword, this.newPassword, this.confirmPassword);
    this.oldPasswordError = errors['oldPassword'] ?? '';
    this.newPasswordError = errors['newPassword'] ?? '';
    this.confirmPasswordError = errors['confirmPassword'] ?? '';
    this.passwordError = '';
    if (this.oldPasswordError.length > 0 || this.newPasswordError.length > 0 ||
      this.confirmPasswordError.length > 0) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.clear_password_inputs();
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.requestSeq;
    const section: number = this.sectionSeq;
    const scope: number | undefined = this.toast?.captureScope();
    this.profileSeq++;
    this.profileLoading = false;
    this.changingPassword = true;
    try {
      const result: AuthResult<boolean> =
        await this.api.changePassword(token, this.oldPassword, this.newPassword, this.confirmPassword);
      // 204 已撤销旧会话，即使已离页也必须清理；旧请求绝不能清除新登录。
      if (result.isSuccess && result.data === true) {
        if (authSession.clear_session_if_token(token)) {
          this.clear_password_inputs();
          this.onPasswordChanged(token);
        }
        return;
      }
      if (this.require_relogin(token, result.error) || !this.alive ||
        seq !== this.requestSeq || section !== this.sectionSeq) {
        return;
      }
      this.oldPasswordError = result.error?.field_error('oldPassword') ?? '';
      this.newPasswordError = result.error?.field_error('newPassword') ?? '';
      this.confirmPasswordError = result.error?.field_error('confirmPassword') ?? '';
      this.passwordError = result.error !== null && result.error.is_network() ?
        '未能确认修改结果；可能已提交，可重新登录确认，请勿自动重复提交' : '密码修改失败，请检查后重试';
      this.show_error('account-password', this.passwordError, scope);
    } catch (error) {
      if (!this.require_relogin(token, null) && this.alive &&
        seq === this.requestSeq && section === this.sectionSeq) {
        this.passwordError = '未能确认修改结果；可能已提交，可重新登录确认，请勿自动重复提交';
        this.show_error('account-password', this.passwordError, scope);
      }
    } finally {
      if (seq === this.requestSeq) {
        this.changingPassword = false;
      }
    }
  }

  private contact_value(value: string | null): string {
    return value === null || value.length === 0 ? '未绑定' : value;
  }

  private async load_profile(notify: boolean = false): Promise<void> {
    if (!this.active || this.profileLoading || this.account_busy()) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.profileSeq;
    const scope: number | undefined = this.toast?.captureScope();
    this.profileLoading = true;
    this.profileError = '';
    try {
      const result: AuthResult<UserProfile> = await this.api.getCurrentUser(token);
      if (seq !== this.profileSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session() ||
        (result.error !== null && result.error.is_unauthorized())) {
        authSession.clear_session_if_token(token);
        this.onSignedOut(token);
        return;
      }
      if (!result.isSuccess || result.data === null) {
        this.profileError = result.error !== null && result.error.is_network() ?
          '网络连接失败，请检查网络后重试' : '账号资料暂不可用，请稍后刷新';
        if (notify) {
          this.show_error('account-profile', this.profileError, scope);
        }
        return;
      }
      const current: UserProfile | null = authSession.read_user();
      if (current === null || current.accountId !== result.data.accountId) {
        this.profileError = '账号资料与当前登录不一致，请重试';
        return;
      }
      if (!authSession.update_user_if_token(token, result.data)) {
        return;
      }
      this.user = result.data;
      if (!this.contactTouched) {
        this.reset_contact_inputs(result.data);
      }
      if (notify) {
        this.toast?.show({ key: 'account-profile', type: 'success', message: '账号资料已刷新' }, scope);
      }
    } catch (error) {
      if (seq === this.profileSeq && authSession.matches_access_token(token)) {
        if (!authSession.has_valid_session()) {
          authSession.clear_session_if_token(token);
          this.onSignedOut(token);
          return;
        }
        this.profileError = '账号资料加载失败，请重试';
        if (notify) {
          this.show_error('account-profile', this.profileError, scope);
        }
      }
    } finally {
      if (seq === this.profileSeq) {
        this.profileLoading = false;
      }
    }
  }

  private async logout(): Promise<void> {
    if (!this.active || this.account_busy()) {
      return;
    }
    this.clear_password_inputs();
    this.errorText = '';
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      authSession.clear_session();
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.requestSeq;
    const scope: number | undefined = this.toast?.captureScope();
    this.profileSeq++;
    this.profileLoading = false;
    this.loggingOut = true;
    try {
      const result: AuthResult<boolean> = await this.api.logout(token);
      if (!authSession.matches_access_token(token)) {
        return;
      }
      if (result.isSuccess || (result.error !== null && result.error.is_unauthorized())) {
        if (authSession.clear_session_if_token(token)) {
          this.onSignedOut(token);
          this.toast?.show({ key: 'account-logout', type: 'success', message: '已退出登录' });
        }
        return;
      }
      if (this.require_relogin(token, result.error) || !this.alive || seq !== this.requestSeq) {
        return;
      }
      this.errorText = '无法确认退出结果，请重试';
      this.show_error('account-logout', this.errorText, scope);
    } catch (error) {
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        authSession.clear_session_if_token(token);
        this.onSignedOut(token);
        return;
      }
      this.errorText = '无法确认退出结果，请重试';
      this.show_error('account-logout', this.errorText, scope);
    } finally {
      if (seq === this.requestSeq) {
        this.loggingOut = false;
      }
    }
  }
}