package com.slb.mining_backend.modules.users.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
@Schema(description = "用户资料更新请求体 / User profile update request body")
public class UserUpdateDTO {

    // 更新后的密码
    @Schema(description = "新登录密码（选填），长度及复杂度规则同注册。/ New login password, optional, same policy as registration.", example = "NewP@ssw0rd123")
    private String newPassword;

    // 更新前的密码
    @Schema(description = "当前登录密码，用于校验后再修改密码。/ Current login password, required when changing password.", example = "OldP@ssw0rd123")
    private String oldPassword;

    @Schema(description = "用户手机号（选填）。/ User mobile phone number, optional.", example = "13900000000")
    private String phone;

    @Email
    @Schema(description = "用户邮箱（选填），用于更新账号邮箱。/ User email, optional, used to update account email.", example = "new@example.com")
    private String email;

}
