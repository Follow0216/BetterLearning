import { AuthScreen } from 'common';

@Entry
@Component
struct Index {
  build() {
    Column() {
      AuthScreen()
    }
    .width('100%')
    .height('100%')
  }
}