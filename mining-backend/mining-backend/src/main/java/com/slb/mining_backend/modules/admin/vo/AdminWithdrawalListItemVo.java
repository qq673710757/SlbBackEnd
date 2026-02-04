package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AdminWithdrawalListItemVo {
    private Long withdrawId;
    private Long uid;
    private String currency;
    private BigDecimal amount;
    private BigDecimal fee;
    private String address;
    private String status;
    private LocalDateTime applyTime;
}
