package com.slb.mining_backend.modules.users.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "修改支付宝账号请求体 / Update Alipay account request body")
public class UpdateAlipayAccountDTO {

    @NotBlank(message = "支付宝账号不能为空")
    @Schema(description = "支付宝账号（可以是手机号或邮箱）。/ Alipay login identifier, may be phone number or email.",
            example = "pay@example.com")
    private String alipayAccount;

    @NotBlank(message = "支付宝实名不能为空")
    @Schema(description = "支付宝实名姓名。/ Name of the real-name holder registered in Alipay.", example = "张三")
    private String alipayName;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "用于接收验证码的邮箱，必须是当前账号绑定的邮箱。/ Email address that receives the verification code (must match bound email).",
            example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "邮箱验证码，6 位数字。/ Email verification code (6 digits).", example = "123456")
    private String code;
}


