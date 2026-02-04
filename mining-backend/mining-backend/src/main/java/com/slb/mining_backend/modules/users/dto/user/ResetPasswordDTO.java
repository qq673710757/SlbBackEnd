package com.slb.mining_backend.modules.users.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "管理员重置用户密码请求体 / Admin reset user password request body")
public class ResetPasswordDTO {

    @NotBlank
    @Schema(description = "目标用户名，必填。/ Target username to reset, required.", example = "hyperion")
    private String userName;

    @NotBlank
    @Schema(description = "管理员安全校验码或口令，必填。/ Admin verification code or secret, required.", example = "ADMIN-SECRET")
    private String adminCode;

    @NotBlank
    @Size(min = 8, max = 20)
    @Schema(description = "新的登录密码，长度 8-20。/ New login password, length 8-20.", example = "N3wP@ssw0rd")
    private String password;
}
