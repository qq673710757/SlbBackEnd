package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolWorkerSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private String workerId;
    private Long userId;
    private BigDecimal hashNowHps;
    private BigDecimal hashAvgHps;
    private LocalDateTime lastShareTime;
    private LocalDateTime bucketTime;
    private String status;
    private String payloadFingerprint;
    private LocalDateTime createdTime;
}
