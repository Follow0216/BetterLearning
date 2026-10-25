import { PhoneApp } from '../components/PhoneApp';
import { phoneBackController } from '../model/PhoneBackController';

@Entry
@Component
struct Index {
  onPageShow(): void {
    AppStorage.setOrCreate('phoneForeground', true);
  }

  onPageHide(): void {
    AppStorage.setOrCreate('phoneForeground', false);
  }

  onBackPress(): boolean {
    return phoneBackController.dispatch();
  }

  build() {
    Column() {
      PhoneApp()
    }
    .width('100%')
    .height('100%')
  }
}
