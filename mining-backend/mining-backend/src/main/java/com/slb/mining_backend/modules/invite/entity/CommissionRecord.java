package com.slb.mining_backend.modules.invite.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 佣金记录实体类，对应数据库中的 'commission_records' 表
 */
@Data
public class CommissionRecord {
    private Long id;
    private Long userId; // 佣金受益人ID (邀请人)
    private Long inviteeId; // 贡献佣金的用户ID (被邀请人)
    private Long sourceEarningId; // 佣金来源的收益记录ID
    /**
     * 佣金来源收益类型：用于区分“被邀请用户贡献的返佣”是来自 CPU 还是 GPU。
     * 约定值：CPU / GPU（可为空表示旧数据或未区分）
     */
    private String sourceEarningType;
    private BigDecimal commissionAmount; // 佣金金额 (CAL)
    private BigDecimal commissionRate; // 当时的佣金率 (%)
    private LocalDateTime createTime;
}
