# 顶部气泡通知契约 v1

状态：冻结；负责人 T1。基线 `0c8bd94556d417a00bde847b055d26363a46eda5`。
设计来源：主项目 `docs/design/center-feedback-20261025/index-top-v2.html`、`*-top-v2.png` 与 `prompts-top-v2.json`（只读审核稿）。旧居中模态版不再采用。

## 跨平台公开语义

```typescript
type ToastType = 'info' | 'success' | 'error';
interface ToastOptions {
  key: string;
  type: ToastType;
  message: string;
  description?: string;
  durationMs?: number;
}

// 每个窗口 / React 根各创建一个实例，不导出进程全局单例。
class ToastStore {
  show(options: ToastOptions, scopeToken?: number): void;
  dismiss(key: string): void;
  clearScope(): void;
  onScopeChange(scopeId: string): void;
  captureScope(): number;
  dispose(): void;
}
```

ArkUI 导出 `ToastType`、`ToastOptions`、`ToastItem`、`ToastStore`、`ToastHost`；React 可使用原生 Provider/Hook 包装同一语义，不要求复用 ArkUI 实现或装饰器。

- `key` 和 `message` 去除首尾空白后必须非空，否则忽略。`description` 空白视为缺失。文案由业务回调给出，不显示原始异常、令牌或调试信息。
- `info` / `success` 默认 3000ms，`error` 默认 6000ms。`durationMs` 仅接受正的有限毫秒数，其余取默认值；不支持永久气泡。
- 同一 Store 最多两条可见，其他条目按 FIFO 排队；只有实际进入 Host 显示后才开始计时。排队期间不消耗时长。Host 尚未挂载时只排队，不启动计时。
- 同 key 合并：更新内容、类型、时长，不改变原有位置，不额外增加一条；可见项从更新后重新计完整时长，排队项保留队列位置。已经关闭或过期的 key 可以再次显示。
- 每条均有右侧 × 手动关闭。`dismiss(key)` 可移除可见项或排队项；重复关闭无副作用。关闭、过期和清理只改变显示，不清业务错误、不重试请求、不清草稿、不改变加载或保存状态。
- Web：悬停或键盘焦点位于气泡内时暂停，两个原因都解除后继续剩余时长；不重新计完整时长。Native 可采用相同暂停语义，不能抢夺现有输入焦点。
- `onScopeChange(scopeId)` 在身份变化时清除当前全部可见和排队项并递增上下文代数；同身份重复调用不清除。身份应组合路由、账号和题目等会使结果过时的输入，不得包含口令或令牌。
- `clearScope()` 无条件清除当前全部显示和队列并递增代数；用于退出、宿主移除等生命周期。`captureScope()` 返回当前代数，异步操作发起前捕获，完成后传入 `show`；不等于当前代数的结果被忽略。省略 token 表示当前同步事件。
- `dispose()` 取消定时器、移除订阅、清空条目；之后调用无副作用。实例不能复用；再次出现的窗口创建新 Store。

## ArkUI 接入与所有权

根页面持有 `private toast: ToastStore = new ToastStore()`；将同一实例显式传给需要通知的子组件（普通字段或回调）。禁止为嵌套页面另建全局单例或每次渲染新建实例。根 `Stack({ alignContent: Alignment.Top })` 的最后一个子组件放置 `ToastHost({ store: this.toast, topInset: 12 })`；Host 在页面安全区内，`topInset` 可由根页面按顶栏、安全区设置。不放入滚动容器、Column 文档流或画布缩放节点。

```typescript
// 根生命周期 / 导航回调
this.toast.onScopeChange('phone:library:' + userId + ':' + questionId);
// 发起异步业务操作前
const scope: number = this.toast.captureScope();
// 成功或失败的业务回调中（字段错误和持续错误仍原位保留）
this.toast.show({ key: 'answer-save', type: 'success', message: '作答已保存，可以继续书写' }, scope);
// 根窗口真正销毁时
this.toast.dispose();
```

`ToastHost` 公开参数：`store: ToastStore`（必填）、`topInset: number = 12`、`maxWidth: number = 440`、`sideInset: number = 16`。一个 Store 同时只挂一个 Host。Host 移除时解除订阅并 `clearScope()`，取消旧计时；之后可在同一窗口重新挂载。

覆盖层无遮罩，不改变原布局，顶部水平居中，不随画布缩放。宿主空白区域穿透；气泡本身及关闭点击不穿透。单个气泡使用白色、细边框、14vp 圆角、轻阴影、左状态标识和右关闭按钮；有 description 时主文案一行、说明最多两行；无说明时主文案最多两行。关闭触控区域至少 44vp，不自动请求焦点，不弹键盘。

## 调用边界

用户主动操作的结果由对应业务回调触发。字段校验、持续错误、未保存、加载状态和原位重试保持原有事实呈现。禁止 API 层对所有异常强制弹通知；禁止在 build/render、自动保存、每次轮询或笔 MOVE 中触发。没有通知中心、持久化、推送、新第三方依赖或后端接口。

T1 唯一维护 common/Index.ets 的新增出口；T2 负责 phone/tablet 根与业务回调接线。AuthScreen 适配须与 T2 确认唯一写入者。其他账号模型/API 负责人通过 T1 提供新增出口需求，禁止多人写同一 Index.ets。

## 验证边界

T1 纯逻辑验证：时长、FIFO、同 key 合并、手动关闭、真实显示后计时、scope 迟到结果拒绝、销毁和失效定时器。Web 另验证悬停/焦点叠加暂停。C4 独占正式 phone/tablet 编译、设备安装与触控穿透/安全区/焦点验收；静态/纯逻辑通过不能替代设备验收。
