package com.slb.mining_backend.modules.invite.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 佣金监控实体类，对应数据库中的 'platform_commissions' 表
 */
@Data
public class PlatformCommission {
    private Long id;
    private Long sourceEarningId;
    private Long userId;
    private String deviceId;
    private BigDecimal originalEarningAmount;
    private BigDecimal platformRate;
    private BigDecimal platformCommissionAmount;
    private String currency;
    private LocalDateTime createTime;
}
