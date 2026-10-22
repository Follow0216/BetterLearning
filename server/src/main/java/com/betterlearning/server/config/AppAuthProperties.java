package com.betterlearning.server.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 登录与注册限流配置。
 *
 * <p>绑定 {@code app.auth} 前缀，启动时校验限流参数：IP 上限与用户名上限必须为正整数，
 * 时间窗口不得为空、必须至少 1 毫秒且不得超出 {@link Duration#toMillis()} 的可表示范围。
 * 具体取值由 {@code application.properties} 的环境变量映射提供，本类不设置内置默认值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    /** 登录限流规则。 */
    @Valid
    @NotNull(message = "app.auth.login 不能为空")
    private Login login = new Login();

    /** 注册限流规则。 */
    @Valid
    @NotNull(message = "app.auth.registration 不能为空")
    private Registration registration = new Registration();

    /** 返回登录限流规则。 */
    public Login getLogin() {
        return login;
    }

    /** 设置登录限流规则。 */
    public void setLogin(Login login) {
        this.login = login;
    }

    /** 返回注册限流规则。 */
    public Registration getRegistration() {
        return registration;
    }

    /** 设置注册限流规则。 */
    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    /**
     * 判断时间窗口是否至少为 1 毫秒。
     *
     * @param window 待校验的时间窗口
     * @return 窗口非空、为正且可转换为至少 1 毫秒时为 {@code true}；溢出或不可表示时为 {@code false}
     */
    private static boolean isWindowAtLeastOneMillisecond(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            return false;
        }
        try {
            return window.toMillis() >= 1;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    /**
     * 登录限流规则：按 IP 与用户名分别限制单位时间内的尝试次数。
     */
    public static class Login {

        /** 单个 IP 在窗口内允许的登录尝试次数。 */
        @Min(value = 1, message = "app.auth.login.ip-limit 必须为正整数")
        private int ipLimit;

        /** 单个用户名在窗口内允许的登录尝试次数。 */
        @Min(value = 1, message = "app.auth.login.username-limit 必须为正整数")
        private int usernameLimit;

        /** 登录限流的时间窗口。 */
        @NotNull(message = "app.auth.login.window 不能为空")
        private Duration window;

        /**
         * 校验登录限流窗口的合法性。
         *
         * @return 窗口至少为 1 毫秒且不溢出时为 {@code true}
         */
        @AssertTrue(message = "app.auth.login.window 必须至少为 1 毫秒且不超出可表示范围")
        public boolean isWindowPositive() {
            return isWindowAtLeastOneMillisecond(window);
        }

        /** 返回单个 IP 的登录尝试上限。 */
        public int getIpLimit() {
            return ipLimit;
        }

        /** 设置单个 IP 的登录尝试上限。 */
        public void setIpLimit(int ipLimit) {
            this.ipLimit = ipLimit;
        }

        /** 返回单个用户名的登录尝试上限。 */
        public int getUsernameLimit() {
            return usernameLimit;
        }

        /** 设置单个用户名的登录尝试上限。 */
        public void setUsernameLimit(int usernameLimit) {
            this.usernameLimit = usernameLimit;
        }

        /** 返回登录限流的时间窗口。 */
        public Duration getWindow() {
            return window;
        }

        /** 设置登录限流的时间窗口。 */
        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    /**
     * 注册限流规则：按 IP 限制单位时间内的注册尝试次数。
     */
    public static class Registration {

        /** 单个 IP 在窗口内允许的注册尝试次数。 */
        @Min(value = 1, message = "app.auth.registration.ip-limit 必须为正整数")
        private int ipLimit;

        /** 注册限流的时间窗口。 */
        @NotNull(message = "app.auth.registration.window 不能为空")
        private Duration window;

        /**
         * 校验注册限流窗口的合法性。
         *
         * @return 窗口至少为 1 毫秒且不溢出时为 {@code true}
         */
        @AssertTrue(message = "app.auth.registration.window 必须至少为 1 毫秒且不超出可表示范围")
        public boolean isWindowPositive() {
            return isWindowAtLeastOneMillisecond(window);
        }

        /** 返回单个 IP 的注册尝试上限。 */
        public int getIpLimit() {
            return ipLimit;
        }

        /** 设置单个 IP 的注册尝试上限。 */
        public void setIpLimit(int ipLimit) {
            this.ipLimit = ipLimit;
        }

        /** 返回注册限流的时间窗口。 */
        public Duration getWindow() {
            return window;
        }

        /** 设置注册限流的时间窗口。 */
        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
