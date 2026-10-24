package com.betterlearning.server.model.dto;

import com.betterlearning.server.validation.PasswordConfirmation;
import com.betterlearning.server.validation.ValidPassword;
import com.betterlearning.server.validation.ValidUsername;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 注册请求。
 *
 * <p>用户名校验基于规范化后的值：去除首尾空白并把英文字母转为小写，长度按 Unicode
 * 码点计为 3~32，字符集限中文、ASCII 字母、数字与下划线。校验不会回写 DTO，
 * 真正的规范化由 service 完成。密码不 trim，为 8~64 个字符且 UTF-8 不超过 72 字节；
 * 确认密码必须与 {@code password} 完全一致，不一致时错误路径为 {@code confirmPassword}。
 * 口令只在请求与写入过程中存在，不会出现在响应或诊断字符串中。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Schema(name = "RegisterUserRequest",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "注册请求；只接受 username、password、confirmPassword，未知字段返回 400 A0427")
@PasswordConfirmation
public class RegisterUserDTO {

    /** 注册用户名；不能为空，规范化后为 3~32 个字符。 */
    @Schema(description = "规范化后 3~32 个字符，仅支持中文、ASCII 字母、数字和下划线；"
            + "服务端去除首尾空白后英文字母转小写", minLength = 3,
            example = "alice_01")
    @NotNull(message = "用户名不能为空")
    @ValidUsername
    private String username;

    /** 注册密码；8~64 个字符且 UTF-8 不超过 72 字节，不 trim。 */
    @Schema(description = "8~64 个字符，UTF-8 编码不超过 72 字节；不 trim；仅用于写入，不会出现在响应中",
            format = "password", writeOnly = true, minLength = 8, maxLength = 64)
    @NotNull(message = "密码不能为空")
    @ValidPassword
    private String password;

    /** 确认密码；不能为空，且必须与 {@code password} 完全一致。 */
    @Schema(description = "必须与 password 完全一致；8~64 个字符，UTF-8 编码不超过 72 字节；"
            + "仅用于写入，不会出现在响应中", format = "password", writeOnly = true,
            minLength = 8, maxLength = 64)
    @NotNull(message = "确认密码不能为空")
    private String confirmPassword;

    /**
     * 返回用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 返回注册密码。
     *
     * @return 注册密码
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置注册密码。
     *
     * @param password 注册密码
     */
    public void setPassword(String password) {
        this.password = password;
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
     * 返回不含用户名与密码的诊断字符串。
     */
    @Override
    public String toString() {
        return "RegisterUserDTO{username='[PROTECTED]'"
                + ", password='[PROTECTED]'"
                + ", confirmPassword='[PROTECTED]'}";
    }
}
