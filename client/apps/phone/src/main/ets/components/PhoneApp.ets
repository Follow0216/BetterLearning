import { authSession, AuthScreen, CitationModal, citationFocusController,
  LearningNotesView, QuestionDetail, UserProfile } from 'common';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_NAV_HEIGHT
} from '../theme/PhoneTheme';
import { AccountPage } from './AccountPage';
import { ManualEntry } from './ManualEntry';
import { PhoneAnswerPage } from './PhoneAnswerPage';
import { PhoneTutorPage } from './PhoneTutorPage';
import { PhoneTutorV2Page } from './PhoneTutorV2Page';
import { phone_v2_clear_all_intents } from '../model/PhoneTutorV2Logic';
import { phoneBackController } from '../model/PhoneBackController';
import { QuestionDetailPage } from './QuestionDetailPage';
import { QuestionLibrary } from './QuestionLibrary';

enum PhoneTab {
  LIBRARY = 'library',
  ENTRY = 'entry',
  ACCOUNT = 'account'
}

/**
 * 手机主壳：登录、题库与题集浏览、手工录题、详情与账号。
 *
 * <p>登录成功后只进入真实题库；不提供游客或演示数据入口。题库、录题与账号
 * 三个页面在会话内常驻并以可见性切换，录题正文与确认快照不会因切换 tab 丢失。
 * 所有子页面以当前访问令牌与请求序号保护异步回写，401、退出或会话失效时统一
 * 清除本端题库、详情和录题状态。</p>
 */
@Component
export struct PhoneApp {
  @State isAuthenticated: boolean = false;
  @State activeTab: PhoneTab = PhoneTab.LIBRARY;
  @State detailQuestionId: string = '';
  @State answerQuestionId: string = '';
  @State answerTurnId: string = '';
  @State tutorQuestionId: string = '';
  @State legacyTutorOpen: boolean = false;
  @State notesOpen: boolean = false;
  @State notesBackToken: number = 0;
  @State libraryRefreshToken: number = 0;
  private citationController: CustomDialogController | null = null;
  private citationFocusId: string = '';
  private sessionAccountId: string = '';
  private backHandler: () => boolean = () => this.handle_back();

  aboutToAppear(): void {
    phoneBackController.register(this.backHandler);
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token !== null && user !== null) {
      this.enter_session();
    }
  }

  aboutToDisappear(): void {
    phoneBackController.unregister(this.backHandler);
    this.close_citation(false);
  }

  build() {
    Stack() {
      if (this.isAuthenticated) {
        this.main_shell()
        if (this.tutorQuestionId.length > 0) {
          PhoneTutorV2Page({
            questionId: this.tutorQuestionId,
            visible: !this.legacyTutorOpen && !this.notesOpen && this.answerQuestionId.length === 0,
            onBack: () => {
              this.tutorQuestionId = '';
            },
            onOpenLegacy: () => {
              this.legacyTutorOpen = true;
            },
            onOpenNotes: () => {
              this.notesOpen = true;
            },
            onOpenCitation: (citationId: string) => {
              this.open_citation(citationId);
            },
            onUnauthorized: (token: string) => {
              this.handle_signed_out(token);
            }
          })
        }
        if (this.legacyTutorOpen && this.tutorQuestionId.length > 0) {
          PhoneTutorPage({
            questionId: this.tutorQuestionId,
            readOnly: true,
            onBack: () => { this.legacyTutorOpen = false; },
            onOpenFrozenAnswer: (questionId: string, turnId: string) => {
              this.answerTurnId = turnId;
              this.answerQuestionId = questionId;
            },
            onUnauthorized: (token: string) => { this.handle_signed_out(token); }
          })
        }
        if (this.notesOpen) {
          LearningNotesView({
            backToken: this.notesBackToken,
            onBack: () => { this.notesOpen = false; },
            onUnauthorized: () => { this.handle_signed_out(authSession.read_access_token() ?? ''); }
          })
        }
        if (this.answerQuestionId.length > 0) {
          PhoneAnswerPage({
            questionId: this.answerQuestionId,
            turnId: this.answerTurnId,
            onBack: () => {
              this.answerQuestionId = '';
              this.answerTurnId = '';
            },
            onUnauthorized: (token: string) => {
              this.handle_signed_out(token);
            }
          })
        }
      } else {
        AuthScreen({
          onAuthenticated: (user: UserProfile) => {
            this.handle_authenticated(user);
          },
          onSignedOut: () => {
            this.reset_session();
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  main_shell() {
    Column() {
      Stack() {
        QuestionLibrary({
          refreshToken: this.libraryRefreshToken,
          onOpenQuestion: (questionId: string) => {
            this.answerQuestionId = '';
            this.answerTurnId = '';
            this.tutorQuestionId = '';
            this.detailQuestionId = questionId;
          },
          onUnauthorized: (token: string) => {
            this.handle_signed_out(token);
          }
        })
          .visibility(this.detailQuestionId.length === 0 &&
          this.activeTab === PhoneTab.LIBRARY ? Visibility.Visible : Visibility.None)

        ManualEntry({
          active: this.detailQuestionId.length === 0 && this.activeTab === PhoneTab.ENTRY,
          onSaved: (detail: QuestionDetail) => {
            this.libraryRefreshToken = this.libraryRefreshToken + 1;
            this.activeTab = PhoneTab.LIBRARY;
            this.answerQuestionId = '';
            this.answerTurnId = '';
            this.tutorQuestionId = '';
            this.detailQuestionId = detail.questionId;
          },
          onUnauthorized: (token: string) => {
            this.handle_signed_out(token);
          }
        })
          .visibility(this.detailQuestionId.length === 0 &&
          this.activeTab === PhoneTab.ENTRY ? Visibility.Visible : Visibility.None)

        AccountPage({
          onBack: () => {
            this.activeTab = PhoneTab.LIBRARY;
          },
          onSignedOut: (token: string) => {
            this.handle_signed_out(token);
          }
        })
          .visibility(this.detailQuestionId.length === 0 &&
          this.activeTab === PhoneTab.ACCOUNT ? Visibility.Visible : Visibility.None)

        if (this.detailQuestionId.length > 0) {
          QuestionDetailPage({
            questionId: this.detailQuestionId,
            onBack: () => {
              this.answerQuestionId = '';
              this.answerTurnId = '';
              this.tutorQuestionId = '';
              this.detailQuestionId = '';
            },
            onOpenAnswer: (questionId: string) => {
              this.answerTurnId = '';
              this.answerQuestionId = questionId;
            },
            onOpenTutor: (questionId: string) => {
              this.tutorQuestionId = questionId;
            },
            onUnauthorized: (token: string) => {
              this.handle_signed_out(token);
            }
          })
        }
      }
      .width('100%')
      .layoutWeight(1)

      this.bottom_nav()
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  bottom_nav() {
    Row() {
      this.nav_item(PhoneTab.LIBRARY, $r('app.media.bl_list'), '题库')
      this.nav_item(PhoneTab.ENTRY, $r('app.media.bl_plus'), '录题')
      this.nav_item(PhoneTab.ACCOUNT, $r('app.media.bl_user'), '账号')
    }
    .width('100%')
    .height(PHONE_NAV_HEIGHT)
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  nav_item(tab: PhoneTab, icon: Resource, label: string) {
    Column() {
      Image(icon)
        .width(22)
        .height(22)
      Text(label)
        .fontSize(11)
        .fontWeight(this.activeTab === tab ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.activeTab === tab ? PHONE_COLOR_PRIMARY : PHONE_COLOR_MUTED)
        .margin({ top: 2 })
    }
    .layoutWeight(1)
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .accessibilityText(label)
    .onClick(() => {
      this.answerQuestionId = '';
      this.answerTurnId = '';
      this.tutorQuestionId = '';
      this.detailQuestionId = '';
      this.activeTab = tab;
    })
  }

  private handle_authenticated(user: UserProfile): void {
    const token: string | null = authSession.read_access_token();
    const current: UserProfile | null = authSession.read_user();
    if (token === null || current === null) {
      return;
    }
    if (current.accountId !== user.accountId) {
      return;
    }
    this.enter_session();
  }

  private enter_session(): void {
    const accountId: string = authSession.read_user()?.accountId ?? '';
    if (this.sessionAccountId.length > 0 && this.sessionAccountId !== accountId) {
      phone_v2_clear_all_intents();
    }
    this.sessionAccountId = accountId;
    this.isAuthenticated = true;
    this.activeTab = PhoneTab.LIBRARY;
    this.answerQuestionId = '';
    this.answerTurnId = '';
    this.tutorQuestionId = '';
    this.legacyTutorOpen = false;
    this.notesOpen = false;
    this.detailQuestionId = '';
    this.libraryRefreshToken = this.libraryRefreshToken + 1;
  }

  /**
   * 处理退出或 401；若当前令牌已被新登录替换则忽略旧回调。
   *
   * @param token 触发退出时的访问令牌；空串表示共享登录页已清除会话
   */
  private handle_signed_out(token: string): void {
    if (token.length > 0) {
      const current: string | null = authSession.read_access_token();
      if (current !== null && current !== token) {
        return;
      }
      authSession.clear_session_if_token(token);
    }
    this.reset_session();
  }

  private reset_session(): void {
    this.close_citation(false);
    phone_v2_clear_all_intents();
    this.sessionAccountId = '';
    this.isAuthenticated = false;
    this.activeTab = PhoneTab.LIBRARY;
    this.answerQuestionId = '';
    this.answerTurnId = '';
    this.tutorQuestionId = '';
    this.legacyTutorOpen = false;
    this.notesOpen = false;
    this.detailQuestionId = '';
    this.libraryRefreshToken = this.libraryRefreshToken + 1;
  }

  private open_citation(citationId: string): void {
    if (this.citationController !== null) { this.close_citation(false); }
    this.citationFocusId = 'citation-' + citationId;
    this.citationController = new CustomDialogController({
      builder: CitationModal({
        citationId: citationId,
        onUnauthorized: () => { this.handle_signed_out(authSession.read_access_token() ?? ''); },
        onClose: () => {
          this.close_citation(true);
        }
      }),
      autoCancel: true,
      customStyle: true,
      cancel: () => {
        const focusId: string = this.citationFocusId;
        this.citationController = null;
        this.citationFocusId = '';
        this.restore_citation_focus(focusId);
      }
    });
    this.citationController.open();
  }

  private close_citation(restoreFocus: boolean): void {
    const focusId: string = this.citationFocusId;
    if (this.citationController !== null) {
      this.citationController.close();
      this.citationController = null;
    }
    this.citationFocusId = '';
    if (restoreFocus && focusId.length > 0) {
      this.restore_citation_focus(focusId);
    } else if (!restoreFocus) {
      citationFocusController.clear();
    }
  }

  private restore_citation_focus(focusId: string): void {
    if (focusId.length === 0) { return; }
    const citationId: string = focusId.substring('citation-'.length);
    if (!citationFocusController.restore(citationId)) {
      focusControl.requestFocus(focusId);
    }
  }

  private handle_back(): boolean {
    if (this.citationController !== null) {
      this.close_citation(true);
      return true;
    }
    if (this.answerQuestionId.length > 0) {
      this.answerQuestionId = '';
      this.answerTurnId = '';
      return true;
    }
    if (this.notesOpen) {
      this.notesBackToken++;
      return true;
    }
    if (this.legacyTutorOpen) {
      this.legacyTutorOpen = false;
      return true;
    }
    if (this.tutorQuestionId.length > 0) {
      this.tutorQuestionId = '';
      return true;
    }
    if (this.detailQuestionId.length > 0) {
      this.detailQuestionId = '';
      return true;
    }
    if (this.activeTab !== PhoneTab.LIBRARY) {
      this.activeTab = PhoneTab.LIBRARY;
      return true;
    }
    return false;
  }
}
