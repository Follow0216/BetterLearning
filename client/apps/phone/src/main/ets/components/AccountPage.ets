import { authSession, AuthApi, AuthResult, UserProfile } from 'common';
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
 * 我的页：展示真实账号资料与现有退出能力，未接入的修改入口明确提示。
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

  @State user: UserProfile | null = null;
  @State errorText: string = '';
  @State loggingOut: boolean = false;
  @State profileLoading: boolean = false;
  @State profileError: string = '';
  @State activeSection: string = '';

  private api: AuthApi = AuthApi.create_default();
  private requestSeq: number = 0;
  private profileSeq: number = 0;

  aboutToAppear(): void {
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.errorText = '登录状态已失效，请重新登录';
      this.onSignedOut(token === null ? '' : token);
      return;
    }
    this.user = user;
    this.load_profile();
  }

  aboutToDisappear(): void {
    this.requestSeq++;
    this.profileSeq++;
    this.profileLoading = false;
    this.loggingOut = false;
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
          .enabled(!this.loggingOut)
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
      this.activeSection = this.activeSection === section ? '' : section;
    })
  }

  @Builder
  section_detail() {
    Column({ space: 12 }) {
      if (this.activeSection === 'password') {
        Text('修改密码')
          .fontSize(17)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
        Text('手机端暂未接入修改密码功能。')
          .fontSize(14)
          .fontColor(PHONE_COLOR_MUTED)
          .lineHeight(22)
      } else {
        Row() {
          Text(this.activeSection === 'contact' ? '联系方式' : '账号信息')
            .fontSize(17)
            .fontWeight(FontWeight.Medium)
            .fontColor(PHONE_COLOR_TITLE)
            .layoutWeight(1)
          Button(this.profileLoading ? '刷新中…' : '刷新资料')
            .height(44)
            .fontSize(13)
            .fontColor(PHONE_COLOR_PRIMARY)
            .backgroundColor(PHONE_COLOR_SOFT)
            .borderRadius(PHONE_RADIUS_CONTROL)
            .enabled(!this.profileLoading && !this.loggingOut)
            .onClick(() => this.load_profile())
        }
        .width('100%')
        if (this.profileError.length > 0) {
          Text(this.profileError + '，当前显示上次获取的资料。')
            .fontSize(13)
            .lineHeight(20)
            .fontColor(PHONE_COLOR_DANGER)
        }
        if (this.user !== null) {
          if (this.activeSection === 'account') {
            this.info_row('用户名', this.user.username)
            this.info_row('账号 ID', this.user.accountId)
            this.info_row('注册时间', format_question_time(this.user.createdAt))
            Text('手机端暂未接入资料编辑。')
              .fontSize(13)
              .fontColor(PHONE_COLOR_MUTED)
          } else {
            this.info_row('手机号', this.contact_value(this.user.phone))
            this.info_row('邮箱', this.contact_value(this.user.email))
            Text('当前可查看已绑定的信息，手机端暂未接入绑定与修改。')
              .fontSize(13)
              .lineHeight(20)
              .fontColor(PHONE_COLOR_MUTED)
          }
        }
      }
    }
    .width('100%')
    .padding(18)
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(PHONE_RADIUS_CARD)
    .alignItems(HorizontalAlign.Start)
    .margin({ top: 12 })
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

  private contact_value(value: string | null): string {
    return value === null || value.length === 0 ? '未绑定' : value;
  }

  private async load_profile(): Promise<void> {
    if (this.profileLoading || this.loggingOut) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.profileSeq;
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
        return;
      }
      const current: UserProfile | null = authSession.read_user();
      if (current === null || current.accountId !== result.data.accountId) {
        this.profileError = '账号资料与当前登录不一致，请重试';
        return;
      }
      this.user = result.data;
    } catch (error) {
      if (seq === this.profileSeq && authSession.matches_access_token(token)) {
        if (!authSession.has_valid_session()) {
          authSession.clear_session_if_token(token);
          this.onSignedOut(token);
          return;
        }
        this.profileError = '账号资料加载失败，请重试';
      }
    } finally {
      if (seq === this.profileSeq) {
        this.profileLoading = false;
      }
    }
  }

  private async logout(): Promise<void> {
    if (this.loggingOut) {
      return;
    }
    this.errorText = '';
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      authSession.clear_session();
      this.onSignedOut('');
      return;
    }
    const seq: number = ++this.requestSeq;
    this.loggingOut = true;
    try {
      const result: AuthResult<boolean> = await this.api.logout(token);
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        authSession.clear_session_if_token(token);
        this.onSignedOut(token);
        return;
      }
      if (result.isSuccess || (result.error !== null && result.error.is_unauthorized())) {
        authSession.clear_session_if_token(token);
        this.onSignedOut(token);
        return;
      }
      this.errorText = '无法确认退出结果，请重试';
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
    } finally {
      if (seq === this.requestSeq) {
        this.loggingOut = false;
      }
    }
  }
}