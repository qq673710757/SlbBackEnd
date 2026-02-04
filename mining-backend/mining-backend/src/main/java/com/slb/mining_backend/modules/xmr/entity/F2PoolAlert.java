package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class F2PoolAlert implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private Long userId;
    private String alertType;
    private String severity;
    private String refKey;
    private String message;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime resolvedTime;
    private Long resolvedBy;
}
