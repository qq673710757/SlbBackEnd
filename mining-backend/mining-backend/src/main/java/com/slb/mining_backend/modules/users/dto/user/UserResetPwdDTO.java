package com.slb.mining_backend.modules.users.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserResetPwdDTO {

    @NotBlank
    private String userName;
    @NotBlank
    private String oldPassword;
    @NotBlank
    @Size(min = 6, max = 20)
    private String newPassword;

}
