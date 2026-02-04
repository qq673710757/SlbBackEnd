package com.slb.mining_backend.modules.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PlatformCommissionRate implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private BigDecimal ratePercent;
    private String updatedBy;
    private LocalDateTime updatedTime;
    private LocalDateTime createdTime;
}
