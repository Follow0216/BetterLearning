/** Entry 页将系统返回委托给当前 PhoneApp；页面销毁后解除绑定。 */
export class PhoneBackController {
  private handler: (() => boolean) | null = null;

  register(handler: () => boolean): void {
    this.handler = handler;
  }

  unregister(handler: () => boolean): void {
    if (this.handler === handler) { this.handler = null; }
  }

  dispatch(): boolean {
    return this.handler === null ? false : this.handler();
  }
}

export const phoneBackController: PhoneBackController = new PhoneBackController();
