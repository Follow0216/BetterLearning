package com.betterlearning.server.validation;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 用户输入规范化与格式校验工具。
 *
 * <p>{@code normalize*} 方法返回处理后的新字符串，不修改入参，入参为 {@code null} 时返回
 * {@code null}；{@code isValid*} 方法对 {@code null} 一律返回 {@code false}。这与各
 * {@code ConstraintValidator} 将 {@code null} 视为通过的约定不同，二者不可互相替代。</p>
 *
 * <p>校验器只对规范化后的临时值做判断，不会回写 DTO；真正的规范化由 service 在写库或
 * 查询前完成。本类不可实例化，只提供静态方法。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class UserInputNormalizer {

    /** 用户名最小长度（Unicode 码点）。 */
    public static final int USERNAME_MIN_LENGTH = 3;

    /** 用户名最大长度（Unicode 码点）。 */
    public static final int USERNAME_MAX_LENGTH = 32;

    /** 邮箱总长度上限。 */
    public static final int EMAIL_MAX_LENGTH = 254;

    /** 邮箱本地部分 UTF-8 编码长度上限。 */
    public static final int EMAIL_LOCAL_MAX_BYTES = 64;

    /** 邮箱域名单个标签长度上限。 */
    public static final int EMAIL_LABEL_MAX_LENGTH = 63;

    /** 密码 UTF-8 编码长度上限，对应 BCrypt 的输入限制。 */
    public static final int PASSWORD_MAX_BYTES = 72;

    /** 用户名允许的字符集：中文、ASCII 字母、数字与下划线。 */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[\\u4e00-\\u9fffA-Za-z0-9_]+$");

    /** 国内 11 位手机号。 */
    private static final Pattern DOMESTIC_PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** E.164 手机号：加号后 2~15 位数字，首位非零。 */
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");

    /** 保守的邮箱格式：本地部分以字母数字开头，域名至少两段。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9]+(?:[._%+-][A-Za-z0-9]+)*@"
            + "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    /** 禁止实例化。 */
    private UserInputNormalizer() {
    }

    /**
     * 规范化用户名：去除首尾空白并转为小写。
     *
     * @param username 原始用户名，可为 {@code null}
     * @return 处理后的新字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        return username.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验用户名格式。
     *
     * <p>先按 {@link #normalizeUsername(String)} 规范化，再要求长度为 3~32 个 Unicode
     * 码点且只含中文、ASCII 字母、数字与下划线。{@code null} 与空串均不通过。</p>
     *
     * @param username 待校验用户名
     * @return 规范化后满足长度与字符集要求时为 {@code true}
     */
    public static boolean isValidUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        int length = normalized.codePointCount(0, normalized.length());
        return length >= USERNAME_MIN_LENGTH
                && length <= USERNAME_MAX_LENGTH
                && USERNAME_PATTERN.matcher(normalized).matches();
    }

    /**
     * 规范化手机号：去除首尾空白，国内 11 位手机号补 {@code +86}。
     *
     * @param phone 原始手机号，可为 {@code null}
     * @return 处理后的新字符串；入参为 {@code null} 时返回 {@code null}，空白串返回空串
     */
    public static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (DOMESTIC_PHONE_PATTERN.matcher(trimmed).matches()) {
            return "+86" + trimmed;
        }
        return trimmed;
    }

    /**
     * 校验手机号格式。
     *
     * <p>规范化后须匹配 E.164：加号后 2~15 位数字且首位非零。{@code null} 与空串均不通过。</p>
     *
     * @param phone 待校验手机号
     * @return 规范化后符合 E.164 时为 {@code true}
     */
    public static boolean isValidPhone(String phone) {
        String normalized = normalizePhone(phone);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return E164_PATTERN.matcher(normalized).matches();
    }

    /**
     * 规范化邮箱：去除首尾空白并转为小写。
     *
     * @param email 原始邮箱，可为 {@code null}
     * @return 处理后的新字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验邮箱格式。
     *
     * <p>按规范化后的值判断：总长度不超过 254；本地部分 UTF-8 编码不超过 64 字节；
     * 域名至少两段，每段非空、不超过 63 个字符且不以连字符开头或结尾。格式规则保持保守，
     * 不覆盖 RFC 允许的全部地址形式。{@code null} 与空串均不通过。</p>
     *
     * @param email 待校验邮箱
     * @return 规范化后满足上述规则时为 {@code true}
     */
    public static boolean isValidEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (normalized.length() > EMAIL_MAX_LENGTH
                || !EMAIL_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        int atIndex = normalized.lastIndexOf('@');
        String localPart = normalized.substring(0, atIndex);
        if (localPart.getBytes(StandardCharsets.UTF_8).length > EMAIL_LOCAL_MAX_BYTES) {
            return false;
        }
        String domain = normalized.substring(atIndex + 1);
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > EMAIL_LABEL_MAX_LENGTH
                    || label.startsWith("-")
                    || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验密码长度与字节上限。
     *
     * <p>密码不 trim，空白字符也计入长度；长度按 Unicode 码点计算，且 UTF-8 编码不得超过
     * {@link #PASSWORD_MAX_BYTES} 字节。{@code null} 不通过。</p>
     *
     * @param password 待校验密码
     * @param minLength 允许的最小字符数
     * @param maxLength 允许的最大字符数
     * @return 满足字符数与字节限制时为 {@code true}
     */
    public static boolean isValidPassword(String password, int minLength, int maxLength) {
        if (password == null) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        if (length < minLength || length > maxLength) {
            return false;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }
}
