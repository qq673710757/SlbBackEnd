package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日 XMR 估值快照。
 */
@Data
public class XmrDailyValuation implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String workerId;
    private BigDecimal balanceXmr;
    private BigDecimal frozenXmr;
    private BigDecimal totalEarnedXmr;
    private BigDecimal balanceCny;
    private BigDecimal totalEarnedCny;
    private BigDecimal rate;
    private LocalDate snapshotDate;
    private LocalDateTime createdTime;
}
