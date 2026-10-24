import {
  authSession,
  AuthResult,
  LearningApi,
  learning_subject_label,
  QuestionDetail,
  QuestionMarkdownView
} from 'common';
import { describe_learning_error, format_question_time } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/**
 * 题目详情：按 questionId 读取完整正文并交给共享 Markdown 组件渲染。
 *
 * <p>加载结果回写前校验令牌与请求序号；401 交由外层回到登录，其他失败
 * 保留重试入口，不把加载失败显示成空题面。</p>
 */
@Component
export struct QuestionDetailPage {
  @Prop @Watch('on_question_changed') questionId: string = '';
  onBack: () => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State detail: QuestionDetail | null = null;
  @State loading: boolean = true;
  @State errorText: string = '';

  private api: LearningApi = LearningApi.create_default();
  private requestSeq: number = 0;

  aboutToAppear(): void {
    this.load_detail();
  }

  on_question_changed(): void {
    this.detail = null;
    this.load_detail();
  }

  build() {
    Column() {
      this.top_bar()
      if (this.loading) {
        Column() {
          LoadingProgress()
            .width(28)
            .height(28)
            .color(PHONE_COLOR_PRIMARY)
          Text('正在加载题目…')
            .fontSize(13)
            .fontColor(PHONE_COLOR_MUTED)
            .margin({ top: 10 })
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.errorText.length > 0) {
        this.error_view()
      } else if (this.detail !== null) {
        this.detail_view(this.detail)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row() {
      Image($r('app.media.bl_back'))
        .width(22)
        .height(22)
        .padding(7)
        .accessibilityText('返回题库')
        .onClick(() => {
          this.onBack();
        })
      Text('题目详情')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
        .margin({ left: 4 })
      Blank()
      Text('正式题库')
        .fontSize(11)
        .fontColor(PHONE_COLOR_PRIMARY)
        .padding({ left: 8, right: 8, top: 4, bottom: 4 })
        .backgroundColor(PHONE_COLOR_SOFT)
        .borderRadius(10)
    }
    .width('100%')
    .padding({ left: 10, right: 18, top: 12, bottom: 8 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  detail_view(detail: QuestionDetail) {
    Column() {
      Row() {
        Text(learning_subject_label(detail.subject))
          .fontSize(11)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(8)
        Text('待整理')
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ left: 6 })
        Blank()
        Text(format_question_time(detail.createdAt))
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .width('100%')
      .padding({ left: 18, right: 18 })
      .alignItems(VerticalAlign.Center)

      Text(detail.summary)
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
        .lineHeight(26)
        .width('100%')
        .padding({ left: 18, right: 18, top: 12, bottom: 12 })

      Column() {
        QuestionMarkdownView({ bodyMarkdown: detail.bodyMarkdown })
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor(PHONE_COLOR_CARD)
      .borderRadius(PHONE_RADIUS_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .clip(true)
      .margin({ left: 14, right: 14, bottom: 14 })
    }
    .width('100%')
    .layoutWeight(1)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  error_view() {
    Column() {
      Image($r('app.media.bl_warning'))
        .width(34)
        .height(34)
      Text(this.errorText)
        .fontSize(14)
        .fontColor(PHONE_COLOR_DANGER)
        .textAlign(TextAlign.Center)
        .margin({ top: 12 })
      Row() {
        Button('重试')
          .height(38)
          .fontSize(13)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(!this.loading)
          .onClick(() => {
            this.load_detail();
          })
        Button('返回题库')
          .height(38)
          .fontSize(13)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_CARD)
          .border({ width: 1, color: PHONE_COLOR_BORDER })
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ left: 10 })
          .onClick(() => {
            this.onBack();
          })
      }
      .margin({ top: 18 })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(28)
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  private async load_detail(): Promise<void> {
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    if (this.questionId.length === 0) {
      this.loading = false;
      this.errorText = '题目 ID 缺失';
      return;
    }
    const seq: number = ++this.requestSeq;
    const requestedId: string = this.questionId;
    this.loading = true;
    this.errorText = '';
    try {
      const result: AuthResult<QuestionDetail> = await this.api.getQuestion(token, requestedId);
      if (seq !== this.requestSeq || !authSession.matches_access_token(token) ||
        requestedId !== this.questionId) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.errorText = describe_learning_error(result.error);
        return;
      }
      if (result.data.questionId !== requestedId) {
        this.errorText = '题目详情与请求不一致，请重试';
        return;
      }
      this.detail = result.data;
    } catch (error) {
      if (seq !== this.requestSeq || !authSession.matches_access_token(token) ||
        requestedId !== this.questionId) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.errorText = '题目加载异常，请稍后重试';
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  private handle_unauthorized(token: string): void {
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}