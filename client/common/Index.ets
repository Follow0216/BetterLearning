/**
 * 公共基础层对外出口。
 *
 * <p>当前提供 features/auth 的认证模型、HTTP 客户端、进程内会话、共享登录/注册
 * 页面与校验规则；页面层从此导入，不直接引用 features 内部文件。</p>
 */
export const COMMON_LAYER_VERSION: string = '0.0.1';

export {
  UserProfile,
  AuthSessionData,
  ServerErrorPayload,
  AuthFailureKind,
  AuthError,
  AuthResult
} from './src/main/ets/features/auth/AuthModels';

export { AuthApi, DEFAULT_DEV_BASE_URL } from './src/main/ets/features/auth/AuthApi';

export { AuthSession, authSession } from './src/main/ets/features/auth/AuthSession';

export { AuthScreen } from './src/main/ets/features/auth/AuthScreen';

export {
  AUTH_COLOR_PRIMARY,
  AUTH_COLOR_TEXT,
  AUTH_COLOR_MUTED,
  AUTH_COLOR_BACKGROUND,
  AUTH_COLOR_BORDER,
  AUTH_COLOR_WHITE,
  AUTH_COLOR_DANGER,
  AUTH_COLOR_DANGER_SOFT,
  AUTH_COLOR_PRIMARY_SOFT,
  AUTH_SPACE,
  AUTH_CONTROL_HEIGHT,
  AUTH_RADIUS,
  AUTH_FORM_MAX_WIDTH,
  AUTH_WIDE_QUERY,
  AUTH_SHORT_QUERY
} from './src/main/ets/features/auth/AuthTheme';

export {
  AuthFormErrors,
  count_code_points,
  count_utf8_bytes,
  validate_registration_username,
  validate_registration_password,
  validate_confirm_password,
  validate_register_form,
  validate_login_form
} from './src/main/ets/features/auth/AuthValidation';

export {
  INBOX_COLLECTION_ID,
  LearningSubject,
  MAX_QUESTION_MARKDOWN_LENGTH,
  learning_subject_label,
  QuestionSummary,
  QuestionDetail,
  QuestionPage,
  QuestionCollection,
  QuestionListQuery,
  ManualQuestionInput
} from './src/main/ets/features/learning/LearningModels';

export { LearningApi } from './src/main/ets/features/learning/LearningApi';

export { QuestionMarkdownView } from './src/main/ets/features/learning/QuestionMarkdownView';

export { AnswerState, AnswerManifest, AnswerQuestionSnapshot, SolutionDetail, FrozenAnswerUpload }
  from './src/main/ets/features/learning/SolutionModels';
export {
  CanvasPoint, CanvasView, CanvasRect, CanvasStroke, CanvasTextBlock, CanvasImageBlock,
  CanvasQuestionLayout, CanvasEditableDocument, CanvasExtent, CanvasLocalFile, CanvasTool, CanvasPenKind,
  CANVAS_TILE_SIZE, CANVAS_RENDERER_ID, CANVAS_CONTENT_WIDTH, CANVAS_FONT_SIZE
} from './src/main/ets/features/canvas/CanvasModels';

export { SolutionApi } from './src/main/ets/features/learning/SolutionApi';
export { canvas_extent, canvas_grid, clone_canvas_document, world_point, zoom_canvas, fit_canvas, initial_canvas_view, valid_canvas_view,
  valid_world_number, validate_canvas_document, validate_answer_manifest, stroke_hit } from './src/main/ets/features/canvas/CanvasGeometry';
export { canvas_utf8, canvas_text, canvas_exists, canvas_mkdir, canvas_read, canvas_write, canvas_sha256 }
  from './src/main/ets/features/canvas/CanvasFiles';

export { canvas_png, canvas_image, canvas_image_bytes } from './src/main/ets/features/canvas/CanvasImages';
export { QuestionRasterHost, QuestionRasterResult } from './src/main/ets/features/canvas/QuestionRasterHost';

export { TutorTurnStatus, TutorTurn, TutorPending, FrozenTutorAnswer, OcrResult } from './src/main/ets/features/tutor/TutorModels';
export { TutorApi } from './src/main/ets/features/tutor/TutorApi';
export { OcrApi } from './src/main/ets/features/tutor/OcrApi';

export { CANVAS_DEFAULT_PRESSURE, valid_canvas_pen, valid_canvas_pressure, canvas_point_width }
  from './src/main/ets/features/canvas/CanvasPens';

export { ToastType, ToastOptions, ToastItem } from './src/main/ets/components/feedback/ToastModels';
export { ToastStore } from './src/main/ets/components/feedback/ToastStore';
export { ToastHost } from './src/main/ets/components/feedback/ToastHost';
