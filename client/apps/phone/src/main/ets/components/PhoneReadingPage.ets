import { citationFocusController, citation_marker_ids, MAX_QUESTION_MARKDOWN_LENGTH,
  QuestionMarkdownView, V2Citation } from 'common';
import { PHONE_COLOR_CARD, PHONE_COLOR_TITLE } from '../theme/PhoneTheme';

/** 独立阅读页：题面、长提问和解答使用全部可用高度，不嵌在聊天卡内滚动。 */
@Component
export struct PhoneReadingPage {
  @Prop title: string = '完整阅读';
  @Prop bodyMarkdown: string = '';
  @Prop plainText: boolean = false;
  @Prop citations: V2Citation[] = [];
  @Prop markdownLimit: number = MAX_QUESTION_MARKDOWN_LENGTH;
  onCitation: (citationId: string) => void = () => {};
  onBack: () => void = () => {};

  build() {
    Column() {
      Row() {
        Button({ type: ButtonType.Normal }) {
          Image($r('app.media.bl_back')).width(22).height(22)
        }
        .width(44).height(44).backgroundColor(Color.Transparent)
        .accessibilityText('返回对话')
        .onClick(() => { this.onBack(); })
        Text(this.title).fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
      }
      .width('100%').padding({ left: 8, right: 16, top: 8, bottom: 8 })
      if (this.plainText || this.bodyMarkdown.length > Math.min(65536,this.markdownLimit)) {
        if (!this.plainText) {
          Text('内容较长，暂以文字显示，公式排版暂不可用。可长按复制全文。')
            .fontSize(13).lineHeight(20).fontColor('#5A6B85').width('100%')
            .padding(14).backgroundColor('#EEF3FB')
        }
        Scroll() {
          Text(this.bodyMarkdown).fontSize(16).lineHeight(26).fontColor(PHONE_COLOR_TITLE)
            .copyOption(CopyOptions.InApp).width('100%').padding(20)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart)
        ForEach(citation_marker_ids(this.bodyMarkdown, this.citations), (citationId: string) => {
          Button('查看引用来源').id('citation-' + citationId).height(44)
            .onClick(() => {
              citationFocusController.clear();
              this.onCitation(citationId);
            })
        }, (citationId: string) => citationId)
      } else {
        QuestionMarkdownView({ bodyMarkdown: this.bodyMarkdown, fontSize: 17, maxLength: this.markdownLimit,
          citations: this.citations,
          onCitation: (citationId: string) => { this.onCitation(citationId); } })
          .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_CARD)
  }
}
