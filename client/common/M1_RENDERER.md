# M1 共享题面渲染使用说明

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

依赖版本与许可证清单见 `docs/dependencies/m1-renderer-approved.md`。
浏览器资源与许可证位于 common，本地资源不依赖 CDN。

- markdown-it 15.0.3：`dist/browser/markdown-it.umd.min.js` 原字节复制为 `vendor/markdown-it.min.js`。
- markdown-it-texmath 1.0.0：`texmath.js`、`css/texmath.css` 原字节复制到 `vendor/`。
- katex 0.19.1：`dist/katex.min.js`、`dist/katex.min.css` 和 CSS 引用的全部 60 件字体
  （20 TTF、20 WOFF、20 WOFF2）原字节复制到 `vendor/` 与 `vendor/fonts/`。
- 原始包告知/许可证共 10 份，位于 `vendor/licenses/`；包含传递包的告知文本。

65 件浏览器运行文件合计 1,489,744 字节。`renderer-assets.lock.json` 记录全部 75 件
第三方文件的目标路径、发行包路径、字节数及 SHA256，以及 10 个包的版本、
发行包 SHA256/SHA512 integrity 与源审计清单哈希。

## 加载与内容边界

自有 HTML 按 markdown-it UMD、KaTeX、texmath 顺序加载，显式传入 `engine: window.katex`。
texmath 负责 `$...$`、`$$...$$`、`\(...\)`、`\[...\]` 的识别；自有 renderer 通过
同版 KaTeX 生成公式 HTML/MathML。每个公式使用独立宏对象，限制展开次数，关闭 trust。

`QuestionMarkdownView` 将固定 `.invalid` 同源 URL 的请求映射到 HAR 合并的本地 rawfile；
只接受自有 HTML/JS/CSS 和指定 vendor 路径，其余请求直接返回 403，不回落到网络。
HTML 禁用远端连接、图片、frame、object、表单及原始 HTML；Markdown 链接显示为文字，
图片仅显示转义后的说明。不注册 JS 到原生的代理，不向网页传入令牌、账号或请求对象。
正文通过 JSON 字符串参数传给固定渲染函数，不拼接为 HTML 或 URL，不输出正文日志。

## 文件归属

- `Index.ets`：公共组件导出。
- `src/main/ets/features/learning/QuestionMarkdownView.ets`：ArkWeb 适配、资源映射和错误重试层。
- `src/main/resources/rawfile/question-markdown/{index.html,renderer.js,renderer.css}`：自有渲染壳。
- `src/main/resources/rawfile/question-markdown/vendor/`：65 件同发行资源与 10 份许可证。
- `renderer-assets.lock.json`：逐文件版本与哈希清单。
- `M1_USAGE.md`、本文：调用与资源说明。
