import { ToastStore } from 'common';
import { AnswerState, QuestionDetail } from 'common';
import { TabletTutorPhase } from '../features/tutor/TabletTutorBridge';
import {
  CanvasWorkspaceState,
  TabletCanvasController,
  TabletCanvasWorkspace
} from '../features/canvas/TabletCanvasWorkspace';
import {
  TABLET_ANSWER_HEADER_HEIGHT,
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_DANGER,
  TABLET_COLOR_DANGER_SOFT,
  TABLET_COLOR_MUTED,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_SUCCESS,
  TABLET_COLOR_SUCCESS_SOFT,
  TABLET_COLOR_TEXT
} from '../theme/TabletTheme';

/**
 * 答题页包装：沉浸式单条页头 + C2 画布工作区。
 *
 * <p>题面、笔迹与工具栏全部由 C2 的 TabletCanvasWorkspace 内部维护；本组件只
 * 负责返回、真实保存状态、保存/完成/继续按钮与必要的错误提示。按钮可用性和
 * 文案直接读取 @Prop，不再经过多参数 Builder 转发动态值。</p>
 */
@Component
export struct AnswerWorkspace {
  toast?: ToastStore;
  @Prop question: QuestionDetail | null = null;
  @Prop collection_name: string = '';
  @Prop question_ordinal: string = '';
  @Prop answer_state: AnswerState = AnswerState.NOT_STARTED;
  @Prop save_hint: string = '';
  @Prop can_back: boolean = true;
  @Prop can_save: boolean = false;
  @Prop can_complete: boolean = false;
  @Prop can_continue: boolean = false;
  @Prop show_continue: boolean = false;
  @Prop pending_action: string = '';
  @Prop feedback_text: string = '';
  @Prop feedback_error: boolean = false;
  @Prop tutor_phase: TabletTutorPhase = TabletTutorPhase.STOPPED;
  @State private header_width_vp: number = 1000;
  controller: TabletCanvasController = new TabletCanvasController();
  on_state_change: (state: CanvasWorkspaceState) => void = () => {
  };
  on_session_expired: () => void = () => {
  };
  on_back: () => void = () => {
  };
  on_save: () => void = () => {
  };
  on_complete: () => void = () => {
  };
  on_continue: () => void = () => {
  };

  build() {
    Column() {
      this.answer_header()

      if (this.tutor_hint().length > 0) {
        Row({ space: 8 }) {
          Image($r('app.media.bl_info'))
            .width(14)
            .height(14)
            .objectFit(ImageFit.Contain)
          Text(this.tutor_hint())
            .fontSize(12)
            .fontColor(this.tutor_hint_color())
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 6, bottom: 6 })
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
      }

      if (this.feedback_error && this.feedback_text.length > 0) {
        Row() {
          Image($r('app.media.bl_warning'))
            .width(15)
            .height(15)
            .objectFit(ImageFit.Contain)
          Text(this.feedback_text)
            .fontSize(12)
            .fontColor(TABLET_COLOR_DANGER)
            .layoutWeight(1)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ left: 8 })
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 8, bottom: 8 })
        .backgroundColor(TABLET_COLOR_DANGER_SOFT)
      }

      TabletCanvasWorkspace({
        toast: this.toast,
        question: this.question,
        controller: this.controller,
        onStateChange: (state: CanvasWorkspaceState) => {
          this.on_state_change(state);
        },
        onSessionExpired: () => {
          this.on_session_expired();
        }
      })
        .width('100%')
        .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(TABLET_COLOR_CARD)
  }

  @Builder
  answer_header() {
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
      .enabled(this.can_back)
      .opacity(this.can_back ? 1 : 0.4)
      .onClick(() => {
        this.on_back();
      })

      Row() {
        Text(this.collection_name.length > 0 ? this.collection_name : '题目')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: 220 })
          .layoutWeight(1)
          .margin({ left: 2, right: 12 })

        Divider()
          .vertical(true)
          .height(16)
          .strokeWidth(1)
          .color(TABLET_COLOR_BORDER)

        Text(this.question_ordinal.length > 0 ? this.question_ordinal : '题目')
          .fontSize(14)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: this.header_width_vp < 640 ? 64 : 100 })
          .margin({ left: 12, right: 10 })

        if (this.header_width_vp >= 640) {
          Text(this.state_label())
            .fontSize(11)
            .fontColor(this.state_foreground())
            .backgroundColor(this.state_background())
            .borderRadius(4)
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
        }

      }
      .layoutWeight(1)

      if (this.save_hint.length > 0) {
        Text(this.save_hint)
          .fontSize(11)
          .fontColor(TABLET_COLOR_MUTED)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: this.header_width_vp < 520 ? 56 : (this.header_width_vp < 760 ? 84 : 160) })
          .margin({ left: 8, right: 12 })
          .accessibilityText(this.save_hint)
      }

      if (!this.show_continue) {
        Button(this.pending_action === 'save' ? '保存中…' : '保存')
          .type(ButtonType.Normal)
          .fontSize(13)
          .fontColor(TABLET_COLOR_PRIMARY)
          .width(this.header_width_vp < 520 ? 64 : 80)
          .height(44)
          .padding({ left: this.header_width_vp < 520 ? 4 : 12, right: this.header_width_vp < 520 ? 4 : 12 })
          .backgroundColor(Color.Transparent)
          .border({ width: 1, color: TABLET_COLOR_BORDER })
          .borderRadius(22)
          .enabled(this.can_save)
          .opacity(this.can_save ? 1 : 0.4)
          .onClick(() => {
            this.dispatch('save');
          })

        Button(this.pending_action === 'complete' ? '完成中…' : '完成')
          .type(ButtonType.Normal)
          .fontSize(13)
          .fontColor(TABLET_COLOR_PRIMARY)
          .width(this.header_width_vp < 520 ? 68 : 84)
          .height(44)
          .padding({ left: this.header_width_vp < 520 ? 4 : 12, right: this.header_width_vp < 520 ? 4 : 12 })
          .backgroundColor(TABLET_COLOR_CARD)
          .border({ width: 1, color: TABLET_COLOR_PRIMARY })
          .borderRadius(22)
          .margin({ left: 8 })
          .enabled(this.can_complete)
          .opacity(this.can_complete ? 1 : 0.4)
          .onClick(() => {
            this.dispatch('complete');
          })
      } else {
        Button(this.pending_action === 'continue' ? '恢复中…' : '继续作答')
          .type(ButtonType.Normal)
          .fontSize(13)
          .fontColor(TABLET_COLOR_PRIMARY)
          .width(100)
          .height(44)
          .padding({ left: this.header_width_vp < 520 ? 4 : 12, right: this.header_width_vp < 520 ? 4 : 12 })
          .backgroundColor(TABLET_COLOR_CARD)
          .border({ width: 1, color: TABLET_COLOR_PRIMARY })
          .borderRadius(22)
          .enabled(this.can_continue)
          .opacity(this.can_continue ? 1 : 0.4)
          .onClick(() => {
            this.dispatch('continue');
          })
      }
    }
    .width('100%')
    .height(TABLET_ANSWER_HEADER_HEIGHT)
    .padding({ left: 16, right: 16 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
    .onAreaChange((_old: Area, area: Area) => {
      const width: number = typeof area.width === 'number' ? area.width : parseFloat(area.width.toString());
      if (Number.isFinite(width) && width > 0) {
        this.header_width_vp = width;
      }
    })
  }

  private tutor_hint(): string {
    switch (this.tutor_phase) {
      case TabletTutorPhase.FREEZING:
        return '手机正在请求解答，等待抬笔后发送';
      case TabletTutorPhase.UPLOADING:
        return '正在发送本轮解答，可继续书写';
      case TabletTutorPhase.GENERATING:
        return '解答已发送，正在生成辅导';
      case TabletTutorPhase.SUCCEEDED:
        return '辅导已生成，可在手机查看';
      case TabletTutorPhase.FAILED:
        return '本轮辅导未完成，请在手机查看或重试';
      case TabletTutorPhase.ERROR:
        return '辅导暂时不可用，请在手机查看本轮状态';
      default:
        return '';
    }
  }

  private tutor_hint_color(): string {
    if (this.tutor_phase === TabletTutorPhase.SUCCEEDED) {
      return TABLET_COLOR_SUCCESS;
    }
    if (this.tutor_phase === TabletTutorPhase.FAILED || this.tutor_phase === TabletTutorPhase.ERROR) {
      return TABLET_COLOR_MUTED;
    }
    return TABLET_COLOR_PRIMARY;
  }

  private dispatch(action: string): void {
    if (action === 'save') {
      this.on_save();
      return;
    }
    if (action === 'complete') {
      this.on_complete();
      return;
    }
    this.on_continue();
  }

  private state_label(): string {
    if (this.answer_state === AnswerState.COMPLETED) {
      return '已完成';
    }
    if (this.answer_state === AnswerState.IN_PROGRESS) {
      return '作答中';
    }
    return '未开始';
  }

  private state_foreground(): string {
    if (this.answer_state === AnswerState.COMPLETED) {
      return TABLET_COLOR_SUCCESS;
    }
    if (this.answer_state === AnswerState.IN_PROGRESS) {
      return TABLET_COLOR_PRIMARY;
    }
    return TABLET_COLOR_MUTED;
  }

  private state_background(): string {
    if (this.answer_state === AnswerState.COMPLETED) {
      return TABLET_COLOR_SUCCESS_SOFT;
    }
    if (this.answer_state === AnswerState.IN_PROGRESS) {
      return TABLET_COLOR_PRIMARY_SOFT;
    }
    return '#F0F3F9';
  }
}