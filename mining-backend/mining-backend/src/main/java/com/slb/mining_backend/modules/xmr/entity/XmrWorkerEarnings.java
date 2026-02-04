package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class XmrWorkerEarnings implements Serializable {

    private Long id;
    private Long userId;
    private String workerId;
    private Long totalAtomic;
    private LocalDateTime updatedTime;
}