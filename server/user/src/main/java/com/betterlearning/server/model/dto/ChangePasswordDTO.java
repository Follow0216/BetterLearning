package com.betterlearning.server.model.dto;

import com.betterlearning.server.validation.PasswordConfirmation;
import com.betterlearning.server.validation.ValidPassword;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 改密请求。
 *
 * <p>只包含旧密码、新密码与确认密码，口令仅存在于请求与校验过程中，不会写入响应。
 * 三个字段均不 trim：旧密码为 1~64 个字符，新密码为 8~64 个字符，且 UTF-8 编码均不超过
 * 72 字节；确认密码只需非空，并与 {@code newPassword} 完全一致。一致性校验失败时，
 * 错误路径为 {@code confirmPassword}。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "ChangePasswordRequest",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "改密请求；只接受 oldPassword、newPassword、confirmPassword，"
        + "未知字段返回 400 A0427")
@PasswordConfirmation(passwordField = "newPassword")
public class ChangePasswordDTO {

    /** 当前密码；不能为空，1~64 个字符且 UTF-8 不超过 72 字节，不 trim。 */
    @Schema(description = "当前密码；不能为空且不超过 64 个字符，UTF-8 编码不超过 72 字节；不 trim；"
            + "错误时返回 400 A0400 且 fieldErrors.oldPassword 为“旧密码错误”",
            format = "password", writeOnly = true, minLength = 1, maxLength = 64)
    @NotNull(message = "旧密码不能为空")
    @ValidPassword(min = 1, message = "旧密码不能为空且不能超过64个字符")
    private String oldPassword;

    /** 新密码；8~64 个字符且 UTF-8 不超过 72 字节，不 trim。 */
    @Schema(description = "新密码；8~64 个字符，UTF-8 编码不超过 72 字节；不 trim；"
            + "仅用于写入，不会出现在响应中", format = "password", writeOnly = true,
            minLength = 8, maxLength = 64)
    @NotNull(message = "新密码不能为空")
    @ValidPassword
    private String newPassword;

    /** 确认密码；不能为空，且必须与 {@code newPassword} 完全一致。 */
    @Schema(description = "必须与 newPassword 完全一致；8~64 个字符，UTF-8 编码不超过 72 字节；"
            + "仅用于写入，不会出现在响应中", format = "password", writeOnly = true,
            minLength = 8, maxLength = 64)
    @NotNull(message = "确认密码不能为空")
    private String confirmPassword;

    /**
     * 返回当前密码。
     *
     * @return 当前密码
     */
    public String getOldPassword() {
        return oldPassword;
    }

    /**
     * 设置当前密码。
     *
     * @param oldPassword 当前密码
     */
    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    /**
     * 返回新密码。
     *
     * @return 新密码
     */
    public String getNewPassword() {
        return newPassword;
    }

    /**
     * 设置新密码。
     *
     * @param newPassword 新密码
     */
    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    /**
     * 返回确认密码。
     *
     * @return 确认密码
     */
    public String getConfirmPassword() {
        return confirmPassword;
    }

    /**
     * 设置确认密码。
     *
     * @param confirmPassword 确认密码
     */
    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    /**
     * 返回不含任何密码值的诊断字符串。
     */
    @Override
    public String toString() {
        return "ChangePasswordDTO{oldPassword='[PROTECTED]'"
                + ", newPassword='[PROTECTED]'"
                + ", confirmPassword='[PROTECTED]'}";
    }
}
