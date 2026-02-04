package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class EarningsGrantRow {
    private LocalDate payoutDate;
    private String coin;
    private BigDecimal grossAmountCoin;
    private BigDecimal commissionCoin;
    private LocalDateTime updatedTime;
}
