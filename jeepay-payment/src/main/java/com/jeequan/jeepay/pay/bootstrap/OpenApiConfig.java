/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 */
package com.jeequan.jeepay.pay.bootstrap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * OpenAPI / Springdoc 配置
 *
 * 任务 #10：暴露国际四方支付系统对外 OpenAPI 3.0 文档。
 * 注意：仅在 dev / test profile 下启用（与 application.yml 中 springdoc.api-docs.enabled 配合），
 *      生产环境通过 profile 隔离，避免商户接口元数据外泄。
 *
 * 全局安全方案：JeePay 商户接口采用 Body 内 sign + appId 鉴权（非标准 HTTP Header），
 *      为兼容 OpenAPI 描述，这里声明为 ApiKey + Header 形式，仅作为文档说明用途。
 */
@Configuration
@Profile({"dev", "test"})
public class OpenApiConfig {

    @Bean
    public OpenAPI internationalPaymentOpenAPI() {
        return new OpenAPI()
                .info(buildInfo())
                .servers(List.of(
                        new Server().url("http://localhost:9216").description("本地开发环境 dev"),
                        new Server().url("https://test-pay.example.com").description("测试环境 test"),
                        new Server().url("https://pay.example.com").description("生产环境 prod")
                ))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("sign")
                                .description("商户签名（MD5/RSA），与 appId 配合使用。实际请求时 sign 与 appId 位于 Body 中，此处为文档展示"))
                        .addSecuritySchemes("AppIdAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("appId")
                                .description("商户应用 ID")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("ApiKeyAuth")
                        .addList("AppIdAuth"));
    }

    private Info buildInfo() {
        return new Info()
                .title("国际四方支付系统 OpenAPI")
                .version("V3.0")
                .description("""
                        商户对外接口文档（国际四方支付改造版，基于 JeePay v3.2.0）。

                        包含：
                        - 多币种统一下单 / 查单 / 退款 / 退款查询 / 转账接口
                        - 反风控扩展字段（设备指纹、IP 国家、卡 BIN 等）
                        - 拒付（chargeback）与投诉异步通知协议
                        - 商户回调签名（MD5/RSA）与重试机制

                        鉴权方式：Body 内 mchNo + appId + sign，签名算法参考《09-Webhook协议规范.md》。
                        """)
                .contact(new Contact().name("国际四方支付团队").email("dev@example.com"))
                .license(new License().name("LGPL-3.0").url("http://www.gnu.org/licenses/lgpl.html"));
    }
}
