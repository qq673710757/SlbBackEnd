package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolReconcileReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private LocalDateTime reportTime;
    private BigDecimal overviewHashrateHps;
    private BigDecimal workersHashrateHps;
    private BigDecimal hashrateDiffRatio;
    private BigDecimal grossAmountCoin;
    private BigDecimal settlementGrossCoin;
    private BigDecimal revenueDiffRatio;
    private String status;
    private LocalDateTime createdTime;
}
