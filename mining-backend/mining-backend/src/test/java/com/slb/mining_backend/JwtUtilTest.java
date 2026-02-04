package com.slb.mining_backend;

import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.util.JwtUtil;
import com.slb.mining_backend.modules.users.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生成并校验测试用 JWT Token（适配新版 JwtUtil API）。
 */
@SpringBootTest
class JwtUtilTest {

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void generateTestToken() {
        // 1) 构造 User / UserDetails
        User testUser = new User();
        testUser.setId(10001L);
        testUser.setUserName("wangwu");
        testUser.setStatus(1);
        testUser.setEmail("wangwu@example.com");
        UserDetails userDetails = new CustomUserDetails(testUser);

        // 2) 模拟请求，生成设备指纹（测试内实现；不依赖 JwtUtil 的静态方法）
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "ApiFox/1.0.0");
        String fingerprint = fingerprintOf(request.getRemoteAddr(), request.getHeader("User-Agent"));

        // 3) 生成 access / refresh
        String accessToken = jwtUtil.generateAccessToken(userDetails, fingerprint, testUser.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails, testUser.getEmail());

        System.out.println("====================================================");
        System.out.println("Generated Test Access Token:\n" + accessToken);
        System.out.println("----------------------------------------------------");
        System.out.println("Generated Test Refresh Token:\n" + refreshToken);
        System.out.println("====================================================");

        // 4) 断言（新版 API）
        assertNotNull(accessToken, "Token 不应为 null");
        assertTrue(jwtUtil.validateToken(accessToken, userDetails), "生成的 Token 应该是有效的");

        // Token 中用户名：优先 claim 'username'，否则退回 'sub'
        String usernameInToken = jwtUtil.getClaim(accessToken, "username");
        if (usernameInToken == null || usernameInToken.isBlank()) {
            usernameInToken = jwtUtil.getSubject(accessToken);
        }
        assertEquals("wangwu", usernameInToken, "Token 中的用户名应与测试用户匹配");

        // 新增断言：Token 携带不可歧义的 uid（用户ID）
        Long uidInToken = jwtUtil.getClaim(accessToken, JwtUtil.CLAIM_UID, Long.class);
        assertNotNull(uidInToken, "Token 中应包含 uid claim");
        assertEquals(10001L, uidInToken, "Token 中的 uid 应与测试用户匹配");
    }

    private static String fingerprintOf(String ip, String ua) {
        String raw = (ip == null ? "" : ip) + "|" + (ua == null ? "" : ua);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
