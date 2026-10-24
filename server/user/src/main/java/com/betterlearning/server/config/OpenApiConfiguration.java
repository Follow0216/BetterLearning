package com.betterlearning.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元数据与认证方案配置。
 *
 * <p>声明接口标题、版本与错误码说明，并注册名为 {@link #BEARER_SCHEME} 的 HTTP Bearer
 * 认证方案，供 Swagger UI 的 Authorize 使用。文档本身是否暴露由环境变量
 * {@code APP_DOCS_ENABLED} 直接映射的 springdoc 开关控制，默认关闭。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    /** Bean 认证方案在 OpenAPI 文档中的名称。 */
    public static final String BEARER_SCHEME = "bearerAuth";

    /**
     * 构建 OpenAPI 文档定义。
     *
     * @return 包含服务信息与 Bearer 认证方案的 OpenAPI 定义
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BetterLearning 认证服务 API")
                        .version("v1")
                        .description("账号注册、登录、退出、个人资料与密码管理。"
                                + "除注册和登录外，其余接口均要求在 Authorization 头中携带 Bearer JWT。"
                                + "错误响应 code 为 5 位字符串（A 用户端、B 系统执行、C 第三方服务，"
                                + "后四位为编号，与 HTTP 状态码无关）；完整列表见 "
                                + "server/README.md 第 5.5 节。"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("使用登录接口返回的 JWT。在 Swagger UI 的 Authorize "
                                        + "对话框中只填写 JWT 本身，不要添加 Bearer 前缀或空格；"
                                        + "界面会自动生成 Authorization: Bearer <token> 请求头。")));
    }
}
