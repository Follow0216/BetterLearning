package com.betterlearning.server.model.dto;

import com.betterlearning.server.validation.ValidEmail;
import com.betterlearning.server.validation.ValidPhone;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 联系方式更新请求。
 *
 * <p>只接受 {@code phone} 与 {@code email}，不接受账号、用户名或角色。字段缺失表示不修改；
 * 显式 {@code null} 表示解绑；空串或非法格式返回 400；非空值必须全局唯一，冲突返回 409
 * 并保留原值。</p>
 *
 * <p>两个 {@code boolean} 字段是反序列化过程中的字段存在性标志，不参与 JSON 读写：
 * 对应 setter 一旦执行即标记为已提供，因此能区分“未提供”“显式 {@code null}”与“非空值”。
 * 校验基于规范化后的值判断，不会回写 DTO；手机号补 {@code +86}、邮箱去空白转小写等
 * 实际规范化由 service 完成。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "UpdateProfileRequest",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "联系方式更新。字段缺失表示不修改；显式 null 表示解绑；"
        + "空串或非法格式返回 400 A0400；非空值必须全局唯一，冲突返回 409 且保留原值。"
        + "请求体不接受账号、用户名或角色。")
public class UpdateProfileDTO {

    /** 手机号；{@code null} 表示解绑，非空时按 E.164 校验并要求全局唯一。 */
    @Schema(types = {"string", "null"},
            description = "E.164 格式；国内 11 位手机号自动补 +86；非空时全局唯一；"
            + "显式 null 表示解绑，空串返回 400", example = "+8613800138000")
    @ValidPhone
    private String phone;

    /** 邮箱；{@code null} 表示解绑，非空时按保守格式规则校验并要求全局唯一。 */
    @Schema(types = {"string", "null"},
            description = "去除首尾空白后转小写；非空时全局唯一；"
            + "显式 null 表示解绑，空串返回 400", example = "alice@example.com")
    @ValidEmail
    private String email;

    /** phone 是否在请求中出现过；不序列化，也不接受外部赋值。 */
    @Schema(hidden = true)
    @JsonIgnore
    private boolean phoneProvided;

    /** email 是否在请求中出现过；不序列化，也不接受外部赋值。 */
    @Schema(hidden = true)
    @JsonIgnore
    private boolean emailProvided;

    /**
     * 返回手机号。
     *
     * @return 手机号
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置手机号，并把该字段标记为已提供（值可以为 {@code null}）。
     *
     * @param phone 手机号
     */
    public void setPhone(String phone) {
        this.phone = phone;
        this.phoneProvided = true;
    }

    /**
     * 返回邮箱。
     *
     * @return 邮箱
     */
    public String getEmail() {
        return email;
    }

    /**
     * 设置邮箱，并把该字段标记为已提供（值可以为 {@code null}）。
     *
     * @param email 邮箱
     */
    public void setEmail(String email) {
        this.email = email;
        this.emailProvided = true;
    }

    /**
     * 返回手机号字段是否在请求中出现过。
     *
     * @return 手机号字段是否在请求中出现过
     */
    @JsonIgnore
    public boolean isPhoneProvided() {
        return phoneProvided;
    }

    /**
     * 返回邮箱字段是否在请求中出现过。
     *
     * @return 邮箱字段是否在请求中出现过
     */
    @JsonIgnore
    public boolean isEmailProvided() {
        return emailProvided;
    }

    /**
     * 返回不含联系方式值的诊断字符串；两个存在性标志不是敏感信息。
     */
    @Override
    public String toString() {
        return "UpdateProfileDTO{phone='[PROTECTED]'"
                + ", email='[PROTECTED]'"
                + ", phoneProvided=" + phoneProvided
                + ", emailProvided=" + emailProvided + '}';
    }
}
