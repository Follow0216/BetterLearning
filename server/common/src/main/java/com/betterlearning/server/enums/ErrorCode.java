package com.betterlearning.server.enums;

import org.springframework.http.HttpStatus;

/**
 * 对外错误码与默认中文含义。
 *
 * <p>对外 {@code code} 为 5 位字符串：A 表示用户端错误、B 表示系统执行错误、
 * C 表示调用第三方服务错误，后四位为编号；编号与 HTTP 状态码相互独立。
 * 枚举名仅用于 Java 内部引用，不直接作为 API 响应值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public enum ErrorCode {

    /** A0400：用户请求参数错误（沿用《Java 开发手册》附 3）。 */
    INVALID_PARAM(HttpStatus.BAD_REQUEST, "A0400", "请求参数不合法"),

    /** A0427：请求 JSON 解析失败（沿用《Java 开发手册》附 3）。 */
    INVALID_JSON(HttpStatus.BAD_REQUEST, "A0427", "请求体格式错误"),

    /** A0200：用户登录异常（沿用《Java 开发手册》附 3）。 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A0200", "未认证"),

    /** A0300：访问权限异常（沿用《Java 开发手册》附 3）。 */
    FORBIDDEN(HttpStatus.FORBIDDEN, "A0300", "无权限"),

    /** A0606：资源不存在；本项目扩展，沿用 A0600 用户资源异常类别。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "A0606", "资源不存在"),

    /** A0507：请求方法不支持；本项目扩展，沿用 A0500 用户请求服务异常类别。 */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "A0507", "请求方法不支持"),

    /** A0428：不支持的媒体类型；本项目扩展，沿用 A0400 用户请求参数错误类别。 */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A0428", "不支持的媒体类型"),

    /** A0111：用户名已存在（沿用《Java 开发手册》附 3）。 */
    USERNAME_EXISTS(HttpStatus.CONFLICT, "A0111", "用户名已存在"),

    /** A0154：手机号已绑定；本项目扩展，沿用 A0150 用户基本信息校验类别。 */
    PHONE_ALREADY_BOUND(HttpStatus.CONFLICT, "A0154", "该手机号已绑定"),

    /** A0155：邮箱已绑定；本项目扩展，沿用 A0150 用户基本信息校验类别。 */
    EMAIL_ALREADY_BOUND(HttpStatus.CONFLICT, "A0155", "该邮箱已绑定"),

    /** A0444：密码已变更导致改密冲突；本项目扩展，沿用 A0440 用户操作异常类别。 */
    PASSWORD_CHANGE_CONFLICT(HttpStatus.CONFLICT, "A0444", "密码已变更，请重新登录"),

    /** A0445：确认动作 ID 已用于不同内容；本项目扩展。 */
    CONFIRM_REQUEST_CONFLICT(HttpStatus.CONFLICT, "A0445", "确认请求已被其他内容使用"),

    /** A0450：同账号同级题集名称重复；本项目扩展。 */
    COLLECTION_NAME_EXISTS(HttpStatus.CONFLICT, "A0450", "同级题集名称已存在"),

    /** M2 当前作答引用冲突。 */
    ANSWER_CHANGED(HttpStatus.CONFLICT, "A0451", "云端作答已更新，请刷新"),
    /** M2 尚无完整保存。 */
    ANSWER_NOT_SAVED(HttpStatus.CONFLICT, "A0452", "请先完整保存作答"),
    /** M2 完成后须显式继续。 */
    ANSWER_COMPLETED(HttpStatus.CONFLICT, "A0453", "已完成，请先继续作答"),
    /** M2 保存动作 ID 内容不一致。 */
    ANSWER_SAVE_CONFLICT(HttpStatus.CONFLICT, "A0454", "保存请求已用于其他内容"),
    /** M2 有界完整上传。 */
    UPLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "A0430", "上传内容超出限制"),

    /** A0501：请求次数超出限制（沿用《Java 开发手册》附 3）。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "A0501", "请求过于频繁"),

    /** C0001：调用第三方服务出错（沿用《Java 开发手册》附 3），用于数据库或 Redis 不可用。 */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "C0001", "服务暂时不可用"),

    /** B0001：系统执行出错（沿用《Java 开发手册》附 3）。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "B0001", "服务器内部错误");

    private final HttpStatus status;

    private final String code;

    private final String message;

    /**
     * 定义错误码的 HTTP 状态、对外编码与默认中文消息。
     *
     * @param status 对应 HTTP 状态
     * @param code 对外 5 位编码
     * @param message 默认中文消息
     */
    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误码对应的 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 返回对外 5 位错误码，例如 {@code A0400}。
     *
     * @return 以 A、B 或 C 开头并后接四位数字的字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回默认中文消息。
     *
     * @return 默认中文消息
     */
    public String getMessage() {
        return message;
    }
}
