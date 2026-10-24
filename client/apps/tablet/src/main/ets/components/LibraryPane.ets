import {
  AnswerState,
  AuthError,
  AuthFailureKind,
  AuthResult,
  INBOX_COLLECTION_ID,
  LearningApi,
  LearningSubject,
  QuestionCollection,
  QuestionDetail,
  QuestionListQuery,
  QuestionPage,
  QuestionSummary,
  SolutionDetail,
  authSession,
  learning_subject_label
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
  TABLET_RADIUS_SMALL,
  TABLET_SIDEBAR_WIDTH
} from '../theme/TabletTheme';
import { format_created_at } from '../utils/DateTimeText';
import { AnswerWorkspace } from './AnswerWorkspace';
import { CanvasWorkspaceState, TabletCanvasController } from '../features/canvas/TabletCanvasWorkspace';

const PAGE_SIZE: number = 20;
const ROOT_COLLECTION_KEY: string = '';
const ANSWER_DONE_FOREGROUND: string = '#2E7D5B';
const ANSWER_DONE_BACKGROUND: string = '#E8F6EE';
const ANSWER_IDLE_BACKGROUND: string = '#F0F3F9';

/** 侧栏题集树的扁平可见节点，避免在构建函数里递归渲染。 */
class CollectionNode {
  readonly collection: QuestionCollection;
  readonly depth: number;
  readonly has_children: boolean;

  constructor(collection: QuestionCollection, depth: number, has_children: boolean) {
    this.collection = collection;
    this.depth = depth;
    this.has_children = has_children;
  }
}

@Component
export struct LibraryPane {
  on_session_expired: () => void = () => {
  };
  @State scope_collection_id: string | null = null;
  @State page: number = 1;
  @State page_size: number = PAGE_SIZE;
  @State total: number = 0;
  @State items: QuestionSummary[] = [];
  @State inbox_count: number = -1;
  @State collection_nodes: CollectionNode[] = [];
  @State collapsed_ids: string[] = [];
  @State collections_error: string = '';
  @State loading: boolean = false;
  @State loaded_once: boolean = false;
  @State error_text: string = '';
  @State open_question_id: string = '';
  @State open_summary: string = '';
  @State detail: QuestionDetail | null = null;
  @State detail_loading: boolean = false;
  @State detail_error: string = '';
  @State canvas_ready: boolean = false;
  @State canvas_busy: boolean = false;
  @State canvas_can_complete: boolean = false;
  @State canvas_completed: boolean = false;
  @State pending_action: string = '';
  @State feedback_text: string = '';
  @State feedback_error: boolean = false;
  @State closing: boolean = false;
  private api: LearningApi = LearningApi.create_default();
  private collections: QuestionCollection[] = [];
  private canvas_controller: TabletCanvasController = new TabletCanvasController();
  private list_seq: number = 0;
  private detail_seq: number = 0;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.refresh_all();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.list_seq += 1;
    this.detail_seq += 1;
  }

  build() {
    Column() {
      if (this.open_question_id.length > 0) {
        this.detail_panel()
      } else {
        Row() {
          this.sidebar()
          this.list_panel()
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(TABLET_COLOR_BACKGROUND)
  }

  @Builder
  sidebar() {
    Column() {
      Column() {
        Text('题库')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
        Text('当前账号的真实题目')
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ top: 4 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 12, right: 12, top: 20, bottom: 14 })

      Column({ space: 4 }) {
        this.scope_all_item()
        this.scope_inbox_item()
      }
      .width('100%')

      if (this.collection_nodes.length > 0) {
        Text('题集')
          .fontSize(11)
          .fontColor(TABLET_COLOR_MUTED)
          .width('100%')
          .padding({ left: 12, top: 16, bottom: 6 })

        Scroll() {
          Column({ space: 2 }) {
            ForEach(this.collection_nodes, (node: CollectionNode) => {
              this.collection_row(node)
            }, (node: CollectionNode): string => node.collection.collectionId)
          }
          .width('100%')
          .padding({ bottom: 12 })
        }
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Auto)
        .edgeEffect(EdgeEffect.Spring)
      } else {
        Blank()
      }

      if (this.collections_error.length > 0) {
        Row() {
          Image($r('app.media.bl_info'))
            .width(16)
            .height(16)
            .objectFit(ImageFit.Contain)
          Text('题集数量暂不可用')
            .fontSize(11)
            .fontColor(TABLET_COLOR_MUTED)
            .margin({ left: 6 })
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 16 })
      }
    }
    .width(TABLET_SIDEBAR_WIDTH)
    .height('100%')
    .padding({ left: 8, right: 8 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { right: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  scope_all_item() {
    Row() {
      Image($r('app.media.bl_list'))
        .width(20)
        .height(20)
        .objectFit(ImageFit.Contain)
      Text('全部题目')
        .fontSize(15)
        .fontWeight(this.scope_collection_id === null ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.scope_collection_id === null ? TABLET_COLOR_PRIMARY : TABLET_COLOR_TEXT)
        .layoutWeight(1)
        .margin({ left: 10 })
    }
    .width('100%')
    .height(TABLET_CONTROL_SIZE)
    .padding({ left: 12, right: 12 })
    .borderRadius(TABLET_RADIUS_SMALL)
    .backgroundColor(this.scope_collection_id === null ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .onClick(() => {
      this.change_scope(null);
    })
  }

  @Builder
  scope_inbox_item() {
    Row() {
      Image($r('app.media.bl_inbox'))
        .width(20)
        .height(20)
        .objectFit(ImageFit.Contain)
      Text('待整理')
        .fontSize(15)
        .fontWeight(this.scope_collection_id === INBOX_COLLECTION_ID ?
          FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.scope_collection_id === INBOX_COLLECTION_ID ?
          TABLET_COLOR_PRIMARY : TABLET_COLOR_TEXT)
        .layoutWeight(1)
        .margin({ left: 10 })
      if (this.inbox_count >= 0) {
        Text(`${this.inbox_count}`)
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
      }
    }
    .width('100%')
    .height(TABLET_CONTROL_SIZE)
    .padding({ left: 12, right: 12 })
    .borderRadius(TABLET_RADIUS_SMALL)
    .backgroundColor(this.scope_collection_id === INBOX_COLLECTION_ID ?
      TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .onClick(() => {
      this.change_scope(INBOX_COLLECTION_ID);
    })
  }

  @Builder
  collection_row(node: CollectionNode) {
    Row() {
      if (node.has_children) {
        Row() {
          Image($r('app.media.bl_chevron_right'))
            .width(14)
            .height(14)
            .objectFit(ImageFit.Contain)
            .rotate({ angle: this.is_collapsed(node.collection.collectionId) ? 0 : 90 })
        }
        .width(22)
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .onClick(() => {
          this.toggle_collection(node.collection.collectionId);
        })
      } else {
        Text('')
          .width(22)
          .height(1)
      }

      Row() {
        Text(node.collection.name)
          .fontSize(14)
          .fontWeight(this.scope_collection_id === node.collection.collectionId ?
            FontWeight.Medium : FontWeight.Normal)
          .fontColor(this.scope_collection_id === node.collection.collectionId ?
            TABLET_COLOR_PRIMARY : TABLET_COLOR_TEXT)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        Text(`${node.collection.questionCount}`)
          .fontSize(11)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ left: 6 })
      }
      .layoutWeight(1)
      .height('100%')
      .padding({ left: 4, right: 8 })
    }
    .width('100%')
    .height(38)
    .padding({ left: 6 + Math.min(node.depth, 4) * 14, right: 6 })
    .borderRadius(TABLET_RADIUS_SMALL)
    .backgroundColor(this.scope_collection_id === node.collection.collectionId ?
      TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .onClick(() => {
      this.change_scope(node.collection.collectionId);
    })
  }

  @Builder
  list_panel() {
    Column() {
      this.list_header()
      if (this.loading && !this.loaded_once) {
        this.loading_state()
      } else if (this.error_text.length > 0 && this.items.length === 0) {
        this.failure_state()
      } else {
        if (this.error_text.length > 0) {
          this.error_bar()
        }
        if (this.items.length === 0) {
          this.empty_state()
        } else {
          this.question_list()
        }
        this.pager()
      }
    }
    .layoutWeight(1)
    .height('100%')
    .padding({ left: TABLET_PAGE_PADDING, right: TABLET_PAGE_PADDING, top: 16 })
  }

  @Builder
  list_header() {
    Row() {
      Column() {
        Text(this.scope_title())
          .fontSize(26)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
        Text(this.scope_meta())
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Button() {
        Row() {
          Image($r('app.media.bl_refresh'))
            .width(18)
            .height(18)
            .objectFit(ImageFit.Contain)
          Text(this.loading ? '刷新中…' : '刷新')
            .fontSize(13)
            .fontColor(TABLET_COLOR_PRIMARY)
            .margin({ left: 6 })
        }
      }
      .type(ButtonType.Normal)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 14, right: 14 })
      .backgroundColor(TABLET_COLOR_CARD)
      .border({ width: 1, color: TABLET_COLOR_BORDER })
      .borderRadius(TABLET_RADIUS_SMALL)
      .enabled(!this.loading)
      .opacity(this.loading ? 0.5 : 1)
      .onClick(() => {
        this.refresh_all();
      })
    }
    .width('100%')
    .margin({ bottom: 14 })
  }

  @Builder
  question_list() {
    Scroll() {
      Column({ space: 10 }) {
        ForEach(this.items, (item: QuestionSummary) => {
          this.question_row(item)
        }, (item: QuestionSummary): string => item.questionId)
      }
      .width('100%')
      .padding({ bottom: 16 })
    }
    .layoutWeight(1)
    .width('100%')
    .scrollBar(BarState.Auto)
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  question_row(item: QuestionSummary) {
    Row() {
      Column() {
        Text(item.summary)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        Row() {
          Text(learning_subject_label(item.subject))
            .fontSize(11)
            .fontColor(TABLET_COLOR_PRIMARY)
            .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
            .borderRadius(6)
            .padding({ left: 7, right: 7, top: 2, bottom: 2 })
          if (item.collectionId === INBOX_COLLECTION_ID) {
            Text('待整理')
              .fontSize(11)
              .fontColor(TABLET_COLOR_MUTED)
              .backgroundColor(ANSWER_IDLE_BACKGROUND)
              .borderRadius(6)
              .padding({ left: 7, right: 7, top: 2, bottom: 2 })
              .margin({ left: 6 })
          }
          Text(this.answer_state_label(item.answerState))
            .fontSize(11)
            .fontColor(this.answer_state_foreground(item.answerState))
            .backgroundColor(this.answer_state_background(item.answerState))
            .borderRadius(6)
            .padding({ left: 7, right: 7, top: 2, bottom: 2 })
            .margin({ left: 6 })
          Text(format_created_at(item.createdAt))
            .fontSize(11)
            .fontColor(TABLET_COLOR_MUTED)
            .margin({ left: 8 })
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')
        .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Image($r('app.media.bl_chevron_right'))
        .width(18)
        .height(18)
        .objectFit(ImageFit.Contain)
        .margin({ left: 8 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 13, bottom: 13 })
    .backgroundColor(TABLET_COLOR_CARD)
    .borderRadius(TABLET_RADIUS)
    .border({ width: 1, color: TABLET_COLOR_BORDER })
    .onClick(() => {
      this.open_detail(item);
    })
  }

  @Builder
  pager() {
    Row() {
      Text(`第 ${this.page} / ${this.total_pages()} 页`)
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
      Blank()
      Button('上一页')
        .type(ButtonType.Normal)
        .fontSize(13)
        .fontColor(TABLET_COLOR_PRIMARY)
        .height(36)
        .padding({ left: 16, right: 16 })
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(TABLET_RADIUS_SMALL)
        .enabled(!this.loading && this.page > 1)
        .opacity(!this.loading && this.page > 1 ? 1 : 0.4)
        .onClick(() => {
          this.go_page(this.page - 1);
        })
      Button('下一页')
        .type(ButtonType.Normal)
        .fontSize(13)
        .fontColor(TABLET_COLOR_PRIMARY)
        .height(36)
        .padding({ left: 16, right: 16 })
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(TABLET_RADIUS_SMALL)
        .margin({ left: 8 })
        .enabled(!this.loading && this.page < this.total_pages())
        .opacity(!this.loading && this.page < this.total_pages() ? 1 : 0.4)
        .onClick(() => {
          this.go_page(this.page + 1);
        })
    }
    .width('100%')
    .height(56)
  }

  @Builder
  loading_state() {
    Column({ space: 10 }) {
      LoadingProgress()
        .width(36)
        .height(36)
        .color(TABLET_COLOR_PRIMARY)
      Text('正在加载题库…')
        .fontSize(13)
        .fontColor(TABLET_COLOR_TEXT)
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  failure_state() {
    Column({ space: 12 }) {
      Image($r('app.media.bl_warning'))
        .width(42)
        .height(42)
        .objectFit(ImageFit.Contain)
      Text('题库加载失败')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
      Text(this.error_text)
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Button('重试')
        .type(ButtonType.Normal)
        .fontSize(14)
        .fontColor(TABLET_COLOR_CARD)
        .height(TABLET_CONTROL_SIZE)
        .padding({ left: 24, right: 24 })
        .backgroundColor(TABLET_COLOR_PRIMARY)
        .borderRadius(TABLET_RADIUS_SMALL)
        .margin({ top: 4 })
        .onClick(() => {
          this.refresh_all();
        })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
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
      Button('重试')
        .type(ButtonType.Normal)
        .fontSize(12)
        .fontColor(TABLET_COLOR_DANGER)
        .height(32)
        .padding({ left: 12, right: 12 })
        .backgroundColor(Color.Transparent)
        .onClick(() => {
          this.refresh_all();
        })
    }
    .width('100%')
    .padding({ left: 12, right: 4, top: 6, bottom: 6 })
    .backgroundColor(TABLET_COLOR_DANGER_SOFT)
    .borderRadius(TABLET_RADIUS_SMALL)
    .margin({ bottom: 10 })
  }

  @Builder
  empty_state() {
    Column({ space: 8 }) {
      Image(this.scope_collection_id === INBOX_COLLECTION_ID ?
        $r('app.media.bl_inbox') : $r('app.media.bl_list'))
        .width(40)
        .height(40)
        .objectFit(ImageFit.Contain)
        .opacity(0.7)
      Text(this.scope_collection_id === INBOX_COLLECTION_ID ?
        '待整理里还没有题目' : '还没有题目')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
      Text(this.scope_collection_id === INBOX_COLLECTION_ID ?
        '新确认的题目会进入待整理。' : '当前账号还没有可浏览的题目。')
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  detail_panel() {
    Column() {
      this.detail_header()
      if (this.detail_loading) {
        this.detail_loading_state()
      } else if (this.detail_error.length > 0) {
        this.detail_failure_state()
      } else if (this.detail !== null) {
        AnswerWorkspace({
          question: this.detail,
          can_save: this.save_enabled(),
          can_complete: this.complete_enabled(),
          can_continue: this.continue_enabled(),
          show_continue: this.canvas_completed,
          pending_action: this.pending_action,
          feedback_text: this.feedback_text,
          feedback_error: this.feedback_error,
          controller: this.canvas_controller,
          on_state_change: (state: CanvasWorkspaceState) => {
            this.apply_canvas_state(state);
          },
          on_session_expired: () => {
            this.expire_session();
          },
          on_save: () => {
            this.run_answer_action('save');
          },
          on_complete: () => {
            this.run_answer_action('complete');
          },
          on_continue: () => {
            this.run_answer_action('continue');
          }
        })
          .width('100%')
          .layoutWeight(1)
      }
    }
    .width('100%')
    .layoutWeight(1)
    .backgroundColor(TABLET_COLOR_BACKGROUND)
  }

  @Builder
  detail_header() {
    Row() {
      Button() {
        Row() {
          Image($r('app.media.bl_back'))
            .width(18)
            .height(18)
            .objectFit(ImageFit.Contain)
          Text('题库')
            .fontSize(13)
            .fontColor(TABLET_COLOR_TEXT)
            .margin({ left: 4 })
        }
      }
      .type(ButtonType.Normal)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 10, right: 14 })
      .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
      .borderRadius(TABLET_RADIUS_SMALL)
      .enabled(!this.closing)
      .opacity(this.closing ? 0.5 : 1)
      .onClick(() => {
        this.request_close_detail();
      })

      Column() {
        Text(this.open_summary.length > 0 ? this.open_summary : '题目详情')
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        if (this.detail !== null) {
          Row() {
            Text(learning_subject_label(this.detail_subject()))
              .fontSize(11)
              .fontColor(TABLET_COLOR_PRIMARY)
              .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
              .borderRadius(6)
              .padding({ left: 7, right: 7, top: 2, bottom: 2 })
            Text(this.answer_state_label(this.detail_answer_state()))
              .fontSize(11)
              .fontColor(this.answer_state_foreground(this.detail_answer_state()))
              .backgroundColor(this.answer_state_background(this.detail_answer_state()))
              .borderRadius(6)
              .padding({ left: 7, right: 7, top: 2, bottom: 2 })
              .margin({ left: 6 })
            Text(format_created_at(this.detail_created_at()))
              .fontSize(11)
              .fontColor(TABLET_COLOR_MUTED)
              .margin({ left: 8 })
          }
          .margin({ top: 3 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 12, right: 12 })
    }
    .width('100%')
    .height(64)
    .padding({ left: 20, right: 20 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  detail_loading_state() {
    Column({ space: 10 }) {
      LoadingProgress()
        .width(36)
        .height(36)
        .color(TABLET_COLOR_PRIMARY)
      Text('正在加载题目详情…')
        .fontSize(13)
        .fontColor(TABLET_COLOR_TEXT)
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  detail_failure_state() {
    Column({ space: 12 }) {
      Image($r('app.media.bl_warning'))
        .width(42)
        .height(42)
        .objectFit(ImageFit.Contain)
      Text('题目详情加载失败')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
      Text(this.detail_error)
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Row({ space: 10 }) {
        Button('重试')
          .type(ButtonType.Normal)
          .fontSize(14)
          .fontColor(TABLET_COLOR_CARD)
          .height(TABLET_CONTROL_SIZE)
          .padding({ left: 24, right: 24 })
          .backgroundColor(TABLET_COLOR_PRIMARY)
          .borderRadius(TABLET_RADIUS_SMALL)
          .onClick(() => {
            this.retry_detail();
          })
        Button('返回题库')
          .type(ButtonType.Normal)
          .fontSize(14)
          .fontColor(TABLET_COLOR_PRIMARY)
          .height(TABLET_CONTROL_SIZE)
          .padding({ left: 24, right: 24 })
          .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
          .borderRadius(TABLET_RADIUS_SMALL)
          .onClick(() => {
            this.request_close_detail();
          })
      }
      .margin({ top: 4 })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(24)
  }

  private save_enabled(): boolean {
    return this.canvas_ready && !this.canvas_busy && !this.canvas_completed &&
      this.pending_action.length === 0;
  }

  private complete_enabled(): boolean {
    return this.canvas_can_complete && !this.canvas_busy && this.pending_action.length === 0;
  }

  private continue_enabled(): boolean {
    return this.canvas_completed && !this.canvas_busy && this.pending_action.length === 0;
  }

  private scope_title(): string {
    if (this.scope_collection_id === null) {
      return '全部题目';
    }
    if (this.scope_collection_id === INBOX_COLLECTION_ID) {
      return '待整理';
    }
    for (let index: number = 0; index < this.collections.length; index++) {
      if (this.collections[index].collectionId === this.scope_collection_id) {
        return this.collections[index].name;
      }
    }
    return '题集';
  }

  private scope_meta(): string {
    if (!this.loaded_once) {
      return '正在读取…';
    }
    return `共 ${this.total} 题`;
  }

  private total_pages(): number {
    if (this.total <= 0) {
      return 1;
    }
    return Math.ceil(this.total / this.page_size);
  }

  private detail_subject(): LearningSubject {
    const detail: QuestionDetail | null = this.detail;
    if (detail === null) {
      return LearningSubject.OTHER;
    }
    return detail.subject;
  }

  private detail_created_at(): string {
    const detail: QuestionDetail | null = this.detail;
    return detail === null ? '' : detail.createdAt;
  }

  private detail_answer_state(): AnswerState {
    const detail: QuestionDetail | null = this.detail;
    return detail === null ? AnswerState.NOT_STARTED : detail.answerState;
  }

  private is_collapsed(collection_id: string): boolean {
    return this.collapsed_ids.includes(collection_id);
  }

  private toggle_collection(collection_id: string): void {
    const next: string[] = [];
    let found: boolean = false;
    for (let index: number = 0; index < this.collapsed_ids.length; index++) {
      if (this.collapsed_ids[index] === collection_id) {
        found = true;
      } else {
        next.push(this.collapsed_ids[index]);
      }
    }
    if (!found) {
      next.push(collection_id);
    }
    this.collapsed_ids = next;
    this.rebuild_collection_nodes();
  }

  private rebuild_collection_nodes(): void {
    const known_ids: Set<string> = new Set<string>();
    for (let index: number = 0; index < this.collections.length; index++) {
      known_ids.add(this.collections[index].collectionId);
    }
    const by_parent: Map<string, QuestionCollection[]> = new Map<string, QuestionCollection[]>();
    for (let index: number = 0; index < this.collections.length; index++) {
      const collection: QuestionCollection = this.collections[index];
      if (collection.collectionId === INBOX_COLLECTION_ID) {
        continue;
      }
      let parent_key: string = ROOT_COLLECTION_KEY;
      const parent_id: string | null = collection.parentCollectionId;
      if (parent_id !== null && parent_id !== INBOX_COLLECTION_ID &&
        known_ids.has(parent_id) && parent_id !== collection.collectionId) {
        parent_key = parent_id;
      }
      const bucket: QuestionCollection[] | undefined = by_parent.get(parent_key);
      if (bucket === undefined) {
        by_parent.set(parent_key, [collection]);
      } else {
        bucket.push(collection);
      }
    }
    const nodes: CollectionNode[] = [];
    this.append_collection_nodes(nodes, by_parent, ROOT_COLLECTION_KEY, 0, new Set<string>());
    this.collection_nodes = nodes;
  }

  private append_collection_nodes(out: CollectionNode[], by_parent: Map<string, QuestionCollection[]>,
    parent_key: string, depth: number, visited: Set<string>): void {
    const children: QuestionCollection[] | undefined = by_parent.get(parent_key);
    if (children === undefined) {
      return;
    }
    for (let index: number = 0; index < children.length; index++) {
      const collection: QuestionCollection = children[index];
      if (visited.has(collection.collectionId)) {
        continue;
      }
      visited.add(collection.collectionId);
      const grand_children: QuestionCollection[] | undefined = by_parent.get(collection.collectionId);
      const has_children: boolean = grand_children !== undefined && grand_children.length > 0;
      out.push(new CollectionNode(collection, depth, has_children));
      if (has_children && !this.is_collapsed(collection.collectionId)) {
        this.append_collection_nodes(out, by_parent, collection.collectionId, depth + 1, visited);
      }
    }
  }

  private change_scope(next: string | null): void {
    if (this.loading || this.scope_collection_id === next) {
      return;
    }
    this.scope_collection_id = next;
    this.page = 1;
    this.items = [];
    this.total = 0;
    this.loaded_once = false;
    this.error_text = '';
    this.refresh_all();
  }

  private async refresh_all(): Promise<void> {
    this.list_seq += 1;
    const seq: number = this.list_seq;
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.expire_session();
      return;
    }
    this.loading = true;
    this.error_text = '';
    await this.load_collections(token, seq);
    if (!this.alive || seq !== this.list_seq || !authSession.matches_access_token(token)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    await this.load_questions(token, seq, this.page);
  }

  private async load_collections(token: string, seq: number): Promise<void> {
    try {
      const result: AuthResult<QuestionCollection[]> = await this.api.listCollections(token);
      if (!this.check_session(token, seq)) {
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (!result.isSuccess || result.data === null) {
        this.collections_error = this.failure_text(result.error);
        return;
      }
      this.collections = result.data;
      this.collections_error = '';
      this.inbox_count = this.inbox_count_of(this.collections);
      this.rebuild_collection_nodes();
    } catch (error) {
      if (!this.check_session(token, seq)) {
        return;
      }
      this.collections_error = '网络连接失败，请检查网络后重试';
    }
  }

  private async load_questions(token: string, seq: number, page: number): Promise<void> {
    try {
      const result: AuthResult<QuestionPage> = await this.api.listQuestions(token,
        new QuestionListQuery(page, PAGE_SIZE, this.scope_collection_id));
      if (!this.check_session(token, seq)) {
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
        this.loaded_once = true;
        return;
      }
      const data: QuestionPage = result.data;
      this.items = data.items;
      this.page = data.page;
      this.page_size = data.pageSize;
      this.total = data.total;
      this.error_text = '';
      this.loaded_once = true;
    } catch (error) {
      if (!this.check_session(token, seq)) {
        return;
      }
      this.error_text = '网络连接失败，请检查网络后重试';
      this.loaded_once = true;
    } finally {
      if (this.alive && seq === this.list_seq) {
        this.loading = false;
      }
    }
  }

  private async go_page(next: number): Promise<void> {
    if (this.loading || next < 1 || next > this.total_pages()) {
      return;
    }
    this.list_seq += 1;
    const seq: number = this.list_seq;
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.expire_session();
      return;
    }
    this.loading = true;
    this.error_text = '';
    await this.load_questions(token, seq, next);
  }

  private open_detail(item: QuestionSummary): void {
    this.reset_answer_view();
    this.open_question_id = item.questionId;
    this.open_summary = item.summary;
    this.detail = null;
    this.detail_error = '';
    this.detail_loading = true;
    this.detail_seq += 1;
    this.run_detail(item.questionId, this.detail_seq);
  }

  private retry_detail(): void {
    if (this.open_question_id.length === 0) {
      return;
    }
    this.reset_answer_view();
    this.detail = null;
    this.detail_error = '';
    this.detail_loading = true;
    this.detail_seq += 1;
    this.run_detail(this.open_question_id, this.detail_seq);
  }

  private request_close_detail(): void {
    if (this.closing) {
      return;
    }
    if (!this.canvas_ready) {
      this.close_detail();
      return;
    }
    this.closing = true;
    this.flush_then_close();
  }

  private async flush_then_close(): Promise<void> {
    let result: AuthResult<boolean> | null = null;
    try {
      result = await this.canvas_controller.flushLocal();
    } catch (error) {
      result = null;
    }
    this.closing = false;
    if (!this.alive) {
      return;
    }
    if (result === null || !result.isSuccess) {
      this.feedback_text = result === null ? '本机草稿保存失败，请重试后再离开' :
        this.answer_failure_text(result.error);
      this.feedback_error = true;
      return;
    }
    this.close_detail();
  }

  private close_detail(): void {
    this.detail_seq += 1;
    this.open_question_id = '';
    this.open_summary = '';
    this.detail = null;
    this.detail_error = '';
    this.detail_loading = false;
    this.reset_answer_view();
  }

  private reset_answer_view(): void {
    this.canvas_ready = false;
    this.canvas_busy = false;
    this.canvas_can_complete = false;
    this.canvas_completed = false;
    this.pending_action = '';
    this.feedback_text = '';
    this.feedback_error = false;
    this.closing = false;
  }

  private apply_canvas_state(state: CanvasWorkspaceState): void {
    if (!this.alive) {
      return;
    }
    this.canvas_ready = state.isReady;
    this.canvas_busy = state.isBusy;
    this.canvas_can_complete = state.canComplete;
    this.canvas_completed = state.isCompleted;
    if (state.errorMessage.length > 0 && this.pending_action.length === 0) {
      this.feedback_text = state.errorMessage;
      this.feedback_error = true;
    }
  }

  private async run_answer_action(action: string): Promise<void> {
    if (this.pending_action.length > 0 || this.detail === null || !this.alive) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.expire_session();
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    this.pending_action = action;
    this.feedback_text = '';
    this.feedback_error = false;
    let result: AuthResult<SolutionDetail> | null = null;
    try {
      if (action === 'save') {
        result = await this.canvas_controller.save();
      } else if (action === 'complete') {
        result = await this.canvas_controller.markCompleted();
      } else {
        result = await this.canvas_controller.continueWriting();
      }
    } catch (error) {
      if (this.alive) {
        this.pending_action = '';
        this.feedback_text = '操作未完成，请重试';
        this.feedback_error = true;
      }
      return;
    }
    this.pending_action = '';
    if (!this.alive || !authSession.matches_access_token(token)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    if (result === null) {
      this.feedback_text = '操作未完成，请重试';
      this.feedback_error = true;
      return;
    }
    if (result.error !== null && result.error.is_unauthorized()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    if (!result.isSuccess || result.data === null) {
      this.feedback_text = this.answer_failure_text(result.error);
      this.feedback_error = true;
      return;
    }
    this.feedback_error = false;
    this.feedback_text = this.success_text(action, result.data);
    await this.refresh_answer_views(this.open_question_id);
  }

  private async refresh_answer_views(question_id: string): Promise<void> {
    if (question_id.length === 0) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null || !this.alive) {
      return;
    }
    this.detail_seq += 1;
    const detail_seq: number = this.detail_seq;
    try {
      const result: AuthResult<QuestionDetail> = await this.api.getQuestion(token, question_id);
      if (!this.check_detail_session(token, detail_seq)) {
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (result.isSuccess && result.data !== null) {
        this.detail = result.data;
      }
    } catch (error) {
      return;
    }
    if (!this.alive || !authSession.matches_access_token(token)) {
      return;
    }
    this.list_seq += 1;
    const list_seq: number = this.list_seq;
    await this.load_questions(token, list_seq, this.page);
  }

  private success_text(action: string, solution: SolutionDetail): string {
    if (action === 'complete') {
      return '本题已完成。';
    }
    if (action === 'continue') {
      return '已恢复作答，可以继续书写。';
    }
    const file_ref: string | null = solution.fileRef;
    if (file_ref === null || file_ref.length === 0) {
      return '已保存到本机草稿，稍后会自动同步。';
    }
    return '已保存。';
  }

  private answer_failure_text(error: AuthError | null): string {
    if (error === null) {
      return '操作未完成，请重试';
    }
    if (error.is_network()) {
      return '网络连接失败，本机草稿已保留，请重试';
    }
    if (error.is_service_unavailable()) {
      return '服务暂时不可用，请稍后重试';
    }
    if (error.httpStatus === 409) {
      return '题目状态已变化，请刷新后重试';
    }
    if (error.message.length > 0) {
      return error.message;
    }
    return '操作未完成，请重试';
  }

  private answer_state_label(state: AnswerState): string {
    if (state === AnswerState.COMPLETED) {
      return '已完成';
    }
    if (state === AnswerState.IN_PROGRESS) {
      return '作答中';
    }
    return '未开始';
  }

  private answer_state_foreground(state: AnswerState): string {
    if (state === AnswerState.COMPLETED) {
      return ANSWER_DONE_FOREGROUND;
    }
    if (state === AnswerState.IN_PROGRESS) {
      return TABLET_COLOR_PRIMARY;
    }
    return TABLET_COLOR_MUTED;
  }

  private answer_state_background(state: AnswerState): string {
    if (state === AnswerState.COMPLETED) {
      return ANSWER_DONE_BACKGROUND;
    }
    if (state === AnswerState.IN_PROGRESS) {
      return TABLET_COLOR_PRIMARY_SOFT;
    }
    return ANSWER_IDLE_BACKGROUND;
  }

  private async run_detail(question_id: string, seq: number): Promise<void> {
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.expire_session();
      return;
    }
    try {
      const result: AuthResult<QuestionDetail> = await this.api.getQuestion(token, question_id);
      if (!this.check_detail_session(token, seq)) {
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (!result.isSuccess || result.data === null) {
        this.detail_error = this.detail_failure_text(result.error);
        return;
      }
      this.detail = result.data;
    } catch (error) {
      if (!this.check_detail_session(token, seq)) {
        return;
      }
      this.detail_error = '网络连接失败，请检查网络后重试';
    } finally {
      if (this.alive && seq === this.detail_seq) {
        this.detail_loading = false;
      }
    }
  }

  private check_session(token: string, seq: number): boolean {
    if (!this.alive || seq !== this.list_seq || !authSession.matches_access_token(token)) {
      return false;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return false;
    }
    return true;
  }

  private check_detail_session(token: string, seq: number): boolean {
    if (!this.alive || seq !== this.detail_seq || !authSession.matches_access_token(token)) {
      return false;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return false;
    }
    return true;
  }

  private expire_session(): void {
    this.alive = false;
    this.list_seq += 1;
    this.detail_seq += 1;
    this.items = [];
    this.total = 0;
    this.collections = [];
    this.collection_nodes = [];
    this.inbox_count = -1;
    this.detail = null;
    this.open_question_id = '';
    this.open_summary = '';
    this.error_text = '';
    this.detail_error = '';
    this.reset_answer_view();
    this.on_session_expired();
  }

  private inbox_count_of(collections: QuestionCollection[]): number {
    for (let index: number = 0; index < collections.length; index++) {
      if (collections[index].collectionId === INBOX_COLLECTION_ID) {
        return collections[index].questionCount;
      }
    }
    return -1;
  }

  private failure_text(error: AuthError | null): string {
    if (error === null) {
      return '请求失败，请稍后重试';
    }
    if (error.is_network()) {
      return '网络连接失败，请检查网络后重试';
    }
    if (error.is_service_unavailable()) {
      return '服务暂时不可用，请稍后重试';
    }
    if (error.httpStatus === 404) {
      return '当前账号下找不到该题集';
    }
    if (error.kind === AuthFailureKind.PROTOCOL) {
      return '服务响应异常，请稍后重试';
    }
    return error.message;
  }

  private detail_failure_text(error: AuthError | null): string {
    if (error === null) {
      return '请求失败，请稍后重试';
    }
    if (error.is_network()) {
      return '网络连接失败，请检查网络后重试';
    }
    if (error.is_service_unavailable()) {
      return '服务暂时不可用，请稍后重试';
    }
    if (error.httpStatus === 404) {
      return '题目不存在或不属于当前账号';
    }
    if (error.kind === AuthFailureKind.PROTOCOL) {
      return '题目详情响应异常，请稍后重试';
    }
    return error.message;
  }
}
