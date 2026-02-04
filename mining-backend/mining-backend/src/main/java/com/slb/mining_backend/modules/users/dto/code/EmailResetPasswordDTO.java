package com.slb.mining_backend.modules.users.dto.code;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "邮箱验证码重置密码请求体 / Reset password with email verification code request body")
public class EmailResetPasswordDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "需要重置密码的账号邮箱，必填。/ Account email to reset password for, required.", example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码只能为6位")
    @Schema(description = "邮箱验证码，固定 6 位数字。/ Email verification code, 6 digits.", example = "654321")
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度必须在8到20之间")
    @Schema(description = "新登录密码，长度 8-20。/ New login password, length 8-20.", example = "N3wP@ssw0rd")
    private String newPassword;

}
