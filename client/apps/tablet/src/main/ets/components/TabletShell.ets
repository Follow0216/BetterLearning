
import { AccountPane } from './AccountPane';
import { LibraryPane } from './LibraryPane';
import {
  TABLET_COLOR_BACKGROUND,
  TABLET_COLOR_BORDER,
  TABLET_COLOR_CARD,
  TABLET_COLOR_MUTED,
  TABLET_COLOR_PRIMARY,
  TABLET_COLOR_PRIMARY_SOFT,
  TABLET_COLOR_TEXT,
  TABLET_CONTROL_SIZE,
  TABLET_RADIUS_SMALL,
  TABLET_TOP_BAR_HEIGHT
} from '../theme/TabletTheme';

enum TabletTab {
  LIBRARY = 'library',
  ACCOUNT = 'account'
}

/**
 * 平板主导航壳：顶栏切换“题库”与“我的”。
 *
 * <p>题库页保持挂载，切换 tab 不重置分页与滚动；任何子页确认会话失效后统一
 * 交给外层清理账号可见数据并回到登录。</p>
 */
@Component
export struct TabletShell {
  @Prop display_name: string = '';
  on_signed_out: () => void = () => {
  };
  @State active_tab: TabletTab = TabletTab.LIBRARY;

  build() {
    Column() {
      this.top_bar()
      Stack() {
        LibraryPane({
          on_session_expired: () => {
            this.on_signed_out();
          }
        })
          .visibility(this.active_tab === TabletTab.LIBRARY ? Visibility.Visible : Visibility.None)

        if (this.active_tab === TabletTab.ACCOUNT) {
          AccountPane({
            on_signed_out: () => {
              this.on_signed_out();
            }
          })
        }
      }
      .layoutWeight(1)
      .width('100%')
      .backgroundColor(TABLET_COLOR_BACKGROUND)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(TABLET_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row() {
      Row() {
        Text('Better')
          .fontSize(19)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_TEXT)
        Text('Learning')
          .fontSize(19)
          .fontWeight(FontWeight.Bold)
          .fontColor(TABLET_COLOR_PRIMARY)
      }

      Row({ space: 4 }) {
        this.nav_tab('题库', TabletTab.LIBRARY)
        this.nav_tab('我的', TabletTab.ACCOUNT)
      }
      .margin({ left: 28 })

      Blank()

      Row() {
        Row() {
          Image($r('app.media.bl_user'))
            .width(18)
            .height(18)
            .objectFit(ImageFit.Contain)
        }
        .width(36)
        .height(36)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(TABLET_COLOR_PRIMARY_SOFT)
        .borderRadius(18)

        Text(this.display_name.length > 0 ? this.display_name : '已登录')
          .fontSize(12)
          .fontColor(TABLET_COLOR_TEXT)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: 120 })
          .margin({ left: 8 })
      }
      .margin({ left: 20 })
    }
    .width('100%')
    .height(TABLET_TOP_BAR_HEIGHT)
    .padding({ left: 24, right: 24 })
    .backgroundColor(TABLET_COLOR_CARD)
    .border({ width: { bottom: 1 }, color: TABLET_COLOR_BORDER })
  }

  @Builder
  nav_tab(label: string, tab: TabletTab) {
    Button(label)
      .type(ButtonType.Normal)
      .fontSize(14)
      .fontWeight(this.active_tab === tab ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.active_tab === tab ? TABLET_COLOR_PRIMARY : TABLET_COLOR_MUTED)
      .height(TABLET_CONTROL_SIZE)
      .padding({ left: 16, right: 16 })
      .backgroundColor(this.active_tab === tab ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
      .borderRadius(TABLET_RADIUS_SMALL)
      .onClick(() => {
        this.active_tab = tab;
      })
  }
}
