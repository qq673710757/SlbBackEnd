package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class XmrWorkerHashSnapshot implements Serializable {

    private Long id;
    private Long userId;
    private String workerId;
    private BigDecimal hashNowHps;
    private BigDecimal hashAvgHps;
    private LocalDateTime reportedAt;
    private LocalDateTime createdTime;
}