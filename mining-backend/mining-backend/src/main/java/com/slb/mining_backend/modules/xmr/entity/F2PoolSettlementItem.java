package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolSettlementItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long settlementId;
    private String account;
    private String coin;
    private Long userId;
    private String workerKey;
    private BigDecimal userScore;
    private BigDecimal revenueRatio;
    private BigDecimal grossAmountCoin;
    private BigDecimal grossAmountCal;
    private BigDecimal feeCal;
    private BigDecimal netCal;
    private BigDecimal netCny;
    private String status;
    private String remark;
    private LocalDateTime createdTime;
}
