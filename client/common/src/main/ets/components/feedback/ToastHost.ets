import { accessibility } from '@kit.AccessibilityKit';
import { ToastItem } from './ToastModels';
import { ToastStore } from './ToastStore';

@Component
struct ToastBubble {
  @Prop item: ToastItem = {
    id: 0, revision: 0, key: '', type: 'info', message: '', description: '', durationMs: 3000
  };
  onDismiss: () => void = () => {};
  onDisplayed: () => boolean = () => false;

  private statusColor(): string {
    return this.item.type === 'error' ? '#C94C5C' : this.item.type === 'success' ? '#238468' : '#246BFF';
  }

  private statusLabel(): string {
    return this.item.type === 'error' ? '!' : this.item.type === 'success' ? '✓' : 'i';
  }

  private announce(): void {
    const context = this.getUIContext().getHostContext();
    if (context === undefined) {
      return;
    }
    const event: accessibility.EventInfo = new accessibility.EventInfo(
      this.item.type === 'error' ? 'announceForAccessibility' : 'announceForAccessibilityNotInterrupt',
      context.applicationInfo.name, 'common');
    event.textAnnouncedForAccessibility = this.item.message +
      (this.item.description.length > 0 ? '，' + this.item.description : '');
    // 仅播报，不请求输入/无障碍焦点；服务不可用不影响通知和业务。
    accessibility.sendAccessibilityEvent(event).catch(() => {});
  }

  build() {
    Row({ space: 10 }) {
      Text(this.statusLabel())
        .fontSize(14)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.statusColor())
        .textAlign(TextAlign.Center)
        .width(22)
        .height(22)
        .border({ width: 1, color: this.statusColor() })
        .borderRadius(11)
        .accessibilityLevel('no')
      Column({ space: 3 }) {
        Text(this.item.message)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor('#152744')
          .maxLines(this.item.description.length > 0 ? 1 : 2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        if (this.item.description.length > 0) {
          Text(this.item.description)
            .fontSize(12)
            .fontColor('#5A6B85')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      Button('×', { type: ButtonType.Normal })
        .width(44)
        .height(44)
        .fontSize(24)
        .fontColor('#5A6B85')
        .backgroundColor(Color.Transparent)
        .borderRadius(10)
        .accessibilityText('关闭通知')
        .onClick(() => this.onDismiss())
    }
    .width('100%')
    .constraintSize({ minHeight: 56 })
    .padding({ left: 14, right: 4, top: 6, bottom: 6 })
    .backgroundColor(Color.White)
    .border({ width: 1, color: '#DDE7F8' })
    .borderRadius(14)
    .shadow({ radius: 12, color: '#14274418', offsetX: 0, offsetY: 4 })
    // Block 会连子节点一起阻断，使用允许 × 接收事件的层级阻断模式。
    .hitTestBehavior(HitTestMode.BLOCK_HIERARCHY)
    .onClick(() => {})
    .onAppear(() => {
      if (this.onDisplayed()) {
        this.announce();
      }
    })
  }
}

/** 根 Stack 的最后一个子节点；不要放进文档流、Scroll 或画布缩放节点。 */
@Component
export struct ToastHost {
  store: ToastStore = new ToastStore();
  @Prop topInset: number = 12;
  @Prop maxWidth: number = 420;
  @Prop sideInset: number = 16;
  @State private items: ToastItem[] = [];
  private hostId: number = -1;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.hostId = this.store.attachHost((items: ToastItem[]) => {
      if (this.alive) {
        this.items = items;
      }
    });
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.store.detachHost(this.hostId);
    this.hostId = -1;
  }

  build() {
    Column({ space: 8 }) {
      ForEach(this.items, (item: ToastItem) => {
        ToastBubble({
          item: item,
          onDismiss: () => this.store.dismissItem(item.id, item.revision),
          onDisplayed: () => this.store.markDisplayed(this.hostId, item.id, item.revision)
        })
          .constraintSize({ maxWidth: this.maxWidth })
      }, (item: ToastItem) => item.id.toString() + ':' + item.revision.toString())
    }
    .width('100%')
    .height('100%')
    .padding({ top: this.topInset, left: this.sideInset, right: this.sideInset })
    .alignItems(HorizontalAlign.Center)
    .hitTestBehavior(HitTestMode.Transparent)
  }
}
