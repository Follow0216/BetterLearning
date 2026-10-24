import { authSession, AuthApi, AuthResult, UserProfile } from 'common';
import { format_question_time } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/**
 * 账号页：展示当前会话资料，提供返回题库与退出登录。
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

  private api: AuthApi = AuthApi.create_default();
  private requestSeq: number = 0;

  aboutToAppear(): void {
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.errorText = '登录状态已失效，请重新登录';
      this.onSignedOut(token === null ? '' : token);
      return;
    }
    this.user = user;
  }

  build() {
    Column() {
      this.header()
      Scroll() {
        Column() {
          if (this.user !== null) {
            this.profile_card(this.user)
          }
          if (this.errorText.length > 0) {
            Text(this.errorText)
              .fontSize(12)
              .fontColor(PHONE_COLOR_DANGER)
              .lineHeight(18)
              .width('100%')
              .padding(12)
              .backgroundColor(PHONE_COLOR_DANGER_SOFT)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .margin({ top: 12 })
          }
          Button('退出登录')
            .width('100%')
            .height(46)
            .fontSize(14)
            .fontColor(PHONE_COLOR_DANGER)
            .backgroundColor(PHONE_COLOR_CARD)
            .border({ width: 1, color: PHONE_COLOR_DANGER })
            .borderRadius(PHONE_RADIUS_CONTROL)
            .enabled(!this.loggingOut)
            .opacity(this.loggingOut ? 0.5 : 1)
            .margin({ top: 18 })
            .onClick(() => {
              this.logout();
            })
          Text('M1 账号页不提供资料编辑；题库与录题数据仅属于当前登录账号。')
            .fontSize(11)
            .fontColor(PHONE_COLOR_MUTED)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 12 })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .padding({ left: 18, right: 18, bottom: 28 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Row() {
      Column() {
        Text('账号')
          .fontSize(30)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
        Text('当前登录账号')
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Row() {
        Text('返回题库')
          .fontSize(13)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
        Image($r('app.media.bl_chevron_right'))
          .width(12)
          .height(12)
          .margin({ left: 3 })
      }
      .padding({ left: 11, right: 8, top: 7, bottom: 7 })
      .backgroundColor(PHONE_COLOR_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .borderRadius(16)
      .accessibilityText('返回题库')
      .onClick(() => {
        this.onBack();
      })
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 18, bottom: 12 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  profile_card(user: UserProfile) {
    Column() {
      Row() {
        Column() {
          Text(user.username)
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(PHONE_COLOR_TITLE)
          Text(user.role === 'ADMIN' ? '管理员' : '普通账号')
            .fontSize(11)
            .fontColor(PHONE_COLOR_PRIMARY_DEEP)
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .backgroundColor(PHONE_COLOR_SOFT)
            .borderRadius(8)
            .margin({ top: 8 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')

      Divider()
        .color(PHONE_COLOR_BORDER)
        .margin({ top: 16, bottom: 14 })

      this.info_row('账号 ID', user.accountId)
      this.info_row('注册时间', format_question_time(user.createdAt))
      this.info_row('最近更新', format_question_time(user.updatedAt))
    }
    .width('100%')
    .padding(18)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  info_row(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .width(76)
      Text(value)
        .fontSize(12)
        .fontColor(PHONE_COLOR_TITLE)
        .layoutWeight(1)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .margin({ top: 6 })
    .alignItems(VerticalAlign.Top)
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