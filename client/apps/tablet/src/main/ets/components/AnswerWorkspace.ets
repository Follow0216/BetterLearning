import { QuestionDetail } from 'common';
import {
  CanvasWorkspaceState,
  TabletCanvasController,
  TabletCanvasWorkspace
} from '../features/canvas/TabletCanvasWorkspace';
import {
  TABLET_COLOR_BACKGROUND,
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_DANGER,
  TABLET_COLOR_DANGER_SOFT,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_TEXT,
  TABLET_CONTROL_SIZE,
  TABLET_RADIUS_SMALL
} from '../theme/TabletTheme';

/**
 * 答题页包装：只负责挂载 C2 画布工作区、操作按钮与状态反馈。
 *
 * <p>固定题面与笔迹都由 C2 的 TabletCanvasWorkspace 内部维护，本组件不再并排
 * 渲染独立 Markdown 或空白纸。保存/完成/继续只调用父级传入的稳定控制器，
 * 按钮可用性完全依据 C2 状态，不在本层猜测保存结果。</p>
 */
@Component
export struct AnswerWorkspace {
  @Prop question: QuestionDetail | null = null;
  @Prop can_save: boolean = false;
  @Prop can_complete: boolean = false;
  @Prop can_continue: boolean = false;
  @Prop show_continue: boolean = false;
  @Prop pending_action: string = '';
  @Prop feedback_text: string = '';
  @Prop feedback_error: boolean = false;
  controller: TabletCanvasController = new TabletCanvasController();
  on_state_change: (state: CanvasWorkspaceState) => void = () => {
  };
  on_session_expired: () => void = () => {
  };
  on_save: () => void = () => {
  };
  on_complete: () => void = () => {
  };
  on_continue: () => void = () => {
  };

  build() {
    Column() {
      TabletCanvasWorkspace({
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

      this.action_bar()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(TABLET_COLOR_BACKGROUND)
  }

  @Builder
  action_bar() {
    Column() {
      if (this.feedback_text.length > 0) {
        Row() {
          Image(this.feedback_error ? $r('app.media.bl_warning') : $r('app.media.bl_info'))
            .width(16)
            .height(16)
            .objectFit(ImageFit.Contain)
          Text(this.feedback_text)
            .fontSize(12)
            .fontColor(this.feedback_error ? TABLET_COLOR_DANGER : TABLET_COLOR_TEXT)
            .layoutWeight(1)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ left: 8 })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(this.feedback_error ? TABLET_COLOR_DANGER_SOFT : TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(TABLET_RADIUS_SMALL)
        .margin({ bottom: 10 })
      }

      Row({ space: 10 }) {
        Blank()
        this.action_button(this.save_label(), 'save', this.can_save, true)
        this.action_button(this.complete_label(), 'complete', this.can_complete, false)
        if (this.show_continue) {
          this.action_button(this.continue_label(), 'continue', this.can_continue, false)
        }
      }
      .width('100%')
    }
    .width('100%')
    .padding({ left: 20, right: 20, top: 12, bottom: 16 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { top: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  action_button(label: string, action: string, enabled: boolean, primary: boolean) {
    Button(label)
      .type(ButtonType.Normal)
      .fontSize(14)
      .fontColor(primary ? TABLET_COLOR_CARD : TABLET_COLOR_PRIMARY)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 22, right: 22 })
      .backgroundColor(primary ? TABLET_COLOR_PRIMARY : TABLET_COLOR_PRIMARY_SOFT)
      .borderRadius(TABLET_RADIUS_SMALL)
      .enabled(enabled)
      .opacity(enabled ? 1 : 0.4)
      .onClick(() => {
        this.dispatch(action);
      })
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

  private save_label(): string {
    return this.pending_action === 'save' ? '保存中…' : '保存';
  }

  private complete_label(): string {
    return this.pending_action === 'complete' ? '完成中…' : '完成';
  }

  private continue_label(): string {
    return this.pending_action === 'continue' ? '恢复中…' : '继续作答';
  }
}