package com.slb.mining_backend.modules.users.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserLoginVO {

    private long uid;
    private String userName;
    private String token;
    private String refreshToken;
    private String workerId;
}
