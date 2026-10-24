import { QuestionDetail } from 'common';
import { CanvasWorkspaceState, TabletCanvasController } from './CanvasWorkspaceController';

export { CanvasWorkspaceState, TabletCanvasController } from './CanvasWorkspaceController';

/** M2 稳定挂载边界；固定题面与手写同在本组件，外层不另挂一个 Markdown 题面。 */
@Component
export struct TabletCanvasWorkspace {
  @Prop question: QuestionDetail | null = null;
  controller: TabletCanvasController = new TabletCanvasController();
  onStateChange: (state: CanvasWorkspaceState) => void = () => {};
  onSessionExpired: () => void = () => {};

  aboutToAppear(): void { this.onStateChange(new CanvasWorkspaceState()); }
  build() {
    Column() {
      LoadingProgress().width(32).height(32)
      Text('正在准备画布…').fontSize(14).margin({ top: 12 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#FFFFFF')
  }
}
