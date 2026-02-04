package com.slb.mining_backend.common.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.trace.TraceIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper for capturing and rendering authentication-related error responses.
 */
public final class AuthProblemSupport {

    public static final String AUTH_ERROR_CONTEXT_ATTR = AuthProblemSupport.class.getName() + ".CONTEXT";

    private AuthProblemSupport() {
    }

    public static void flag(HttpServletRequest request, AuthErrorType type, @Nullable String detail, @Nullable Map<String, String> errors) {
        flag(request, type, detail, errors, null, null);
    }

    public static void flag(HttpServletRequest request, AuthErrorType type, @Nullable String detail, @Nullable Map<String, String> errors,
                             @Nullable String scope, @Nullable String errorUri) {
        if (request.getAttribute(AUTH_ERROR_CONTEXT_ATTR) == null) {
            request.setAttribute(AUTH_ERROR_CONTEXT_ATTR, new AuthErrorContext(type, detail, errors, scope, errorUri));
        }
    }

    @Nullable
    public static AuthErrorContext get(HttpServletRequest request) {
        Object context = request.getAttribute(AUTH_ERROR_CONTEXT_ATTR);
        if (context instanceof AuthErrorContext authErrorContext) {
            return authErrorContext;
        }
        return null;
    }

    public static AuthErrorContext fallbackForRequest(HttpServletRequest request, AuthErrorType defaultType, @Nullable String detail) {
        AuthErrorContext existing = get(request);
        if (existing != null) {
            return existing;
        }
        return new AuthErrorContext(defaultType, detail, null, null, null);
    }

    public static void writeProblem(HttpServletRequest request, HttpServletResponse response, AuthErrorContext context,
                                    ObjectMapper mapper) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        AuthErrorType type = context.type();
        String detail = resolveDetail(context);
        Map<String, String> errors = context.errors() != null ? context.errors() : Collections.emptyMap();
        String traceId = TraceIdHolder.require();

        response.setStatus(type.getStatus().value());
        response.setContentType("application/problem+json");
        response.setHeader(TraceIdHolder.TRACE_ID_HEADER, traceId);
        response.setHeader("WWW-Authenticate", buildWwwAuthenticate(type.getOauthError(), detail, context.scope(), context.errorUri()));

        ProblemDetailBody body = new ProblemDetailBody(
                type.getTypeUri(),
                type.getTitle(),
                type.getStatus().value(),
                detail,
                request.getRequestURI(),
                type.getCode(),
                traceId,
                errors
        );

        mapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * 以 ApiResponse 形式输出鉴权/授权错误（兼容旧前端：message=稳定机器码）。
     * - code: HTTP status（401/403/400）
     * - message: 稳定机器码（如 AUTH_TOKEN_EXPIRED）
     * - displayMessage: 中文展示文案
     * - WWW-Authenticate: 仅在 401 返回
     */
    public static void writeApiResponse(HttpServletRequest request, HttpServletResponse response, AuthErrorContext context,
                                        ObjectMapper mapper) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        AuthErrorType type = context.type();
        int status = type.getStatus().value();
        String machineCode = type.getCode();
        String displayMessage = type.getDisplayMessage();
        String detail = resolveDetail(context);
        Map<String, String> errors = context.errors() != null ? context.errors() : Collections.emptyMap();
        String traceId = TraceIdHolder.require();

        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(TraceIdHolder.TRACE_ID_HEADER, traceId);

        // 只在 401 返回 WWW-Authenticate，避免 400/403 语义混乱
        if (status == 401) {
            // Header detail 不要直接使用异常 message（可能泄露内部信息）；优先用“可控”的默认文案
            String headerDetail = type.getDefaultDetail();
            response.setHeader("WWW-Authenticate",
                    buildWwwAuthenticate(type.getOauthError(), headerDetail, context.scope(), context.errorUri()));
        }

        ApiResponse<Void> body = ApiResponse.authError(status, machineCode, displayMessage, errors);
        if (detail != null && !detail.isBlank()) {
            ApiResponse.ErrorBody error = body.getError();
            if (error != null) {
                error.setDetail(detail);
            }
        }
        mapper.writeValue(response.getOutputStream(), body);
    }

    private static String resolveDetail(AuthErrorContext context) {
        if (StringUtils.hasText(context.detail())) {
            return context.detail();
        }
        return context.type().getDefaultDetail();
    }

    private static String buildWwwAuthenticate(String oauthError, String description, @Nullable String scope, @Nullable String errorUri) {
        List<String> attributes = new ArrayList<>();
        if (StringUtils.hasText(oauthError)) {
            attributes.add("error=\"" + escapeHeader(oauthError) + "\"");
        }
        if (StringUtils.hasText(description)) {
            attributes.add("error_description=\"" + escapeHeader(description) + "\"");
        }
        if (StringUtils.hasText(scope)) {
            attributes.add("scope=\"" + escapeHeader(scope) + "\"");
        }
        if (StringUtils.hasText(errorUri)) {
            attributes.add("error_uri=\"" + escapeHeader(errorUri) + "\"");
        }
        if (attributes.isEmpty()) {
            return "Bearer";
        }
        return "Bearer " + String.join(", ", attributes);
    }

    private static String escapeHeader(String value) {
        return value.replace("\"", "\\\"");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProblemDetailBody(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String code,
            String traceId,
            Map<String, String> errors
    ) {
    }
}
