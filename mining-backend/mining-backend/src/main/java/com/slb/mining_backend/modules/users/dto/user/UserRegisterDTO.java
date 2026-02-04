package com.slb.mining_backend.modules.users.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "用户注册请求体 / User registration request body")
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 5, max = 50)
    @Schema(description = "用户名，长度 5-50，必填且唯一（业务约束）。/ Username, length 5-20, required and unique at business level.", example = "hyperion")
    private String userName;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20)
    @Schema(description = "登录密码，长度 8-20，需满足密码策略。/ Login password, length 8-20, must satisfy password policy.", example = "P@ssw0rd123")
    private String userPassword;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱地址，用于登录和收取验证码。/ Email address used for login and verification codes.", example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码必须为6位")
    @Schema(description = "邮箱验证码，固定 6 位数字。/ Email verification code, fixed 6 digits.", example = "123456")
    private String code; // 新增验证码字段

    @Schema(description = "绑定的支付宝账号（选填，可为手机号或邮箱）。/ Alipay account, optional.", example = "pay@example.com")
    private String alipayAccount;

    @Schema(description = "支付宝实名姓名（选填）。/ Alipay real-name holder, optional.", example = "张三")
    private String alipayName;

    @Schema(description = "邀请码（选填），用于建立邀请关系。/ Invitation code, optional, used to build invite relationship.", example = "INVITE123")
    private String inviteCode;

    @Schema(description = "用户手机号（选填）。/ User mobile phone number, optional.", example = "13900000000")
    private String phone;

    @NotNull(message = "注册来源不能为空")
    @Pattern(regexp = "web|app|client", message = "无效的注册来源")
    @Schema(description = "注册来源：web/app/client。/ Registration source: web/app/client.", example = "web")
    private String regInto;
}

 