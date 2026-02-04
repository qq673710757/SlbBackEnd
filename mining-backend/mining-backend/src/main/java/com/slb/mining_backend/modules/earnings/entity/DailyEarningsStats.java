package com.slb.mining_backend.modules.earnings.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日收益统计实体类, 对应 'daily_earnings_stats' 表
 */
@Data
public class DailyEarningsStats {
    private Long id;
    private Long userId;
    private LocalDate statDate;
    private BigDecimal totalCalAmount;
    private BigDecimal totalCnyAmount;
    private BigDecimal cpuCalEarnings;
    private BigDecimal gpuCalEarnings;
}
