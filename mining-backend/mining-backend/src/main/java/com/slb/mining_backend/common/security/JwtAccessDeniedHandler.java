package com.slb.mining_backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Map;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        AuthErrorContext context = AuthProblemSupport.get(request);
        // AccessDeniedHandler 语义固定为 403；如果上下文里不是 403，就兜底为 INSUFFICIENT_SCOPE
        if (context == null || context.type().getStatus() != HttpStatus.FORBIDDEN) {
            context = new AuthErrorContext(
                    AuthErrorType.INSUFFICIENT_SCOPE,
                    accessDeniedException != null ? accessDeniedException.getMessage() : null,
                    Map.of("scope", "insufficient"),
                    null,
                    null
            );
        }
        AuthProblemSupport.writeApiResponse(request, response, context, objectMapper);
    }
}
