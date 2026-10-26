# M1 Markdown 与公式渲染依赖清单

下列版本与对应浏览器资源为已批准的 M1 渲染依赖范围。变更版本或增加包须重新核对锁文件与许可证。此清单记录依赖范围；功能质量以对应版本的实际构建和端侧验收为准。

| 包 | 版本 | 类型 | 许可证 |
| --- | --- | --- | --- |
| `katex` | `0.19.1` | 直接 | MIT |
| `markdown-it` | `15.0.3` | 直接 | MIT |
| `markdown-it-texmath` | `1.0.0` | 直接 | MIT |
| `argparse` | `3.0.4` | 传递 | PSF-2.0 |
| `commander` | `15.0.0` | 传递 | MIT |
| `entities` | `8.1.0` | 传递 | BSD-2-Clause |
| `linkify-it` | `6.1.0` | 传递 | MIT |
| `mdurl` | `2.1.0` | 传递 | MIT |
| `punycode.js` | `2.3.1` | 传递 | MIT |
| `uc.micro` | `3.0.0` | 传递 | MIT |

ArkWeb 使用同发行包的 `markdown-it` UMD、`texmath.js` 与 CSS、KaTeX JS/CSS，以及 KaTeX CSS 实际引用的 60 个字体文件（20 WOFF2、20 WOFF、20 TTF）。三类 JS、两类 CSS 与字体共 65 件浏览器运行文件；另保留 10 份许可证/告知文本。完整版本与文件哈希由 `client/common/renderer-assets.lock.json` 约束。ArkWeb 不依赖 CDN；按 markdown-it、KaTeX、texmath 顺序加载，并显式注入 KaTeX engine。Web 包版本以 workspace 锁文件为准。

审批范围不包括额外渲染、清洗、高亮、字体或后端依赖。依赖审批本身不代表三端公式呈现已验收。
