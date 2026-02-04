package com.slb.mining_backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        AuthErrorContext context = AuthProblemSupport.get(request);
        if (context == null) {
            context = resolveDefaultContext(request, authException);
        }
        AuthProblemSupport.writeApiResponse(request, response, context, objectMapper);
    }

    private AuthErrorContext resolveDefaultContext(HttpServletRequest request, AuthenticationException authException) {
        String authHeader = request.getHeader("Authorization");
        if (!StringUtils.hasText(authHeader)) {
            return new AuthErrorContext(
                    AuthErrorType.MISSING_AUTHORIZATION,
                    "Missing Authorization: Bearer header",
                    Map.of("Authorization", "Required header not provided"),
                    null,
                    null
            );
        }
        if (!authHeader.startsWith("Bearer ")) {
            return new AuthErrorContext(
                    AuthErrorType.BAD_AUTHORIZATION_HEADER,
                    "Malformed Authorization header",
                    Map.of("Authorization", "Expected 'Authorization: Bearer <token>' format"),
                    null,
                    null
            );
        }
        return new AuthErrorContext(
                AuthErrorType.INVALID_TOKEN,
                authException != null ? authException.getMessage() : null,
                Map.of("token", "invalid"),
                null,
                null
        );
    }
}
