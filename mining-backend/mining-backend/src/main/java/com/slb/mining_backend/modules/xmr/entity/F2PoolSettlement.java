package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class F2PoolSettlement implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private LocalDate payoutDate;
    private BigDecimal grossAmountCoin;
    private BigDecimal grossAmountCal;
    private BigDecimal calRate;
    private String rateSource;
    private BigDecimal poolScore;
    private BigDecimal feeRate;
    private BigDecimal feeCal;
    private BigDecimal netCal;
    private String status;
    private String reconcileStatus;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
