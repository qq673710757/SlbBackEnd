package com.slb.mining_backend.common.util;

import com.slb.mining_backend.common.security.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtil {

    public static final String CLAIM_TYP = "typ";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_UID = "uid";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.access-token-expire}")
    private long accessTokenExpire;  // seconds

    @Value("${security.jwt.refresh-token-expire}")
    private long refreshTokenExpire; // seconds

    private Key key;

    @PostConstruct
    public void init() {
        // 保证 secret 至少 32 bytes，HS256 可用
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /* ----------- 公共解析工具 ----------- */

    public String getSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String getClaim(String token, String name) {
        return extractAllClaims(token).get(name, String.class);
    }

    public <T> T getClaim(String token, String name, Class<T> type) {
        return extractAllClaims(token).get(name, type);
    }

    public Claims parseClaims(String token) {
        return extractAllClaims(token);
    }

    public Claims parseRefreshClaims(String token) {
        Claims claims = extractAllClaims(token);
        ensureTokenType(claims, TOKEN_TYPE_REFRESH);
        return claims;
    }

    public Claims parseAccessClaims(String token) {
        Claims claims = extractAllClaims(token);
        ensureTokenType(claims, TOKEN_TYPE_ACCESS);
        return claims;
    }

    public boolean isTokenExpired(String token) {
        try {
            Date exp = extractClaim(token, Claims::getExpiration);
            return exp == null || exp.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /** 统一校验：默认 Access Token */
    public boolean validateToken(String token, UserDetails user) {
        return validateAccessToken(token, user);
    }

    public boolean validateAccessToken(String token, UserDetails user) {
        return validateTokenInternal(token, user, TOKEN_TYPE_ACCESS);
    }

    public boolean validateRefreshToken(String token, UserDetails user) {
        return validateTokenInternal(token, user, TOKEN_TYPE_REFRESH);
    }

    /** 生成 AccessToken：subject=用户名，claim 写入 username / typ / fpt(设备指纹) / email(可选) */
    public String generateAccessToken(UserDetails user, @Nullable String fingerprint, @Nullable String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYP, TOKEN_TYPE_ACCESS);
        claims.put(CLAIM_USERNAME, user.getUsername());
        Long uid = resolveUid(user);
        if (uid != null) claims.put(CLAIM_UID, uid);
        if (StringUtils.hasText(email)) claims.put("email", email);
        if (StringUtils.hasText(fingerprint)) claims.put("fpt", fingerprint);
        return buildToken(claims, user.getUsername(), accessTokenExpire);
    }

    /** 生成 RefreshToken：subject=用户名，claim 写入 username / typ=refresh */
    public String generateRefreshToken(UserDetails user, @Nullable String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYP, TOKEN_TYPE_REFRESH);
        claims.put(CLAIM_USERNAME, user.getUsername());
        Long uid = resolveUid(user);
        if (uid != null) claims.put(CLAIM_UID, uid);
        if (StringUtils.hasText(email)) claims.put("email", email);
        return buildToken(claims, user.getUsername(), refreshTokenExpire);
    }

    /* ----------- 私有工具 ----------- */

    private String buildToken(Map<String, Object> claims, String subject, long ttlSeconds) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) throws JwtException {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }

    private boolean validateTokenInternal(String token, UserDetails user, String expectedType) {
        try {
            Claims claims = extractAllClaims(token);
            ensureTokenType(claims, expectedType);
            String username = resolveUsername(claims);
            return StringUtils.hasText(username)
                    && username.equals(user.getUsername())
                    && !isExpired(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Failed to validate {} token: {}", expectedType, e.getMessage());
            return false;
        }
    }

    private void ensureTokenType(Claims claims, String expectedType) {
        String tokenType = claims.get(CLAIM_TYP, String.class);
        if (!expectedType.equalsIgnoreCase(tokenType)) {
            throw new UnsupportedJwtException("Wrong token type: " + tokenType);
        }
    }

    private String resolveUsername(Claims claims) {
        String username = claims.get(CLAIM_USERNAME, String.class);
        if (!StringUtils.hasText(username)) {
            username = claims.getSubject();
        }
        return username;
    }

    private boolean isExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration == null || expiration.before(new Date());
    }

    /**
     * 从 UserDetails 中尽可能提取出唯一、不可歧义的用户ID（uid）。
     * - 仅当 UserDetails 是 CustomUserDetails 且内部 User.id 不为空时返回；
     * - 兼容旧实现：未携带 uid 的 token 仍可通过 username/email/sub 解析用户。
     */
    @Nullable
    private Long resolveUid(UserDetails userDetails) {
        if (userDetails instanceof CustomUserDetails customUserDetails
                && customUserDetails.getUser() != null
                && customUserDetails.getUser().getId() != null) {
            return customUserDetails.getUser().getId();
        }
        return null;
    }
}
