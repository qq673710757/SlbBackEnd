package com.slb.mining_backend.modules.users.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.users.dto.user.UserLoginDTO;
import com.slb.mining_backend.modules.users.service.AuthService;
import com.slb.mining_backend.modules.users.vo.AuthVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "用户端/认证", description = "提供用户登录、刷新令牌、退出登录等认证相关接口")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    public AuthController(AuthService authService) {
        this.authService = authService;
    }


    @PostMapping("/login")
    @Operation(
            summary = "账号密码登录",
            description = """
                    使用邮箱和登录密码进行登录，成功后返回访问令牌和刷新令牌。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/auth/login" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "email": "user@example.com",
                        "userPassword": "P@ssw0rd123"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<AuthVo> login(
            @Parameter(description = "登录请求体，包含邮箱与密码", required = true)
            @RequestBody UserLoginDTO loginRequest,
            @Parameter(description = "HTTP 请求对象，用于记录客户端信息")
            HttpServletRequest request) {
        // 目前仅支持通过邮箱登录
        AuthVo authVo = authService.login(loginRequest.getEmail(), loginRequest.getUserPassword(), request);
        return ApiResponse.ok(authVo);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "刷新访问令牌",
            description = """
                    通过请求头 Authorization 中携带的 Bearer 刷新令牌，获取新的访问令牌和刷新令牌。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/auth/refresh" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.refreshToken..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.newAccess...",
                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.newRefresh..."
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<AuthVo> refresh(
            @Parameter(description = "HTTP 请求对象，从中读取 Authorization 头中的 Bearer 刷新令牌", required = true)
            HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            throw new BizException(400, "AUTH_HEADER_REQUIRED");
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new BizException(400, "AUTH_HEADER_FORMAT");
        }
        String refreshToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(400, "AUTH_TOKEN_MISSING");
        }
        AuthVo authVo = authService.refreshToken(refreshToken, request);
        return ApiResponse.ok(authVo);
    }

    /**
     * 用户登出接口
     */
    @PostMapping("/logout")
    @Operation(
            summary = "退出登录（注销刷新令牌）",
            description = """
                    前端在请求头 Authorization 中携带 Bearer 刷新令牌，服务端注销该刷新令牌，使其后续无法再刷新访问令牌。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/auth/logout" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.refreshToken..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> logout(
            @Parameter(description = "Authorization 请求头，格式为 Bearer {refreshToken}", required = true, example = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
            @RequestHeader("Authorization") String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer")) {
            String refreshToken = authorizationHeader.substring(7);
            authService.logout(refreshToken);
        }
        return ApiResponse.ok();
    }
}
