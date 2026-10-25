import {
  AnswerState,
  authSession,
  AuthResult,
  INBOX_COLLECTION_ID,
  LearningApi,
  learning_subject_label,
  QuestionCollection,
  QuestionDetail,
  QuestionMarkdownView
} from 'common';
import {
  describe_answer_state,
  describe_learning_error,
  format_question_time
} from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT_STRONG,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/**
 * 题目详情：按 questionId 读取完整正文并交给共享 Markdown 组件渲染，
 * 同时按本次详情回显的 collectionId 展示真实题集归属与当前作答状态。
 *
 * <p>详情与题集并行读取；题集失败只影响归属文案，不阻断题面。加载结果
 * 回写前校验令牌与请求序号；401 交由外层回到登录，其他失败保留重试入口，
 * 不把加载失败显示成空题面，也不复用列表快照中的旧归属名。</p>
 */
@Component
export struct QuestionDetailPage {
  @Prop @Watch('on_question_changed') questionId: string = '';
  onBack: () => void = () => {
  };
  onOpenAnswer: (questionId: string) => void = () => {
  };
  onOpenTutor: (questionId: string) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State detail: QuestionDetail | null = null;
  @State loading: boolean = true;
  @State errorText: string = '';
  @State collections: QuestionCollection[] = [];
  @State collectionsLoading: boolean = false;
  @State collectionsFailed: boolean = false;

  private api: LearningApi = LearningApi.create_default();
  private requestSeq: number = 0;
  private collectionSeq: number = 0;

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
    Row({ space: 6 }) {
      Button({ type: ButtonType.Normal }) {
        Image($r('app.media.bl_back'))
          .width(24)
          .height(24)
      }
      .width(44)
      .height(44)
      .padding(10)
      .backgroundColor(Color.Transparent)
      .accessibilityText('返回题库，保留阅读位置')
      .onClick(() => this.onBack())
      Text('题目详情')
        .fontSize(22)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
        .layoutWeight(1)
      if (this.detail !== null && !this.loading && this.errorText.length === 0) {
        Text(describe_answer_state(this.detail.answerState))
          .fontSize(13)
          .fontColor(this.answer_state_foreground(this.detail.answerState))
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .backgroundColor(this.answer_state_background(this.detail.answerState))
          .borderRadius(20)
      }
    }
    .width('100%')
    .padding({ left: 8, right: 18, top: 12, bottom: 10 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  detail_view(detail: QuestionDetail) {
    Column() {
      Column({ space: 6 }) {
        Row({ space: 8 }) {
          Text(learning_subject_label(detail.subject))
            .fontSize(14)
            .fontColor(PHONE_COLOR_MUTED)
          Text('·')
            .fontSize(14)
            .fontColor(PHONE_COLOR_MUTED)
          if (this.collection_name(detail.collectionId).length > 0) {
            Text(this.collection_name(detail.collectionId))
              .fontSize(14)
              .fontColor(PHONE_COLOR_MUTED)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
          } else if (this.collectionsFailed) {
            Button('题集加载失败 · 重试')
              .height(44)
              .fontSize(13)
              .fontColor(PHONE_COLOR_DANGER)
              .backgroundColor(PHONE_COLOR_DANGER_SOFT)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .accessibilityText('重新加载题集归属')
              .onClick(() => this.retry_collections())
          } else {
            Text(this.collectionsLoading ? '题集加载中…' : '题集名称暂不可用')
              .fontSize(14)
              .fontColor(PHONE_COLOR_MUTED)
          }
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        Text(format_question_time(detail.createdAt) + ' 录入')
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 20, right: 20, top: 4, bottom: 16 })

      Column() {
        QuestionMarkdownView({ bodyMarkdown: detail.bodyMarkdown, fontSize: 17 })
          .width('100%')
          .layoutWeight(1)
        Row({ space: 8 }) {
          Text(detail.answerState === AnswerState.NOT_STARTED ? '○' : '✓')
            .fontSize(17)
            .fontWeight(FontWeight.Medium)
            .fontColor(detail.answerState === AnswerState.NOT_STARTED ?
              PHONE_COLOR_MUTED : PHONE_COLOR_PRIMARY)
          Text(this.answer_hint(detail.answerState))
            .fontSize(13)
            .lineHeight(20)
            .fontColor(detail.answerState === AnswerState.NOT_STARTED ?
              PHONE_COLOR_MUTED : PHONE_COLOR_PRIMARY)
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 14 })
        .alignItems(VerticalAlign.Top)
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor(PHONE_COLOR_CARD)
      .borderRadius(PHONE_RADIUS_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .clip(true)

      Row({ space: 12 }) {
        Button('查看作答')
          .height(52)
          .layoutWeight(1)
          .fontSize(17)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_PRIMARY)
          .backgroundColor(PHONE_COLOR_CARD)
          .border({ width: 1, color: PHONE_COLOR_BORDER })
          .borderRadius(26)
          .accessibilityText('只读查看已保存的作答')
          .onClick(() => this.onOpenAnswer(detail.questionId))
        Button('AI 辅导')
          .height(52)
          .layoutWeight(1)
          .fontSize(17)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius(26)
          .accessibilityText('打开这道题的 AI 辅导')
          .onClick(() => this.onOpenTutor(detail.questionId))
      }
      .width('100%')
      .padding({ top: 16, bottom: 16 })
    }
    .width('100%')
    .layoutWeight(1)
    .padding({ left: 14, right: 14 })
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
          .height(44)
          .fontSize(13)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(!this.loading)
          .onClick(() => {
            this.load_detail();
          })
        Button('返回题库')
          .height(44)
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
    this.collections = [];
    this.collectionsFailed = false;
    this.load_collections(token, ++this.collectionSeq);
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

  /**
   * 读取题集树，用于按详情回显的 collectionId 解析归属名。
   *
   * @param token 访问令牌
   * @param seq 本次题集请求序号
   */
  private async load_collections(token: string, seq: number): Promise<void> {
    this.collectionsLoading = true;
    this.collectionsFailed = false;
    try {
      const result: AuthResult<QuestionCollection[]> = await this.api.listCollections(token);
      if (!this.is_current_collections_request(seq, token)) {
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
        this.collectionsFailed = true;
        return;
      }
      this.collections = result.data;
    } catch (error) {
      if (!this.is_current_collections_request(seq, token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.collectionsFailed = true;
    } finally {
      if (seq === this.collectionSeq) {
        this.collectionsLoading = false;
      }
    }
  }

  /**
   * 仅重试题集归属，不重新加载题面。
   */
  private retry_collections(): void {
    if (this.collectionsLoading) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    this.load_collections(token, ++this.collectionSeq);
  }

  private collection_name(collectionId: string): string {
    if (collectionId === INBOX_COLLECTION_ID) {
      return '待整理';
    }
    for (let index: number = 0; index < this.collections.length; index++) {
      if (this.collections[index].collectionId === collectionId) {
        return this.collections[index].name;
      }
    }
    return '';
  }

  private is_current_collections_request(seq: number, token: string): boolean {
    return seq === this.collectionSeq && authSession.matches_access_token(token);
  }

  private answer_state_foreground(state: AnswerState): string {
    if (state === AnswerState.COMPLETED) {
      return '#147D4B';
    }
    return state === AnswerState.IN_PROGRESS ? PHONE_COLOR_PRIMARY : PHONE_COLOR_MUTED;
  }

  private answer_state_background(state: AnswerState): string {
    if (state === AnswerState.COMPLETED) {
      return '#E5F5EC';
    }
    if (state === AnswerState.IN_PROGRESS) {
      return PHONE_COLOR_SOFT_STRONG;
    }
    return '#EEF2F8';
  }

  private answer_hint(state: AnswerState): string {
    if (state === AnswerState.NOT_STARTED) {
      return '尚未保存作答，可在平板上书写后查看。';
    }
    return '已保存作答，可在手机查看。';
  }

  private handle_unauthorized(token: string): void {
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}