import {
  AnswerState,
  AuthError,
  AuthFailureKind,
  AuthResult,
  INBOX_COLLECTION_ID,
  LearningApi,
  QuestionCollection,
  QuestionDetail,
  QuestionListQuery,
  QuestionPage,
  QuestionSummary,
  SolutionDetail,
  authSession
} from 'common';
import {
  TABLET_ANSWER_HEADER_HEIGHT,
  TABLET_COLOR_BACKGROUND,
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_DANGER,
  TABLET_COLOR_DANGER_SOFT,
  TABLET_COLOR_MUTED,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_SIDEBAR,
  TABLET_COLOR_SUCCESS,
  TABLET_COLOR_TEXT,
  TABLET_CONTROL_SIZE,
  TABLET_PAGE_PADDING,
  TABLET_RADIUS_SMALL
} from '../theme/TabletTheme';
import { AnswerWorkspace } from './AnswerWorkspace';
import { CanvasWorkspaceState, TabletCanvasController } from '../features/canvas/TabletCanvasWorkspace';

import { SharedTabletTutorApi, TabletTutorBridge, TabletTutorPhase, TabletTutorState } from '../features/tutor/TabletTutorBridge';

const PAGE_SIZE: number = 20;
const ROOT_COLLECTION_KEY: string = '';

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

/**
 * 平板题库：左侧层级题集树，右侧当前范围题目列表，点题进入沉浸式答题页。
 *
 * <p>答案状态、保存结果全部来自服务端与 C2 画布；本组件只做真实的加载、分页、
 * 会话守卫与答题动作调度。进入答题时通过 on_answer_mode_changed 隐藏全局导航，
 * 但本组件保持挂载，返回仍先经过 flushLocal 门禁。</p>
 */
@Component
export struct LibraryPane {
  @Prop @Watch('sync_tutor') active: boolean = false;
  @State tutor_phase: TabletTutorPhase = TabletTutorPhase.STOPPED;
  on_session_expired: () => void = () => {
  };
  on_answer_mode_changed: (active: boolean) => void = () => {
  };
  @State scope_collection_id: string | null = null;
  @State page: number = 1;
  @State page_size: number = PAGE_SIZE;
  @State total: number = 0;
  @State items: QuestionSummary[] = [];
  @State inbox_count: number = -1;
  @State all_question_count: number = -1;
  @State collection_nodes: CollectionNode[] = [];
  @State collapsed_ids: string[] = [];
  @State collections_error: string = '';
  @State loading: boolean = false;
  @State loaded_once: boolean = false;
  @State error_text: string = '';
  @State open_question_id: string = '';
  @State detail: QuestionDetail | null = null;
  @State detail_loading: boolean = false;
  @State detail_error: string = '';
  @State current_answer_state: AnswerState = AnswerState.NOT_STARTED;
  @State canvas_ready: boolean = false;
  @State canvas_busy: boolean = false;
  @State canvas_can_complete: boolean = false;
  @State canvas_completed: boolean = false;
  @State canvas_has_unsaved_changes: boolean = false;
  @State canvas_local_draft_saved: boolean = false;
  @State canvas_has_saved_solution: boolean = false;
  @State pending_action: string = '';
  @State feedback_text: string = '';
  @State feedback_error: boolean = false;
  @State closing: boolean = false;
  @State answer_mount_revision: number = 0;
  private api: LearningApi = LearningApi.create_default();
  private collections: QuestionCollection[] = [];
  private canvas_controller: TabletCanvasController = new TabletCanvasController();
  private list_seq: number = 0;
  private detail_seq: number = 0;
  private alive: boolean = false;
  private tutor_bridge: TabletTutorBridge | null = null;
  private tutor_revision: number = 0;
  private tutor_question_id: string = '';
  private tutor_token: string = '';
  private tutor_started: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.refresh_all();
    this.sync_tutor();
  }

  aboutToDisappear(): void {
    this.dispose_tutor();
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
        Text('我的习题集')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
        Text(this.sidebar_caption())
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ top: 5 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 24, right: 20, top: 24, bottom: 16 })

      Scroll() {
        Column({ space: 6 }) {
          this.scope_all_item()

          ForEach(this.collection_nodes, (node: CollectionNode) => {
            this.collection_row(node)
          }, (node: CollectionNode): string => {
            return `${node.collection.collectionId}:${node.collection.questionCount}:` +
              `${node.depth}:${this.is_collapsed(node.collection.collectionId)}:` +
              `${this.scope_collection_id === node.collection.collectionId}`;
          })

          this.scope_inbox_item()
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 20 })
      }
      .layoutWeight(1)
      .width('100%')
      .align(Alignment.TopStart)
      .scrollBar(BarState.Auto)
      .edgeEffect(EdgeEffect.Spring)

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
        .padding({ left: 24, right: 16, top: 12, bottom: 16 })
        .border({ width: { top: 1 }, color: TABLET_COLOR_BORDER })
      }
    }
    .width('25%')
    .constraintSize({ minWidth: 224, maxWidth: 320 })
    .height('100%')
    .backgroundColor(TABLET_COLOR_SIDEBAR)
    .border({ width: { right: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  scope_all_item() {
    Row() {
      Image($r('app.media.bl_book_cream'))
        .width(40)
        .height(54)
        .objectFit(ImageFit.Contain)
      Text('全部题目')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .margin({ left: 12 })
      if (this.all_question_count >= 0 && this.collections_error.length === 0) {
        Text(`${this.all_question_count} 题`)
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ left: 8 })
      }
    }
    .width('100%')
    .height(76)
    .padding({ left: 12, right: 12 })
    .borderRadius(12)
    .backgroundColor(this.scope_collection_id === null ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .onClick(() => {
      this.change_scope(null);
    })
  }

  @Builder
  scope_inbox_item() {
    Row() {
      Image($r('app.media.bl_collection_folder'))
        .width(40)
        .height(54)
        .objectFit(ImageFit.Contain)
      Text('待整理')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .margin({ left: 12 })
      if (this.inbox_count >= 0) {
        Text(`${this.inbox_count} 题`)
          .fontSize(12)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ left: 8 })
      }
    }
    .width('100%')
    .height(76)
    .padding({ left: 12, right: 12 })
    .borderRadius(12)
    .backgroundColor(this.scope_collection_id === INBOX_COLLECTION_ID ?
      TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .onClick(() => {
      this.change_scope(INBOX_COLLECTION_ID);
    })
  }

  @Builder
  collection_row(node: CollectionNode) {
    Row() {
      Image(this.collection_cover(node.collection.collectionId))
        .width(node.depth > 0 ? 32 : 40)
        .height(node.depth > 0 ? 44 : 54)
        .objectFit(ImageFit.Contain)

      Text(node.collection.name)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .margin({ left: node.depth > 0 ? 8 : 12 })

      Text(`${node.collection.questionCount}`)
        .fontSize(12)
        .fontColor(TABLET_COLOR_MUTED)
        .margin({ left: 8 })

      if (node.has_children) {
        Button() {
          Image($r('app.media.bl_chevron_right'))
            .width(16)
            .height(16)
            .objectFit(ImageFit.Contain)
            .rotate({ angle: this.is_collapsed(node.collection.collectionId) ? 0 : 90 })
        }
        .type(ButtonType.Normal)
        .width(TABLET_CONTROL_SIZE)
        .height(TABLET_CONTROL_SIZE)
        .padding(0)
        .backgroundColor(Color.Transparent)
        .accessibilityText((this.is_collapsed(node.collection.collectionId) ? '展开' : '收起') +
          node.collection.name)
        .onClick(() => {
          this.toggle_collection(node.collection.collectionId);
        })
      }
    }
    .width('100%')
    .height(76)
    .padding({ left: 12 + Math.min(node.depth, 3) * 8, right: node.has_children ? 0 : 12 })
    .borderRadius(12)
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
        if (this.total_pages() > 1) {
          this.pager()
        }
      }
    }
    .layoutWeight(1)
    .height('100%')
    .padding({ left: TABLET_PAGE_PADDING, right: TABLET_PAGE_PADDING })
    .backgroundColor(TABLET_COLOR_CARD)
  }

  @Builder
  list_header() {
    Row() {
      Image(this.collection_cover(this.scope_collection_id))
        .width(72)
        .height(96)
        .objectFit(ImageFit.Contain)
        .margin({ right: 22 })

      Column() {
        Text(this.scope_title())
          .fontSize(30)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        Text(this.scope_meta())
          .fontSize(14)
          .fontColor(TABLET_COLOR_MUTED)
          .margin({ top: 10 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Button() {
        Row() {
          Image($r('app.media.bl_refresh'))
            .width(16)
            .height(16)
            .objectFit(ImageFit.Contain)
          Text(this.loading ? '刷新中…' : '刷新')
            .fontSize(12)
            .fontColor(TABLET_COLOR_MUTED)
            .margin({ left: 6 })
        }
      }
      .type(ButtonType.Normal)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 12, right: 12 })
      .backgroundColor(Color.Transparent)
      .border({ width: 1, color: TABLET_COLOR_BORDER })
      .borderRadius(TABLET_RADIUS_SMALL)
      .margin({ left: 20 })
      .enabled(!this.loading)
      .opacity(this.loading ? 0.5 : 1)
      .onClick(() => {
        this.refresh_all();
      })
    }
    .width('100%')
    .constraintSize({ minHeight: 144 })
    .padding({ top: 24, bottom: 24 })
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  question_list() {
    Scroll() {
      Column() {
        ForEach(this.items, (item: QuestionSummary, index: number) => {
          this.question_row(item, index)
        }, (item: QuestionSummary): string => `${item.questionId}:${item.answerState}`)
      }
      .width('100%')
      .padding({ bottom: 24 })
    }
    .layoutWeight(1)
    .width('100%')
    .align(Alignment.TopStart)
    .scrollBar(BarState.Auto)
    .edgeEffect(EdgeEffect.Spring)
  }

  @Builder
  question_row(item: QuestionSummary, index: number) {
    Row() {
      Text(this.ordinal_text(index))
        .fontSize(27)
        .fontWeight(FontWeight.Normal)
        .fontColor(TABLET_COLOR_MUTED)
        .width(52)
        .margin({ top: 1 })

      Column() {
        Text(item.summary)
          .fontSize(19)
          .lineHeight(29)
          .fontWeight(FontWeight.Medium)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        Row({ space: 8 }) {
          if (item.answerState === AnswerState.COMPLETED) {
            Image($r('app.media.bl_state_completed'))
              .width(15)
              .height(15)
              .objectFit(ImageFit.Contain)
          } else if (item.answerState === AnswerState.IN_PROGRESS) {
            Circle()
              .width(12)
              .height(12)
              .fill(Color.Transparent)
              .stroke(TABLET_COLOR_PRIMARY)
              .strokeWidth(2)
          } else {
            Circle()
              .width(10)
              .height(10)
              .fill('#9AA9CC')
          }
          Text(this.answer_state_label(item.answerState))
            .fontSize(13)
            .fontColor(this.answer_state_foreground(item.answerState))
        }
        .width('100%')
        .margin({ top: 10 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Image($r('app.media.bl_arrow_blue'))
        .width(22)
        .height(22)
        .objectFit(ImageFit.Contain)
        .margin({ left: 24 })
        .alignSelf(ItemAlign.Center)
    }
    .alignItems(VerticalAlign.Top)
    .width('100%')
    .constraintSize({ minHeight: 112 })
    .padding({ top: 24, bottom: 24 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
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
        .fontSize(12)
        .fontColor(TABLET_COLOR_PRIMARY)
        .height(44)
        .padding({ left: 12, right: 12 })
        .backgroundColor(Color.Transparent)
        .enabled(!this.loading && this.page > 1)
        .opacity(!this.loading && this.page > 1 ? 1 : 0.4)
        .onClick(() => {
          this.go_page(this.page - 1);
        })
      Button('下一页')
        .type(ButtonType.Normal)
        .fontSize(12)
        .fontColor(TABLET_COLOR_PRIMARY)
        .height(44)
        .padding({ left: 12, right: 12 })
        .backgroundColor(Color.Transparent)
        .margin({ left: 4 })
        .enabled(!this.loading && this.page < this.total_pages())
        .opacity(!this.loading && this.page < this.total_pages() ? 1 : 0.4)
        .onClick(() => {
          this.go_page(this.page + 1);
        })
    }
    .width('100%')
    .height(48)
  }

  @Builder
  loading_state() {
    Column({ space: 10 }) {
      LoadingProgress()
        .width(36)
        .height(36)
        .color(TABLET_COLOR_PRIMARY)
      Text('正在加载题目…')
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
      Text('题目加载失败')
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
      if (this.detail_loading) {
        this.detail_fallback_header()
        this.detail_loading_state()
      } else if (this.detail_error.length > 0) {
        this.detail_fallback_header()
        this.detail_failure_state()
      } else if (this.detail !== null) {
        // 仅开题/重试/关闭时更换挂载代次；普通保存的请求代次不重建画布。
        ForEach([this.answer_mount_revision], (revision: number) => {
          AnswerWorkspace({
            question: this.detail,
            tutor_phase: this.tutor_phase,
            collection_name: this.scope_title(),
            question_ordinal: this.detail_ordinal(),
            answer_state: this.current_answer_state,
            save_hint: this.save_hint(),
            can_back: this.back_enabled(),
            can_save: this.save_enabled(),
            can_complete: this.complete_enabled(),
            can_continue: this.continue_enabled(),
            show_continue: this.canvas_completed,
            pending_action: this.pending_action,
            feedback_text: this.feedback_text,
            feedback_error: this.feedback_error,
            controller: this.canvas_controller,
            on_state_change: (state: CanvasWorkspaceState) => {
              if (this.answer_callback_current(revision)) {
                this.apply_canvas_state(state);
              }
            },
            on_session_expired: () => {
              if (this.alive && revision === this.answer_mount_revision) { this.expire_session(); }
            },
            on_back: () => {
              if (this.answer_callback_current(revision)) { this.request_close_detail(); }
            },
            on_save: () => {
              if (this.answer_callback_current(revision)) { this.run_answer_action('save'); }
            },
            on_complete: () => {
              if (this.answer_callback_current(revision)) { this.run_answer_action('complete'); }
            },
            on_continue: () => {
              if (this.answer_callback_current(revision)) { this.run_answer_action('continue'); }
            }
          })
            .width('100%')
            .layoutWeight(1)
        }, (revision: number): string => revision.toString())
      }
    }
    .width('100%')
    .layoutWeight(1)
    .backgroundColor(TABLET_COLOR_BACKGROUND)
  }

  @Builder
  detail_fallback_header() {
    Row() {
      Button() {
        Image($r('app.media.bl_back'))
          .width(20)
          .height(20)
          .objectFit(ImageFit.Contain)
      }
      .type(ButtonType.Normal)
      .width(44)
      .height(44)
      .padding(0)
      .backgroundColor(Color.Transparent)
      .borderRadius(22)
      .accessibilityText('返回题库')
      .enabled(this.back_enabled())
      .opacity(this.back_enabled() ? 1 : 0.4)
      .onClick(() => {
        this.request_close_detail();
      })

      Text('题目详情')
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor(TABLET_COLOR_TEXT)
        .margin({ left: 2, right: 12 })

      Blank()
    }
    .width('100%')
    .height(TABLET_ANSWER_HEADER_HEIGHT)
    .padding({ left: 16, right: 16 })
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
      this.pending_action.length === 0 && !this.closing;
  }

  private complete_enabled(): boolean {
    return this.canvas_can_complete && !this.canvas_busy && this.pending_action.length === 0 &&
      !this.closing;
  }

  private continue_enabled(): boolean {
    return this.canvas_completed && !this.canvas_busy && this.pending_action.length === 0 &&
      !this.closing;
  }

  private back_enabled(): boolean {
    return !this.closing && this.pending_action.length === 0 && !this.canvas_busy;
  }

  private save_hint(): string {
    if (this.pending_action === 'save') {
      return '正在保存…';
    }
    if (this.pending_action === 'complete') {
      return '正在完成…';
    }
    if (this.pending_action === 'continue') {
      return '正在恢复作答…';
    }
    if (this.canvas_busy) {
      return '处理中…';
    }
    if (this.canvas_completed) {
      return '已完成';
    }
    if (!this.canvas_ready) {
      return '';
    }
    if (this.canvas_has_unsaved_changes) {
      if (this.canvas_local_draft_saved) {
        return '本机已保存，待上传';
      }
      if (this.feedback_error) {
        return '';
      }
      return '正在保存草稿…';
    }
    if (this.canvas_has_saved_solution) {
      return '已保存到云端';
    }
    return '';
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

  private collection_cover(collection_id: string | null): Resource {
    if (collection_id === null) {
      return $r('app.media.bl_book_cream');
    }
    if (collection_id === INBOX_COLLECTION_ID) {
      return $r('app.media.bl_collection_folder');
    }
    let tone: number = 0;
    for (let index: number = 0; index < collection_id.length; index++) {
      tone = (tone * 31 + collection_id.charCodeAt(index)) % 3;
    }
    if (tone === 1) {
      return $r('app.media.bl_book_coral');
    }
    if (tone === 2) {
      return $r('app.media.bl_book_lavender');
    }
    return $r('app.media.bl_book_blue');
  }

  private scope_meta(): string {
    if (!this.loaded_once) {
      return '正在读取…';
    }
    return `共 ${this.total} 题`;
  }

  private sidebar_caption(): string {
    if (this.collections_error.length > 0) {
      return '题集列表暂不可用';
    }
    if (!this.loaded_once) {
      return '正在读取…';
    }
    let count: number = this.collections.length;
    for (let index: number = 0; index < this.collections.length; index++) {
      if (this.collections[index].collectionId === INBOX_COLLECTION_ID) {
        count -= 1;
        break;
      }
    }
    return `${count} 个题集`;
  }

  private total_pages(): number {
    if (this.total <= 0) {
      return 1;
    }
    return Math.ceil(this.total / this.page_size);
  }

  private ordinal_text(index: number): string {
    const ordinal: number = (this.page - 1) * this.page_size + index + 1;
    if (ordinal < 10) {
      return `0${ordinal}`;
    }
    return `${ordinal}`;
  }

  private detail_ordinal(): string {
    for (let index: number = 0; index < this.items.length; index++) {
      if (this.items[index].questionId === this.open_question_id) {
        return `第 ${(this.page - 1) * this.page_size + index + 1} 题`;
      }
    }
    return '';
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
      // questionCount 为直接归属题数，含待整理；只用于左侧真实汇总展示。
      this.all_question_count = this.collections.reduce((sum: number, collection: QuestionCollection): number =>
        sum + collection.questionCount, 0);
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
    this.current_answer_state = item.answerState;
    this.detail = null;
    this.detail_error = '';
    this.detail_loading = true;
    this.detail_seq += 1;
    this.on_answer_mode_changed(true);
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
    if (this.pending_action.length > 0 || this.canvas_busy) {
      this.feedback_text = '正在保存，请稍候再离开。';
      this.feedback_error = true;
      return;
    }
    if (!this.canvas_ready) {
      this.close_detail();
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.expire_session();
      return;
    }
    const question_id: string = this.open_question_id;
    const seq: number = this.detail_seq;
    this.closing = true;
    this.flush_then_close(token, question_id, seq);
  }

  private async flush_then_close(token: string, question_id: string, seq: number): Promise<void> {
    let result: AuthResult<boolean> | null = null;
    try {
      result = await this.canvas_controller.flushLocal();
    } catch (error) {
      result = null;
    }
    if (!this.is_detail_request_current(token, question_id, seq)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    this.closing = false;
    if (result === null || !result.isSuccess || result.data !== true) {
      this.feedback_text = '本机草稿尚未保存成功，请重试后再离开。';
      this.feedback_error = true;
      return;
    }
    this.close_detail();
  }

  private close_detail(): void {
    this.detail_seq += 1;
    this.open_question_id = '';
    this.detail = null;
    this.detail_error = '';
    this.detail_loading = false;
    this.reset_answer_view();
    this.on_answer_mode_changed(false);
  }

  private reset_answer_view(): void {
    this.answer_mount_revision += 1;
    this.dispose_tutor();
    this.canvas_ready = false;
    this.canvas_busy = false;
    this.canvas_can_complete = false;
    this.canvas_completed = false;
    this.canvas_has_unsaved_changes = false;
    this.canvas_local_draft_saved = false;
    this.canvas_has_saved_solution = false;
    this.current_answer_state = AnswerState.NOT_STARTED;
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
    this.canvas_has_unsaved_changes = state.hasUnsavedChanges;
    this.canvas_local_draft_saved = state.localDraftSaved;
    this.canvas_has_saved_solution = state.hasSavedSolution;
    if (state.errorMessage.length > 0 && this.pending_action.length === 0) {
      this.feedback_text = state.errorMessage;
      this.feedback_error = true;
    }
    this.sync_tutor();
  }

  private async run_answer_action(action: string): Promise<void> {
    if (!this.alive || this.closing || this.pending_action.length > 0 || this.canvas_busy ||
      this.detail === null || this.open_question_id.length === 0) {
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
    const question_id: string = this.open_question_id;
    this.detail_seq += 1;
    const seq: number = this.detail_seq;
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
      if (!this.is_detail_request_current(token, question_id, seq)) {
        return;
      }
      this.pending_action = '';
      this.feedback_text = '操作未完成，请重试';
      this.feedback_error = true;
      return;
    }
    if (!this.is_detail_request_current(token, question_id, seq)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    this.pending_action = '';
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
    if (!this.is_valid_solution(action, result.data, question_id)) {
      this.feedback_text = '服务返回的作答结果异常，请重试。';
      this.feedback_error = true;
      return;
    }
    this.current_answer_state = result.data.state;
    this.feedback_error = false;
    this.feedback_text = '';
    await this.refresh_answer_views(question_id, seq, token);
  }

  private is_detail_request_current(token: string, question_id: string, seq: number): boolean {
    return this.alive && this.open_question_id === question_id && this.detail_seq === seq &&
      authSession.matches_access_token(token);
  }

  private is_valid_solution(action: string, solution: SolutionDetail, question_id: string): boolean {
    if (solution.questionId !== question_id) {
      return false;
    }
    const file_ref: string | null = solution.fileRef;
    if (file_ref === null || file_ref.length === 0) {
      return false;
    }
    if (action === 'complete') {
      return solution.state === AnswerState.COMPLETED;
    }
    return solution.state === AnswerState.IN_PROGRESS;
  }

  private async refresh_answer_views(question_id: string, seq: number, token: string): Promise<void> {
    if (!this.is_detail_request_current(token, question_id, seq)) {
      return;
    }
    try {
      const result: AuthResult<QuestionDetail> = await this.api.getQuestion(token, question_id);
      if (!this.is_detail_request_current(token, question_id, seq)) {
        return;
      }
      if (result.error !== null && result.error.is_unauthorized()) {
        if (authSession.clear_session_if_token(token)) {
          this.expire_session();
        }
        return;
      }
      if (result.isSuccess && result.data !== null && result.data.questionId === question_id) {
        this.current_answer_state = result.data.answerState;
      }
    } catch (error) {
      return;
    }
    if (!this.is_detail_request_current(token, question_id, seq)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      if (authSession.clear_session_if_token(token)) {
        this.expire_session();
      }
      return;
    }
    this.list_seq += 1;
    const list_seq: number = this.list_seq;
    await this.load_questions(token, list_seq, this.page);
  }

  private answer_failure_text(error: AuthError | null): string {
    if (error === null) {
      return '操作未完成，请重试';
    }
    if (error.is_network()) {
      return '网络连接失败，请重试';
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
      return TABLET_COLOR_SUCCESS;
    }
    if (state === AnswerState.IN_PROGRESS) {
      return TABLET_COLOR_PRIMARY;
    }
    return TABLET_COLOR_MUTED;
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
      this.current_answer_state = result.data.answerState;
    } catch (error) {
      if (!this.check_detail_session(token, seq)) {
        return;
      }
      this.detail_error = '网络连接失败，请检查网络后重试';
    } finally {
      if (this.alive && seq === this.detail_seq) {
        this.detail_loading = false;
        this.sync_tutor();
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
    this.error_text = '';
    this.detail_error = '';
    this.reset_answer_view();
    this.on_answer_mode_changed(false);
    this.on_session_expired();
  }

  private answer_callback_current(revision: number): boolean {
    return this.alive && revision === this.answer_mount_revision && this.detail !== null &&
      this.detail.questionId === this.open_question_id && !this.detail_loading;
  }

  private tutor_eligible(): boolean {
    return this.alive && this.active && this.canvas_ready && !this.detail_loading &&
      this.detail_error.length === 0 && this.detail !== null &&
      this.open_question_id.length > 0 && this.detail.questionId === this.open_question_id;
  }

  private sync_tutor(): void {
    if (!this.tutor_eligible()) {
      this.stop_tutor();
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.stop_tutor();
      return;
    }
    const question_id: string = this.open_question_id;
    if (this.tutor_bridge !== null &&
      (this.tutor_question_id !== question_id || this.tutor_token !== token)) {
      this.dispose_tutor();
    }
    if (this.tutor_bridge === null) {
      const revision: number = ++this.tutor_revision;
      this.tutor_question_id = question_id;
      this.tutor_token = token;
      this.tutor_bridge = new TabletTutorBridge(this.canvas_controller, new SharedTabletTutorApi(),
        (state: TabletTutorState) => {
          if (this.tutor_context_current(revision, question_id) && state.questionId === question_id &&
            authSession.read_access_token() === token) {
            this.tutor_phase = state.phase;
          }
        }, () => {
          // Bridge 在真实 401 后已条件清理原 token；此处仍必须允许回到既有登录通路。
          const current_token: string | null = authSession.read_access_token();
          if (this.tutor_context_current(revision, question_id) &&
            (current_token === null || current_token === token)) {
            this.expire_session();
          }
        });
    }
    if (!this.tutor_started) {
      this.tutor_started = true;
      this.tutor_bridge.start(question_id);
    }
  }

  private tutor_context_current(revision: number, question_id: string): boolean {
    return revision === this.tutor_revision && this.tutor_started && this.tutor_eligible() &&
      this.tutor_question_id === question_id && this.open_question_id === question_id;
  }

  private stop_tutor(): void {
    const was_started: boolean = this.tutor_started;
    this.tutor_started = false;
    this.tutor_phase = TabletTutorPhase.STOPPED;
    if (was_started && this.tutor_bridge !== null) {
      this.tutor_bridge.stop();
    }
  }

  private dispose_tutor(): void {
    this.tutor_revision += 1;
    this.tutor_started = false;
    this.tutor_phase = TabletTutorPhase.STOPPED;
    const bridge: TabletTutorBridge | null = this.tutor_bridge;
    this.tutor_bridge = null;
    this.tutor_question_id = '';
    this.tutor_token = '';
    if (bridge !== null) {
      bridge.dispose();
    }
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
