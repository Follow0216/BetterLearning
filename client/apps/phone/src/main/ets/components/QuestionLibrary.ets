import {
  AnswerState,
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
import {
  describe_answer_state,
  describe_collection_error,
  describe_learning_error,
  format_question_time,
  question_summary_needs_full_view,
  question_summary_title
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
const COLLECTION_INDENT: number = 10;

/** 题集导航项：保持服务端同级顺序，depth 用于多层缩进。 */
class CollectionRow {
  readonly collectionId: string;
  readonly name: string;
  readonly questionCount: number;
  readonly depth: number;

  constructor(collectionId: string, name: string, questionCount: number, depth: number) {
    this.collectionId = collectionId;
    this.name = name;
    this.questionCount = questionCount;
    this.depth = depth;
  }
}

/**
 * 正式题库列表：全部/待整理/具体题集的只读树浏览、服务端摘要、页码分页、
 * 显式刷新与失败重试。
 *
 * <p>题集按服务端同级顺序与 parentCollectionId 组树，仅展示直接 questionCount；
 * 搜索、筛选与题集写操作不在此页面。所有异步回写同时校验发起请求时的令牌与
 * 本端请求序号，旧账号或旧请求的结果不会覆盖当前页面。401 交由外层清除会话
 * 并回到登录。</p>
 */
@Component
export struct QuestionLibrary {
  @Prop @Watch('on_refresh_token_changed') refreshToken: number = 0;
  onOpenQuestion: (questionId: string) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };
  onCreateQuestion: () => void = () => {
  };

  @State scope: string = SCOPE_ALL;
  @State page: number = 1;
  @State total: number = 0;
  @State items: QuestionSummary[] = [];
  @State inboxCount: number = -1;
  @State loading: boolean = true;
  @State hasLoaded: boolean = false;
  @State errorText: string = '';
  @State failedPage: number = 0;
  @State collectionRows: CollectionRow[] = [];
  @State collectionsLoading: boolean = false;
  @State collectionsError: string = '';
  @State hasLoadedCollections: boolean = false;

  private api: LearningApi = LearningApi.create_default();
  private requestSeq: number = 0;
  private collectionsSeq: number = 0;
  private collections: QuestionCollection[] = [];
  private sessionToken: string = '';
  private listScroller: Scroller = new Scroller();

  aboutToAppear(): void {
    const token: string | null = authSession.read_access_token();
    const currentToken: string = token === null ? '' : token;
    if (this.hasLoaded && currentToken === this.sessionToken) {
      return;
    }
    this.sessionToken = currentToken;
    this.reload_from_first_page();
  }

  on_refresh_token_changed(): void {
    const token: string | null = authSession.read_access_token();
    const currentToken: string = token === null ? '' : token;
    if (currentToken !== this.sessionToken) {
      this.sessionToken = currentToken;
      this.scope = SCOPE_ALL;
      this.clear_collections();
    }
    this.reload_from_first_page();
  }

  build() {
    Column() {
      this.header()
      this.scope_row()
      this.collection_status()

      if (this.loading && !this.hasLoaded) {
        this.loading_view()
      } else {
        Scroll(this.listScroller) {
          Column() {
            this.list_heading()
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
        .align(Alignment.TopStart)
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
    Row({ space: 8 }) {
      Column() {
        Text('题库')
          .fontSize(26)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
        Text('把题目收好，慢慢弄懂。')
          .fontSize(14)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Button('录题')
        .height(44)
        .padding({ left: 14, right: 14 })
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_PRIMARY)
        .backgroundColor(PHONE_COLOR_SOFT)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .accessibilityText('录入一道题')
        .onClick(() => this.onCreateQuestion())
      Button({ type: ButtonType.Normal }) {
        Image($r('app.media.bl_refresh'))
          .width(22)
          .height(22)
      }
      .width(44)
      .height(44)
      .padding(11)
      .backgroundColor(PHONE_COLOR_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.loading)
      .opacity(this.loading ? 0.45 : 1)
      .accessibilityText('刷新题库')
      .onClick(() => this.reload_from_first_page())
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 20, bottom: 18 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  list_heading() {
    Row() {
      Text('我的题目')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
      Blank()
      if (this.hasLoaded && this.errorText.length === 0) {
        Text(this.total.toString() + ' 道')
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
      }
    }
    .width('100%')
    .padding({ top: 16, bottom: 2 })
  }

  @Builder
  scope_row() {
    Scroll() {
      Row() {
        this.scope_chip('全部', SCOPE_ALL, -1, 0)
        this.scope_chip('待整理', SCOPE_INBOX, this.inboxCount, 0)
        ForEach(this.collectionRows, (row: CollectionRow) => {
          this.scope_chip(row.name, row.collectionId, row.questionCount, row.depth)
        }, (row: CollectionRow) => row.collectionId + '|' + row.name + '|' +
          row.questionCount.toString() + '|' + row.depth.toString())
      }
      .padding({ left: 18, right: 18, bottom: 6 })
    }
    .width('100%')
    .align(Alignment.Start)
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  scope_chip(label: string, value: string, count: number, depth: number) {
    Row() {
      Text(label)
        .fontSize(14)
        .fontWeight(this.scope === value ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.scope === value ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
      if (count >= 0) {
        Text(count.toString())
          .fontSize(11)
          .fontColor(this.scope === value ? PHONE_COLOR_WHITE : PHONE_COLOR_MUTED)
          .margin({ left: 5 })
      }
    }
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16 + depth * COLLECTION_INDENT, right: 16, top: 10, bottom: 10 })
    .backgroundColor(this.scope === value ? PHONE_COLOR_PRIMARY : PHONE_COLOR_CARD)
    .borderRadius(22)
    .border({ width: 1, color: this.scope === value ? PHONE_COLOR_PRIMARY : PHONE_COLOR_BORDER })
    .margin({ right: 8 })
    .opacity(this.scope_chip_opacity(value))
    .accessibilityText('查看' + label)
    .onClick(() => {
      this.switch_scope(value);
    })
  }

  /**
   * 题集加载状态：进行中显示进度，失败显示原因与重试；成功时不占位。
   * 题集失败不阻断“全部/待整理”，也不清空已成功的题目列表。
   */
  @Builder
  collection_status() {
    if (this.collectionsLoading) {
      Row() {
        LoadingProgress()
          .width(14)
          .height(14)
          .color(PHONE_COLOR_PRIMARY)
        Text(this.hasLoadedCollections ? '正在刷新题集…' : '正在加载题集…')
          .fontSize(12)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ left: 6 })
      }
      .width('100%')
      .padding({ left: 18, right: 18, bottom: 6 })
      .alignItems(VerticalAlign.Center)
    } else if (this.collectionsError.length > 0) {
      Column() {
        Row() {
          Image($r('app.media.bl_warning'))
            .width(16)
            .height(16)
          Column() {
            Text(this.hasLoadedCollections ? '题集未刷新' : '题集加载失败')
              .fontSize(12)
              .fontWeight(FontWeight.Medium)
              .fontColor(PHONE_COLOR_DANGER)
            Text(this.collectionsError)
              .fontSize(11)
              .fontColor(PHONE_COLOR_DANGER)
              .margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)
          .margin({ left: 6 })
          Button('重试')
            .height(44)
            .fontSize(11)
            .fontColor(PHONE_COLOR_WHITE)
            .backgroundColor(PHONE_COLOR_DANGER)
            .borderRadius(9)
            .margin({ left: 8 })
            .onClick(() => {
              this.retry_collections();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 9, bottom: 9 })
        .backgroundColor(PHONE_COLOR_DANGER_SOFT)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 18, right: 18, bottom: 6 })
    }
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
        Column() {
          Text(this.failedPage > 0 ? '第 ' + this.failedPage.toString() + ' 页加载失败' : '题库加载失败')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .fontColor(PHONE_COLOR_DANGER)
          Text(this.errorText)
            .fontSize(12)
            .fontColor(PHONE_COLOR_DANGER)
            .margin({ top: 3 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 8 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)

      Row() {
        Button('重试')
          .height(44)
          .fontSize(12)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_DANGER)
          .borderRadius(12)
          .enabled(!this.loading)
          .opacity(this.loading ? 0.5 : 1)
          .onClick(() => {
            this.retry_failed();
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
      Text(this.empty_title())
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .margin({ top: 12 })
      Text(this.empty_hint())
        .fontSize(14)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .margin({ top: 6 })
      Button('录入一道题')
        .height(44)
        .fontSize(14)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .margin({ top: 20 })
        .onClick(() => this.onCreateQuestion())
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
        .height(44)
        .fontSize(12)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(12)
        .margin({ top: 12 })
        .onClick(() => {
          this.reload_from_first_page();
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
      Row() {
        Text(learning_subject_label(item.subject))
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
        Blank()
        Text(describe_answer_state(item.answerState))
          .fontSize(12)
          .fontColor(this.answer_state_foreground(item.answerState))
          .padding({ left: 11, right: 11, top: 6, bottom: 6 })
          .backgroundColor(this.answer_state_background(item.answerState))
          .borderRadius(16)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Text(question_summary_title(item.summary, item.questionId))
        .fontSize(17)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .lineHeight(27)
        .maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .margin({ top: 12, bottom: 8 })
      if (question_summary_needs_full_view(item.summary)) {
        Text('公式请打开查看')
          .fontSize(13)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ bottom: 8 })
      }

      Row() {
        Column({ space: 4 }) {
          Text(this.collection_label(item.collectionId).length > 0 ?
            this.collection_label(item.collectionId) : '题集名称暂不可用')
            .fontSize(12)
            .fontColor(PHONE_COLOR_MUTED)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(format_question_time(item.createdAt))
            .fontSize(12)
            .fontColor(PHONE_COLOR_MUTED)
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        Button({ type: ButtonType.Normal }) {
          Row({ space: 4 }) {
            Text('查看题目')
              .fontSize(14)
              .fontColor(PHONE_COLOR_PRIMARY)
            Image($r('app.media.bl_chevron_right'))
              .width(16)
              .height(16)
          }
        }
        .height(44)
        .padding({ left: 10, right: 0 })
        .backgroundColor(Color.Transparent)
        .accessibilityText('查看完整题干')
        .onClick(() => this.onOpenQuestion(item.questionId))
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 16, bottom: 10 })
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 12 })
    .accessibilityText('题目，' + describe_answer_state(item.answerState) + '，' +
      question_summary_title(item.summary, item.questionId))
    .onClick(() => this.onOpenQuestion(item.questionId))
  }

  @Builder
  pagination() {
    Row() {
      Button('上一页')
        .height(44)
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
        .height(44)
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
   * 回到第一页并清空旧列表，供刷新、范围切换与账号刷新使用。
   */
  private reload_from_first_page(): void {
    this.reset_list_position();
    this.page = 1;
    this.items = [];
    this.total = 0;
    this.hasLoaded = false;
    this.failedPage = 0;
    this.load_page(1);
  }

  /**
   * 按目标页加载题目，再补充待整理题数。
   *
   * <p>请求期间不修改 {@code page}；只有成功后才提交服务端回显的页码，
   * 失败时保留 {@code failedPage} 供重试对应目标页，避免页码与旧条目错配。</p>
   *
   * @param targetPage 目标页码
   */
  private async load_page(targetPage: number): Promise<void> {
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const seq: number = ++this.requestSeq;
    const requestedScope: string = this.scope;
    this.loading = true;
    this.errorText = '';
    try {
      const collectionId: string | null = requestedScope === SCOPE_ALL ? null : requestedScope;
      const result: AuthResult<QuestionPage> = await this.api.listQuestions(token,
        new QuestionListQuery(targetPage, PAGE_SIZE, collectionId));
      if (!this.is_current_request(seq, token)) {
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
        this.failedPage = targetPage;
        this.hasLoaded = true;
        return;
      }
      const page: QuestionPage = result.data;
      this.items = page.items;
      this.page = page.page;
      this.total = page.total;
      this.failedPage = 0;
      this.hasLoaded = true;
      this.errorText = '';
      await this.load_collections(token);
    } catch (error) {
      if (!this.is_current_request(seq, token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.errorText = '题库加载异常，请稍后重试';
      this.failedPage = targetPage;
      this.hasLoaded = true;
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  /**
   * 读取题集树并补充待整理题数；失败不影响已成功加载的题目列表。
   *
   * <p>题集请求使用独立序号，保留旧树时明确标记未刷新；不把失败当成
   * 空题集，也不静默丢弃真实题集入口。</p>
   *
   * @param token 访问令牌
   */
  private async load_collections(token: string): Promise<void> {
    const seq: number = ++this.collectionsSeq;
    this.collectionsLoading = true;
    this.collectionsError = '';
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
        this.collectionsError = describe_collection_error(result.error);
        return;
      }
      this.apply_collections(result.data);
      this.hasLoadedCollections = true;
    } catch (error) {
      if (!this.is_current_collections_request(seq, token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.collectionsError = '题集加载异常，请稍后重试';
    } finally {
      if (seq === this.collectionsSeq) {
        this.collectionsLoading = false;
      }
    }
  }

  private apply_collections(items: QuestionCollection[]): void {
    this.collections = items;
    let inboxCount: number = -1;
    for (let index: number = 0; index < items.length; index++) {
      if (items[index].collectionId === INBOX_COLLECTION_ID) {
        inboxCount = items[index].questionCount;
      }
    }
    this.inboxCount = inboxCount;
    this.collectionRows = this.build_collection_rows();
  }

  private clear_collections(): void {
    this.collectionsSeq = this.collectionsSeq + 1;
    this.collections = [];
    this.collectionRows = [];
    this.inboxCount = -1;
    this.collectionsLoading = false;
    this.collectionsError = '';
    this.hasLoadedCollections = false;
  }

  /**
   * 按服务端同级顺序生成题集导航；待整理固定在最前，缺失父级按根处理。
   */
  private build_collection_rows(): CollectionRow[] {
    const rows: CollectionRow[] = [];
    const known: Set<string> = new Set();
    let inbox: QuestionCollection | null = null;
    for (let index: number = 0; index < this.collections.length; index++) {
      const item: QuestionCollection = this.collections[index];
      known.add(item.collectionId);
      if (item.collectionId === INBOX_COLLECTION_ID) {
        inbox = item;
      }
    }

    const roots: QuestionCollection[] = [];
    const children: Map<string, QuestionCollection[]> = new Map();
    for (let index: number = 0; index < this.collections.length; index++) {
      const item: QuestionCollection = this.collections[index];
      if (item.collectionId === INBOX_COLLECTION_ID) {
        continue;
      }
      const parentId: string | null = item.parentCollectionId;
      if (item.system || parentId === null || parentId.length === 0 ||
        parentId === item.collectionId || !known.has(parentId)) {
        roots.push(item);
        continue;
      }
      const siblings: QuestionCollection[] | undefined = children.get(parentId);
      if (siblings === undefined) {
        children.set(parentId, [item]);
      } else {
        siblings.push(item);
      }
    }

    const visited: Set<string> = new Set();
    if (inbox !== null) {
      visited.add(inbox.collectionId);
    }
    for (let index: number = 0; index < roots.length; index++) {
      this.append_collection_rows(roots[index], 0, children, visited, rows);
    }
    return rows;
  }

  private append_collection_rows(item: QuestionCollection, depth: number,
    children: Map<string, QuestionCollection[]>, visited: Set<string>, rows: CollectionRow[]): void {
    if (visited.has(item.collectionId)) {
      return;
    }
    visited.add(item.collectionId);
    rows.push(new CollectionRow(item.collectionId, item.name, item.questionCount, depth));
    const childItems: QuestionCollection[] | undefined = children.get(item.collectionId);
    if (childItems === undefined) {
      return;
    }
    for (let index: number = 0; index < childItems.length; index++) {
      this.append_collection_rows(childItems[index], depth + 1, children, visited, rows);
    }
  }

  private collection_label(collectionId: string): string {
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

  private empty_title(): string {
    if (this.scope === SCOPE_ALL) {
      return '还没有题目';
    }
    if (this.scope === SCOPE_INBOX) {
      return '待整理中还没有题目';
    }
    const name: string = this.collection_label(this.scope);
    return name.length > 0 ? '“' + name + '”中还没有题目' : '该题集还没有题目';
  }

  private empty_hint(): string {
    if (this.scope === SCOPE_ALL) {
      return '拍照、从相册选择或手动输入，保存第一道题。';
    }
    if (this.scope === SCOPE_INBOX) {
      return '新录入并确认的题目会先放在这里。';
    }
    return '该题集暂时没有直接归属的题目。';
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

  /**
   * 题集导航透明度：加载中整体变淡；题集未刷新时真实题集项进一步变淡，
   * 提示计数与名称可能过期，但按 ID 查询仍可用。
   */
  private scope_chip_opacity(value: string): number {
    if (this.loading || this.collectionsLoading) {
      return 0.55;
    }
    if (this.collectionsError.length > 0 && this.hasLoadedCollections &&
      value !== SCOPE_ALL && value !== SCOPE_INBOX) {
      return 0.65;
    }
    return 1;
  }

  /**
   * 仅重新加载题集树，不重置当前题目列表或页码。
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
    this.load_collections(token);
  }

  private switch_scope(nextScope: string): void {
    if (this.loading || this.scope === nextScope) {
      return;
    }
    this.scope = nextScope;
    this.reload_from_first_page();
  }

  private go_page(delta: number): void {
    if (this.loading) {
      return;
    }
    const target: number = this.page + delta;
    if (target < 1 || target > this.page_count()) {
      return;
    }
    this.reset_list_position();
    this.items = [];
    this.total = 0;
    this.hasLoaded = false;
    this.failedPage = 0;
    this.load_page(target);
  }

  /**
   * 重试失败的目标页；无失败页时重试当前页。
   */
  private retry_failed(): void {
    if (this.loading) {
      return;
    }
    const target: number = this.failedPage > 0 ? this.failedPage : this.page;
    this.hasLoaded = false;
    this.load_page(target);
  }

  /** 显式刷新／范围切换／翻页才归零；打开详情不触碰 Scroller。 */
  private reset_list_position(): void {
    if (!this.hasLoaded) {
      return;
    }
    this.listScroller.scrollTo({ xOffset: 0, yOffset: 0, animation: false });
  }

  private page_count(): number {
    if (this.total <= 0) {
      return 1;
    }
    return Math.ceil(this.total / PAGE_SIZE);
  }

  private is_current_request(seq: number, token: string): boolean {
    return seq === this.requestSeq && authSession.matches_access_token(token);
  }

  private is_current_collections_request(seq: number, token: string): boolean {
    return seq === this.collectionsSeq && authSession.matches_access_token(token);
  }

  private handle_unauthorized(token: string): void {
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}