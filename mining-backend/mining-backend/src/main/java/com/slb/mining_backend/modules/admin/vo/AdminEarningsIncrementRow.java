package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AdminEarningsIncrementRow {
    private LocalDate day;
    private String coin;
    private BigDecimal totalCoin;
    private BigDecimal totalCal;
    private BigDecimal userCal;
    private LocalDateTime updatedTime;
}
