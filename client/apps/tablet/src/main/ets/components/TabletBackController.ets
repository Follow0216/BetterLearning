/** 页面返回栈只保存当前挂载组件的处理器；最上层先处理系统返回。 */
export class TabletBackController {
  private handlers: Array<() => boolean> = [];

  attach(handler: () => boolean): void {
    this.detach(handler);
    this.handlers.push(handler);
  }

  detach(handler: () => boolean): void {
    this.handlers = this.handlers.filter((item: () => boolean) => item !== handler);
  }

  handle(): boolean {
    for (let index: number = this.handlers.length - 1; index >= 0; index--) {
      if (this.handlers[index]()) { return true; }
    }
    return false;
  }
}
