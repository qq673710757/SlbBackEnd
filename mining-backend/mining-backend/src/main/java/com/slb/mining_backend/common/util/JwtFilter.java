package com.slb.mining_backend.common.util;

import com.slb.mining_backend.common.security.AuthErrorType;
import com.slb.mining_backend.common.security.AuthProblemSupport;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private UserMapper userMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            processAuthentication(request);
        }

        filterChain.doFilter(request, response);
    }

    private void processAuthentication(HttpServletRequest request) {
        String deprecatedHeader = request.getHeader("Auth-Token");
        if (StringUtils.hasText(deprecatedHeader)) {
            // 这是一个明确的错误用法，可以拦截提示
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.MISSING_AUTHORIZATION,
                    "Auth-Token header is no longer supported. Use Authorization: Bearer <token>.",
                    Map.of("Auth-Token", "Deprecated header")
            );
            return;
        }

        String authHeader = request.getHeader("Authorization");
        // 修改点：如果没有 Authorization 头，直接放行，不报错。
        // 让 Spring Security 的后续过滤器去决定这个接口是否必须需要认证。
        // 如果是 /login 或 /register 等公开接口，没 Token 也能访问；
        // 如果是受保护接口，Spring Security 会抛出 AccessDeniedException / AuthenticationException。
        if (!StringUtils.hasText(authHeader)) {
            return;
        }

        if (!authHeader.startsWith(BEARER)) {
            // 既然带了 Authorization 但格式不对，说明客户端试图认证但失败了，可以报错
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.BAD_AUTHORIZATION_HEADER,
                    "Malformed Authorization header",
                    Map.of("Authorization", "Expected 'Authorization: Bearer <token>' format")
            );
            return;
        }

        String token = authHeader.substring(BEARER.length()).trim();
        if (!StringUtils.hasText(token)) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.BAD_AUTHORIZATION_HEADER,
                    "Missing bearer token",
                    Map.of("Authorization", "Bearer token value is missing")
            );
            return;
        }

        try {
            Claims claims = jwtUtil.parseClaims(token);

            if (isExpired(claims)) {
                AuthProblemSupport.flag(
                        request,
                        AuthErrorType.TOKEN_EXPIRED,
                        "The bearer token is expired at " + claims.getExpiration(),
                        Map.of("token", "expired")
                );
                return;
            }

            String tokenType = claims.get("typ", String.class);
            if (StringUtils.hasText(tokenType) && !"access".equalsIgnoreCase(tokenType)) {
                if (allowsRefreshToken(request, tokenType)) {
                    return;
                }
                AuthProblemSupport.flag(
                        request,
                        AuthErrorType.WRONG_TOKEN_TYPE,
                        "Wrong token type: " + tokenType,
                        Map.of("token", "wrong_type")
                );
                return;
            }

            // 优先使用不可歧义的 uid（用户ID）来恢复用户，避免 username/email 可能发生“串号”
            Long uid = resolveUidFromClaims(claims);
            UserDetails userDetails;
            if (uid != null) {
                User user = userMapper.selectById(uid).orElse(null);
                if (user == null) {
                    AuthProblemSupport.flag(
                            request,
                            AuthErrorType.CLAIM_MISMATCH,
                            "Subject not found",
                            Map.of("token", "unknown_uid")
                    );
                    return;
                }
                if (user.getStatus() != null && user.getStatus() == 0) {
                    AuthProblemSupport.flag(
                            request,
                            AuthErrorType.CLAIM_MISMATCH,
                            "User is disabled",
                            Map.of("token", "user_disabled")
                    );
                    return;
                }
                userDetails = new CustomUserDetails(user);
            } else {
                String principal = resolvePrincipalFromClaims(claims);
                if (!StringUtils.hasText(principal)) {
                    AuthProblemSupport.flag(
                            request,
                            AuthErrorType.CLAIM_MISMATCH,
                            "Issuer/Audience mismatch or missing principal claim",
                            Map.of("token", "principal_missing")
                    );
                    return;
                }
                userDetails = userDetailsService.loadUserByUsername(principal);
            }

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } catch (ExpiredJwtException e) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.TOKEN_EXPIRED,
                    "The bearer token is expired at " + e.getClaims().getExpiration(),
                    Map.of("token", "expired")
            );
        } catch (SignatureException e) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.INVALID_TOKEN,
                    "Signature invalid",
                    Map.of("token", "signature_invalid")
            );
        } catch (MalformedJwtException | UnsupportedJwtException e) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.INVALID_TOKEN,
                    "Malformed token: " + e.getMessage(),
                    Map.of("token", "malformed")
            );
        } catch (UsernameNotFoundException e) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.CLAIM_MISMATCH,
                    "Subject not found",
                    Map.of("token", "unknown_principal")
            );
        } catch (JwtException | IllegalArgumentException e) {
            AuthProblemSupport.flag(
                    request,
                    AuthErrorType.INVALID_TOKEN,
                    "Invalid token: " + e.getMessage(),
                    Map.of("token", "invalid")
            );
        }
    }

    private Long resolveUidFromClaims(Claims claims) {
        Object raw = claims.get(JwtUtil.CLAIM_UID);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && StringUtils.hasText(s)) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private boolean allowsRefreshToken(HttpServletRequest request, String tokenType) {
        if (!"refresh".equalsIgnoreCase(tokenType)) {
            return false;
        }
        String uri = request.getRequestURI();
        if (!StringUtils.hasText(uri)) {
            return false;
        }
        return uri.startsWith("/api/v1/auth/refresh") || uri.startsWith("/api/v1/auth/logout");
    }

    private String resolvePrincipalFromClaims(Claims claims) {
        String email = claims.get("email", String.class);
        if (StringUtils.hasText(email)) {
            return email;
        }
        String username = claims.get("username", String.class);
        if (StringUtils.hasText(username)) {
            return username;
        }
        return claims.getSubject();
    }

    private boolean isExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration == null || expiration.before(new Date());
    }
}
