import { authSession, AuthScreen, QuestionDetail, UserProfile } from 'common';
import { PhoneBackController } from '../model/PhoneHomeState';
import {
  PHONE_COLOR_BACKGROUND, PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY, PHONE_NAV_HEIGHT
} from '../theme/PhoneTheme';
import { AccountPage } from './AccountPage';
import { ManualEntry } from './ManualEntry';
import { PhoneAnswerPage } from './PhoneAnswerPage';
import { PhoneHomePage } from './PhoneHomePage';
import { QuestionDetailPage } from './QuestionDetailPage';
import { QuestionLibrary } from './QuestionLibrary';

enum PhoneTab {
  HOME = 'home',
  LIBRARY = 'library',
  ACCOUNT = 'account'
}

/** 首页、题库和我的在登录会话内常驻；录题是保留草稿的覆盖层。 */
@Component
export struct PhoneApp {
  navigation: PhoneBackController = new PhoneBackController();
  @State isAuthenticated: boolean = false;
  @State activeTab: PhoneTab = PhoneTab.HOME;
  @State detailQuestionId: string = '';
  @State answerQuestionId: string = '';
  @State answerTurnId: string = '';
  @State homeQuestionId: string = '';
  @State homeRequestSerial: number = 0;
  @State entryVisible: boolean = false;
  @State homeFullscreen: boolean = false;
  @State homeComposing: boolean = false;
  @State libraryRefreshToken: number = 0;
  private entryNavigation: PhoneBackController = new PhoneBackController();
  private homeNavigation: PhoneBackController = new PhoneBackController();

  aboutToAppear(): void {
    this.navigation.handleBack = (): boolean => this.handle_back();
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token !== null && user !== null) {
      this.enter_session();
    }
  }

  aboutToDisappear(): void {
    this.navigation.handleBack = (): boolean => false;
  }

  build() {
    Stack() {
      if (this.isAuthenticated) {
        this.main_shell()
        if (this.answerQuestionId.length > 0) {
          PhoneAnswerPage({
            questionId: this.answerQuestionId,
            turnId: this.answerTurnId,
            onBack: () => { this.close_answer(); },
            onUnauthorized: (token: string) => { this.handle_signed_out(token); }
          })
        }
      } else {
        AuthScreen({
          onAuthenticated: (user: UserProfile) => { this.handle_authenticated(user); },
          onSignedOut: () => { this.reset_session(); }
        })
      }
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  main_shell() {
    Column() {
      Stack() {
        PhoneHomePage({
          requestedQuestionId: this.homeQuestionId,
          requestSerial: this.homeRequestSerial,
          active: this.activeTab === PhoneTab.HOME && this.detailQuestionId.length === 0 &&
            !this.entryVisible && this.answerQuestionId.length === 0,
          navigation: this.homeNavigation,
          onChooseQuestion: () => { this.activeTab = PhoneTab.LIBRARY; },
          onComposingChanged: (value: boolean) => { this.homeComposing = value; },
          onFullscreenChanged: (value: boolean) => { this.homeFullscreen = value; },
          onOpenFrozenAnswer: (questionId: string, turnId: string) => {
            this.answerTurnId = turnId;
            this.answerQuestionId = questionId;
          },
          onUnauthorized: (token: string) => { this.handle_signed_out(token); }
        })
          .visibility(this.activeTab === PhoneTab.HOME && this.detailQuestionId.length === 0 &&
            !this.entryVisible ? Visibility.Visible : Visibility.None)

        QuestionLibrary({
          refreshToken: this.libraryRefreshToken,
          onCreateQuestion: () => { this.entryVisible = true; },
          onOpenQuestion: (questionId: string) => {
            this.close_answer();
            this.detailQuestionId = questionId;
          },
          onUnauthorized: (token: string) => { this.handle_signed_out(token); }
        })
          .visibility(this.detailQuestionId.length === 0 && !this.entryVisible &&
            this.activeTab === PhoneTab.LIBRARY ? Visibility.Visible : Visibility.None)

        AccountPage({
          onBack: () => { this.activeTab = PhoneTab.HOME; },
          onSignedOut: (token: string) => { this.handle_signed_out(token); }
        })
          .visibility(this.detailQuestionId.length === 0 && !this.entryVisible &&
            this.activeTab === PhoneTab.ACCOUNT ? Visibility.Visible : Visibility.None)

        if (this.detailQuestionId.length > 0 && !this.entryVisible) {
          QuestionDetailPage({
            questionId: this.detailQuestionId,
            onBack: () => { this.detailQuestionId = ''; },
            onOpenAnswer: (questionId: string) => {
              this.answerTurnId = '';
              this.answerQuestionId = questionId;
            },
            onOpenTutor: (questionId: string) => {
              this.homeQuestionId = questionId;
              this.homeRequestSerial = this.homeRequestSerial + 1;
              this.detailQuestionId = '';
              this.activeTab = PhoneTab.HOME;
            },
            onUnauthorized: (token: string) => { this.handle_signed_out(token); }
          })
        }

        ManualEntry({
          backController: this.entryNavigation,
          active: this.entryVisible && this.answerQuestionId.length === 0,
          onBack: () => { this.entryVisible = false; },
          onSaved: (detail: QuestionDetail) => {
            this.libraryRefreshToken = this.libraryRefreshToken + 1;
            this.entryVisible = false;
            this.activeTab = PhoneTab.LIBRARY;
            this.close_answer();
            this.detailQuestionId = detail.questionId;
          },
          onUnauthorized: (token: string) => { this.handle_signed_out(token); }
        })
          .visibility(this.entryVisible ? Visibility.Visible : Visibility.None)
      }
      .width('100%').layoutWeight(1)
      if (!this.entryVisible && this.detailQuestionId.length === 0 && !this.homeFullscreen && !this.homeComposing) {
        this.bottom_nav()
      }
    }
    .width('100%').height('100%')
  }

  @Builder
  bottom_nav() {
    Row() {
      this.nav_item(PhoneTab.HOME, $r('app.media.bl_nav_home_active'), $r('app.media.bl_nav_home_inactive'), '首页')
      this.nav_item(PhoneTab.LIBRARY, $r('app.media.bl_nav_library_active'), $r('app.media.bl_nav_library_inactive'), '题库')
      this.nav_item(PhoneTab.ACCOUNT, $r('app.media.bl_nav_account_active'), $r('app.media.bl_nav_account_inactive'), '我的')
    }
    .width('100%').height(PHONE_NAV_HEIGHT)
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  nav_item(tab: PhoneTab, activeIcon: Resource, inactiveIcon: Resource, label: string) {
    Column() {
      Image(this.activeTab === tab ? activeIcon : inactiveIcon).width(24).height(24)
      Text(label).fontSize(12)
        .fontWeight(this.activeTab === tab ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.activeTab === tab ? PHONE_COLOR_PRIMARY : PHONE_COLOR_MUTED).margin({ top: 3 })
    }
    .layoutWeight(1).height('100%').justifyContent(FlexAlign.Center).accessibilityText(label)
    .onClick(() => { this.activeTab = tab; })
  }

  private handle_back(): boolean {
    if (!this.isAuthenticated) { return false; }
    if (this.answerQuestionId.length > 0) { this.close_answer(); return true; }
    if (this.entryVisible) { this.entryNavigation.handleBack(); return true; }
    if (this.detailQuestionId.length > 0) { this.detailQuestionId = ''; return true; }
    if (this.activeTab === PhoneTab.HOME && this.homeNavigation.handleBack()) { return true; }
    if (this.activeTab !== PhoneTab.HOME) { this.activeTab = PhoneTab.HOME; return true; }
    return false;
  }

  private close_answer(): void {
    this.answerQuestionId = '';
    this.answerTurnId = '';
  }

  private handle_authenticated(user: UserProfile): void {
    const token: string | null = authSession.read_access_token();
    const current: UserProfile | null = authSession.read_user();
    if (token !== null && current !== null && current.accountId === user.accountId) { this.enter_session(); }
  }

  private enter_session(): void {
    this.isAuthenticated = true;
    this.clear_routes();
    this.libraryRefreshToken = this.libraryRefreshToken + 1;
  }

  private handle_signed_out(token: string): void {
    if (token.length > 0) {
      const current: string | null = authSession.read_access_token();
      if (current !== null && current !== token) { return; }
      authSession.clear_session_if_token(token);
    }
    this.reset_session();
  }

  private clear_routes(): void {
    this.activeTab = PhoneTab.HOME;
    this.close_answer();
    this.detailQuestionId = '';
    this.homeQuestionId = '';
    this.homeRequestSerial = 0;
    this.entryVisible = false;
    this.homeFullscreen = false;
    this.homeComposing = false;
  }

  private reset_session(): void {
    this.isAuthenticated = false;
    this.clear_routes();
    this.libraryRefreshToken = this.libraryRefreshToken + 1;
  }
}
