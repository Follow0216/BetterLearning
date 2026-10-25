import { KeyboardAvoidMode } from '@kit.ArkUI';
import { PhoneApp } from '../components/PhoneApp';
import { PhoneBackController } from '../model/PhoneHomeState';

@Entry
@Component
struct Index {
  private navigation: PhoneBackController = new PhoneBackController();
  private previousKeyboardMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardModeOwned: boolean = false;

  aboutToAppear(): void {
    this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardModeOwned = true;
  }

  aboutToDisappear(): void {
    if (this.keyboardModeOwned) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.keyboardModeOwned = false;
    }
  }

  onBackPress(): boolean {
    return this.navigation.handleBack();
  }

  build() {
    Column() {
      PhoneApp({ navigation: this.navigation })
    }
    .width('100%').height('100%')
  }
}
