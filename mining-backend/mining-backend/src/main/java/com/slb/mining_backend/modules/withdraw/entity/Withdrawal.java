package com.slb.mining_backend.modules.withdraw.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现记录实体类, 对应 'withdrawals' 表
 */
@Data
public class Withdrawal {
    private Long id;
    private Long userId;
    private BigDecimal amount;
    private String accountType;
    private String accountInfo;
    private Integer status;
    private String remark;
    private String transactionId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 提现币种 (CNY 或 CAL) */
    private String currency;

    /** 申请时等值需要冻结的 XMR 数量 */
    private BigDecimal xmrEquivalent;

    /** 申请时 XMR/CNY 汇率 (用于锁定参考) */
    private BigDecimal rateAtRequest;

    /** USDT 提现时指定的网络 (TRC20/ERC20) */
    private String transferNetwork;

    /** 提现金额中的手续费 (CNY)，用于记录平台收取的费用。 */
    private BigDecimal feeAmount;

    /** 管理员审核时间 */
    private LocalDateTime reviewTime;

    /** 处理此提现的管理员 ID */
    private Long reviewedBy;
}
