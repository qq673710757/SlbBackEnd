package com.slb.mining_backend.modules.users.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.service.RedisService;
import com.slb.mining_backend.common.util.JwtUtil;
import com.slb.mining_backend.modules.users.vo.AuthVo;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final RedisService redisService;
    private final JwtUtil jwtUtil;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            RedisService redisService,
            JwtUtil jwtUtil
    ) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.redisService = redisService;
        this.jwtUtil = jwtUtil;
    }

    /** 登录并生成 accessToken / refreshToken */
    public AuthVo login(String userName, String password, HttpServletRequest request) {
        // 1) 认证
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(userName, password));

        // 2) 加载用户
        final UserDetails userDetails = userDetailsService.loadUserByUsername(userName);

        // 3) 指纹
        String fingerprint = fingerprintOf(request.getRemoteAddr(), request.getHeader("User-Agent"));

        // 4) 生成 token（新版 JwtUtil API）
        String accessToken = jwtUtil.generateAccessToken(userDetails, fingerprint, userName);
        String refreshToken = jwtUtil.generateRefreshToken(userDetails, userName);

        // 5) 返回
        return AuthVo.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    /** 刷新 accessToken（refreshToken 旋转与多端策略可后续补） */
    public AuthVo refreshToken(String refreshToken, HttpServletRequest request) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(401, "AUTH_INVALID_TOKEN");
        }

        // 0) 黑名单检查（使用 refreshToken 的 SHA-256 作为 key）
        final String blacklistKey = "jwt:blacklist:rt:" + sha256Hex(refreshToken);
        if (redisService.hasKey(blacklistKey)) {
            throw new BizException(401, "AUTH_EXPIRED");
        }

        // 1) 解析用户名：优先 claim 'username'，否则退回 'sub'
        Claims refreshClaims;
        try {
            refreshClaims = jwtUtil.parseRefreshClaims(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new BizException(401, "AUTH_EXPIRED");
        } catch (UnsupportedJwtException e) {
            throw new BizException(401, "AUTH_WRONG_TOKEN_TYPE");
        } catch (JwtException e) {
            throw new BizException(401, "AUTH_INVALID_TOKEN");
        }

        String emailClaim = refreshClaims.get("email", String.class);
        String username = refreshClaims.get(JwtUtil.CLAIM_USERNAME, String.class);
        String identifier = StringUtils.hasText(emailClaim) ? emailClaim : username;
        if (!StringUtils.hasText(identifier)) {
            identifier = refreshClaims.getSubject();
        }
        if (!StringUtils.hasText(identifier)) {
            throw new BizException(401, "AUTH_INVALID_TOKEN");
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(identifier);
        if (jwtUtil.validateRefreshToken(refreshToken, userDetails)) {
            // 3) 生成新的 accessToken（可根据需要把 refresh 也旋转）
            String fingerprint = fingerprintOf(request.getRemoteAddr(), request.getHeader("User-Agent"));
            String emailForToken = StringUtils.hasText(emailClaim) ? emailClaim : null;
            if (!StringUtils.hasText(emailForToken) && userDetails instanceof CustomUserDetails customUserDetails) {
                emailForToken = customUserDetails.getUser().getEmail();
            }
            String newAccessToken = jwtUtil.generateAccessToken(userDetails, fingerprint, emailForToken);

            return AuthVo.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(refreshToken) // 如需旋转，把这里改成新 refresh
                    .build();
        }
        throw new BizException(401, "AUTH_INVALID_TOKEN");
    }

    /** 用户登出：将 refreshToken 放入黑名单，直到其自然过期 */
    public void logout(String refreshToken) {
        try {
            // 取过期时间（exp）
            Date expiration = jwtUtil.getClaim(refreshToken, "exp", Date.class);
            if (expiration == null) return;

            long remainingMillis = expiration.getTime() - System.currentTimeMillis();
            if (remainingMillis > 0) {
                final String blacklistKey = "jwt:blacklist:rt:" + sha256Hex(refreshToken);
                redisService.set(blacklistKey, "1", remainingMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignore) {
            // 忽略——无论如何客户端都会丢弃本地 token
        }
    }

    /* -------------------- 内部小工具 -------------------- */

    private static String fingerprintOf(String ip, String ua) {
        String raw = (ip == null ? "" : ip) + "|" + (ua == null ? "" : ua);
        return md5Hex(raw);
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 几乎不可能
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}


