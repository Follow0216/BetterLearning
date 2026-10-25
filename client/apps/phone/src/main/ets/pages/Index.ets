import { PhoneApp } from '../components/PhoneApp';
import { PhoneBackController } from '../model/PhoneHomeState';

@Entry
@Component
struct Index {
  private navigation: PhoneBackController = new PhoneBackController();

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
