# Web 顶部气泡

遵循 `docs/contracts/frontend-toast-v1.md`。仅使用现有 React / ReactDOM / CSS，实例属于每个 Provider，不保存 Next 服务端全局状态。

在 Server Layout 中导入 `@betterlearning/ui-feedback/styles.css`，用小型客户端包装组件挂 `ToastProvider`，将原 Server children 原样传入。CSS 直接由 Next 编译，无单独构建产物；workspace 源码包无需额外 transpile 配置。默认顶端 80px，可在应用全局样式用 `--bl-toast-top` 调整顶栏下方间距。

```tsx
"use client";
import { ToastProvider, useToast } from "@betterlearning/ui-feedback";

// 根包装：<ToastProvider>{children}<RouteScope /></ToastProvider>
// RouteScope 用 Next pathname / 明确的业务上下文调用 onScopeChange。
const { show, dismiss, clearScope, onScopeChange, captureScope } = useToast();
onScopeChange("library:route:account:question");
const scope = captureScope(); // 异步请求之前
// await 业务操作；保留页面原有错误、草稿、loading 与原位重试。
show({ key: "collection-save", type: "success", message: "题集已保存" }, scope);
```

所有方法返回 void，captureScope 返回 number。route/account/question 变化时先 onScopeChange；同身份重复调用不会清理。注销和主动失效可 clearScope。账号身份用非敏感 ID，不包含口令/token。成功后马上跳转的操作以目标页真实身份状态表达成功，避免扩展跨路由通知队列。

同时最多两条，其他 FIFO 排队；卡片 DOM 提交后才计时。同 key 保留位置并更新内容、重新计时；悬停/键盘焦点各自暂停，均离开后继续剩余时间。普通/成功默认 3 秒，错误 6 秒，所有卡片提供 44px 关闭按钮。关闭只清显示。宿主空白区域 pointer-events:none；气泡本身可交互。

`pnpm --filter @betterlearning/ui-feedback test:run` 使用 Node 24 内置测试与类型擦除，覆盖计时、队列、同 key、暂停、scope、卸载及失效回调，不引入新的测试依赖。组件/浏览器验收仍须由 C4 完成。
