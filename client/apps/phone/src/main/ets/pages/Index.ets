import { PhoneApp } from '../components/PhoneApp';

@Entry
@Component
struct Index {
  build() {
    Column() {
      PhoneApp()
    }
    .width('100%')
    .height('100%')
  }
}