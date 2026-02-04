package com.slb.mining_backend.modules.users.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserVO {
    private Long id;
    private String userName;
    private String phone;
    private String email;
}
