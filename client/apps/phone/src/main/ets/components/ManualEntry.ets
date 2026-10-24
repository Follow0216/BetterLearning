import {
  authSession,
  AuthResult,
  LearningApi,
  learning_subject_label,
  LearningSubject,
  ManualQuestionInput,
  MAX_QUESTION_MARKDOWN_LENGTH,
  QuestionDetail
} from 'common';
import { describe_confirm_error } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/**
 * 手工 Markdown 录题：选择学科、输入正文并确认入库。
 *
 * <p>首次确认生成一次不可变的 {@link ManualQuestionInput}；双击、超时或断网
 * 重试复用同一对象，只有正文或学科改变才重建 requestId。成功后在父级打开
 * 同 questionId 的详情并刷新题库。</p>
 */
@Component
export struct ManualEntry {
  onSaved: (detail: QuestionDetail) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State subject: LearningSubject = LearningSubject.HIGHER_MATHEMATICS;
  @State bodyMarkdown: string = '';
  @State errorText: string = '';
  @State submitting: boolean = false;
  @State pendingInput: ManualQuestionInput | null = null;

  private api: LearningApi = LearningApi.create_default();
  private requestSeq: number = 0;

  build() {
    Column() {
      this.header()
      Scroll() {
        Column() {
          this.subject_section()
          this.editor_section()
          if (this.errorText.length > 0) {
            this.error_banner()
          }
          this.submit_button()
          Text('确认后题目进入“待整理”，入库后 M1 不提供修改；不含照片、OCR 或 AI 识别。')
            .fontSize(11)
            .fontColor(PHONE_COLOR_MUTED)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 10 })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .padding({ left: 18, right: 18, bottom: 28 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Column() {
      Text('录题')
        .fontSize(30)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
      Text('手工粘贴 Markdown，确认后保存到当前账号')
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 4 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 18, right: 18, top: 18, bottom: 12 })
  }

  @Builder
  subject_section() {
    Column() {
      Text('学科')
        .fontSize(13)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .width('100%')
      Row() {
        this.subject_chip(LearningSubject.HIGHER_MATHEMATICS)
        this.subject_chip(LearningSubject.OTHER)
      }
      .width('100%')
      .margin({ top: 8 })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 6 })
  }

  @Builder
  subject_chip(subject: LearningSubject) {
    Text(learning_subject_label(subject))
      .fontSize(13)
      .fontWeight(this.subject === subject ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.subject === subject ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .backgroundColor(this.subject === subject ? PHONE_COLOR_PRIMARY : PHONE_COLOR_SOFT)
      .borderRadius(14)
      .margin({ right: 8 })
      .enabled(!this.submitting)
      .accessibilityText('选择学科 ' + learning_subject_label(subject))
      .onClick(() => {
        this.change_subject(subject);
      })
  }

  @Builder
  editor_section() {
    Column() {
      Row() {
        Text('题目 Markdown')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Text(this.bodyMarkdown.length.toString() + ' / ' + MAX_QUESTION_MARKDOWN_LENGTH.toString())
          .fontSize(11)
          .fontColor(this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH ?
          PHONE_COLOR_DANGER : PHONE_COLOR_MUTED)
      }
      .width('100%')

      TextArea({ text: this.bodyMarkdown, placeholder: '# 题干\n在这里输入完整题目 Markdown，可包含公式。' })
        .width('100%')
        .height(260)
        .fontSize(14)
        .fontColor(PHONE_COLOR_TITLE)
        .placeholderColor(PHONE_COLOR_MUTED)
        .backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(PHONE_RADIUS_CONTROL)
        .padding(12)
        .maxLength(MAX_QUESTION_MARKDOWN_LENGTH)
        .enabled(!this.submitting)
        .margin({ top: 10 })
        .onChange((value: string) => {
          this.bodyMarkdown = value;
          this.pendingInput = null;
          this.errorText = '';
        })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 12 })
  }

  @Builder
  error_banner() {
    Text(this.errorText)
      .fontSize(12)
      .fontColor(PHONE_COLOR_DANGER)
      .lineHeight(18)
      .width('100%')
      .padding(12)
      .backgroundColor(PHONE_COLOR_DANGER_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .margin({ top: 12 })
  }

  @Builder
  submit_button() {
    Button(this.submit_label())
      .width('100%')
      .height(48)
      .fontSize(15)
      .fontWeight(FontWeight.Medium)
      .fontColor(PHONE_COLOR_WHITE)
      .backgroundColor(this.submitting ? PHONE_COLOR_MUTED : PHONE_COLOR_PRIMARY)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.submitting)
      .opacity(this.submitting ? 0.65 : 1)
      .margin({ top: 16 })
      .onClick(() => {
        this.confirm();
      })
  }

  private submit_label(): string {
    if (this.submitting) {
      return '正在保存…';
    }
    return this.pendingInput === null ? '确认保存' : '重试保存';
  }

  private change_subject(subject: LearningSubject): void {
    if (this.submitting || this.subject === subject) {
      return;
    }
    this.subject = subject;
    this.pendingInput = null;
    this.errorText = '';
  }

  /**
   * 校验正文并确认入库；失败保留同一确认对象供重试。
   */
  private async confirm(): Promise<void> {
    if (this.submitting) {
      return;
    }
    const validation: string = this.validate();
    if (validation.length > 0) {
      this.errorText = validation;
      return;
    }
    let input: ManualQuestionInput | null = this.pendingInput;
    if (input === null || input.subject !== this.subject ||
      input.bodyMarkdown !== this.bodyMarkdown) {
      input = ManualQuestionInput.create(this.subject, this.bodyMarkdown);
      this.pendingInput = input;
    }
    if (input === null) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const seq: number = ++this.requestSeq;
    this.submitting = true;
    this.errorText = '';
    try {
      const result: AuthResult<QuestionDetail> = await this.api.confirmManualQuestion(token, input);
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) {
          authSession.clear_session_if_token(token);
          this.onUnauthorized(token);
          return;
        }
        this.errorText = describe_confirm_error(result.error);
        return;
      }
      const detail: QuestionDetail = result.data;
      this.pendingInput = null;
      this.bodyMarkdown = '';
      this.onSaved(detail);
    } catch (error) {
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      this.errorText = '确认请求异常，请重试';
    } finally {
      if (seq === this.requestSeq) {
        this.submitting = false;
      }
    }
  }

  private validate(): string {
    if (this.bodyMarkdown.trim().length === 0) {
      return '请输入题目正文';
    }
    if (this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
      return '题目正文不能超过 20000 个字符';
    }
    return '';
  }
}