package com.slb.mining_backend.config;

import com.slb.mining_backend.common.security.JwtAccessDeniedHandler;
import com.slb.mining_backend.common.security.JwtAuthenticationEntryPoint;
import com.slb.mining_backend.common.util.JwtFilter;
import com.slb.mining_backend.common.trace.TraceIdFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private TraceIdFilter traceIdFilter;
    @Autowired
    private JwtFilter jwtFilter;
    @Autowired
    private CorsProperties corsProperties;
    @Autowired
    private JwtAuthenticationEntryPoint unauthorizedHandler;
    @Autowired
    private JwtAccessDeniedHandler accessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 开启 CORS（会自动注册 CorsFilter；具体规则见 corsConfigurationSource）
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorizedHandler)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // 预检请求必须放行，否则浏览器会直接报跨域（并且不会真正发业务请求）
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/user/login",
                                "/api/v1/user/register",
                                "/api/v1/user/reset-password",
                                "/api/v1/user/send-code",
                                "/api/v1/user/login-by-code",
                                "/api/v1/user/reset-password-by-code",
                                // 未登录收益预估（客户端注册/登录前展示用）
                                "/api/v1/earnings/estimate",
                                // 未登录单位收益（客户端/网页按算力自行计算）
                                "/api/v1/earnings/unit-income",
                                "/api/v1/earnings/unit-income/gpu",
                                "/api/v1/earnings/gpu-income",
                                // 客户端检查更新（未登录也允许）
                                "/api/v1/system/app-version/check",
                                // Android app version info
                                "/api/v1/system/app-version",
                                // Tauri plugin-updater manifest（未登录也允许）
                                "/api/v1/system/app-updater/manifest",
                                // 客户端安装包/更新包下载(静态资源)
                                "/downloads/**",
                                // 兼容生产用 /updates/** 承载 updater artifacts（可由 app.downloads.url-prefix 配置为 /updates/）
                                "/updates/**",
                                "/api/v1/exchange-rate/**",
                                "/v3/api-docs/**",
                                "/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                // 让 TraceId 在很早的位置进入
                .addFilterBefore(traceIdFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                // 让 JWT 在用户名密码认证过滤器之前
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Spring Security 的 CORS 数据源。
     * <p>注意：仅有 WebMvcConfigurer 的 addCorsMappings 对于 Security 链不一定生效；推荐使用该方式。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        if (!corsProperties.isEnabled()) {
            // 通过不注册任何 mapping 来等价“关闭”
            return request -> null;
        }

        CorsConfiguration config = new CorsConfiguration();

        List<String> allowedOrigins = corsProperties.getAllowedOrigins();
        List<String> allowedOriginPatterns = corsProperties.getAllowedOriginPatterns();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            config.setAllowedOrigins(allowedOrigins);
        } else if (allowedOriginPatterns != null && !allowedOriginPatterns.isEmpty()) {
            config.setAllowedOriginPatterns(allowedOriginPatterns);
        } else {
            // 兜底：避免多环境配置把列表覆盖成空后，导致所有跨域预检直接失败
            config.setAllowedOriginPatterns(List.of("*"));
        }

        List<String> methods = corsProperties.getAllowedMethods();
        config.setAllowedMethods(methods == null || methods.isEmpty()
                ? List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                : methods);

        List<String> headers = corsProperties.getAllowedHeaders();
        config.setAllowedHeaders(headers == null || headers.isEmpty()
                ? List.of("Content-Type", "Authorization", "X-Requested-With", "X-Trace-Id")
                : headers);

        List<String> exposed = corsProperties.getExposedHeaders();
        if (exposed != null && !exposed.isEmpty()) {
            config.setExposedHeaders(exposed);
        }

        config.setAllowCredentials(corsProperties.isAllowCredentials());
        config.setMaxAge(corsProperties.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
