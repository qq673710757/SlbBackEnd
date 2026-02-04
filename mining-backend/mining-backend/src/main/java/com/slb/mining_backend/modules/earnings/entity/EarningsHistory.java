package com.slb.mining_backend.modules.earnings.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收益历史记录实体类, 对应 'earnings_history' 表
 *
 * earningType 约定：
 * - "CPU"         : CPU 挖矿收益
 * - "GPU"         : GPU 挖矿收益
 * - "INVITE"      : 邀请奖励/邀请收益
 * - "INVITED"     : 被邀请者奖励（例如新手折扣/让利部分，按 CAL 等值记账；不计入 CPU/GPU 明细以避免重复统计）
 * - "INVITE_CPU"  : 邀请奖励（来自被邀请者 CPU 收益贡献）
 * - "INVITE_GPU"  : 邀请奖励（来自被邀请者 GPU 收益贡献）
 * - "COMPENSATION": 补偿（系统补偿）
 * - "INCENTIVE"   : 激励（活动/运营激励等）
 * - "SYSTEM_INCENTIVE": 系统激励（与 INCENTIVE 区分时使用）
 * - 其他（如 "POOL"、"AUTO"）可按需要扩展，仅在明细中使用或由统计逻辑自行映射。
 */
@Data
public class EarningsHistory {
    private Long id;
    private Long userId;
    private String deviceId;
    private BigDecimal amountCal;
    private BigDecimal amountCny;
    // --- 预留字段：额外奖励 CAL 数量 ---
    private BigDecimal bonusCalAmount;

    /**
     * 收益类型，参见类注释中的 earningType 约定。
     */
    private String earningType;
    private LocalDateTime earningTime;
}
