import { common } from '@kit.AbilityKit';
import {
  AnswerManifest,
  AnswerState,
  AuthError,
  authSession,
  AuthResult,
  CanvasImageBlock,
  SolutionApi,
  SolutionDetail,
  TutorApi,
  TutorTurn,
  UserProfile
} from 'common';
import {
  describe_learning_error,
  format_question_time
} from '../model/QuestionFormat';
import {
  AnswerTileBounds,
  AnswerTileImage
} from '../model/AnswerTileGeometry';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';
import { AnswerTileViewer } from './AnswerTileViewer';

/**
 * 云端作答的只读查看页：turnId 为空时读取当前完整作答；turnId 非空时只读取
 * 该辅导轮次的冻结文件，绝不回退到当前作答引用。
 *
 * <p>网络、缓存、摘要校验和 file:// 路径全部复用 common 的 SolutionApi 或
 * TutorApi，不在本页复制实现。任一图块失败都不展示半份文档；旧引用 404 或图片
 * 失败时，用户手动重试会重新读取该来源的 manifest 后整份下载。每次 await 后
 * 复查题号、轮次、请求序号、令牌与账号，页面销毁或登录变化后的迟到结果直接丢弃。</p>
 */
@Component
export struct PhoneAnswerPage {
  @Prop @Watch('on_question_changed') questionId: string = '';
  @Prop @Watch('on_question_changed') turnId: string = '';
  onBack: () => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State loading: boolean = false;
  @State errorText: string = '';
  @State answerState: AnswerState = AnswerState.NOT_STARTED;
  @State fileRef: string = '';
  @State bounds: AnswerTileBounds = { x: 0, y: 0, width: 1, height: 1 };
  @State tiles: AnswerTileImage[] = [];
  @State savedAt: string = '';
  @State progressLoaded: number = 0;
  @State progressTotal: number = 0;

  private api: SolutionApi = SolutionApi.create_default();
  private tutorApi: TutorApi = TutorApi.create_default();
  private requestSeq: number = 0;

  aboutToAppear(): void {
    this.load_answer();
  }

  aboutToDisappear(): void {
    this.requestSeq = this.requestSeq + 1;
    this.loading = false;
    this.clear_answer();
  }

  on_question_changed(): void {
    this.requestSeq = this.requestSeq + 1;
    this.loading = false;
    this.clear_answer();
    this.load_answer();
  }

  build() {
    Column() {
      this.top_bar()
      if (!this.loading && this.errorText.length === 0 && this.savedAt.length > 0) {
        this.source_metadata()
      }
      if (this.loading) {
        this.loading_view()
      } else if (this.errorText.length > 0) {
        this.error_view()
      } else if (this.answerState === AnswerState.NOT_STARTED) {
        this.empty_view()
      } else if (this.fileRef.length > 0 && this.tiles.length > 0) {
        Column() {
          AnswerTileViewer({
            documentKey: this.fileRef,
            bounds: this.bounds,
            tiles: this.tiles,
            returnLabel: this.back_label(),
            onReturn: () => {
              this.onBack();
            },
            onRetry: () => {
              this.load_answer();
            }
          })
            .width('100%')
            .height('100%')
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: 12, right: 12, bottom: 12 })
      } else {
        this.error_view()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row({ space: 6 }) {
      Button({ type: ButtonType.Normal }) {
        Row({ space: 2 }) {
          Image($r('app.media.bl_back'))
            .width(20)
            .height(20)
          Text(this.back_label())
            .fontSize(14)
            .fontColor(PHONE_COLOR_TITLE)
        }
      }
      .height(44)
      .padding({ left: 0, right: 4 })
      .backgroundColor(Color.Transparent)
      .accessibilityText(this.back_label())
      .onClick(() => this.onBack())
      Text(this.turnId.length > 0 ? '本轮作答' : '当前作答')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
      Text('只读')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .padding({ left: 12, right: 12, top: 7, bottom: 7 })
        .backgroundColor(PHONE_COLOR_SOFT)
        .borderRadius(18)
    }
    .width('100%')
    .padding({ left: 12, right: 16, top: 12, bottom: 10 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  source_metadata() {
    Column({ space: 5 }) {
      Text((this.turnId.length > 0 ? '提问于 ' : '保存于 ') + format_question_time(this.savedAt))
        .fontSize(15)
        .fontColor(PHONE_COLOR_TITLE)
      Text(this.turnId.length > 0 ? '与这次提问对应的作答' : '这道题已保存的作答')
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
    }
    .width('100%')
    .padding({ left: 18, right: 18, bottom: 14 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  loading_view() {
    Column() {
      LoadingProgress()
        .width(28)
        .height(28)
        .color(PHONE_COLOR_PRIMARY)
      Text(this.progress_total_text())
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 10 })
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(24)
  }

  @Builder
  empty_view() {
    Column() {
      Image($r('app.media.bl_inbox'))
        .width(42)
        .height(42)
      Text('尚无已保存的作答')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .margin({ top: 12 })
      Text('先在平板上书写并保存，再回到这里查看。')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .margin({ top: 6 })
      Button('刷新')
        .height(44)
        .fontSize(12)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .margin({ top: 16 })
        .onClick(() => {
          this.load_answer();
        })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(28)
  }

  @Builder
  error_view() {
    Column() {
      Image($r('app.media.bl_warning'))
        .width(34)
        .height(34)
      Text(this.errorText.length > 0 ? this.errorText : '完整作答暂不可用，请重新加载')
        .fontSize(14)
        .fontColor(PHONE_COLOR_DANGER)
        .textAlign(TextAlign.Center)
        .margin({ top: 12 })
      Row() {
        Button('重新加载')
          .height(44)
          .fontSize(13)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .onClick(() => {
            this.load_answer();
          })
        Button(this.turnId.length > 0 ? '返回辅导' : '返回详情')
          .height(44)
          .fontSize(13)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_CARD)
          .border({ width: 1, color: PHONE_COLOR_BORDER })
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ left: 10 })
          .onClick(() => {
            this.onBack();
          })
      }
      .margin({ top: 18 })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(28)
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  private back_label(): string {
    return this.turnId.length > 0 ? '返回辅导' : '返回详情';
  }

  private async load_answer(): Promise<void> {
    if (this.loading) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.clear_answer();
      this.loading = false;
      this.onUnauthorized('');
      return;
    }
    const user: UserProfile | null = authSession.read_user();
    if (user === null) {
      this.clear_answer();
      this.loading = false;
      this.onUnauthorized('');
      return;
    }
    if (this.questionId.length === 0) {
      this.clear_answer();
      this.loading = false;
      this.errorText = '题目 ID 缺失';
      return;
    }
    const accountId: string = user.accountId;
    const seq: number = ++this.requestSeq;
    this.loading = true;
    this.errorText = '';
    this.clear_answer();
    this.progressLoaded = 0;
    this.progressTotal = 0;
    try {
      if (this.turnId.length > 0) {
        await this.load_turn_answer(token, accountId, seq);
      } else {
        await this.load_current_answer(token, accountId, seq);
      }
    } catch (error) {
      if (this.stale(seq, token, accountId)) {
        return;
      }
      this.fail(this.turnId.length > 0 ? '本轮作答加载异常，请稍后重试' : '作答加载异常，请稍后重试');
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  private async load_current_answer(token: string, accountId: string, seq: number): Promise<void> {
    const result: AuthResult<SolutionDetail> = await this.api.getAnswer(token, this.questionId);
    if (this.stale(seq, token, accountId)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      this.handle_unauthorized(token);
      return;
    }
    if (!result.isSuccess || result.data === null) {
      if (result.error !== null && result.error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      this.fail('作答加载失败：' + describe_learning_error(result.error));
      return;
    }
    const answer: SolutionDetail = result.data;
    if (answer.questionId !== this.questionId) {
      this.fail('作答与当前题目不一致，请重新加载');
      return;
    }
    if (answer.state === AnswerState.NOT_STARTED) {
      this.answerState = AnswerState.NOT_STARTED;
      this.loading = false;
      return;
    }
    const fileRef: string | null = answer.fileRef;
    const manifest: AnswerManifest | null = answer.manifest;
    if (fileRef === null || fileRef.length === 0 || manifest === null) {
      this.fail('完整作答缺少文档，请重新加载');
      return;
    }
    const width: number = manifest.canvas.width;
    const height: number = manifest.canvas.height;
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 ||
      manifest.blocks.length === 0) {
      this.fail('完整作答范围无效，请重新加载');
      return;
    }
    const filesDir: string = this.files_dir();
    if (filesDir.length === 0) {
      this.fail('无法获取应用文件目录，请重新加载');
      return;
    }
    this.progressTotal = manifest.blocks.length;
    const downloaded: AnswerTileImage[] = [];
    for (let index: number = 0; index < manifest.blocks.length; index++) {
      const block: CanvasImageBlock = manifest.blocks[index];
      const tile: AuthResult<string> = await this.api.downloadBlock(token, accountId,
        this.questionId, fileRef, block, filesDir, false);
      if (this.stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!tile.isSuccess || tile.data === null) {
        if (tile.error !== null && tile.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.fail(this.download_error(tile.error, block.index));
        return;
      }
      downloaded.push({
        index: block.index,
        x: block.x,
        y: block.y,
        width: block.width,
        height: block.height,
        source: tile.data
      });
      this.progressLoaded = this.progressLoaded + 1;
    }
    if (this.stale(seq, token, accountId)) {
      return;
    }
    this.bounds = { x: 0, y: 0, width: width, height: height };
    this.fileRef = fileRef;
    this.answerState = answer.state;
    this.savedAt = answer.savedAt === null ? '' : answer.savedAt;
    this.tiles = downloaded;
    this.loading = false;
  }

  private async load_turn_answer(token: string, accountId: string, seq: number): Promise<void> {
    const turnResult: AuthResult<TutorTurn> = await this.tutorApi.getTurn(token, this.questionId, this.turnId);
    if (this.stale(seq, token, accountId)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      this.handle_unauthorized(token);
      return;
    }
    if (!turnResult.isSuccess || turnResult.data === null) {
      if (turnResult.error !== null && turnResult.error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      this.fail('本轮状态加载失败：' + describe_learning_error(turnResult.error));
      return;
    }
    const turn: TutorTurn = turnResult.data;
    if (turn.turnId !== this.turnId || turn.questionId !== this.questionId) {
      this.fail('本轮信息与当前题目不一致，请重新加载');
      return;
    }
    const fileRef: string | null = turn.fileRef;
    if (fileRef === null || fileRef.length === 0) {
      this.fail('本次提问尚未收到作答，请返回辅导查看进度');
      return;
    }
    const manifestResult: AuthResult<AnswerManifest> =
      await this.tutorApi.getFrozenAnswer(token, this.questionId, this.turnId);
    if (this.stale(seq, token, accountId)) {
      return;
    }
    if (!authSession.has_valid_session()) {
      this.handle_unauthorized(token);
      return;
    }
    if (!manifestResult.isSuccess || manifestResult.data === null) {
      if (manifestResult.error !== null && manifestResult.error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      this.fail('本轮作答加载失败：' + describe_learning_error(manifestResult.error));
      return;
    }
    const manifest: AnswerManifest = manifestResult.data;
    const width: number = manifest.canvas.width;
    const height: number = manifest.canvas.height;
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 ||
      manifest.blocks.length === 0) {
      this.fail('本轮作答范围无效，请重新加载');
      return;
    }
    const filesDir: string = this.files_dir();
    if (filesDir.length === 0) {
      this.fail('无法获取应用文件目录，请重新加载');
      return;
    }
    this.progressTotal = manifest.blocks.length;
    const downloaded: AnswerTileImage[] = [];
    for (let index: number = 0; index < manifest.blocks.length; index++) {
      const block: CanvasImageBlock = manifest.blocks[index];
      const tile: AuthResult<string> = await this.tutorApi.downloadFrozenBlock(token, accountId,
        this.questionId, this.turnId, fileRef, block, filesDir, false);
      if (this.stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!tile.isSuccess || tile.data === null) {
        if (tile.error !== null && tile.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.fail(this.download_error(tile.error, block.index));
        return;
      }
      downloaded.push({
        index: block.index,
        x: block.x,
        y: block.y,
        width: block.width,
        height: block.height,
        source: tile.data
      });
      this.progressLoaded = this.progressLoaded + 1;
    }
    if (this.stale(seq, token, accountId)) {
      return;
    }
    this.bounds = { x: 0, y: 0, width: width, height: height };
    this.fileRef = fileRef;
    // 辅导是否生成成功不能代表学生已完成作答；此处只表明存在只读快照。
    this.answerState = AnswerState.IN_PROGRESS;
    // 契约没有快照采集时间。明确标为提问时间，不把 AI 状态更新时间当作作答时间。
    this.savedAt = turn.createdAt;
    this.tiles = downloaded;
    this.loading = false;
  }

  private stale(seq: number, token: string, accountId: string): boolean {
    if (seq !== this.requestSeq) {
      return true;
    }
    if (!authSession.matches_access_token(token) || !this.account_matches(accountId)) {
      this.clear_answer();
      this.loading = false;
      return true;
    }
    return false;
  }

  private account_matches(accountId: string): boolean {
    const user: UserProfile | null = authSession.read_user();
    return user !== null && user.accountId === accountId;
  }

  private files_dir(): string {
    try {
      const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      return context.filesDir;
    } catch (error) {
      return '';
    }
  }

  private download_error(error: AuthError | null, blockIndex: number): string {
    if (error !== null && error.httpStatus === 404) {
      return this.turnId.length > 0 ? '本轮作答暂不可用，请返回后重新打开' :
        '当前作答已更新，请重新加载完整作答';
    }
    return '作答图像 ' + (blockIndex + 1).toString() + ' 加载失败：' +
      describe_learning_error(error);
  }

  private progress_total_text(): string {
    if (this.progressTotal > 0) {
      return '正在加载作答图像 ' + this.progressLoaded.toString() + '/' +
        this.progressTotal.toString();
    }
    return this.turnId.length > 0 ? '正在加载本轮作答…' : '正在加载已保存的作答…';
  }

  private fail(message: string): void {
    this.clear_answer();
    this.errorText = message;
    this.loading = false;
  }

  private clear_answer(): void {
    this.answerState = AnswerState.NOT_STARTED;
    this.fileRef = '';
    this.bounds = { x: 0, y: 0, width: 1, height: 1 };
    this.tiles = [];
    this.savedAt = '';
  }

  private handle_unauthorized(token: string): void {
    this.requestSeq = this.requestSeq + 1;
    this.loading = false;
    this.errorText = '';
    this.clear_answer();
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}