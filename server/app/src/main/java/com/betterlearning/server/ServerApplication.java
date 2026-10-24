package com.betterlearning.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 服务端启动入口。
 *
 * <p>引导 Spring Boot 应用启动，加载自动配置与自定义配置属性。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@SpringBootApplication
public class ServerApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

}
