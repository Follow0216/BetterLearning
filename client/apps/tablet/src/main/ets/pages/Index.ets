import { AuthScreen, UserProfile, authSession } from 'common';
import { TabletShell } from '../components/TabletShell';

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

  build() {
    Column() {
      if (this.signed_in) {
        TabletShell({
          display_name: this.display_name,
          on_signed_out: () => {
            this.handle_signed_out();
          }
        })
      } else {
        AuthScreen({
          onAuthenticated: (user: UserProfile) => {
            this.display_name = user.username;
            this.signed_in = true;
          },
          onSignedOut: () => {
            this.handle_signed_out();
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F5F8FE')
  }

  private handle_signed_out(): void {
    authSession.clear_session();
    this.display_name = '';
    this.signed_in = false;
  }
}
