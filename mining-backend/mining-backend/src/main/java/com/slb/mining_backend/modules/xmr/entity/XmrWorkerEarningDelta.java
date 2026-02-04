package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class XmrWorkerEarningDelta implements Serializable {

    private Long id;
    private Long userId;
    private String workerId;
    private Long deltaAtomic;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private LocalDateTime createdTime;
    private Boolean settled;
}
