package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolPayhashHourlySettlement implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private BigDecimal totalCoin;
    private BigDecimal totalCal;
    private BigDecimal totalXmr;
    private String rateSource;
    private String allocationSource;
    private String fallbackReason;
    private String remark;
    private Long startSnapshotId;
    private LocalDateTime startSnapshotAt;
    private Long endSnapshotId;
    private LocalDateTime endSnapshotAt;
    private BigDecimal cpuTotalCal;
    private BigDecimal gpuCfxTotalCal;
    private BigDecimal gpuRvnTotalCal;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
