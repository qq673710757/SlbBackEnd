package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class F2PoolAccountOverview implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private BigDecimal hashrateHps;
    private Integer workers;
    private Integer activeWorkers;
    private BigDecimal fixedValue;
    private LocalDateTime fetchedAt;
    private String payloadFingerprint;
    private LocalDateTime createdTime;
}
