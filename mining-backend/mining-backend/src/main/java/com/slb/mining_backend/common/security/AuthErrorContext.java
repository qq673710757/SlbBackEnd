package com.slb.mining_backend.common.security;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Captures contextual information for an authentication/authorization error.
 */
public record AuthErrorContext(
        AuthErrorType type,
        @Nullable String detail,
        @Nullable Map<String, String> errors,
        @Nullable String scope,
        @Nullable String errorUri
) {
}
