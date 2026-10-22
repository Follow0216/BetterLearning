/**
 * 认证页面共享样式常量。
 *
 * <p>手机、平板与 Web 端保持同一套品牌色；本文件只集中颜色与尺寸，不包含
 * 组件或布局逻辑。页面不得再散落十六进制颜色。</p>
 */

/** 主色，用于主按钮、链接与强调文字。 */
export const AUTH_COLOR_PRIMARY: string = '#246BFF';

/** 宽屏品牌字标 "Better" 使用的深海军蓝。 */
export const AUTH_COLOR_BRAND_DEEP: string = '#0B1E43';

/** 主要文字颜色。 */
export const AUTH_COLOR_TEXT: string = '#152744';

/** 次要文字与占位文案颜色。 */
export const AUTH_COLOR_MUTED: string = '#71829B';

/** 页面背景浅蓝色。 */
export const AUTH_COLOR_BACKGROUND: string = '#F5F8FE';

/** 边框与分隔线颜色。 */
export const AUTH_COLOR_BORDER: string = '#E2EAF5';

/** 纯白，用于宽屏表单面板与状态卡。 */
export const AUTH_COLOR_WHITE: string = '#FFFFFF';

/** 错误提示颜色；仅用于校验与失败文案。 */
export const AUTH_COLOR_DANGER: string = '#D93025';

/** 错误提示浅色背景。 */
export const AUTH_COLOR_DANGER_SOFT: string = '#FDECEA';

/** 主色浅色背景，用于成功提示。 */
export const AUTH_COLOR_PRIMARY_SOFT: string = '#EAF1FF';

/** 页面外边距，单位 vp。 */
export const AUTH_SPACE: number = 24;

/** 输入框与主按钮高度，单位 vp。 */
export const AUTH_CONTROL_HEIGHT: number = 52;

/** 输入框、按钮与卡片圆角，单位 vp。 */
export const AUTH_RADIUS: number = 16;

/** 宽屏表单最大宽度，单位 vp。 */
export const AUTH_FORM_MAX_WIDTH: number = 420;

/** 宽屏断点：窗口宽度达到该值后使用左右两栏。 */
export const AUTH_WIDE_QUERY: string = '(min-width: 840vp)';

/** 低高度断点：窗口高度低于该值后缩小装饰图。 */
export const AUTH_SHORT_QUERY: string = '(max-height: 700vp)';