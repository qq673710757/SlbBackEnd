package com.slb.mining_backend.modules.users.dto.code;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "邮箱验证码登录请求体 / Email verification code login request body")
public class EmailCodeLoginDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "用于登录的邮箱地址，必填。/ Email address used for login, required.", example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码必须为6位")
    @Schema(description = "邮箱验证码，固定 6 位数字。/ Email verification code, 6 digits.", example = "123456")
    private String code;
}
