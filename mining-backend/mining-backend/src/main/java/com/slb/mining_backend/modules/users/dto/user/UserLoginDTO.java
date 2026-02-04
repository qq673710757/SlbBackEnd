package com.slb.mining_backend.modules.users.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "用户登录请求体 / User login request body")
public class UserLoginDTO {

    public UserLoginDTO(String email, String userPassword) {
        this.email = email;
        this.userPassword = userPassword;
    }

    //    @NotBlank
    //    private String userName;
    @NotBlank
    @Schema(description = "用户登录邮箱，必填。/ User email for login, required.", example = "user@example.com")
    private String email;

    @NotBlank
    @Schema(description = "登录密码，必填。/ Login password, required.", example = "P@ssw0rd123")
    private String userPassword;

}
