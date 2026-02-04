package com.slb.mining_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                // 1. 配置 API 的基本信息：名称、版本、作者、发布日期等
                .info(new Info()
                        .title("算力宝后端服务 API / SLB Mining Backend API")
                        .version("1.0.0")
                        .description(
                                """
                                1. 基本信息 / Basic Information
                                - API 名称 / API Name: 算力宝后端服务 API (SLB Mining Backend API)
                                - 版本号 / Version: 1.0.0
                                - 作者 / Author: Hyperion
                                - 发布日期 / Release Date: 2025-11-18
                                
                                API 介绍 / API Introduction:
                                本文档采用 OpenAPI 3.0 规范并统一使用 UTF-8 编码，清晰描述“算力宝”后端对外提供的全部 HTTP API，
                                包括接口概述、请求和响应格式、参数含义、异常返回以及可复制执行的示例代码。
                                This document follows the OpenAPI 3.0 specification with UTF-8 encoding. It describes all HTTP APIs
                                exposed by the SLB Mining backend, including API overview, request/response formats, parameter
                                semantics, error responses and copy-paste runnable examples.
                                
                                收益计算公式示例 / Example Earnings Formula:
                                日预计收益 ≈ 有效算力(H/s) × 单位算力收益(XMR/H/s) × 当日时间(秒)。
                                Daily Estimated Earnings ≈ Effective Hashrate(H/s) × Unit Reward(XMR/H/s) × Seconds in Day.
                                
                                2. 请求与响应规范 / Request & Response Conventions
                                - 所有请求和响应均使用 UTF-8 编码，字段命名遵循驼峰命名法。
                                - 请求参数（包括 header、query、path、body）在各接口中会标明：是否必填、类型、长度（如适用）及业务意义。
                                - 如果参数参与计算，说明中会给出相应的计算规则或示例公式。
                                - 对涉及常量或枚举的字段，会在描述中列出所有可能取值及其含义（例如排行榜类型 week/month）。
                                - 为避免精度丢失，前后端交互中涉及 Long 的 ID/金额等字段，建议前端按字符串(String)处理。
                                
                                统一返回结构 / Unified Response Envelope:
                                所有接口（成功或异常）统一包裹在 ApiResponse<T> 结构中：
                                - code: 业务状态码，0 表示成功，非 0 表示业务或系统错误；对于 BizException，通常与 HTTP 状态码保持一致。
                                - message: 提示信息，成功通常为 "ok"，错误时为具体的错误原因。
                                - data: 业务数据载体，类型由各接口的泛型 T 决定。
                                - traceId: 请求链路追踪 ID，便于排查问题。
                                
                                异常约定 / Error Handling:
                                - 业务异常统一使用 BizException 抛出，由 GlobalExceptionHandler 统一转换为 ApiResponse 错误响应。
                                - HTTP 状态码通常与 BizException.code 对齐；当 code 无法映射到标准状态码时，默认使用 400(BAD_REQUEST)。
                                - 文档中会说明常见错误码的含义及使用场景。
                                
                                3. 示例代码与预期结果 / Examples & Expected Results
                                以下以“账号密码登录接口”为示例（仅供参考，具体路径和字段以接口文档为准）：
                                
                                示例请求 / Example Request (cURL):
                                curl -X POST "http://localhost:8080/api/v1/auth/login" \\
                                  -H "Content-Type: application/json" \\
                                  -d "{\\"email\\":\\"test@example.com\\",\\"userPassword\\":\\"123456\\"}"
                                
                                预期成功响应 / Expected Success Response (JSON):
                                {
                                  \\"code\\": 0,
                                  \\"message\\": \\"ok\\",
                                  \\"data\\": {
                                    \\"accessToken\\": \\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\\",
                                    \\"refreshToken\\": \\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\\",
                                    \\"expiresIn\\": 3600
                                  },
                                  \\"traceId\\": \\"b3f7e6c9a1d24c31\\"
                                }
                                
                                本文档中所有中文说明尽量与英文说明一一对应，保持格式统一、无错别字且上下文连贯，
                                以保证在中文和英文环境中均具有良好的可读性和可维护性。
                                All Chinese descriptions in this document are intended to have one-to-one English counterparts,
                                with consistent formatting and clear context, to ensure good readability and maintainability.
                                """
                        )
                        .contact(new Contact()
                                .name("Hyperion")
                                .email("backend@slb.xyz")
                        )
                )
                // 2. 添加全局的安全认证配置（除显式标明为公开接口的 API 之外）
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                // 3. 定义认证方式为 Bearer Token (JWT)
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("在此处输入 JWT 访问令牌，格式为：Bearer {token}")
                        )
                );
    }
}
