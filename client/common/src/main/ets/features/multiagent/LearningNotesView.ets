import { AuthError, AuthResult } from '../auth/AuthModels';
import { authSession } from '../auth/AuthSession';
import { QuestionMarkdownView } from '../learning/QuestionMarkdownView';
import { LearningV2Api } from './LearningV2Api';
import { V2Note, V2NotePage } from './LearningV2Models';

/** Phone/Tablet only read notes. Each open reloads the authorized current version. */
@Component
export struct LearningNotesView {
  @Prop @Watch('backTokenChanged') backToken: number = 0;
  onBack: () => void = () => {};
  onUnauthorized: () => void = () => {};
  @State private notes: V2Note[] = [];
  @State private selected: V2Note | null = null;
  @State private loading: boolean = false;
  @State private errorText: string = '';
  @State private nextCursor: string | null = null;
  private api: LearningV2Api = LearningV2Api.create_default();
  private generation: number = 0;
  private token: string = '';
  private sessionTimer: number = -1;

  aboutToAppear(): void {
    this.loadList();
    this.sessionTimer = setInterval(() => {
      if (!authSession.has_valid_session() || !authSession.matches_access_token(this.token)) {
        this.generation++; this.notes = []; this.selected = null; this.nextCursor = null;
        this.errorText = '登录状态已变化，请重新打开笔记';
      }
    },1000);
  }
  aboutToDisappear(): void {
    this.generation++; clearInterval(this.sessionTimer); this.notes = []; this.selected = null; this.token = '';
  }
  private active(generation: number, token: string): boolean {
    return generation === this.generation && authSession.has_valid_session() && authSession.matches_access_token(token);
  }
  private fail(error: AuthError | null, token: string): void {
    if (error !== null && error.is_unauthorized()) {
      this.notes = []; this.selected = null;
      if (authSession.clear_session_if_token(token)) { this.onUnauthorized(); }
      this.errorText = '登录已失效，请重新登录';
    } else if (error !== null && (error.httpStatus === 403 || error.httpStatus === 404)) {
      this.selected = null; this.errorText = '笔记已删除或无权访问';
    } else { this.errorText = '笔记加载失败，请检查网络后重试'; }
  }
  private async loadList(more: boolean = false): Promise<void> {
    if (this.loading) { return; }
    const generation: number = ++this.generation;
    const token: string | null = authSession.read_access_token(); this.token = token ?? '';
    if (token === null) { this.onUnauthorized(); return; }
    this.loading = true; this.errorText = '';
    let result: AuthResult<V2NotePage>;
    try { result = await this.api.listNotes(token,more ? this.nextCursor : null); }
    catch (error) {
      if (this.active(generation,token)) { this.loading = false; this.errorText = '笔记加载失败，请重试'; }
      return;
    }
    if (!this.active(generation,token)) { return; }
    this.loading = false;
    if (!result.isSuccess || result.data === null) { this.fail(result.error,token); return; }
    const items: V2Note[] = more ? this.notes.slice() : [];
    result.data.items.forEach((note: V2Note) => {
      if (!items.some((old: V2Note) => old.noteId === note.noteId)) { items.push(note); }
    });
    this.notes = items; this.nextCursor = result.data.nextCursor ?? null;
  }
  private async openNote(noteId: string): Promise<void> {
    const generation: number = ++this.generation;
    const token: string | null = authSession.read_access_token(); this.token = token ?? '';
    if (token === null) { this.onUnauthorized(); return; }
    this.loading = true; this.errorText = ''; this.selected = null;
    let result: AuthResult<V2Note>;
    try { result = await this.api.getNote(token,noteId); }
    catch (error) {
      if (this.active(generation,token)) { this.loading = false; this.errorText = '笔记加载失败，请重试'; }
      return;
    }
    if (!this.active(generation,token)) { return; }
    this.loading = false;
    if (!result.isSuccess || result.data === null) { this.fail(result.error,token); return; }
    this.selected = result.data;
  }
  private backTokenChanged(): void { this.goBack(); }
  private goBack(): void {
    this.generation++; this.loading = false;
    if (this.selected !== null) { this.selected = null; this.errorText = ''; }
    else { this.onBack(); }
  }
  private statusLabel(note: V2Note): string {
    if (note.indexStatus === 'READY') { return '可检索'; }
    if (note.indexStatus === 'INDEX_PENDING') { return '已保存，检索索引待更新'; }
    if (note.indexStatus === 'FAILED') { return '已保存，索引更新失败'; }
    return '已撤回';
  }
  build() {
    Column({ space: 12 }) {
      Row({ space: 12 }) {
        Button('返回').id('learning-notes-back').onClick(() => {
          this.goBack();
        })
        Text(this.selected === null ? '学习笔记' : '笔记详情').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)
        Button('刷新').onClick(() => {
          if (this.selected !== null) { this.openNote(this.selected.noteId); } else { this.loadList(); }
        })
      }.width('100%')
      Text('手机和平板只读；可在 Web 管理文字与公式笔记。').fontSize(12).fontColor('#64748B').width('100%')
      if (this.errorText.length > 0) { Text(this.errorText).fontColor('#B91C1C').width('100%') }
      if (this.loading) { LoadingProgress().width(32).height(32) }
      if (this.selected !== null) {
        Text(this.selected.title).fontSize(20).fontWeight(FontWeight.Medium).width('100%')
        Text('v' + this.selected.version.toString() + ' · ' + this.selected.updatedAt).fontSize(12).fontColor('#64748B').width('100%')
        Text(this.statusLabel(this.selected)).fontSize(12).fontColor('#64748B').width('100%')
        QuestionMarkdownView({ bodyMarkdown: this.selected.bodyMarkdown, maxLength: 65536 }).width('100%').layoutWeight(1)
      } else {
        Scroll() {
          Column({ space: 10 }) {
            if (this.notes.length === 0 && !this.loading && this.errorText.length === 0) {
              Text('暂无笔记，可在 Web 创建。').fontSize(14).fontColor('#64748B').padding(24)
            }
            ForEach(this.notes,(note: V2Note) => {
              Button() {
                Column({ space: 6 }) {
                  Text(note.title).fontSize(16).fontWeight(FontWeight.Medium).width('100%')
                  Text('v' + note.version.toString() + ' · ' + note.updatedAt).fontSize(12).fontColor('#64748B').width('100%')
                  Text(this.statusLabel(note)).fontSize(12).fontColor('#64748B').width('100%')
                }.width('100%').alignItems(HorizontalAlign.Start).padding(12)
              }.width('100%').type(ButtonType.Normal).backgroundColor('#F8FAFC').fontColor('#0F172A').borderRadius(10)
                .onClick(() => { this.openNote(note.noteId); })
            },(note: V2Note) => note.noteId + ':' + note.version.toString())
            if (this.nextCursor !== null) {
              Button('加载更多').enabled(!this.loading).onClick(() => { this.loadList(true); })
            }
          }.width('100%')
        }.width('100%').layoutWeight(1)
      }
    }.padding(16).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
