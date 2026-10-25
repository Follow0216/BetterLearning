import { AuthScreen, UserProfile, ToastHost, ToastStore, authSession } from 'common';
import { TabletShell } from '../components/TabletShell';
import { TabletBackController } from '../components/TabletBackController';

/**
 * 平板入口：未登录时展示共享登录页，登录成功后才能看到题库。
 *
 * <p>登录页与主导航壳互斥挂载：退出或会话失效时主导航壳整体卸载，登录页以
 * 初始表单状态重新出现，不残留上一账号的题库数据。</p>
 */
@Entry
@Component
struct Index {
  @State signed_in: boolean = false;
  @State display_name: string = '';
  @State page_visible: boolean = false;
  private backController: TabletBackController = new TabletBackController();
  @State toast_top: number = 12;
  private toast: ToastStore = new ToastStore();
  private account_id: string = '';
  private toast_context: string = 'auth';

  onPageShow(): void { this.page_visible = true; this.sync_toast_scope(this.toast_context); }
  onPageHide(): void { this.page_visible = false; this.toast.clearScope(); }
  aboutToDisappear(): void { this.page_visible = false; this.toast.dispose(); }
  onBackPress(): boolean { return this.backController.handle(); }

  build() {
    Stack({ alignContent: Alignment.Top }) {
      if (this.signed_in) {
        TabletShell({
          display_name: this.display_name,
          toast: this.toast,
          on_context_changed: (context: string, answer: boolean) => {
            this.toast_top = answer ? 72 : 76;
            this.sync_toast_scope(context);
          },
          foreground: this.page_visible,
          backController: this.backController,
          on_signed_out: () => {
            this.handle_signed_out();
          }
        })
      } else {
        AuthScreen({
          toast: this.toast,
          onAuthenticated: (user: UserProfile) => {
            this.account_id = user.accountId;
            this.toast_top = 76;
            this.sync_toast_scope('library:all');
            this.display_name = user.username;
            this.signed_in = true;
          },
          onSignedOut: () => {
            this.handle_signed_out();
          }
        })
      }
      ToastHost({ store: this.toast, topInset: this.toast_top })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F5F8FE')
  }

  private sync_toast_scope(context: string): void {
    this.toast_context = context;
    this.toast.onScopeChange('tablet:' + this.account_id + ':' + context);
  }

  private handle_signed_out(): void {
    this.toast.clearScope();
    this.account_id = '';
    this.toast_top = 12;
    this.sync_toast_scope('auth');
    authSession.clear_session();
    this.display_name = '';
    this.signed_in = false;
  }
}
