package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CommissionRateVo {
    private BigDecimal ratePercent;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
