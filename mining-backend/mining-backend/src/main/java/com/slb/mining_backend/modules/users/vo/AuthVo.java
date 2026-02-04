package com.slb.mining_backend.modules.users.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "认证结果视图对象 / Authentication result view object")
public class AuthVo {

    @Schema(description = "访问令牌（JWT），用于访问需要鉴权的接口。/ Access token (JWT) used for authenticated API calls.", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9....")
    private String accessToken;

    @Schema(description = "刷新令牌（JWT），用于刷新访问令牌。/ Refresh token (JWT) used to obtain a new access token.", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9....")
    private String refreshToken;
}
