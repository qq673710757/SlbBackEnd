package com.slb.mining_backend.common.security;

import org.springframework.http.HttpStatus;

/**
 * Catalogue of authentication/authorization failure types.
 */
public enum AuthErrorType {
    MISSING_AUTHORIZATION(HttpStatus.UNAUTHORIZED, "AUTH_MISSING_AUTHZ", "invalid_token", "https://api.slb.com/problems/invalid-token", "Authorization header is required", "Invalid authorization header",
            "未登录或登录信息缺失，请先登录"),
    BAD_AUTHORIZATION_HEADER(HttpStatus.BAD_REQUEST, "AUTH_BAD_HEADER", "invalid_request", "https://api.slb.com/problems/invalid-request", "Malformed Authorization header", "Malformed authorization header",
            "登录信息格式错误，请使用 Authorization: Bearer <token>"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_TOKEN", "invalid_token", "https://api.slb.com/problems/invalid-token", "Invalid access token", "Invalid access token",
            "登录已失效，请重新登录"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "invalid_token", "https://api.slb.com/problems/invalid-token", "Token expired", "Expired access token",
            "登录已过期，请重新登录"),
    CLAIM_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_CLAIM_MISMATCH", "invalid_token", "https://api.slb.com/problems/invalid-token", "Token claims do not match expected values", "Invalid token claims",
            "登录信息异常，请重新登录"),
    DEVICE_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_DEVICE_MISMATCH", "invalid_token", "https://api.slb.com/problems/invalid-token", "Device binding failed", "Invalid device binding",
            "设备校验失败，请重新登录"),
    WRONG_TOKEN_TYPE(HttpStatus.UNAUTHORIZED, "AUTH_WRONG_TOKEN_TYPE", "invalid_token", "https://api.slb.com/problems/invalid-token", "Wrong token type for this resource", "Wrong token type",
            "登录信息类型不匹配，请重新登录"),
    INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, "AUTH_INSUFFICIENT_SCOPE", "insufficient_scope", "https://api.slb.com/problems/insufficient-scope", "Insufficient scope", "Insufficient scope",
            "权限不足");

    private final HttpStatus status;
    private final String code;
    private final String oauthError;
    private final String typeUri;
    private final String defaultDetail;
    private final String title;
    private final String displayMessage;

    AuthErrorType(HttpStatus status, String code, String oauthError, String typeUri, String defaultDetail, String title, String displayMessage) {
        this.status = status;
        this.code = code;
        this.oauthError = oauthError;
        this.typeUri = typeUri;
        this.defaultDetail = defaultDetail;
        this.title = title;
        this.displayMessage = displayMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getOauthError() {
        return oauthError;
    }

    public String getTypeUri() {
        return typeUri;
    }

    public String getDefaultDetail() {
        return defaultDetail;
    }

    public String getTitle() {
        return title;
    }

    public String getDisplayMessage() {
        return displayMessage;
    }
}
