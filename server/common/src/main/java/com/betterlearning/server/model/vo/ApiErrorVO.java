package com.betterlearning.server.model.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一错误响应体。
 *
 * <p>{@code code}、{@code message} 与 {@code traceId} 始终存在；{@code fieldErrors}
 * 只在参数校验失败时出现，未提供或为空时序列化为 {@code null} 并被 {@code NON_EMPTY}
 * 省略。字段映射在构造时复制为不可变副本，避免调用方后续修改。
 * {@link #toString()} 对动态文案与字段错误值脱敏。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "ApiErrorVO", description = "统一错误响应；code、message、traceId 始终存在")
public class ApiErrorVO {

    /** 5 位错误码，前缀表示来源类别，编号与 HTTP 状态码无关。 */
    @Schema(description = "5 位错误码：A 用户端、B 系统执行、C 第三方服务，后四位为编号；"
            + "编号与 HTTP 状态码无关",
            example = "A0400",
            pattern = "^[ABC][0-9]{4}$",
            allowableValues = {"A0400", "A0427", "A0200", "A0300", "A0606", "A0507",
                    "A0428", "A0429", "A0111", "A0154", "A0155", "A0444", "A0445", "A0450", "A0451", "A0452", "A0453", "A0454", "A0430", "A0455", "C0002", "A0501", "C0001",
                    "B0001"})
    private final String code;

    /** 可直接展示的中文错误文案。 */
    @Schema(description = "可直接展示的中文错误文案", example = "请求参数不合法")
    private final String message;

    /** 本次请求的追踪 ID，与响应头 {@code X-Request-Id} 相同。 */
    @Schema(description = "本次请求的追踪 ID，与响应头 X-Request-Id 相同",
            example = "9a79fc873c26471fa45e7e3d72d097cb")
    private final String traceId;

    /** 字段级错误；键为字段名，仅在参数校验失败时提供。 */
    @Schema(description = "字段级错误，仅参数校验失败时出现；键为字段名", nullable = true,
            example = "{\"oldPassword\":\"旧密码错误\"}")
    private final Map<String, String> fieldErrors;

    /**
     * 创建错误响应。
     *
     * @param code 5 位错误码
     * @param message 可直接展示的错误文案
     * @param traceId 本次请求的追踪 ID
     * @param fieldErrors 字段级错误，可为 {@code null} 或空
     */
    public ApiErrorVO(String code, String message, String traceId, Map<String, String> fieldErrors) {
        this.code = code;
        this.message = message;
        this.traceId = traceId;
        this.fieldErrors = copy(fieldErrors);
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回错误文案。
     *
     * @return 错误文案
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回追踪 ID。
     *
     * @return 追踪 ID
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 返回字段级错误；无字段错误时为 {@code null} 且不参与序列化。
     *
     * @return 字段级错误；无字段错误时为 {@code null} 且不参与序列化
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * 复制字段错误为非空情况下的不可变映射。
     *
     * @param source 原始字段错误
     * @return 不可变副本；原始映射为 {@code null} 或空时返回 {@code null}
     */
    private static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 返回含错误码与追踪 ID 的诊断字符串；动态文案与字段错误值输出为保护标记。
     */
    @Override
    public String toString() {
        return "ApiErrorVO{code='" + code + '\''
                + ", traceId='" + traceId + '\''
                + ", message='[PROTECTED]'"
                + ", fieldErrors='[PROTECTED]'}";
    }
}
