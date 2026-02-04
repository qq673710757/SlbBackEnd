package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class EarningsGrantVo {
    private String grantId;
    private LocalDate grantDate;
    private BigDecimal payoutXmr;
    private BigDecimal payoutCfx;
    private BigDecimal payoutRvn;
    private BigDecimal commissionXmr;
    private BigDecimal commissionCfx;
    private BigDecimal commissionRvn;
    private LocalDateTime createdAt;
}
