import { AccountPane } from './AccountPane';
import { LibraryPane } from './LibraryPane';
import { LearningNotesView } from 'common';
import { TabletBackController } from './TabletBackController';
import {
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_HEADER,
  TABLET_COLOR_MUTED,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_TEXT,
  TABLET_TOP_BAR_HEIGHT
} from '../theme/TabletTheme';

enum TabletTab {
  LIBRARY = 'library',
  NOTES = 'notes',
  ACCOUNT = 'account'
}

/**
 * 平板主导航壳：顶栏切换“题库”与“我的”。
 *
 * <p>题库页与我的页都保持挂载，切换 tab 不重置分页、滚动或刚刷新到的账号资料；
 * 进入答题后隐藏全局导航，但题库页本身不卸载，返回时画布与草稿状态仍在。
 * 任何子页确认会话失效后统一交给外层清理账号可见数据并回到登录。</p>
 */
@Component
export struct TabletShell {
  @Prop display_name: string = '';
  @Prop foreground: boolean = false;
  backController: TabletBackController = new TabletBackController();
  on_signed_out: () => void = () => {
  };
  @State active_tab: TabletTab = TabletTab.LIBRARY;
  @State answer_mode: boolean = false;
  @State private notesBackToken: number = 0;
  private backHandler: () => boolean = () => {
    if (!this.foreground) { return false; }
    if (this.active_tab === TabletTab.NOTES) {
      this.notesBackToken += 1;
      return true;
    }
    return false;
  };

  aboutToAppear(): void { this.backController.attach(this.backHandler); }
  aboutToDisappear(): void { this.backController.detach(this.backHandler); }

  build() {
    Column() {
      this.top_bar()
      Stack() {
        LibraryPane({
          backController: this.backController,
          active: this.foreground && this.active_tab === TabletTab.LIBRARY,
          on_session_expired: () => {
            this.on_signed_out();
          },
          on_answer_mode_changed: (active: boolean) => {
            this.answer_mode = active;
          }
        })
          .visibility(this.active_tab === TabletTab.LIBRARY ? Visibility.Visible : Visibility.None)

        AccountPane({
          on_signed_out: () => {
            this.on_signed_out();
          }
        })
          .visibility(this.active_tab === TabletTab.ACCOUNT ? Visibility.Visible : Visibility.None)

        if (this.active_tab === TabletTab.NOTES) {
          LearningNotesView({
            backToken: this.notesBackToken,
            onUnauthorized: () => this.on_signed_out(),
            onBack: () => { this.active_tab = TabletTab.LIBRARY; }
          })
        }
      }
      .layoutWeight(1)
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .backgroundColor(TABLET_COLOR_CARD)
  }

  @Builder
  top_bar() {
    Stack({ alignContent: Alignment.Center }) {
      Row() {
        Row() {
          Text('Better')
            .fontSize(20)
            .fontWeight(FontWeight.Bold)
            .fontColor(TABLET_COLOR_TEXT)
          Text('Learning')
            .fontSize(20)
            .fontWeight(FontWeight.Bold)
            .fontColor(TABLET_COLOR_PRIMARY)
        }

        Blank()

        Row() {
          Image($r('app.media.bl_user'))
            .width(20)
            .height(20)
            .objectFit(ImageFit.Contain)
        }
        .width(40)
        .height(40)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(20)
        .accessibilityText(this.display_name.length > 0 ? this.display_name : '我的')
      }
      .width('100%')
      .height('100%')
      .padding({ left: 28, right: 28 })

      Row({ space: 44 }) {
        Column() {
          Text('题库')
            .fontSize(15)
            .fontWeight(this.active_tab === TabletTab.LIBRARY ? FontWeight.Medium : FontWeight.Normal)
            .fontColor(this.active_tab === TabletTab.LIBRARY ? TABLET_COLOR_PRIMARY : TABLET_COLOR_MUTED)
          Text('')
            .width(26)
            .height(3)
            .borderRadius(2)
            .backgroundColor(this.active_tab === TabletTab.LIBRARY ? TABLET_COLOR_PRIMARY : Color.Transparent)
            .margin({ top: 6 })
        }
        .height(TABLET_TOP_BAR_HEIGHT)
        .justifyContent(FlexAlign.Center)
        .padding({ left: 8, right: 8 })
        .onClick(() => {
          this.active_tab = TabletTab.LIBRARY;
        })

        Column() {
          Text('学习笔记')
            .fontSize(15)
            .fontWeight(this.active_tab === TabletTab.NOTES ? FontWeight.Medium : FontWeight.Normal)
            .fontColor(this.active_tab === TabletTab.NOTES ? TABLET_COLOR_PRIMARY : TABLET_COLOR_MUTED)
          Text('')
            .width(52)
            .height(3)
            .borderRadius(2)
            .backgroundColor(this.active_tab === TabletTab.NOTES ? TABLET_COLOR_PRIMARY : Color.Transparent)
            .margin({ top: 6 })
        }
        .height(TABLET_TOP_BAR_HEIGHT)
        .justifyContent(FlexAlign.Center)
        .padding({ left: 8, right: 8 })
        .onClick(() => {
          this.active_tab = TabletTab.NOTES;
        })

        Column() {
          Text('我的')
            .fontSize(15)
            .fontWeight(this.active_tab === TabletTab.ACCOUNT ? FontWeight.Medium : FontWeight.Normal)
            .fontColor(this.active_tab === TabletTab.ACCOUNT ? TABLET_COLOR_PRIMARY : TABLET_COLOR_MUTED)
          Text('')
            .width(26)
            .height(3)
            .borderRadius(2)
            .backgroundColor(this.active_tab === TabletTab.ACCOUNT ? TABLET_COLOR_PRIMARY : Color.Transparent)
            .margin({ top: 6 })
        }
        .height(TABLET_TOP_BAR_HEIGHT)
        .justifyContent(FlexAlign.Center)
        .padding({ left: 8, right: 8 })
        .onClick(() => {
          this.active_tab = TabletTab.ACCOUNT;
        })
      }
    }
    .width('100%')
    .height(TABLET_TOP_BAR_HEIGHT)
    .backgroundColor(TABLET_COLOR_HEADER)
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
    .visibility(this.answer_mode ? Visibility.None : Visibility.Visible)
  }
}
