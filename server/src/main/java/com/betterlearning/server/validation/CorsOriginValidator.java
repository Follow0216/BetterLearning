package com.betterlearning.server.validation;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * CORS 来源格式校验器。
 *
 * <p>与请求 DTO 的校验器不同，本校验器不把 {@code null} 视为通过：{@code null}、空白、
 * 含通配符或无法解析的值一律返回 {@code false}，因为来源属于启动期配置而非可空输入。</p>
 *
 * <p>校验只解析字符串与 IP 字面量，不验证域名是否可解析。合法来源须为 {@code http}
 * 或 {@code https} origin，不含用户信息、路径、查询串与片段；主机为域名、IPv4 或
 * 方括号 IPv6，端口缺省或位于 1~65535。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class CorsOriginValidator implements ConstraintValidator<ValidCorsOrigin, String> {

    /** 单个域名标签的格式。 */
    private static final Pattern DOMAIN_LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    /** IPv4 字面量格式。 */
    private static final Pattern IPV4 = Pattern.compile(
            "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
            + "(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    /** IPv6 字面量允许出现的字符。 */
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]+");

    /** 域名总长度上限。 */
    private static final int MAX_HOST_LENGTH = 253;

    /** 单个域名标签长度上限。 */
    private static final int MAX_LABEL_LENGTH = 63;

    /** 端口上限。 */
    private static final int MAX_PORT = 65535;

    /**
     * 校验来源字符串是否为明确的 HTTP(S) origin。
     *
     * @param value 待校验来源
     * @param context 校验上下文
     * @return 合法时为 {@code true}；{@code null}、空白、含通配符或任一结构不合法时为 {@code false}
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.indexOf('*') >= 0) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return false;
        }
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            return false;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || !isValidHost(host)) {
            return false;
        }
        int port = uri.getPort();
        return port == -1 || (port >= 1 && port <= MAX_PORT);
    }

    /**
     * 校验主机部分的格式。
     *
     * @param host 已解析出的主机名或 IP 字面量
     * @return 合法 IPv4、方括号 IPv6 或域名时为 {@code true}
     */
    private static boolean isValidHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return isIpv6(host.substring(1, host.length() - 1));
        }
        if (host.indexOf(':') >= 0) {
            return false;
        }
        if (IPV4.matcher(host).matches()) {
            return true;
        }
        if (host.length() > MAX_HOST_LENGTH) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > MAX_LABEL_LENGTH
                    || !DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验方括号内的地址是否为 IPv6 字面量。
     *
     * @param candidate 去掉方括号后的地址
     * @return 可解析为 {@link Inet6Address} 时为 {@code true}
     */
    private static boolean isIpv6(String candidate) {
        if (candidate.isEmpty() || candidate.indexOf(':') < 0
                || !IPV6_CHARACTERS.matcher(candidate).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(candidate) instanceof Inet6Address;
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
