import {
  AuthApi,
  AuthError,
  AuthResult,
  UserProfile,
  authSession
} from 'common';
import {
  TABLET_COLOR_BACKGROUND,
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_DANGER,
  TABLET_COLOR_DANGER_SOFT,
  TABLET_COLOR_MUTED,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_TEXT,
  TABLET_CONTROL_SIZE,
  TABLET_PAGE_PADDING,
  TABLET_RADIUS,
  TABLET_RADIUS_SMALL
} from '../theme/TabletTheme';
import { format_created_at } from '../utils/DateTimeText';

/**
 * “我的”页：展示当前会话里的账号资料，并提供资料刷新与退出登录。
 *
 * <p>资料来自进程内会话，刷新与退出复用共享 AuthApi；令牌只在本组件内短期持有，
 * 不写入状态或日志。任何 401 都清除当前令牌并回到登录页。</p>
 */
@Component
export struct AccountPane {
  on_signed_out: () => void = () => {
  };
  @State profile: UserProfile | null = null;
  @State loading: boolean = false;
  @State logging_out: boolean = false;
  @State error_text: string = '';
  private api: AuthApi = AuthApi.create_default();
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const user: UserProfile | null = authSession.read_user();
    if (user === null) {
      this.expire_session();
      return;
    }
    this.profile = user;
  }

  aboutToDisappear(): void {
    this.alive = false;
  }

  build() {
    Scroll() {
      Column() {
        Text('我的')
          .fontSize(26)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
        Text('当前登录账号的资料与退出入口')
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ top: 4, bottom: 18 })

        if (this.error_text.length > 0) {
          this.error_bar()
        }

        if (this.profile !== null) {
          this.profile_card()
        }

        this.actions()
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding(TABLET_PAGE_PADDING)
    }
    .width('100%')
    .height('100%')

    .backgroundColor(TABLET_COLOR_BACKGROUND)
    .scrollBar(BarState.Auto)
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  profile_card() {
    Column() {
      Row() {
        Row() {
          Image($r('app.media.bl_user'))
            .width(24)
            .height(24)
            .objectFit(ImageFit.Contain)
        }
        .width(52)
        .height(52)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(26)

        Column() {
          Text(this.username_text())
            .fontSize(20)
            .fontWeight(FontWeight.Medium)
            .fontColor(TABLET_COLOR_TEXT)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(this.role_text())
            .fontSize(11)
            .fontColor(TABLET_COLOR_PRIMARY)
            .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
            .borderRadius(6)
            .padding({ left: 7, right: 7, top: 2, bottom: 2 })
            .margin({ top: 6 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 14 })
      }
      .width('100%')

      Divider()
        .color(TABLET_COLOR_BORDER)
        .margin({ top: 18, bottom: 6 })

      this.field_row('账号 ID', this.account_id_text())
      this.field_row('注册时间', this.created_at_text())
      this.field_row('邮箱', this.email_text())
      this.field_row('手机号', this.phone_text())
    }
    .width('100%')
    .padding(20)
    .backgroundColor(TABLET_COLOR_CARD)
    .borderRadius(TABLET_RADIUS)
    .border({ width: 1, color: TABLET_COLOR_BORDER })
  }

  @Builder
  field_row(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
        .width(88)
      Text(value)
        .fontSize(13)
        .fontColor(TABLET_COLOR_TEXT)
        .layoutWeight(1)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .padding({ top: 9, bottom: 9 })
  }

  @Builder
  actions() {
    Row({ space: 12 }) {
      Button() {
        Row() {
          Image($r('app.media.bl_refresh'))
            .width(18)
            .height(18)
            .objectFit(ImageFit.Contain)
          Text(this.loading ? '刷新中…' : '刷新资料')
            .fontSize(14)
            .fontColor(TABLET_COLOR_PRIMARY)
            .margin({ left: 6 })
        }
      }
      .type(ButtonType.Normal)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 18, right: 18 })
      .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
      .borderRadius(TABLET_RADIUS_SMALL)
      .enabled(!this.loading && !this.logging_out)
      .opacity(!this.loading && !this.logging_out ? 1 : 0.4)
      .onClick(() => {
        this.refresh_profile();
      })

      Button(this.logging_out ? '正在退出…' : '退出登录')
        .type(ButtonType.Normal)
        .fontSize(14)
        .fontColor(TABLET_COLOR_DANGER)
        .height(TABLET_CONTROL_SIZE)
        .padding({ left: 18, right: 18 })
        .backgroundColor(TABLET_COLOR_DANGER_SOFT)
        .borderRadius(TABLET_RADIUS_SMALL)
        .enabled(!this.loading && !this.logging_out)
        .opacity(!this.loading && !this.logging_out ? 1 : 0.4)
        .onClick(() => {
          this.perform_logout();
        })
    }
    .width('100%')
    .margin({ top: 18 })
  }

  @Builder

  error_bar() {
    Row() {
      Image($r('app.media.bl_warning'))
        .width(16)
        .height(16)
        .objectFit(ImageFit.Contain)
      Text(this.error_text)
        .fontSize(12)
        .fontColor(TABLET_COLOR_DANGER)
        .layoutWeight(1)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ left: 8 })
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(TABLET_COLOR_DANGER_SOFT)
    .borderRadius(TABLET_RADIUS_SMALL)
    .margin({ bottom: 12 })
  }

  private username_text(): string {
    const profile: UserProfile | null = this.profile;
    return profile === null ? '' : profile.username;
  }

  private account_id_text(): string {
    const profile: UserProfile | null = this.profile;
    return profile === null ? '' : profile.accountId;
  }

  private created_at_text(): string {
    const profile: UserProfile | null = this.profile;
    return profile === null ? '' : format_created_at(profile.createdAt);
  }

  private email_text(): string {
    const profile: UserProfile | null = this.profile;
    if (profile === null || profile.email === null || profile.email.length === 0) {
      return '未绑定';
    }
    return profile.email;
  }

  private phone_text(): string {
    const profile: UserProfile | null = this.profile;
    if (profile === null || profile.phone === null || profile.phone.length === 0) {
      return '未绑定';
    }
    return profile.phone;
  }

  private role_text(): string {
    const profile: UserProfile | null = this.profile;
    if (profile === null) {
      return '';
    }
    return profile.role === 'ADMIN' ? '管理员' : '普通用户';
  }

  private async refresh_profile(): Promise<void> {
    if (this.loading || this.logging_out) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      authSession.clear_session();
      this.expire_session();
      return;
    }
    this.loading = true;
    this.error_text = '';
    try {
      const result: AuthResult<UserProfile> = await this.api.getCurrentUser(token);
      if (!authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (!result.isSuccess || result.data === null) {
        this.error_text = this.failure_text(result.error);
        return;
      }
      this.profile = result.data;
    } catch (error) {
      if (authSession.matches_access_token(token)) {
        this.error_text = '网络连接失败，请检查网络后重试';
      }
    } finally {
      if (this.alive) {
        this.loading = false;
      }
    }
  }

  private async perform_logout(): Promise<void> {
    if (this.loading || this.logging_out) {
      return;
    }
    this.error_text = '';
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      authSession.clear_session();
      this.expire_session();
      return;
    }
    this.logging_out = true;
    try {
      const result: AuthResult<boolean> = await this.api.logout(token);
      if (!authSession.matches_access_token(token)) {
        return;
      }
      if (result.isSuccess || (result.error !== null && result.error.is_unauthorized())) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      this.error_text = '无法确认退出结果，请重试';
    } catch (error) {
      this.error_text = '无法确认退出结果，请重试';
    } finally {
      if (this.alive) {
        this.logging_out = false;
      }
    }
  }

  private failure_text(error: AuthError | null): string {
    if (error === null) {
      return '无法加载账号资料，请稍后重试';
    }
    if (error.is_network()) {
      return '网络连接失败，请检查网络后重试';
    }
    if (error.is_service_unavailable()) {
      return '服务暂时不可用，请稍后重试';
    }
    return '无法加载账号资料，请稍后重试';
  }

  private expire_session(): void {
    this.profile = null;
    this.error_text = '';
    this.on_signed_out();
  }
}
