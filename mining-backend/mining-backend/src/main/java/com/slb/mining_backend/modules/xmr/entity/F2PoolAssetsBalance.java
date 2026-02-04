package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolAssetsBalance implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private String miningUserName;
    private String address;
    private Boolean calculateEstimatedIncome;
    private Boolean historicalTotalIncomeOutcome;
    private BigDecimal balance;
    private BigDecimal immatureBalance;
    private BigDecimal paid;
    private BigDecimal totalIncome;
    private BigDecimal yesterdayIncome;
    private BigDecimal estimatedTodayIncomeRaw;
    private BigDecimal estimatedTodayIncome;
    private String payloadFingerprint;
    private LocalDateTime fetchedAt;
    private LocalDateTime createdTime;
}
