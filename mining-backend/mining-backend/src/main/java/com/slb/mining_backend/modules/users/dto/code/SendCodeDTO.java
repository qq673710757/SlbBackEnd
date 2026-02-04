package com.slb.mining_backend.modules.users.dto.code;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "发送邮箱验证码请求体 / Send email verification code request body")
public class SendCodeDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "接收验证码的邮箱地址，必填。/ Email address to receive verification code, required.", example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码类型不能为空")
    @Pattern(regexp = "LOGIN|RESET_PASSWORD|REGISTER|ALIPAY_UPDATE", message = "无效的验证码类型")
    @Schema(description = "验证码业务类型：LOGIN(登录)、RESET_PASSWORD(重置密码)、REGISTER(注册)、ALIPAY_UPDATE(修改支付宝)。/ Verification code type: LOGIN, RESET_PASSWORD, REGISTER, or ALIPAY_UPDATE.", example = "LOGIN")
    private String type; // 新增 'REGISTER' 类型
}
