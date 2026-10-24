# M1 共享题面渲染交付

## 使用与范围

从 `common` 引入 `QuestionMarkdownView`：

```ts
QuestionMarkdownView({
  bodyMarkdown: detail.bodyMarkdown,
  fontSize: 16,
  onRenderError: () => {
    // 可选：页面需要时显示重试提示；不要在这里记录题目正文。
  }
})
  .width('100%')
  .height('100%')
```

`bodyMarkdown` 和 `fontSize` 变化会重新渲染。父容器给出实际宽高，Web 内部垂直滚动；
超宽行内、块级及带编号公式由公式容器横向滚动。失败公式显示明确错误与安全转义原式，
便于核对输入；资源或渲染初始化失败显示原生重试层，不把原 LaTeX 当正常公式交付。
本组件只展示题面，不包含 M2 画布、预览图片、截图生成、作答或 AI。

## 本地资源与精确版本

用户已通过统筹明确批准 `docs/testing/mvp-m1/dependency-candidates.md` 的精确 10 包清单。
本提交只接入批准发行包中的浏览器文件及许可证，不修改 common 的 oh-package 依赖，
不引入其他包。Web 的 package/catalog/lock 由 C4 在独立 TEST 树维护。

- markdown-it 15.0.3：`dist/browser/markdown-it.umd.min.js` 原字节复制为 `vendor/markdown-it.min.js`。
- markdown-it-texmath 1.0.0：`texmath.js`、`css/texmath.css` 原字节复制到 `vendor/`。
- katex 0.19.1：`dist/katex.min.js`、`dist/katex.min.css` 和 CSS 引用的全部 60 件字体
  （20 TTF、20 WOFF、20 WOFF2）原字节复制到 `vendor/` 与 `vendor/fonts/`。
- 原始包告知/许可证共 10 份，位于 `vendor/licenses/`；包含已批准传递包的告知文本。

65 件浏览器运行文件合计 1,489,744 字节。`renderer-assets.lock.json` 记录全部 75 件
第三方文件的目标路径、发行包路径、字节数及 SHA256，以及 10 个批准包的版本、
发行包 SHA256/SHA512 integrity 与源审计清单哈希。写入前已核对全部 10 个发行包，
不执行 npm 生命周期脚本，不执行依赖代码，也不提交 tgz、源码、CLI 或构建产物。

## 加载与内容边界

自有 HTML 按 markdown-it UMD、KaTeX、texmath 顺序加载，显式传入 `engine: window.katex`。
texmath 负责 `$...$`、`$$...$$`、`\(...\)`、`\[...\]` 的识别；自有 renderer 通过
同版 KaTeX 生成公式 HTML/MathML。每个公式使用独立宏对象，限制展开次数，关闭 trust。

`QuestionMarkdownView` 将固定 `.invalid` 同源 URL 的请求映射到 HAR 合并的本地 rawfile；
只接受自有 HTML/JS/CSS 和批准 vendor 路径，其余请求直接返回 403，不回落到网络。
HTML 禁用远端连接、图片、frame、object、表单及原始 HTML；Markdown 链接显示为文字，
图片仅显示转义后的说明。不注册 JS 到原生的代理，不向网页传入令牌、账号或请求对象。
正文通过 JSON 字符串参数传给固定渲染函数，不拼接为 HTML 或 URL，不输出正文日志。

## 文件归属

- `Index.ets`：公共组件导出。
- `src/main/ets/features/learning/QuestionMarkdownView.ets`：ArkWeb 适配、资源映射和错误重试层。
- `src/main/resources/rawfile/question-markdown/{index.html,renderer.js,renderer.css}`：自有渲染壳。
- `src/main/resources/rawfile/question-markdown/vendor/`：65 件同发行资源与 10 份许可证。
- `renderer-assets.lock.json`：逐文件版本与哈希清单。
- `M1_USAGE.md`、本文：调用与交付说明。

此前共享 API 提交 `65dd320523a0bb127afde0a88f3c13e525278dc3` 保持不变；本提交不改
LearningApi、DTO、认证流程、18081 开发地址、签名或设备映射。

## 验证与 C4 接收

C2 已做只读协议/代码复核、源包 SHA256/SHA512 及逐文件 SHA256 核对、字体 CSS
引用集合对照、HTML 资源路径/ArkWeb 白名单及 UMD/token 名静态核对。75 件第三方文件
在 Git 索引中的字节也逐一核对一致，无换行转换。自有 renderer.js 的 `node --check`
语法检查及排除 vendor 后的 `git diff --cached --check` 通过。

完整 `git diff --cached --check` 返回 2，408 项均来自保持原字节的上游 texmath 文件：
`vendor/texmath.js` 368 行、`vendor/texmath.css` 19 行、
`vendor/licenses/markdown-it-texmath@1.0.0.txt` 21 行。CRLF 与上游行尾空白由原包自带，
按获批哈希保留；本提交没有修改 Git 空白规则或提交钩子。

这些检查不能证明实际 ArkWeb 呈现。开发树没有运行构建、测试、服务或设备命令。

C4 接收确定提交后统一验证：

1. Phone/Tablet 实际编译和 HAR 资源合并；两端从同一正式 questionId 取回 bodyMarkdown。
2. 段落、列表、粗体、换行；行内、块级、分式、积分、根式、上下标及错误公式。
3. 横屏/窄屏下长行内、块级、带编号公式的横向浏览，以及长题面的纵向浏览。
4. 60 字体的资源覆盖与实际字体加载；离线资源映射不发 CDN 或其他外部网络请求。
5. bodyMarkdown 更新、重复打开详情、加载失败重试，以及退出/换账号后原页面不回写。
6. HTML、链接、远程图片和 KaTeX trust 命令不能跨越既定内容边界。

功能验收结果与实机证据由 C4 保存，本文件不宣称构建、设备或端到端已经通过。
