import {
  authSession,
  AuthResult,
  INBOX_COLLECTION_ID,
  LearningApi,
  learning_subject_label,
  QuestionCollection,
  QuestionListQuery,
  QuestionPage,
  QuestionSummary
} from 'common';
import { describe_learning_error, format_question_time } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_SOFT_STRONG,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

const PAGE_SIZE: number = 20;
const SCOPE_ALL: string = 'all';
const SCOPE_INBOX: string = 'inbox';

/**
 * 正式题库列表：全部/待整理、服务端摘要、页码分页、显式刷新与失败重试。
 *
 * <p>所有异步回写同时校验发起请求时的令牌与本端请求序号，旧账号或旧请求的
 * 结果不会覆盖当前页面。401 交由外层清除会话并回到登录。</p>
 */
@Component
export struct QuestionLibrary {
  @Prop @Watch('on_refresh_token_changed') refreshToken: number = 0;
  onOpenQuestion: (questionId: string) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State scope: string = SCOPE_ALL;
  @State page: number = 1;
  @State total: number = 0;
  @State items: QuestionSummary[] = [];
  @State inboxCount: number = -1;
  @State loading: boolean = true;
  @State hasLoaded: boolean = false;
  @State errorText: string = '';

  private api: LearningApi = LearningApi.create_default();
  private requestSeq: number = 0;

  aboutToAppear(): void {
    this.load_questions(true);
  }

  on_refresh_token_changed(): void {
    this.load_questions(true);
  }

  build() {
    Column() {
      this.header()
      this.scope_row()

      if (this.loading && !this.hasLoaded) {
        this.loading_view()
      } else {
        Scroll() {
          Column() {
            if (this.errorText.length > 0) {
              this.error_banner()
            }
            if (this.hasLoaded && this.items.length === 0 && this.errorText.length === 0) {
              if (this.total === 0) {
                this.empty_view()
              } else {
                this.page_empty_view()
              }
            }
            ForEach(this.items, (item: QuestionSummary) => {
              this.question_card(item)
            }, (item: QuestionSummary) => item.questionId)
            if (this.items.length > 0) {
              this.pagination()
            }
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 18, right: 18, bottom: 24 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Row() {
      Column() {
        Text('题库')
          .fontSize(30)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
        Text('当前账号的正式题库')
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Row() {
        Image($r('app.media.bl_refresh'))
          .width(18)
          .height(18)
        Text('刷新')
          .fontSize(13)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .margin({ left: 5 })
      }
      .padding({ left: 11, right: 11, top: 7, bottom: 7 })
      .backgroundColor(PHONE_COLOR_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .borderRadius(16)
      .opacity(this.loading ? 0.45 : 1)
      .accessibilityText('刷新题库')
      .onClick(() => {
        if (!this.loading) {
          this.load_questions(true);
        }
      })
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 18, bottom: 12 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  scope_row() {
    Row() {
      this.scope_chip('全部', SCOPE_ALL, -1)
      this.scope_chip('待整理', SCOPE_INBOX, this.inboxCount)
    }
    .width('100%')
    .padding({ left: 18, right: 18, bottom: 6 })
  }

  @Builder
  scope_chip(label: string, value: string, count: number) {
    Row() {
      Text(label)
        .fontSize(13)
        .fontWeight(this.scope === value ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.scope === value ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
      if (count >= 0) {
        Text(count.toString())
          .fontSize(11)
          .fontColor(this.scope === value ? PHONE_COLOR_WHITE : PHONE_COLOR_MUTED)
          .margin({ left: 5 })
      }
    }
    .padding({ left: 14, right: 14, top: 7, bottom: 7 })
    .backgroundColor(this.scope === value ? PHONE_COLOR_PRIMARY : PHONE_COLOR_CARD)
    .borderRadius(16)
    .margin({ right: 8 })
    .opacity(this.loading ? 0.55 : 1)
    .accessibilityText('查看' + label)
    .onClick(() => {
      this.switch_scope(value);
    })
  }

  @Builder
  loading_view() {
    Column() {
      LoadingProgress()
        .width(28)
        .height(28)
        .color(PHONE_COLOR_PRIMARY)
      Text('正在加载题库…')
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 10 })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  error_banner() {
    Column() {
      Row() {
        Image($r('app.media.bl_warning'))
          .width(18)
          .height(18)
        Text(this.errorText)
          .fontSize(13)
          .fontColor(PHONE_COLOR_DANGER)
          .layoutWeight(1)
          .margin({ left: 8 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)

      Row() {
        Button('重试')
          .height(34)
          .fontSize(12)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_DANGER)
          .borderRadius(12)
          .enabled(!this.loading)
          .opacity(this.loading ? 0.5 : 1)
          .onClick(() => {
            if (!this.loading) {
              this.load_questions(false);
            }
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
      .margin({ top: 10 })
    }
    .width('100%')
    .padding(14)
    .backgroundColor(PHONE_COLOR_DANGER_SOFT)
    .borderRadius(PHONE_RADIUS_CONTROL)
    .alignItems(HorizontalAlign.Start)
    .margin({ top: 8, bottom: 4 })
  }

  @Builder
  empty_view() {
    Column() {
      Image($r('app.media.bl_inbox'))
        .width(42)
        .height(42)
      Text(this.scope === SCOPE_INBOX ? '待整理中还没有题目' : '还没有题目')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .margin({ top: 12 })
      Text(this.scope === SCOPE_INBOX ?
        '在“录题”中手工确认的题目会进入待整理。' : '前往“录题”粘贴 Markdown，创建第一道题。')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .margin({ top: 6 })
    }
    .width('100%')
    .padding({ top: 48, bottom: 40, left: 24, right: 24 })
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 10 })
  }

  @Builder
  page_empty_view() {
    Column() {
      Text('本页没有题目')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
      Text('题库可能已变化，请刷新后重试。')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 6 })
      Button('刷新题库')
        .height(36)
        .fontSize(12)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(12)
        .margin({ top: 12 })
        .onClick(() => {
          this.load_questions(true);
        })
    }
    .width('100%')
    .padding(20)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .alignItems(HorizontalAlign.Start)
    .margin({ top: 10 })
  }

  @Builder
  question_card(item: QuestionSummary) {
    Column() {
      Text(item.summary)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .lineHeight(23)
        .maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')

      Row() {
        Text(learning_subject_label(item.subject))
          .fontSize(11)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(8)
        Text(item.collectionId === INBOX_COLLECTION_ID ? '待整理' : '题集')
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ left: 6 })
        Blank()
        Text(format_question_time(item.createdAt))
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .margin({ top: 12 })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 10 })
    .accessibilityText('打开题目 ' + item.summary)
    .onClick(() => {
      this.onOpenQuestion(item.questionId);
    })
  }

  @Builder
  pagination() {
    Row() {
      Button('上一页')
        .height(36)
        .fontSize(12)
        .fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT_STRONG)
        .borderRadius(12)
        .enabled(!this.loading && this.page > 1)
        .opacity(!this.loading && this.page > 1 ? 1 : 0.45)
        .onClick(() => {
          this.go_page(-1);
        })
      Text('第 ' + this.page.toString() + ' / ' + this.page_count().toString() + ' 页 · 共 ' +
        this.total.toString() + ' 题')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
      Button('下一页')
        .height(36)
        .fontSize(12)
        .fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT_STRONG)
        .borderRadius(12)
        .enabled(!this.loading && this.page < this.page_count())
        .opacity(!this.loading && this.page < this.page_count() ? 1 : 0.45)
        .onClick(() => {
          this.go_page(1);
        })
    }
    .width('100%')
    .margin({ top: 16, bottom: 4 })
    .alignItems(VerticalAlign.Center)
  }

  /**
   * 按当前范围与页码加载题目，再补充待整理题数。
   *
   * @param resetPage 是否回到第一页
   */
  private async load_questions(resetPage: boolean): Promise<void> {
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const seq: number = ++this.requestSeq;
    if (resetPage) {
      this.page = 1;
    }
    const requestedPage: number = this.page;
    const requestedScope: string = this.scope;
    this.loading = true;
    this.errorText = '';
    try {
      const collectionId: string | null = requestedScope === SCOPE_INBOX ? INBOX_COLLECTION_ID : null;
      const result: AuthResult<QuestionPage> = await this.api.listQuestions(token,
        new QuestionListQuery(requestedPage, PAGE_SIZE, collectionId));
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.errorText = describe_learning_error(result.error);
        this.hasLoaded = true;
        return;
      }
      const page: QuestionPage = result.data;
      this.items = page.items;
      this.page = page.page;
      this.total = page.total;
      this.hasLoaded = true;
      this.errorText = '';
      await this.load_collections(token, seq);
    } catch (error) {
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      this.errorText = '题库加载异常，请稍后重试';
      this.hasLoaded = true;
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  /**
   * 读取待整理题数；失败不影响已成功加载的题目列表。
   *
   * @param token 访问令牌
   * @param seq 请求序号
   */
  private async load_collections(token: string, seq: number): Promise<void> {
    try {
      const result: AuthResult<QuestionCollection[]> = await this.api.listCollections(token);
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (result.isSuccess && result.data !== null) {
        let found: boolean = false;
        let count: number = 0;
        for (let index: number = 0; index < result.data.length; index++) {
          if (result.data[index].collectionId === INBOX_COLLECTION_ID) {
            found = true;
            count = result.data[index].questionCount;
          }
        }
        this.inboxCount = found ? count : -1;
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        this.handle_unauthorized(token);
      }
    } catch (error) {
      if (seq === this.requestSeq && authSession.matches_access_token(token)) {
        this.inboxCount = -1;
      }
    }
  }

  private switch_scope(nextScope: string): void {
    if (this.loading || this.scope === nextScope) {
      return;
    }
    this.scope = nextScope;
    this.page = 1;
    this.items = [];
    this.total = 0;
    this.hasLoaded = false;
    this.load_questions(true);
  }

  private go_page(delta: number): void {
    if (this.loading) {
      return;
    }
    const target: number = this.page + delta;
    if (target < 1 || target > this.page_count()) {
      return;
    }
    this.page = target;
    this.load_questions(false);
  }

  private page_count(): number {
    if (this.total <= 0) {
      return 1;
    }
    return Math.ceil(this.total / PAGE_SIZE);
  }

  private handle_unauthorized(token: string): void {
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}