package com.betterlearning.server.learning.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 服务端持久化根目录；无临时／工作目录默认值。 */
@Validated
@ConfigurationProperties(prefix = "app.files")
public class AppFilesProperties {
    @NotBlank(message = "APP_FILES_ROOT 必须配置为绝对持久化目录")
    private String root;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
