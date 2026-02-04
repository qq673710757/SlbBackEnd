package com.slb.mining_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS 配置（给浏览器前端跨域调用用）。
 *
 * <p>注意：移动端/服务端调用不受 CORS 影响；只有浏览器会做同源策略校验。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
@Data
public class CorsProperties {

    /**
     * 是否启用 CORS（默认开启，避免浏览器前端无法调用接口）。
     */
    private boolean enabled = true;

    /**
     * 精确允许的 Origin 列表（如 https://suanlibao.xyz）。为空则使用 allowedOriginPatterns。
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * 允许的 Origin pattern 列表（如 https://*.example.com）。默认 ["*"]。
     * <p>若 allowCredentials=true，建议改成更严格的域名 pattern 或使用 allowedOrigins 精确白名单。</p>
     */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));

    /**
     * 允许的方法。默认包含常用方法和 OPTIONS（预检）。
     */
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

    /**
     * 允许的请求头。默认 "*"（若需要携带 Cookie 且 allowCredentials=true，某些浏览器更偏好显式列举）。
     */
    private List<String> allowedHeaders = new ArrayList<>(List.of(
            "Content-Type",
            "Authorization",
            "X-Requested-With",
            "X-Trace-Id"
    ));

    /**
     * 允许前端读取的响应头（不包含简单响应头）。例如 X-Trace-Id。
     */
    private List<String> exposedHeaders = new ArrayList<>(List.of("X-Trace-Id"));

    /**
     * 是否允许携带 Cookie/Authorization 等凭据。
     * <p>默认 false：更安全，且可配合 allowedOriginPatterns="*" 通配。</p>
     */
    private boolean allowCredentials = false;

    /**
     * 预检缓存时间（秒）。
     */
    private long maxAgeSeconds = 3600;
}


